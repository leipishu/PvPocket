param()
# Prints just the Minecraft window rectangles, one line each, so they can be cropped precisely.
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class W2 {
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
    public delegate bool EnumProc(IntPtr h, IntPtr p);
    public struct RECT { public int L, T, R, B; }
}
"@
$rows = New-Object System.Collections.ArrayList
$cb = [W2+EnumProc]{
    param($h, $p)
    $pid2 = 0
    [void][W2]::GetWindowThreadProcessId($h, [ref]$pid2)
    $sb = New-Object System.Text.StringBuilder 512
    [void][W2]::GetWindowTextW($h, $sb, 512)
    if ($sb.ToString() -like "Minecraft*") {
        $r = New-Object W2+RECT
        [void][W2]::GetWindowRect($h, [ref]$r)
        [void]$rows.Add("pid=$pid2 iconic=$([W2]::IsIconic($h)) rect=$($r.L),$($r.T),$($r.R),$($r.B)")
    }
    return $true
}
[void][W2]::EnumWindows($cb, [IntPtr]::Zero)
$rows
