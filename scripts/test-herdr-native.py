#!/usr/bin/env python3
"""Exercise Herdr JNI against an ephemeral local SSH fixture on an existing device.

Requires Python paramiko and installed app + androidTest APKs. Never accesses a
tailnet or an existing SSH server. The SSH host key exists only in memory.
"""
import argparse
import base64
import hashlib
import logging
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import uuid

import paramiko
from paramiko.common import MSG_CHANNEL_REQUEST


class RejectAuth(paramiko.ServerInterface):
    def check_auth_none(self, username):
        return paramiko.AUTH_FAILED


class LocalHerdr(paramiko.ServerInterface):
    """Expose only the three fixture exec operations, with no SSH PTY."""
    def __init__(self, env, workdir, processes, lock, pty_requests, stages, allow_stall=False):
        self.env, self.workdir = env, workdir
        self.processes, self.lock, self.pty_requests = processes, lock, pty_requests
        self.stages = stages
        self.allow_stall = allow_stall
        self.exec_ready = {}

    def check_auth_none(self, username):
        return paramiko.AUTH_SUCCESSFUL if username == "fixture" else paramiko.AUTH_FAILED

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_pty_request(self, *args):
        self.pty_requests.append(True)
        return False

    def check_channel_exec_request(self, channel, command):
        try:
            text = command.decode("utf-8")
        except UnicodeDecodeError:
            return False
        if not any(marker in text for marker in ("NOVA_EXEC_V1", "NOVA_CLIENT_V1", "NOVA_HERDR_BRIDGE_V1")):
            return False
        self.stages.append(next(marker for marker in ("NOVA_EXEC_V1", "NOVA_CLIENT_V1", "NOVA_HERDR_BRIDGE_V1") if marker in text))
        # Paramiko calls this callback before it writes SSH_MSG_CHANNEL_SUCCESS.
        # A fast discovery subprocess could otherwise write and close the
        # channel before the client receives its exec-request response.
        ready = threading.Event()
        with self.lock:
            self.exec_ready[channel.get_id()] = ready
        threading.Thread(target=self.run_command, args=(channel, text, ready), daemon=True).start()
        return True

    def request_replied(self, channel):
        with self.lock:
            ready = self.exec_ready.pop(channel.get_id(), None)
        if ready is not None:
            ready.set()

    def run_command(self, channel, command, ready):
        process = None
        stage = next(marker for marker in ("NOVA_EXEC_V1", "NOVA_CLIENT_V1", "NOVA_HERDR_BRIDGE_V1") if marker in command)
        try:
            if not ready.wait(timeout=5):
                self.stages.append(stage + ":request-reply-timeout")
                return
            process = subprocess.Popen(["/bin/sh", "-c", command], env=self.env, cwd=self.workdir,
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            with self.lock:
                self.processes.add(process)

            stalled = threading.Event()

            def stdin_pump():
                try:
                    while True:
                        data = channel.recv(32768)
                        if not data:
                            break
                        if stalled.is_set():
                            continue
                        process.stdin.write(data)
                        process.stdin.flush()
                except (EOFError, OSError):
                    pass
                finally:
                    try:
                        process.stdin.close()
                    except OSError:
                        pass

            def output_pump(stream, send, kind):
                sent = 0
                try:
                    while data := stream.read1(32768):
                        marker = Path(self.workdir) / "nova_fixture_stall"
                        if self.allow_stall and stage == "NOVA_HERDR_BRIDGE_V1" and kind == "stdout" and marker.exists():
                            marker.unlink()
                            stalled.set()
                            self.stages.append("bridge-stalled")
                        if stalled.is_set():
                            continue
                        send(data)
                        sent += len(data)
                except (EOFError, OSError, paramiko.SSHException) as error:
                    self.stages.append(stage + ":" + kind + "-error=" + type(error).__name__)
                finally:
                    self.stages.append(stage + ":" + kind + "-bytes=" + str(sent))

            threading.Thread(target=stdin_pump, daemon=True).start()
            outputs = [threading.Thread(target=output_pump, args=(process.stdout, channel.sendall, "stdout"), daemon=True),
                       threading.Thread(target=output_pump, args=(process.stderr, channel.sendall_stderr, "stderr"), daemon=True)]
            for worker in outputs:
                worker.start()
            status = process.wait()
            self.stages.append(stage + ":exit=" + str(status))
            for worker in outputs:
                worker.join(timeout=3)
            channel.send_exit_status(status)
        except (EOFError, OSError, paramiko.SSHException) as error:
            self.stages.append(stage + ":error=" + type(error).__name__)
        finally:
            if process:
                with self.lock:
                    self.processes.discard(process)
            channel.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True, help="already-running Android emulator/device serial")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--flavor", choices=("community", "play"), default="community", help="installed debug build flavor")
    parser.add_argument("--herdr", help="absolute path to a local Herdr 0.9.1 or 0.9.5 binary for the optional live bridge test")
    parser.add_argument("--stress", action="store_true", help="measure a maximum-size 500x300 viewport")
    parser.add_argument("--ui", action="store_true", help="exercise the Compose Herdr screen against the isolated bridge")
    parser.add_argument("--recovery", action="store_true", help="test long background and stalled-bridge recovery; requires --ui")
    args = parser.parse_args()
    if args.recovery and not args.ui:
        parser.error("--recovery requires --ui")
    if args.ui and not args.herdr:
        parser.error("--ui requires --herdr")
    if args.stress and not args.herdr:
        parser.error("--stress requires --herdr")
    fixture_dir = None
    fixture_env = None
    fixture_name = None
    binary = None
    version = None
    if args.herdr:
        binary = Path(args.herdr).resolve(strict=True)
        if not binary.is_file():
            raise SystemExit("The live fixture requires a Herdr executable")
        version = subprocess.check_output([str(binary), "--version"], text=True).strip()
        if version not in ("herdr 0.9.1", "herdr 0.9.5"):
            raise SystemExit("The live fixture requires a Herdr 0.9.1 or 0.9.5 executable")
        fixture_dir = tempfile.TemporaryDirectory(prefix="novascale-herdr-jni-")
        fixture_root = Path(fixture_dir.name)
        fixture_name = "novascale-test-" + uuid.uuid4().hex[:12]
        fixture_env = os.environ.copy()
        for key_name in list(fixture_env):
            if key_name.startswith("HERDR_") or key_name in ("ENV", "BASH_ENV", "SSH_AUTH_SOCK"):
                fixture_env.pop(key_name, None)
        for variable, directory in (("XDG_CONFIG_HOME", "config"), ("XDG_DATA_HOME", "data"),
                                    ("XDG_STATE_HOME", "state"), ("XDG_CACHE_HOME", "cache"),
                                    ("XDG_RUNTIME_DIR", "runtime")):
            path = fixture_root / directory
            path.mkdir(mode=0o700)
            fixture_env[variable] = str(path)
        fixture_env["SHELL"] = "/bin/sh"
        fixture_env["HISTFILE"] = "/dev/null"
        fixture_env["INPUTRC"] = "/dev/null"
        fixture_env["PATH"] = str(binary.parent) + ":/usr/bin:/bin"
        # Reproduce a TUI retaining its dark startup background while using the
        # terminal's default foreground. No Codex login, prompt or project data.
        (fixture_root / "nova_fixture_colors.py").write_text(r'''
import os
os.write(1, b"\x1b[0;39;48;2;48;57;89mNOVA_CONTRAST \x1b[2mNOVA_DIM \x1b[0;38;2;48;57;89;48;2;48;57;89mNOVA_EXPLICIT\x1b[0m\r\n")
''')
        # An independent API client scrolls the disposable pane while its
        # Android screen is detached. This reproduces reopening from Sessions
        # without touching any owner session or adding a production test hook.
        (fixture_root / "nova_fixture_scroll.py").write_text(r'''
import json
from pathlib import Path
import socket
import sys
import time

def call(method, params):
    path = Path(__file__).parent / "config/herdr/sessions" / __SESSION__ / "herdr.sock"
    with socket.socket(socket.AF_UNIX) as client:
        client.settimeout(5)
        client.connect(str(path))
        client.sendall(json.dumps({"id": "fixture", "method": method, "params": params}).encode() + b"\n")
        response = json.loads(client.makefile().readline())
        assert "error" not in response, response
        return response["result"]

for row in range(200):
    print(f"HISTORY_ROW_{row:03d}")
print("NOVASCALE_BOTTOM_" + sys.argv[1], flush=True)
time.sleep(3)
pane = call("pane.current", {})["pane"]["pane_id"]
call("pane.scroll", {"pane_id": pane, "offset_from_bottom": 80})
'''.replace("__SESSION__", repr(fixture_name)))
        # A real alternate-screen child application, with PTY mouse reporting.
        # Wheel input must reach this application instead of pane.scroll.
        (fixture_root / "nova_fixture_mouse.py").write_text(r'''
import os
import re
import select
import sys
import termios
import tty

saved = termios.tcgetattr(0)
ok = False
try:
    tty.setraw(0)
    os.write(1, b"\x1b[?1049h\x1b[?1000h\x1b[?1006hAPP_WHEEL_READY\r\n")
    data = b""
    while len(data) < 4096 and select.select([0], [], [], 10)[0]:
        data += os.read(0, 4096)
        if len(re.findall(rb"\x1b\[<64;[1-9][0-9]*;[1-9][0-9]*M", data)) >= 3:
            ok = True
            break
finally:
    os.write(1, b"\x1b[?1000l\x1b[?1006l\x1b[?1049l")
    termios.tcsetattr(0, termios.TCSANOW, saved)
print("APP_WHEEL_OK" if ok else "APP_WHEEL_FAILED", flush=True)
''')
    processes = set()
    processes_lock = threading.Lock()
    pty_requests = []
    stages = []
    logging.getLogger("paramiko").setLevel(logging.CRITICAL)
    key = paramiko.RSAKey.generate(2048)
    fingerprint = "SHA256:" + base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip("=")
    stop = threading.Event()
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen(4)
    listener.settimeout(0.25)

    def handle(connection):
        transport = paramiko.Transport(connection)
        try:
            transport.add_server_key(key)
            server = (LocalHerdr(fixture_env, fixture_dir.name, processes, processes_lock, pty_requests, stages, allow_stall=args.recovery)
                      if fixture_env else RejectAuth())
            if fixture_env:
                # The dispatcher normally stores an unbound Channel method.
                # Signal the fixture worker only after that method has sent
                # the request reply. The override is local to this transport.
                handlers = transport._channel_handler_table.copy()
                original = handlers[MSG_CHANNEL_REQUEST]
                def request_and_release(channel, message):
                    try:
                        original(channel, message)
                    finally:
                        server.request_replied(channel)
                handlers[MSG_CHANNEL_REQUEST] = request_and_release
                transport._channel_handler_table = handlers
            transport.start_server(server=server)
            deadline = time.monotonic() + ((360 if args.recovery else 160 if args.stress else 120 if args.ui else 70) if fixture_env else 10)
            channels = []
            while transport.is_active() and time.monotonic() < deadline and not stop.wait(0.05):
                channel = transport.accept(timeout=0.05)
                if channel is not None:
                    # Paramiko's channel map holds weak references. Retain each
                    # accepted channel until it closes so it cannot disappear
                    # between SSH open and the subsequent exec request.
                    channels.append(channel)
                channels[:] = [channel for channel in channels if not channel.closed]
        except (EOFError, OSError, paramiko.SSHException):
            pass
        finally:
            transport.close()

    def serve():
        while not stop.is_set():
            try:
                connection, _ = listener.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            threading.Thread(target=handle, args=(connection,), daemon=True).start()

    thread = threading.Thread(target=serve, daemon=True)
    thread.start()
    adb = [args.adb, "-s", args.serial]
    reverse_port = None
    reverse_port2 = None
    try:
        reverse_port = subprocess.check_output(
            adb + ["reverse", "tcp:0", f"tcp:{listener.getsockname()[1]}"], text=True
        ).strip()
        if not reverse_port.isdecimal():
            raise RuntimeError("adb did not return a dynamically allocated reverse port")
        if args.ui:
            reverse_port2 = subprocess.check_output(
                adb + ["reverse", "tcp:0", f"tcp:{listener.getsockname()[1]}"], text=True
            ).strip()
            if not reverse_port2.isdecimal():
                raise RuntimeError("adb did not return the second fixture host port")
        command = adb + ["shell", "am", "instrument", "-w", "-e", "suite", "herdr-live-ui" if args.ui else "herdr-native",
                         "-e", "sshPort", reverse_port, "-e", "sshFingerprint", fingerprint]
        if fixture_name:
            command += ["-e", "herdrExecutable", str(binary), "-e", "herdrSession", fixture_name]
        if reverse_port2:
            command += ["-e", "sshPort2", reverse_port2]
        if args.stress:
            command += ["-e", "herdrStress", "true"]
        if args.recovery:
            command += ["-e", "herdrRecovery", "true"]
        package = "cc.galaxnet.novascale.community.debug" if args.flavor == "community" else "cc.galaxnet.novascale.debug"
        command += [f"{package}.test/cc.galaxnet.novascale.browser.ProxyInstrumentation"]
        result = subprocess.run(command, text=True, capture_output=True, timeout=420 if args.recovery else 180 if args.ui else 110)
        print(result.stdout, end="")
        if result.stderr:
            print(result.stderr, end="")
        expected_pass = "PASS: Herdr live UI input" if args.ui else "PASS: Herdr JNI descriptor ownership"
        if result.returncode != 0 or expected_pass not in result.stdout:
            print("Fixture exec stages:", stages)
            raise SystemExit("Herdr JNI instrumentation failed; see output above")
        if fixture_name:
            expected_socket = fixture_root / "config" / "herdr" / "sessions" / fixture_name / "herdr-client.sock"
            if not expected_socket.is_socket():
                raise SystemExit("Herdr did not create its client socket in the isolated test namespace")
            if pty_requests:
                raise SystemExit("Herdr attempted to request an SSH PTY")
            coverage = "Compose input, live theme/contrast, touch/wheel history, application mouse scrolling, detach, reconnect, close/replace across host entries and same-host session selection" if args.ui else "tagged input, resize and same-server reattach"
            if args.recovery:
                if stages.count("bridge-stalled") != 2 or stages.count("NOVA_HERDR_BRIDGE_V1") != 3:
                    raise SystemExit("The stalled-bridge fault was not exercised")
                coverage = "65-second background, healthy connection reuse, stalled-bridge recovery/cancellation, retained shell, restored input and unchanged daily usage"
            print(f"PASS: isolated {version} bridge, {coverage}; no SSH PTY.")
    finally:
        if reverse_port:
            subprocess.run(adb + ["reverse", "--remove", f"tcp:{reverse_port}"], check=False)
        if reverse_port2:
            subprocess.run(adb + ["reverse", "--remove", f"tcp:{reverse_port2}"], check=False)
        try:
            if fixture_name:
                # The unique generated name and isolated XDG tree are mandatory.
                # Never run a stop operation against an arbitrary/default session.
                assert fixture_name.startswith("novascale-test-") and fixture_root.name.startswith("novascale-herdr-jni-")
                subprocess.run([str(binary), "session", "stop", fixture_name, "--json"], env=fixture_env,
                               cwd=fixture_root, capture_output=True, timeout=20, check=False)
                if args.ui:
                    subprocess.run([str(binary), "session", "stop", fixture_name + "-other", "--json"], env=fixture_env,
                                   cwd=fixture_root, capture_output=True, timeout=20, check=False)
        finally:
            with processes_lock:
                for process in list(processes):
                    process.terminate()
            stop.set()
            listener.close()
            thread.join(timeout=2)
            if fixture_dir:
                fixture_dir.cleanup()


if __name__ == "__main__":
    main()
