#!/usr/bin/env python3
# Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
"""Loopback-only SFTP fixture with real OS symlinks in a disposable directory.

Requires Paramiko. Prints the ephemeral port and host fingerprint as JSON.
No shell/exec access or user files; terminate after the Android checks.
"""
import base64
import errno
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import tempfile
import threading

import paramiko


class Authentication(paramiko.ServerInterface):
    def get_allowed_auths(self, username):
        return "password"

    def check_auth_password(self, username, password):
        return paramiko.AUTH_SUCCESSFUL if (username, password) == ("fixture", "fixture-correct") else paramiko.AUTH_FAILED

    def check_channel_request(self, kind, channel_id):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED


class Handle(paramiko.SFTPHandle):
    def stat(self):
        return paramiko.SFTPAttributes.from_stat(os.fstat((self.readfile or self.writefile).fileno()))


class Files(paramiko.SFTPServerInterface):
    def __init__(self, server, root):
        super().__init__(server)
        self.root = root

    def local(self, path, follow=True):
        candidate = os.path.normpath(self.root + "/" + path.lstrip("/"))
        resolved = os.path.realpath(candidate if follow else os.path.dirname(candidate))
        if os.path.commonpath((self.root, candidate)) != self.root or os.path.commonpath((self.root, resolved)) != self.root:
            raise PermissionError(errno.EACCES, "Outside fixture")
        return candidate

    @staticmethod
    def attempt(operation):
        try:
            return operation()
        except OSError as error:
            return paramiko.SFTPServer.convert_errno(error.errno)

    def list_folder(self, path):
        def listing():
            directory = self.local(path)
            result = []
            for name in os.listdir(directory):
                attrs = paramiko.SFTPAttributes.from_stat(os.lstat(directory + "/" + name))
                attrs.filename = name
                result.append(attrs)
            return result
        return self.attempt(listing)

    def stat(self, path):
        if path == "/H-denied":
            return paramiko.SFTP_PERMISSION_DENIED
        return self.attempt(lambda: paramiko.SFTPAttributes.from_stat(os.stat(self.local(path))))

    def lstat(self, path):
        return self.attempt(lambda: paramiko.SFTPAttributes.from_stat(os.lstat(self.local(path, False))))

    def canonicalize(self, path):
        if path == "/I-unresolved-realpath.txt":
            return path  # Some servers normalize without resolving the final link.
        return self.attempt(lambda: "/" + os.path.relpath(os.path.realpath(self.local(path)), self.root).removeprefix("./"))

    def open(self, path, flags, attr):
        def opening():
            descriptor = os.open(self.local(path), flags, 0o600)
            mode = "r+b" if flags & os.O_RDWR else "wb" if flags & os.O_WRONLY else "rb"
            stream = os.fdopen(descriptor, mode)
            handle = Handle(flags)
            handle.readfile = stream if mode != "wb" else None
            handle.writefile = stream if mode != "rb" else None
            return handle
        return self.attempt(opening)

    def remove(self, path):
        return self.attempt(lambda: (os.unlink(self.local(path, False)), paramiko.SFTP_OK)[1])

    def rename(self, old, new):
        def moving():
            source, destination = self.local(old, False), self.local(new, False)
            if os.path.lexists(destination):
                raise FileExistsError(errno.EEXIST, "Destination exists")
            os.rename(source, destination)
            return paramiko.SFTP_OK
        return self.attempt(moving)

    def mkdir(self, path, attr):
        return self.attempt(lambda: (os.mkdir(self.local(path, False)), paramiko.SFTP_OK)[1])

    def rmdir(self, path):
        return self.attempt(lambda: (os.rmdir(self.local(path, False)), paramiko.SFTP_OK)[1])


def main():
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(SystemExit(0)))
    with tempfile.TemporaryDirectory(prefix="novascale-sftp-links-") as directory:
        root = Path(directory)
        (root / "real-dir").mkdir()
        (root / "real-dir/child.txt").write_text("nested target\n")
        (root / "real-file.txt").write_text("original target\n")
        (root / "large.bin").write_bytes(b"x" * (2 * 1024 * 1024 + 4096))
        for name, target in {
            "A-folder": "real-dir", "B-folder-chain": "A-folder",
            "C-relative-file.txt": "real-file.txt", "D-file-chain.txt": "C-relative-file.txt",
            "E-absolute-file.txt": str(root / "real-file.txt"),
            "F-dangling": "missing-target.txt", "G-loop": "G-loop",
            "H-denied": "real-file.txt", "I-unresolved-realpath.txt": "real-file.txt",
        }.items():
            (root / name).symlink_to(target)
        (root / "many").mkdir()
        for index in range(24):
            (root / "many" / f"link-{index:02}.txt").symlink_to("../real-file.txt")
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
                transport.set_subsystem_handler("sftp", paramiko.SFTPServer, Files, root=directory)
                transport.start_server(server=Authentication())
                while transport.is_active():
                    threading.Event().wait(.1)
            except (paramiko.SSHException, EOFError, OSError):
                pass
            finally:
                transport.close()

        while True:
            client, _ = listener.accept()
            threading.Thread(target=serve, args=(client,), daemon=True).start()


if __name__ == "__main__":
    main()
