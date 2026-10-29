#!/usr/bin/env python3
"""Robust RCON client: rcon.py <host> <port> <password> <command> [command...]

Socket timeout defaults to 30s; override with RCON_TIMEOUT (seconds) for
long-running commands like WorldEdit //set.
"""
import os
import socket
import struct
import sys

_TIMEOUT = int(os.environ.get("RCON_TIMEOUT", "30"))


def session(host, port, password, commands):
    with socket.create_connection((host, int(port)), timeout=_TIMEOUT) as s:
        def send(rid, ptype, body):
            p = struct.pack("<ii", rid, ptype) + body.encode() + b"\x00\x00"
            s.sendall(struct.pack("<i", len(p)) + p)
        def recv():
            raw = b""
            while len(raw) < 4:
                chunk = s.recv(4 - len(raw))
                if not chunk: return None
                raw += chunk
            (n,) = struct.unpack("<i", raw)
            d = b""
            while len(d) < n:
                chunk = s.recv(n - len(d))
                if not chunk: break
                d += chunk
            rid, ptype = struct.unpack("<ii", d[:8])
            return rid, d[8:-2].decode(errors="replace")
        send(1, 3, password)
        rid, _ = recv()
        if rid == -1:
            print("AUTH FAILED"); sys.exit(1)
        out = []
        for cmd in commands:
            send(2, 2, cmd)
            out.append(recv()[1])
        return out

if __name__ == "__main__":
    for r in session(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]):
        print(r)
