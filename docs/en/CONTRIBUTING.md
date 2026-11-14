<p align="center">
  <a href="../../CONTRIBUTING.md">中文</a> · <strong>English</strong>
</p>

# Contributing

For people who want to change code, add a map, or report a problem. **Just want to play?** Start at
the [README](../../README.md); deployment and player flow are in [`05-usage.md`](05-usage.md).

## Read first

| Want to know | Read |
| --- | --- |
| Layers, state machine, network protocol, snapshot and restore | [`03-architecture.md`](03-architecture.md) |
| UI visual specification (colour, metrics, states, motion, copy) | [`04-visual.md`](04-visual.md) |
| How built-in maps are converted, how to add one | [`06-maps.md`](06-maps.md) |
| Which conventions are hard, and where the traps are | [`AGENTS.md`](../../AGENTS.md) |

`AGENTS.md` is the condensed version written for coding agents (AI included) and reads the same for
humans: it only covers things you cannot read off the code and that cost a rewrite when missed —
localisation, command surface, UI dimensions, sandbox rules, documentation sync. **This file covers
environment, build, testing and submission.** Design rationale is not repeated here.

## Environment

| Tool | Version |
| --- | --- |
| JDK | 21 (required by 1.21.1; **JDK 17 cannot run the client**) |
| Gradle | 8.14.3 (bundled with the wrapper, no separate install) |
| Fabric Loom | 1.11.8 |
| Yarn mappings | 1.21.1+build.3 |
| Fabric Loader | 0.16.9 |
| Fabric API | 0.116.17+1.21.1 |

