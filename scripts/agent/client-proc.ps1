# Finds a dev client's java process by the username it runs as.
#
# Why this is a shared file: the scripts that touch a client window (shot-window, click-frac,
# post-key, post-type, raise-client) all need the same lookup, and they got it wrong in the same
# way, so the bug is in the lookup rather than in any one of them.
#
# The arguments are not necessarily on the command line. ModDevGradle writes them to
# `clientRunProgramArgs.txt` and hands DevLaunch an @argfile, so CommandLine contains only the
# file path and `--username NeoDev1` lives inside it. Matching CommandLine alone therefore never
# finds a NeoForge client, and the error reads "no client process", which looks like the client
# never started.
#
# ASCII only: this file has no BOM and Windows PowerShell 5.1 reads it with the ANSI codepage.

function Get-EffectiveCommandLine($proc) {
    $cl = $proc.CommandLine
    if (-not $cl) { return "" }
    $extra = ""
    foreach ($m in [regex]::Matches($cl, '@([^\s"]+\.txt)')) {
        $path = $m.Groups[1].Value.Replace('\\', '\')
        if (Test-Path -LiteralPath $path) {
            $extra += " " + ((Get-Content -LiteralPath $path -Raw) -replace '\s+', ' ')
        }
    }
    return "$cl $extra"
}

function Find-ClientProcess([string]$Username) {
    return Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { (Get-EffectiveCommandLine $_) -like "*--username $Username*" } |
        Select-Object -First 1
}
