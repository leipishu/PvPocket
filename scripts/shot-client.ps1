param(
    [string]$Username = "Dev1",
    [string]$Out = "",
    [int]$SettleMs = 700,
    # Both dev clients open at the same spot, so the one that is not wanted sits on top of the one
    # that is. Minimising it is safe (it is a visibility change, not a resize) and keeps the capture
    # to a single window.
    [switch]$HideOthers
)

# Raise one dev client and capture just its window.
#
# Split out of client-act.ps1 because that one captures the whole desktop, and a 5120-wide capture
# has to be cropped afterwards by guessing where the window is -- which breaks the moment a window
# moves. This reports the rect it used, so a wrong picture is a number to read rather than a mystery.
#
# The window is raised with NOMOVE|NOSIZE|NOACTIVATE: geometry changes on a GLFW window crash the
# client (learned the hard way, see client-act.ps1).

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class ShotWin {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after,
        int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
}
"@

$proc = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like "*--username $Username*" } |
    Select-Object -First 1
if (-not $proc) { Write-Error "no client process for $Username"; exit 1 }

if ($HideOthers) {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like "*--username Dev*" -and $_.ProcessId -ne $proc.ProcessId } |
        ForEach-Object {
            $o = Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue
            if ($o -and $o.MainWindowHandle -ne 0) { [void][ShotWin]::ShowWindow($o.MainWindowHandle, 6) }
        }
    Start-Sleep -Milliseconds 500
}

$p = Get-Process -Id $proc.ProcessId
$hwnd = $p.MainWindowHandle
Write-Output "pid=$($p.Id) hwnd=$hwnd title=$($p.MainWindowTitle)"
if ($hwnd -eq 0) { Write-Error "no main window handle"; exit 1 }

# A minimised window reports rect -32000,-32000 160x28 and captures as blank. SW_SHOWNOACTIVATE
# (4) brings it back without activating it; the geometry it restores to is the size it already had,
# so this is not the resize that crashed the clients before (see client-act.ps1).
if ([ShotWin]::IsIconic($hwnd)) {
    Write-Output "window is minimised; showing without activating"
    [void][ShotWin]::ShowWindow($hwnd, 4)
    Start-Sleep -Milliseconds 600
}

# Topmost without stealing geometry. 0x0013 = NOSIZE|NOMOVE|NOACTIVATE.
[void][ShotWin]::SetWindowPos($hwnd, [IntPtr]::Zero, 0, 0, 0, 0, 0x0013)
[void][ShotWin]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds $SettleMs

$r = New-Object ShotWin+RECT
[void][ShotWin]::GetWindowRect($hwnd, [ref]$r)
$w = $r.R - $r.L
$h = $r.B - $r.T
Write-Output "rect=$($r.L),$($r.T) ${w}x${h}"
if ($w -le 0 -or $h -le 0) { Write-Error "bad rect"; exit 1 }

$bmp = New-Object System.Drawing.Bitmap $w, $h
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.L, $r.T, 0, 0, $bmp.Size)
$g.Dispose()

if ($Out -eq "") {
    $dir = Join-Path $PSScriptRoot "..\shots"
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    $Out = Join-Path $dir "$Username-$(Get-Date -Format 'HHmmss').png"
}
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "saved $Out"
