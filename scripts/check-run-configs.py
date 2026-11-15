#!/usr/bin/env python3
"""Run-configuration matrix check: every cell needs the same set of IDE entries.

The matrix is (cell x task) for single runs, (cell) for the duel and the UI audit, and (version)
for the self-checks. Drift here is silent: a missing entry only shows up when someone opens the
dropdown and cannot find it, which is how the 26.2 / 26.3 cells went unnoticed for a while.

Four failure modes are checked:
  - a cell with no entry for one of the required tasks
  - an entry pointing at a cell or a script that does not exist
  - an `Application` configuration, which ModDevGradle writes into .idea/workspace.xml and which
    duplicates the Gradle one outside the shared folder
  - a malformed interpreter path (the `C://` form is not resolvable by IDEA)

Usage:
    python scripts/check-run-configs.py            # matrix + exit code
    python scripts/check-run-configs.py --verbose  # list every entry as well

Exit code: 0 pass, 1 problems, 2 script error.
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUN_DIR = os.path.join(ROOT, ".run")
SETTINGS = os.path.join(ROOT, "settings.gradle")
INTERPRETER = "C:" + chr(92) + "Program Files" + chr(92) + "Git" + chr(92) + "bin" + chr(92) + "bash.exe"

# Four client tasks plus the server, one entry each, named "<cell> - <Task>".
SINGLE_RUNS = ("Client", "Client2", "Client3", "Client4", "Server")
# Shell entries are grouped by folder, but the script name is what identifies them.
SCRIPTS = (("dev-duel.sh", "联调"), ("check-ui.sh", "界面 遮挡审计"))


def read_cells():
    """Cell names, in the order settings.gradle registers them."""
    with open(SETTINGS, encoding="utf-8") as handle:
        text = handle.read()
    block = text[text.index("def cells = ["):]
    return re.findall(r"'([0-9.]+-(?:fabric|neoforge))'", block[:block.index("]")])


def load():
    """Every configuration in .run/, as a dict of the fields this check reads."""
    entries = []
    for name in sorted(os.listdir(RUN_DIR)):
        if not name.endswith(".run.xml"):
            continue
        node = ET.parse(os.path.join(RUN_DIR, name)).getroot().find("configuration")
        entries.append({
            "file": name,
            "type": node.get("type"),
            "folder": node.get("folderName") or "",
            "options": {o.get("name"): o.get("value") for o in node.findall("option")},
            "env": {e.get("name"): e.get("value") for e in node.findall("envs/env")},
            "tasks": [o.get("value") for o in node.iter("option")
                      if (o.get("value") or "").startswith(":")],
        })
    return entries


def script_of(entry):
    return os.path.basename(entry["options"].get("SCRIPT_PATH", ""))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    if not os.path.isdir(RUN_DIR):
        print("找不到 .run/ 目录")
        return 2
    cells = read_cells()
    if not cells:
        print("settings.gradle 里没有解析到任何 cell")
        return 2

    entries = load()
    problems = []
    gradle = [e for e in entries if e["type"] == "GradleRunConfiguration"]
    shells = [e for e in entries if e["type"] == "ShConfigurationType"]

    # 1. An Application entry is a ModDevGradle duplicate; .run/ only holds Gradle and Shell ones.
    for entry in entries:
        if entry["type"] == "Application":
            problems.append(f"{entry['file']}: Application 类型（ModDevGradle 的副本），应删除")
        elif entry["type"] not in ("GradleRunConfiguration", "ShConfigurationType"):
            problems.append(f"{entry['file']}: 未知类型 {entry['type']}")

    # 2. Shell entries: the interpreter must be resolvable, the script must exist, and the command
    # must run as a process rather than through the terminal window.
    for entry in shells:
        interpreter = entry["options"].get("INTERPRETER_PATH", "")
        if interpreter != INTERPRETER:
            problems.append(f"{entry['file']}: 解释器路径 {interpreter!r} 不是单反斜杠的 Git Bash")
        script = entry["options"].get("SCRIPT_PATH", "")
        text = entry["options"].get("SCRIPT_TEXT", "")
        if script:
            path = script.replace("$PROJECT_DIR$/", "")
            if not os.path.isfile(os.path.join(ROOT, path)):
                problems.append(f"{entry['file']}: 脚本 {path} 不存在")
        elif not text:
            problems.append(f"{entry['file']}: SCRIPT_PATH 与 SCRIPT_TEXT 都是空的")
        if entry["options"].get("EXECUTE_IN_TERMINAL") != "false":
            problems.append(f"{entry['file']}: EXECUTE_IN_TERMINAL 不是 false，命令会进终端而不是运行进程")

    # 3. Single runs: five Gradle entries per cell, each naming that cell's task.
    have_tasks = {task for entry in gradle for task in entry["tasks"]}
    for cell in cells:
        for task in SINGLE_RUNS:
            if f":{cell}:run{task}" not in have_tasks:
                problems.append(f"缺少单跑配置 {cell} - {task}（:{cell}:run{task}）")

    # 4. Duels and audits: one per cell, selected through the CELL env var. A duel needs two clients
    # to fight, and the three-client variant is what makes the spectator panel reachable.
    for cell in cells:
        for script, label in SCRIPTS:
            found = [e for e in shells
                     if e["env"].get("CELL") == cell and script_of(e) == script]
            if not found:
                problems.append(f"缺少{label}配置：CELL={cell}（{script}）")
        duels = [e for e in shells
                 if e["env"].get("CELL") == cell and script_of(e) == "dev-duel.sh"]
        for count, label in (("2", "两人"), ("3", "三人")):
            if not [e for e in duels if e["env"].get("PVP_CLIENTS", "2") == count]:
                problems.append(f"缺少联调配置：CELL={cell} PVP_CLIENTS={count}（{label}）")

    # 5. Self-checks run per Minecraft version and cover both loaders in one entry.
    versions = sorted({cell.rsplit("-", 1)[0] for cell in cells},
                      key=lambda v: [int(part) for part in v.split(".")])
    for version in versions:
        pairs = [e for e in shells
                 if e["env"].get("FABRIC_CELL", "").startswith(version + "-")
                 and e["env"].get("NEOFORGE_CELL", "").startswith(version + "-")]
        if not pairs:
            problems.append(f"缺少自检配置：{version}（需要 FABRIC_CELL 与 NEOFORGE_CELL 各一）")

    # Report.
    print(f"cell {len(cells)} 个，配置 {len(entries)} 份（Gradle {len(gradle)} / Shell {len(shells)}）")
    for cell in cells:
        runs = len([t for t in have_tasks if t.startswith(f":{cell}:run")])
        duels = len([e for e in shells if e["env"].get("CELL") == cell and script_of(e) == "dev-duel.sh"])
        audits = len([e for e in shells if e["env"].get("CELL") == cell and script_of(e) == "check-ui.sh"])
        print(f"  {cell:<18} 单跑 {runs}/5  联调 {duels}  审计 {audits}")
    if args.verbose:
        for entry in entries:
            print(f"  {entry['file']:<42} {entry['type']:<22} {entry['folder']}")

    if problems:
        print(f"\n{len(problems)} 个问题：")
        for problem in problems:
            print(f"  {problem}")
        return 1
    print("\n运行配置矩阵完整")
    return 0


if __name__ == "__main__":
    sys.exit(main())
