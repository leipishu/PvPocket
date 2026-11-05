param(
    # Clicks inside a dev client's window and captures the result.
    #
    # Same lesson as post-key.ps1: SendKeys and SetForegroundWindow depend on real keyboard focus,
    # and on this machine the game reports as foreground and still receives nothing. Posting
    # WM_MOUSEMOVE before the button messages is not optional -- Minecraft reads the cursor position
    # from GLFW's cursor callback, so a click without a preceding move lands wherever the pointer
    # happened to be.
    #
    # Coordinates are client-area pixels, not screen pixels: the window decorations differ per run
    # directory and guessing them from a screenshot is how a click lands on the wrong tab.
    [string]$Username = "Dev3",
    [int]$X = 0,
    [int]$Y = 0,
    [string]$Out = "",
    [int]$SettleMs = 900
)

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class ClickWin {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after,
        int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint msg,
        IntPtr w, IntPtr l);
}
"@

$proc = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like "*--username $Username*" } |
    Select-Object -First 1
if (-not $proc) { Write-Error "no client process for $Username"; exit 1 }

$p = Get-Process -Id $proc.ProcessId
$hwnd = $p.MainWindowHandle
if ($hwnd -eq 0) { Write-Error "no window handle"; exit 1 }

if ([ClickWin]::IsIconic($hwnd)) {
    [void][ClickWin]::ShowWindow($hwnd, 9)
    Start-Sleep -Milliseconds 700
}
# Topmost so the capture below returns this window rather than whichever client is on top of it.
[void][ClickWin]::SetWindowPos($hwnd, [IntPtr](-1), 0, 0, 0, 0, 0x0013)
Start-Sleep -Milliseconds 500

$lparam = [IntPtr](($Y -shl 16) -bor ($X -band 0xFFFF))
[void][ClickWin]::PostMessage($hwnd, 0x0200, [IntPtr]::Zero, $lparam)   # WM_MOUSEMOVE
Start-Sleep -Milliseconds 120
[void][ClickWin]::PostMessage($hwnd, 0x0201, [IntPtr]1, $lparam)        # WM_LBUTTONDOWN
Start-Sleep -Milliseconds 60
[void][ClickWin]::PostMessage($hwnd, 0x0202, [IntPtr]0, $lparam)        # WM_LBUTTONUP
Write-Output "clicked ($X,$Y) on pid=$($p.Id)"
Start-Sleep -Milliseconds $SettleMs

if ($Out -ne "") {
    $r = New-Object ClickWin+RECT
    [void][ClickWin]::GetWindowRect($hwnd, [ref]$r)
    $w = $r.R - $r.L
    $h = $r.B - $r.T
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($r.L, $r.T, 0, 0, $bmp.Size)
    $g.Dispose()
    [void][ClickWin]::SetWindowPos($hwnd, [IntPtr](-2), 0, 0, 0, 0, 0x0013)
    $bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "saved $Out"
}
