#!/usr/bin/env python3
# Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: GPL-3.0-only
"""Local-only SSH password fixture. Requires Paramiko on the development host."""
import base64
import hashlib
import json
import socket
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
                while transport.is_active():
                    data = channel.recv(1024)
                    if not data:
                        break
                    channel.send(data)
        except (paramiko.SSHException, EOFError, OSError):
            pass
        finally:
            transport.close()

    while True:
        client, _ = listener.accept()
        threading.Thread(target=serve, args=(client,), daemon=True).start()


if __name__ == "__main__":
    main()
