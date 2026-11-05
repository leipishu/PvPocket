param(
    # Restores the window if it is minimised, activates it, sends keys, then captures.
    #
    # All four steps have to happen inside one process. Split across two script invocations the
    # editor window takes focus back in between, and the keystroke is delivered to the editor while
    # the capture still shows the game -- which reads as "the key does nothing".
    [string]$Username = "Dev3",
    [string]$Keys = "",
    [string]$Out = "",
    [int]$SettleMs = 1200
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class KeyWin {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after,
        int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
"@

$proc = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like "*--username $Username*" } |
    Select-Object -First 1
if (-not $proc) { Write-Error "no client process for $Username"; exit 1 }

$p = Get-Process -Id $proc.ProcessId
$hwnd = $p.MainWindowHandle
if ($hwnd -eq 0) { Write-Error "no window handle"; exit 1 }

# A minimised GLFW window still accepts SendKeys into the void, so restoring is not cosmetic here.
if ([KeyWin]::IsIconic($hwnd)) {
    [void][KeyWin]::ShowWindow($hwnd, 9)   # SW_RESTORE
    Start-Sleep -Milliseconds 700
}
[void][KeyWin]::SetWindowPos($hwnd, [IntPtr](-1), 0, 0, 0, 0, 0x0013)
$shell = New-Object -ComObject WScript.Shell
[void]$shell.AppActivate($p.Id)
[void][KeyWin]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds 700
Write-Output "foreground is game: $([KeyWin]::GetForegroundWindow() -eq $hwnd)"

if ($Keys -ne "") {
    [System.Windows.Forms.SendKeys]::SendWait($Keys)
}
Start-Sleep -Milliseconds $SettleMs

if ($Out -ne "") {
    $r = New-Object KeyWin+RECT
    [void][KeyWin]::GetWindowRect($hwnd, [ref]$r)
    $w = $r.R - $r.L
    $h = $r.B - $r.T
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($r.L, $r.T, 0, 0, $bmp.Size)
    $g.Dispose()
    [void][KeyWin]::SetWindowPos($hwnd, [IntPtr](-2), 0, 0, 0, 0, 0x0013)
    $bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "saved $Out"
}
