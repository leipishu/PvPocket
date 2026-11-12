#!/usr/bin/env python3
"""Drives a full multi-round series on the dev server and prints the journal as it happens.

Why this exists: a series is the one part of the mod that cannot be checked by looking at a single
screenshot. It is a state machine over time — settle, intermission, terrain revert, next round,
series end — and the only evidence that it behaves is the event log the server already writes. This
script issues the same commands a player would (challenge, propose, accept, surrender) through RCON
and tails the journal between them, so a regression shows up as a missing or out-of-order event
instead of as "the match felt wrong".

Rounds are decided by surrender rather than by killing: a death parks the loser on the client's death
screen, which needs a click to respawn, and no headless run can supply that click. Surrender reaches
the same settle() through a different door, so the intermission and the round transition are
exercised identically.

Usage:
    python scripts/series-test.py                     # 3 rounds, Dev1 loses every round
    python scripts/series-test.py --alternate         # 3 rounds, alternating loser
    python scripts/series-test.py --loser Dev2
"""
import argparse
import glob
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

JOURNAL_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..",
                           "runs", "1.21.1-fabric", "server", "world", "pvpocket", "journal")


def newest_journal(after_mtime=0):
    files = glob.glob(os.path.join(JOURNAL_DIR, "*.jsonl"))
    files = [f for f in files if os.path.getmtime(f) >= after_mtime]
    if not files:
        return None
    return max(files, key=os.path.getmtime)


class Tail:
    """Reads a journal file and hands back only the events it has not printed yet."""

    def __init__(self, path):
        self.path = path
        self.offset = 0
        self.seen = []

    def poll(self):
        out = []
        try:
            with open(self.path, encoding="utf-8") as fh:
                fh.seek(self.offset)
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    self.offset += len(line.encode("utf-8")) + 1
                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    self.seen.append(event)
                    out.append(event)
        except FileNotFoundError:
            return []
        return out

    def wait_for(self, *types, timeout=30.0):
        """Blocks until one of the named events appears; returns it or None on timeout.

        Every event read is printed, including the ones after the match in the same batch: a poll
        returns whatever accumulated since the last call, and returning early on the match would
        silently swallow the events that follow it — which is exactly where the intermission and the
        terrain revert live.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            found = None
            for event in self.poll():
                print_event(event)
                if found is None and event.get("type") in types:
                    found = event
            if found is not None:
                return found
            time.sleep(0.25)
        return None


def print_event(event):
    kind = event.get("type", "?")
    rest = {k: v for k, v in event.items() if k not in ("type", "t")}
    print(f"  {kind:<20} {rest}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="pvpocket")
    parser.add_argument("--challenger", default="Dev1")
    parser.add_argument("--target", default="Dev2")
    parser.add_argument("--loser", default=None, help="who surrenders each round")
    parser.add_argument("--alternate", action="store_true", help="alternate the loser each round")
    parser.add_argument("--rounds", type=int, default=3, help="stop after this many rounds")
    args = parser.parse_args()

    sock = rcon.connect(args.host, args.port, args.password)
    started = time.time() - 1

    def cmd(text):
        out = rcon.command(sock, text, linger=0.4).strip()
        print(f"> {text}" + (f"   -> {out}" if out else ""))
        return out

    print("== 准备")
    cmd(f"execute as {args.challenger} run pvpocket duel {args.target}")
    time.sleep(1.0)
    cmd(f"execute as {args.challenger} run pvpocket propose")
    time.sleep(1.0)
    cmd(f"execute as {args.target} run pvpocket accept")

    path = None
    deadline = time.time() + 20
    while path is None and time.time() < deadline:
        path = newest_journal(started)
        time.sleep(0.3)
    if path is None:
        print("没有找到新的会话日志，发起阶段就失败了。", file=sys.stderr)
        return 1
    print(f"== 日志 {os.path.basename(path)}")

    tail = Tail(path)
    loser = args.loser or args.challenger
    round_no = 0
    while round_no < args.rounds:
        event = tail.wait_for("ROUND_STARTED", "SESSION_ABORTED", "VOIDED", timeout=25)
        if event is None:
            print("等不到开局，中止。", file=sys.stderr)
            return 1
        if event.get("type") != "ROUND_STARTED":
            print("系列赛在开局前就结束了，中止。", file=sys.stderr)
            return 1
        round_no = event.get("round", round_no + 1)
        print(f"-- 第 {round_no} 局，{loser} 认输")
        cmd(f"execute as {loser} run pvpocket surrender")
        settled = tail.wait_for("ROUND_ENDED", timeout=15)
        if settled is None:
            print("认输没有触发结算。", file=sys.stderr)
            return 1
        if args.alternate:
            loser = args.target if loser == args.challenger else args.challenger
        # A series ends with SERIES_ENDED, a live one continues with ROUND_READY.
        nxt = tail.wait_for("SERIES_ENDED", "ROUND_READY", timeout=15)
        if nxt is None:
            print("结算之后既没有续局也没有结束。", file=sys.stderr)
            return 1
        if nxt.get("type") == "SERIES_ENDED":
            tail.wait_for("ARCHIVED", timeout=20)
            print("== 系列赛结束")
            break

    # Anything left over (restores, release) is worth seeing.
    time.sleep(1.0)
    for event in tail.poll():
        print_event(event)
    sock.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
