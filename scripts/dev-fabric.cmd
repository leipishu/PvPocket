@echo off
setlocal
rem Windows entry point for dev-fabric.sh. Only Git Bash works: the bash on PATH is often the
rem WSL launcher, which has no Windows view of netstat/taskkill.
rem ASCII only, no BOM: cmd.exe parses .cmd in the OEM codepage.
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
  echo       Or run it from a Git Bash window: bash scripts/dev-fabric.sh
  exit /b 1
)
"%GITBASH%" "%~dp0dev-fabric.sh" %*
