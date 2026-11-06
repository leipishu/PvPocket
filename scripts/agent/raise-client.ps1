param(
    # Brings a dev client's window back on screen and reports what the window manager says about it.
    #
    # Why this exists: a client that has been minimised by another script's -HideOthers, or that
    # ended up behind a maximised editor, still reports a plausible GetWindowRect — so a capture
    # "succeeds" and writes a picture of the desktop wallpaper. Nothing in that failure names the
    # window, which is why this prints visibility and iconic state instead of just restoring.
    [string]$Username = "Dev1"
)

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class RaiseWin {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after,
        int x, int y, int cx, int cy, uint flags);
}
"@

$proc = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like "*--username $Username*" } |
    Select-Object -First 1
if (-not $proc) { Write-Error "no client process for $Username"; exit 1 }
$hwnd = (Get-Process -Id $proc.ProcessId).MainWindowHandle
if ($hwnd -eq 0) { Write-Error "no window handle for $Username"; exit 1 }

$before = "visible=$([RaiseWin]::IsWindowVisible($hwnd)) iconic=$([RaiseWin]::IsIconic($hwnd))"

# SW_SHOWNOACTIVATE (4) rather than SW_RESTORE (9): restoring a minimised GLFW window resizes it,
# and a geometry change on a live dev client has taken the client down before.
[void][RaiseWin]::ShowWindow($hwnd, 4)
Start-Sleep -Milliseconds 400
if ([RaiseWin]::IsIconic($hwnd)) {
    [void][RaiseWin]::ShowWindow($hwnd, 9)
    Start-Sleep -Milliseconds 900
}
[void][RaiseWin]::SetWindowPos($hwnd, [IntPtr](-1), 0, 0, 0, 0, 0x0013)
Start-Sleep -Milliseconds 300
[void][RaiseWin]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds 600

$r = New-Object RaiseWin+RECT
[void][RaiseWin]::GetWindowRect($hwnd, [ref]$r)
$after = "visible=$([RaiseWin]::IsWindowVisible($hwnd)) iconic=$([RaiseWin]::IsIconic($hwnd))"
Write-Output "$Username $before -> $after rect=$($r.L),$($r.T) $($r.R-$r.L)x$($r.B-$r.T)"
