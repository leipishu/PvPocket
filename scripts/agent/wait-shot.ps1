param(
    # Polls the server log until a marker's occurrence count rises, then captures the client
    # window immediately. Built for the preparation wall: it only exists for the countdown
    # (5 s by default), which is shorter than a person can react to, so the capture has to be
    # triggered by the log line rather than by hand.
    [string]$Log = "",
    [string]$Marker = "mode 2",
    [string]$Username = "Dev1",
    [string]$Out = "",
    [int]$TimeoutSec = 45,
    [int]$SettleMs = 350
)

function Count-Marker {
    if (-not (Test-Path $Log)) { return 0 }
    # -Raw keeps newlines so the count matches what grep -c would report for line occurrences.
    $text = Get-Content -Path $Log -Raw -ErrorAction SilentlyContinue
    if ($null -eq $text) { return 0 }
    return ([regex]::Matches($text, [regex]::Escape($Marker))).Count
}

$baseline = Count-Marker
Write-Output "baseline $Marker = $baseline, waiting up to ${TimeoutSec}s ..."
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$hit = $false
while ((Get-Date) -lt $deadline) {
    if ((Count-Marker) -gt $baseline) { $hit = $true; break }
    Start-Sleep -Milliseconds 25
}
if (-not $hit) { Write-Output "marker never appeared"; exit 1 }
Write-Output "marker hit at $(Get-Date -Format 'HH:mm:ss.fff') -> capturing"

& (Join-Path $PSScriptRoot "shot-client.ps1") -Username $Username -Out $Out -SettleMs $SettleMs
Write-Output "captured at $(Get-Date -Format 'HH:mm:ss.fff')"
