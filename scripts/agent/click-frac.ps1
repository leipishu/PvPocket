param(
    # Clicks a point given as a fraction of the game's client area, and captures the client area.
    #
    # Why fractions and not pixels: a dev client gets restored from minimised (SW_RESTORE) or
    # maximised by whatever script ran before, and both change the client size. Absolute coordinates
    # then land on the wrong button, and the failure looks like "the mod ignored my click" instead of
    # "the click went somewhere else". A fraction of the client area survives every resize, and the
    # capture is cropped to the client area so a fraction read off the picture maps back exactly.
    #
    # Coordinates are fractions in 0..1 of the client area, measured from its top-left. The capture
    # is written at native size, so a pixel p in the image is p/width horizontally.
    [string]$Username = "Dev1",
    [double]$FX = 0.5,
    [double]$FY = 0.5,
    [string]$Out = "",
    [int]$SettleMs = 900
)

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class FracWin {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after,
        int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg,
        IntPtr w, IntPtr l);
}
"@

. "$PSScriptRoot/client-proc.ps1"
$proc = Find-ClientProcess $Username
if (-not $proc) { Write-Error "no client process for $Username"; exit 1 }

$p = Get-Process -Id $proc.ProcessId
$hwnd = $p.MainWindowHandle
if ($hwnd -eq 0) { Write-Error "no window handle"; exit 1 }

if ([FracWin]::IsIconic($hwnd)) {
    [void][FracWin]::ShowWindow($hwnd, 9)
    Start-Sleep -Milliseconds 800
}
[void][FracWin]::SetWindowPos($hwnd, [IntPtr](-1), 0, 0, 0, 0, 0x0013)
Start-Sleep -Milliseconds 500

$cr = New-Object FracWin+RECT
[void][FracWin]::GetClientRect($hwnd, [ref]$cr)
$cw = $cr.R
$ch = $cr.B
$origin = New-Object FracWin+POINT
[void][FracWin]::ClientToScreen($hwnd, [ref]$origin)

$cx = [int][Math]::Round($FX * $cw)
$cy = [int][Math]::Round($FY * $ch)
# Clamp: a click one pixel outside the client area is a click on the frame, which the game never sees.
$cx = [Math]::Max(0, [Math]::Min($cw - 1, $cx))
$cy = [Math]::Max(0, [Math]::Min($ch - 1, $cy))

$lparam = [IntPtr](($cy -shl 16) -bor ($cx -band 0xFFFF))
[void][FracWin]::PostMessage($hwnd, 0x0200, [IntPtr]::Zero, $lparam)   # WM_MOUSEMOVE
Start-Sleep -Milliseconds 120
[void][FracWin]::PostMessage($hwnd, 0x0201, [IntPtr]1, $lparam)        # WM_LBUTTONDOWN
Start-Sleep -Milliseconds 60
[void][FracWin]::PostMessage($hwnd, 0x0202, [IntPtr]0, $lparam)        # WM_LBUTTONUP
Write-Output "$Username client=${cw}x${ch} origin=$($origin.X),$($origin.Y) click=$cx,$cy (frac $FX,$FY)"
Start-Sleep -Milliseconds $SettleMs

if ($Out -ne "") {
    $bmp = New-Object System.Drawing.Bitmap $cw, $ch
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($origin.X, $origin.Y, 0, 0, $bmp.Size)
    $g.Dispose()
    [void][FracWin]::SetWindowPos($hwnd, [IntPtr](-2), 0, 0, 0, 0, 0x0013)
    $bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "saved $Out"
}
