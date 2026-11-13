@echo off
setlocal
rem Double-clickable entry for the full duel: server in the background, then the dev clients.
rem
rem Usage:  scripts\dev.cmd                     -> CELL from the environment, else 1.21.1-neoforge
rem         scripts\dev.cmd 1.21.11-neoforge    -> that cell
rem
rem Only Git Bash works: the bash on PATH is often the WSL launcher, which has no Windows view of
rem netstat/taskkill, and the port guard silently stops working. ASCII only, no BOM: cmd.exe parses
rem .cmd in the OEM codepage.

set "CELL_ARG=%~1"
if not "%CELL_ARG%"=="" set "CELL=%CELL_ARG%"

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
  echo       Or run it from a Git Bash window: bash scripts/dev-duel.sh
  exit /b 1
)

"%GITBASH%" "%~dp0dev-duel.sh"
