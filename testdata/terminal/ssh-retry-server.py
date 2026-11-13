#!/usr/bin/env python3
# Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
"""Local-only SSH password fixture. Requires Paramiko on the development host."""
import base64
import hashlib
import json
import socket
import random
import struct
import zlib
import threading
import paramiko


class Server(paramiko.ServerInterface):
    def get_allowed_auths(self, username):
        return "password"

    def check_auth_password(self, username, password):
        return paramiko.AUTH_SUCCESSFUL if username == "fixture" and password == "fixture-correct" else paramiko.AUTH_FAILED

    def check_channel_request(self, kind, channel_id):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_pty_request(self, *args):
        return True

    def check_channel_shell_request(self, channel):
        return True

    def check_channel_window_change_request(self, *args):
        # Deliberately do not repaint. Local resize updates cannot depend on output.
        return True


def kitty_roundtrip(channel):
    # A noisy RGB PNG exceeds the SSH receive window and exercises chunked,
    # zlib-wrapped upload acknowledgements separately from image placement.
    width = height = 1254
    rgb = random.Random(42).randbytes(width * height * 3)
    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))
    rows = b"".join(b"\0" + rgb[y * width * 3:(y + 1) * width * 3] for y in range(height))
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")
    payload = base64.b64encode(zlib.compress(png, 1))
    def send(control, data=b""):
        channel.sendall(b"\x1b_G" + control + b";" + data + b"\x1b\\")
    def acknowledged(placement=False):
        expected = b"\x1b_Gi=321" + (b",p=1" if placement else b"") + b";OK\x1b\\"
        received = b""
        channel.settimeout(15)
        try:
            while expected not in received:
                data = channel.recv(4096)
                if not data:
                    raise EOFError()
                received = (received + data)[-16384:]
        finally:
            channel.settimeout(None)
    channel.sendall(b"\x1b[2J\x1b[HKITTY_BEGIN\r\n")
    for offset in range(0, len(payload), 4096):
        data = payload[offset:offset + 4096]
        control = f"a=t,t=d,f=100,S={len(png)},i=321,q=0,o=z,".encode() if offset == 0 else b""
        send(control + (b"m=0" if offset + len(data) == len(payload) else b"m=1"), data)
    acknowledged()
    channel.sendall(b"KITTY_UPLOAD_OK\r\n" + b"\r\n" * 12 + b"\x1b[12A\r")
    send(b"a=p,i=321,p=1,c=24,r=12,C=1,q=0")
    acknowledged(placement=True)
    channel.sendall(b"\x1b[12B\rKITTY_PLACEMENT_OK\r\n")


def main():
    key = paramiko.RSAKey.generate(2048)
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen(8)
    fingerprint = "SHA256:" + base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip("=")
    print(json.dumps({"port": listener.getsockname()[1], "fingerprint": fingerprint}), flush=True)

    def serve(client):
        transport = paramiko.Transport(client)
        try:
            transport.add_server_key(key)
            transport.start_server(server=Server())
            channel = transport.accept(30)
            if channel is not None:
                channel.send(b"retry fixture ready\r\n")
                pending = b""
                while transport.is_active():
                    data = channel.recv(1024)
                    if not data:
                        break
                    channel.send(data)
                    pending += data.replace(b"\r", b"\n")
                    while b"\n" in pending:
                        command, pending = pending.split(b"\n", 1)
                        if command in (b"exit", b"exit 37"):
                            channel.send_exit_status(37 if command == b"exit 37" else 0)
                            channel.close()
                            return
                        if command == b"fixture-drop":
                            return  # A lost transport has no confirmed shell exit.
                        if command == b"fixture-kitty":
                            kitty_roundtrip(channel)
                        if command == b"fixture-hold-ready":
                            channel.sendall(b"\x1b[2J\x1b[HSYNC-READY")
                        if command == b"fixture-hold":
                            # Deliberately never release the hold or send more data.
                            # The native event loop must schedule the timeout itself.
                            channel.sendall(b"\x1b[?2026h\x1b[2J\x1b[HSYNC-PENDING")
                        if command == b"fixture-prompt":
                            channel.sendall(b"\x1b[2J\x1b[H" + b"previous output\r\n" * 100 +
                                            b"+----------------+\r\n> QUIET-PROMPT\r\n+--PROMPT-BOTTOM--+")
        except (paramiko.SSHException, EOFError, OSError):
            pass
        finally:
            transport.close()

    while True:
        client, _ = listener.accept()
        threading.Thread(target=serve, args=(client,), daemon=True).start()


if __name__ == "__main__":
    main()
