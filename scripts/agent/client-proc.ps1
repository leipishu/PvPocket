# Finds a dev client's java process by --username. The args may sit in an @argfile, not on the
# command line. ASCII only: Windows PowerShell 5.1 reads this file with the ANSI codepage.

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