`gradle.properties` pins the JDK path used by the Gradle daemon (Loom 1.11 requires JVM 21 or
newer). On another machine or in CI, change that line or delete it and use the environment variable:

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21" ./gradlew build
```

## Build

**The repository root is the Gradle root.** A **cell** is one Minecraft version x one loader, and it
is a subproject, so commands read `./gradlew :<cell>:<task>`. There are two cells today:
`1.21.1-fabric` and `1.21.1-neoforge`.

```bash
./gradlew :1.21.1-fabric:compileJava :1.21.1-fabric:compileClientJava   # one cell, fastest feedback
./gradlew :1.21.1-fabric:build            # output collected into dist/1.21.1-fabric/
./gradlew buildAll                        # every cell, one folder per cell under dist/
```

**There is one place for a finished jar: `dist/<cell>/` at the repository root.** Two jars per cell
(the mod and its sources), eight for the four cells; `buildAll` prints the list when it finishes.
`versions/<cell>/build/libs/` also holds intermediates (Fabric's dev jar sits in `devlibs/`), which
are not what gets installed. `dist/` is in `.gitignore`; build output is never committed.

The two loaders are **parallel**, with no primary one: `versions/1.21.1-fabric/` and
`versions/1.21.1-neoforge/` each have a `build.gradle` and share the `src/` at the root. Everything
that does not depend on the loader (archive name, Java level, licence files, dev client list) lives
in `gradle/pvpocket-cells.gradle`, which both cells `apply from:`.

> Why a loader has to be a subproject rather than a second configuration on one project: Loom and
> ModDevGradle both take over the minecraft dependency, the run tasks and remapping, and a project
> can only apply one of them.

**The jar root carries three legal files**: `LICENSE`, `LICENSE-GPL-3.0.txt` and
`THIRD-PARTY-NOTICES.md`. They are not in `src/main/resources`; `processResources` in `build.gradle`
copies them in from the repository root, because a licence should exist in exactly one place — the
root copy — and copies drift. LGPL-3.0 §4 requires that whoever receives the object code also receives
the licence, and what gets distributed is the jar, not the repository. Renaming any of them means
updating `legalFiles` in `build.gradle`: a name that no longer matches fails the build instead of
quietly producing a jar without a licence.

Sources are split across two source sets, and the boundary must hold:

| Directory | Contents | Constraint |
| --- | --- | --- |
| `src/main/java/` | Shared by both sides: `session`, `rules`, `loadout`, `preset`, `network`, `arena` | **Loaded by the dedicated server**, so no client classes |
| `src/client/java/` | Client only: `client/gui`, `client/hud`, `client/preset` | May reference `src/main`, never the reverse |

Open the repository root in IDEA and import it as a Gradle project; `1.21.1-fabric` and
`1.21.1-neoforge` show up as two projects.

## Development testing

A duel needs at least two players, so four clients are configured. **They cannot share a run
directory**: two JVMs in the same directory fight over `logs/latest.log` (the loser silently loses
its log), and `options.txt`, `saves` and the resource index overwrite each other. Run directories are
decided by `gradle/pvpocket-cells.gradle` and all live under `runs/<cell>/` (not in version control):

| Run configuration | Directory | Username | Purpose |
| --- | --- | --- | --- |
| `runServer` | `runs/<cell>/server/` | — | Shared server (world save and `pvpocket/journal` live here) |
| `runClient` | `runs/<cell>/client1/` | `Dev1` | Client 1 |
| `runClient2` | `runs/<cell>/client2/` | `Dev2` | Client 2 |
| `runClient3` | `runs/<cell>/client3/` | `Dev3` | Client 3, **only needed to test spectating** |
| `runClient4` | `runs/<cell>/client4/` | `Dev4` | Client 4, **only needed to test "one map cannot host two matches"** |

Usernames must differ too: a development client logs in with the operating-system username by
default, so two clients become the same player and the later join kicks the earlier one out.

All five are Gradle tasks, **double-click them in IDEA's Gradle tool window** (`Tasks > 1.21.1-fabric >
runClient` … `runServer`; `1.21.1-neoforge` exposes the same names) — no terminal, no run configurations to maintain. The
client already connects to `localhost:25565` with a 1G heap, so it is playable as soon as it opens:

| Parameter | Default | Purpose |
| --- | --- | --- |
| `-PquickJoin=<host:port>` | `localhost:25565` | Connect somewhere else |
| `-PquickJoin=off` | — | Stay on the title screen (server down, UI-only work) |
| `-PclientHeap=<size>` | `1G` | Raise the client heap |
| `-PdebugBlocking` | — | Force the local player into sword blocking to inspect the pose (first and third person) without starting a Legacy duel |

```bash
# Terminal 1: shared server, keep it in the foreground (so the console still accepts stop / op / pvpocket)
./gradlew :1.21.1-fabric:runServer

