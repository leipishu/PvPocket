param()
# Lists top-level windows belonging to java.exe: title, rect and minimized state.
# Read-only. Deliberately does not call ShowWindow/SetForegroundWindow - restoring a minimised
# GLFW window is what crashed dev clients in the past.
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class W {
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
    public delegate bool EnumProc(IntPtr h, IntPtr p);
    public struct RECT { public int L, T, R, B; }
}
"@
$pids = @(Get-Process java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
$found = New-Object System.Collections.ArrayList
$cb = [W+EnumProc]{
    param($h, $p)
    $pid2 = 0
    [void][W]::GetWindowThreadProcessId($h, [ref]$pid2)
    if ($pids -contains $pid2) {
        $sb = New-Object System.Text.StringBuilder 512
        [void][W]::GetWindowTextW($h, $sb, 512)
        $t = $sb.ToString()
        if ($t.Length -gt 0) {
            $r = New-Object W+RECT
            [void][W]::GetWindowRect($h, [ref]$r)
            [void]$found.Add([pscustomobject]@{
                pid = $pid2; title = $t; visible = [W]::IsWindowVisible($h); iconic = [W]::IsIconic($h)
                rect = "$($r.L),$($r.T) - $($r.R),$($r.B)"
            })
        }
    }
    return $true
}
[void][W]::EnumWindows($cb, [IntPtr]::Zero)
$found | Format-Table -AutoSize
