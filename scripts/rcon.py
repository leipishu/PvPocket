#!/usr/bin/env python3
"""Minimal RCON client for driving the dev server from a script.

Why this exists: the dev server runs as a background process, so its stdin is not reachable from a
test script. RCON is the only way to run a command *as a player* (`execute as Dev1 run ...`), which
is what makes it possible to open the rule panel on a real client without a human at the keyboard.

Usage:
    python scripts/rcon.py "execute as Dev1 run pvpocket duel Dev2"
    python scripts/rcon.py --wait 3 "list"        # keep reading for N extra seconds
"""
import argparse
import socket
import struct
import sys
import time

SERVERDATA_AUTH = 3
SERVERDATA_AUTH_RESPONSE = 2
SERVERDATA_EXECCOMMAND = 2
SERVERDATA_RESPONSE_VALUE = 0


def _pack(request_id, packet_type, body):
    payload = struct.pack("<ii", request_id, packet_type) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def _read_exact(sock, count):
    buf = b""
    while len(buf) < count:
        chunk = sock.recv(count - len(buf))
        if not chunk:
            raise EOFError("connection closed by server")
        buf += chunk
    return buf


def _read_packet(sock):
    (length,) = struct.unpack("<i", _read_exact(sock, 4))
    data = _read_exact(sock, length)
    request_id, packet_type = struct.unpack("<ii", data[:8])
    return request_id, packet_type, data[8:-2].decode("utf-8", "replace")


def connect(host, port, password, timeout=5.0):
    sock = socket.create_connection((host, port), timeout=timeout)
    sock.settimeout(timeout)
    sock.sendall(_pack(1, SERVERDATA_AUTH, password))
    # The server may emit an empty RESPONSE_VALUE before the auth result; read until we see it.
    while True:
        request_id, packet_type, _ = _read_packet(sock)
        if packet_type == SERVERDATA_AUTH_RESPONSE:
            if request_id == -1:
                raise PermissionError("rcon auth failed")
            break
    return sock


def command(sock, cmd, linger=0.25):
    sock.sendall(_pack(2, SERVERDATA_EXECCOMMAND, cmd))
    out = []
    deadline = time.time() + linger
    sock.settimeout(linger)
    while True:
        try:
            _, packet_type, body = _read_packet(sock)
        except (socket.timeout, TimeoutError):
            break
        if packet_type == SERVERDATA_RESPONSE_VALUE and body:
            out.append(body)
        if time.time() > deadline:
            break
    return "".join(out)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("cmd")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="pvpocket")
    parser.add_argument("--linger", type=float, default=0.25)
    args = parser.parse_args()

    try:
        sock = connect(args.host, args.port, args.password)
    except Exception as exc:  # noqa: BLE001 - a script, not a library
        print(f"rcon: cannot connect: {exc}", file=sys.stderr)
        return 1
    try:
        print(command(sock, args.cmd, args.linger))
    finally:
        sock.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