# Terminal 2: once "Done (...)" appears, launch clients that join automatically
scripts/dev-clients.sh                       # two clients
PVP_CLIENTS=3 scripts/dev-clients.sh         # three, the third one for spectating
PVP_ADDR=192.168.1.10:25565 scripts/dev-clients.sh
```

To bring up the whole set with one command (server in the background, clients start only once it accepts connections):

```bash
scripts/dev-duel.sh                           # NeoForge, Dev1 + Dev2
CELL=1.21.1-fabric scripts/dev-duel.sh        # another cell
```

The trade-off is that the server console is no longer interactive (`stop` / `op` are unavailable); what
you get is "edit, run, look" without babysitting two terminals. Ctrl+C stops the clients and the server.

The script is only a batch wrapper around `runClient` / `runClient2` / …; to start one on its own use
the task name (`./gradlew runClient3`), or double-click it in IDEA's Gradle panel.

Three JVMs are heavy for one development machine, and the third client sometimes stalls on the title
screen without joining (`quickPlay` did not fire) — close and reopen it. With no server running the
client stops at "connection failed"; click back to the title screen and connect by hand. Day-to-day
testing should prefer the dedicated server above.

Logs: server `runs/server/logs/latest.log`, clients `build/devlogs/client{1,2,3}.out`.
**When a panel does not appear, read the `canSend=` line in the client log first.**

## Repository layout

The repository root holds documentation and one directory per platform; **each loader + game-version
pair gets its own subdirectory** with its own Gradle build, so adding Forge / NeoForge or another
1.21.x later does not contaminate the others.

```
PvPocket/
├── docs/
│   ├── zh/                    Specification (Chinese)
│   ├── en/                    Specification (English, one-to-one with zh); README.md and
│   │                          CONTRIBUTING.md here are the English entry points
│   ├── assets/                Documentation images; logo.png is the one at the top of the README
│   └── archive/               Historical: proposals, phase logs, one-off research (not normative)
├── scripts/                   Index in scripts/README.md
│   ├── dev-duel.sh            One command for the whole set: server + two clients, auto-join
│   ├── dev-clients.sh         Development: launch N clients and join automatically
│   ├── dev.cmd                Windows entry for dev-duel.sh; takes a cell name
│   ├── check-logic.sh         Offline self-check: rules / loadout content / mixin targets
│   ├── check-*.py             Comments, lang keys, loadout manifest, layering, rule docs
│   ├── check-ui.sh            UI occlusion audit: walks every screen, fails on any finding
│   ├── checks/                Three *Check.java classes, compiled and run by check-logic.sh
│   ├── map/                   Map conversion and checks (world2struct / colosseum / arena-* / rcon)
│   ├── ui/                    Screenshot and input scripts for AI agents; not needed for normal work
│   ├── mojmap/                Yarn→Mojmap migration tooling (one-off, finished)
│   └── port/                  Version-difference generator and rename tables
├── settings.gradle            Cell list: one cell, one subproject
├── build.gradle               Only repositories + buildAll; applies no loader plugin
├── gradle.properties          JDK path, Loom download mirrors, mod identity (mod_id / mod_version / maven_group)
├── gradle/
│   ├── pvpocket-cells.gradle  The half both cells share: archive name, Java level, licence, dev clients
│   ├── libs.versions.toml     Loader toolchain versions (fabric-loader / Loom / ModDevGradle) + plugin coordinates
│   ├── catalogs/<mc>.toml     One file per MC version: minecraft / fabricApi / neoforge
│   └── wrapper/
├── src/                       Shared sources, compiled by every cell
│   ├── main/                  common: shared by server and client
│   ├── client/                Client only, loader-agnostic
│   ├── fabric/ client-fabric/ Fabric wiring
│   └── neoforge/ client-neoforge/   NeoForge wiring
├── versions/
│   ├── 1.21.1-fabric/         The Fabric cell: loader plugin + dependencies + runs
│   └── 1.21.1-neoforge/       The NeoForge cell, parallel to the line above
├── legacy/1.20.1/             Separate Gradle root for 1.20.1 (FG6 needs Gradle 8; not built yet)
├── runs/<cell>/               Run directories (clients 1–4 + server, not in version control)
├── arena-src/                 Map sources (not in version control, see docs/zh/06-maps.md)
├── CHANGELOG.md
├── CONTRIBUTING.md            This file's Chinese original
├── AGENTS.md                  Notes for coding agents: commands, hard rules, traps
├── LICENSE                    LGPL-3.0
├── LICENSE-GPL-3.0.txt        Copy of GPL-3.0, which LGPL-3.0 incorporates
├── THIRD-PARTY-NOTICES.md
└── .gitignore / .gitattributes
```

## Scripts

| Script | For | Purpose |
| --- | --- | --- |
| `scripts/dev-clients.sh` | Development | Launch development clients that join the local server |
| `scripts/dev-duel.sh` | Development | Whole set in one command: background server + two clients, connecting only after `Done (` |
| `scripts/dev.cmd` | Development | Windows entry for the line above; `scripts\dev.cmd 1.21.11-neoforge` picks a cell |
| `scripts/check-logic.sh` | Development / CI | Compiles and runs `scripts/checks/*Check.java`, then the mixin targets |
| `scripts/check-ui.sh` | Development / CI | UI occlusion audit: every screen and both canvas sizes, non-zero exit on a finding |
| `scripts/map/world2struct.py` | Development / maps | World save, `.litematic`, `.schem` → structure template |
| `scripts/map/colosseum.py` | Maps | Generate the colosseum template |
| `scripts/map/arena-verify.py` | Maps | Compare block by block against a third-party reader |
| `scripts/map/arena-map.py` | Diagnostics | Render a top-down view to check cell boundaries |
| `scripts/map/rcon.py` | Scripted testing | Run commands as a player from a script |
| `scripts/ui/*.ps1` | AI agents | Window screenshots, key presses, mouse clicks; Windows + PowerShell |

The four map scripts have invocation examples in [`../zh/06-maps.md`](../zh/06-maps.md) section 3 and
a list in section 6. Every script, and which one to run for a given task, is indexed in
[`scripts/README.md`](../../scripts/README.md).

Scripts under `scripts/ui/` assume a development client is running on this machine with
`Minecraft` in the window title. They are not meant to be run by hand; check for other automation
before changing or deleting them.

## Verification

**Compiling is not the same as working.** Pick from these five by the scope of the change:

1. **Compile**: `./gradlew compileJava compileClientJava`.
2. **Play a match**: changes to rules, sessions, loadouts or networking need a full match on both
   sides. The server journal (`runs/server/world/pvpocket/journal/*.jsonl`) records `RULES_PROPOSED`,
   `RULES_ACCEPTED`, `ROUND_STARTED` and friends, which answers "which revision of the rules was this
   round actually played under". Note that `=` is escaped as `\u003d` in the journal, so
   `grep 'c.maxHealth='` finds nothing.
3. **UI audit**: run `scripts/check-ui.sh` after touching a GUI (about two minutes; exit code `0` is
   clean, `1` means occlusion, `2` means the audit did not finish). It makes the development client
   walk six screens × every page and dialog × two canvas sizes, recording widget boxes and text in
   real rendered frames. **When adding a screen or page, add a variant to
   `auditVariantCount()/auditVariant()`**, otherwise the new page is not covered.
4. **Data self-check**: run `./gradlew statsCheck` after touching `stats/DuelStats`. The stats files
   are the one place where a bug is permanent — a duplicated history row, a cap that drops the wrong
   end — and neither fails anything visibly; it just leaves one player's record wrong forever.
5. **Language files**: after editing `zh_cn.json` / `en_us.json`, run the key-set and placeholder
   check (the command is in [`AGENTS.md`](../../AGENTS.md) under "Localisation").

Two rules for UI automation; breaking them produces false conclusions. Capture with
`scripts/ui/shot-window.ps1` (`PrintWindow` + `PW_RENDERFULLCONTENT`; `CopyFromScreen` silently
returns the desktop wallpaper when the window is covered). Click with
`scripts/ui/click-frac.ps1` using client-area fractions, never absolute pixels. The rest of the
trap list is in `AGENTS.md`.

**Delete screenshots once used** (`find runs -name '*.png' -delete`); keep only the ones meant for
showing someone.

## Committing

- Commit messages in Chinese: one line saying what changed, a body explaining **why**, especially
  trade-offs.
- One commit, one concern. Keep formatting and renames out of functional changes, or review cannot
  see what really moved.
- Never disable a check to get a build through, and never `--no-verify`.
- Player-visible behaviour changes go into `CHANGELOG.md`.

## Documentation

- `docs/zh/` and `docs/en/` are parallel, six documents one-to-one. **A change to one must be made
  to the other**, or the two specifications drift apart.
- `CHANGELOG.md` records only what users can perceive and decisions that change how things are done
  later, not internal refactors.
- `docs/archive/` is historical and **not normative**; old paths and old commands there are not kept
  up to date.
- Division of labour: **README introduces the mod, this file covers the engineering workflow, `docs/`
  holds the specification and design rationale, `AGENTS.md` lists the hard rules.**

## Licence

By submitting you agree to license your contribution under [LGPL-3.0](../../LICENSE). New
third-party assets (textures, maps, sounds) must be compatible with that licence and recorded with
source and licence in [`THIRD-PARTY-NOTICES.md`](../../THIRD-PARTY-NOTICES.md).
