param(
    # Posts a key straight into the game window's message queue.
    #
    # SendKeys depends on the window really having keyboard focus, and on this machine it silently
    # does not -- the game reports as the foreground window, renders fine, and still never sees the
    # key. PostMessage skips focus entirely: GLFW translates WM_KEYDOWN in its window proc, so the
    # keybinding fires whether or not the window is active.
    [string]$Username = "Dev3",
    [string]$Key = "G",
    [int]$HoldMs = 60
)

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class PostKey {
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr w, IntPtr l);
    [DllImport("user32.dll")] public static extern IntPtr MapVirtualKey(uint code, uint mapType);
}
"@

$proc = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like "*--username $Username*" } |
    Select-Object -First 1
if (-not $proc) { Write-Error "no client process for $Username"; exit 1 }

$hwnd = (Get-Process -Id $proc.ProcessId).MainWindowHandle
if ($hwnd -eq 0) { Write-Error "no window handle"; exit 1 }

# Virtual-key code for a single ASCII letter, e.g. G -> 0x47.
$vk = [int][char]$Key.ToUpper()
$scan = [int][PostKey]::MapVirtualKey([uint32]$vk, 0)
$down = [IntPtr](1 -bor ($scan -shl 16))
$up = [IntPtr](1 -bor ($scan -shl 16) -bor 0xC0000000)

[void][PostKey]::PostMessage($hwnd, 0x0100, [IntPtr]$vk, $down)   # WM_KEYDOWN
Start-Sleep -Milliseconds $HoldMs
[void][PostKey]::PostMessage($hwnd, 0x0101, [IntPtr]$vk, $up)     # WM_KEYUP
Write-Output "posted $Key (vk=$vk scan=$scan) to pid=$($proc.ProcessId) hwnd=$hwnd"
