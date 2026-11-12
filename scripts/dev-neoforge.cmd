@echo off
setlocal
rem Windows entry point for dev-neoforge.sh.
rem
rem Why not just "bash": on Windows, bash on PATH is often
rem C:\Users\<user>\AppData\Local\Microsoft\WindowsApps\bash.exe, which is the WSL
rem launcher. This script needs netstat / taskkill with the Windows view of
rem processes (cleanup kills orphaned JVMs by port), and WSL has neither that view
rem nor the same paths. So only Git Bash is accepted, found by common install path.
rem
rem ASCII only, no BOM: cmd.exe reads a .cmd with the OEM codepage. A UTF-8 file
rem with non-ASCII comments is parsed as garbage and the batch file dies on the
rem first block -- which looks like "the script is broken", not "the encoding is".
rem
rem Usage (PowerShell or cmd):
rem   scripts\dev-neoforge.cmd
rem   $env:PVP_CLIENTS=3; scripts\dev-neoforge.cmd
set "GITBASH="
for %%P in (
  "%ProgramFiles%\Git\bin\bash.exe"
  "%ProgramFiles%\Git\usr\bin\bash.exe"
  "%ProgramFiles(x86)%\Git\bin\bash.exe"
  "%LocalAppData%\Programs\Git\bin\bash.exe"
) do (
  if not defined GITBASH if exist %%P set "GITBASH=%%~P"
)
if not defined GITBASH (
  echo [dev] Git Bash not found. Install Git for Windows, expected at:
  echo        C:\Program Files\Git\bin\bash.exe
  echo       Or run it from a Git Bash window: bash scripts/dev-neoforge.sh
  exit /b 1
)
"%GITBASH%" "%~dp0dev-neoforge.sh" %*
