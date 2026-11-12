param(
    # Captures a dev client by asking the window to render itself, instead of copying the screen.
    #
    # Why: CopyFromScreen returns whatever is actually on the desktop at those coordinates, so it
    # silently produces a picture of the wallpaper whenever the game window is behind something or
    # has lost focus. A paused Minecraft stops drawing new frames, which makes that failure look
    # like "the mod froze" rather than "the capture missed".
    #
    # PrintWindow with PW_RENDERFULLCONTENT (2) asks the window to paint into a bitmap. For a
    # DWM-composited GLFW window this returns the last composited frame even when the window is
    # occluded, which is exactly what a screenshot of a paused client needs.
    [string]$Username = "Dev1",
    [string]$Out = "",
    [int]$SettleMs = 400
)

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Pw {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
}
"@

# Find the client by --username, not by window title: all four Fabric clients share a title.
# The argfile expansion that makes this work on NeoForge lives in client-proc.ps1, shared with the
# click / key / type helpers so the six of them cannot drift apart again.
. "$PSScriptRoot/client-proc.ps1"

$proc = Find-ClientProcess $Username
if (-not $proc) { Write-Error "no client process for $Username"; exit 1 }
$hwnd = (Get-Process -Id $proc.ProcessId).MainWindowHandle
if ($hwnd -eq 0) { Write-Error "no window handle for $Username"; exit 1 }

# SW_SHOWNOACTIVATE only. Anything that resizes a live GLFW window has taken a dev client down.
[void][Pw]::ShowWindow($hwnd, 4)
Start-Sleep -Milliseconds $SettleMs

$r = New-Object Pw+RECT
[void][Pw]::GetWindowRect($hwnd, [ref]$r)
$w = $r.R - $r.L
$h = $r.B - $r.T
if ($w -le 0 -or $h -le 0) { Write-Error "window has no area: ${w}x${h}"; exit 1 }

$bmp = New-Object System.Drawing.Bitmap $w, $h
$g = [System.Drawing.Graphics]::FromImage($bmp)
$hdc = $g.GetHdc()
$ok = [Pw]::PrintWindow($hwnd, $hdc, 2)
$g.ReleaseHdc($hdc)
$g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

# A window that refuses to paint returns an all-black bitmap; say so rather than writing a picture
# that looks like a crash.
Write-Output "$Username PrintWindow=$ok size=${w}x${h} -> $Out"
