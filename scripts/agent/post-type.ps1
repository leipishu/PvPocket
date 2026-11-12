param(
    # Opens the in-game chat and types a string into it, then presses Enter.
    #
    # Same constraint as post-key.ps1: SendKeys never reaches this game even when it reports as the
    # foreground window, so everything goes in as posted window messages. Characters have to be sent
    # as WM_CHAR, not WM_KEYDOWN -- the chat field is filled by GLFW's char callback, which is fed by
    # WM_CHAR; a key-down only produces a keystroke, and a keystroke with no character is nothing in
    # a text field.
    [string]$Username = "Dev1",
    [string]$Text = "",
    [switch]$NoEnter,
    [int]$SettleMs = 400
)

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class PostType {
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr w, IntPtr l);
    [DllImport("user32.dll")] public static extern IntPtr MapVirtualKey(uint code, uint mapType);
}
"@

. "$PSScriptRoot/client-proc.ps1"
$proc = Find-ClientProcess $Username
if (-not $proc) { Write-Error "no client process for $Username"; exit 1 }

$hwnd = (Get-Process -Id $proc.ProcessId).MainWindowHandle
if ($hwnd -eq 0) { Write-Error "no window handle"; exit 1 }

function Send-Vk([int]$vk) {
    $scan = [int][PostType]::MapVirtualKey([uint32]$vk, 0)
    $down = [IntPtr](1 -bor ($scan -shl 16))
    $up = [IntPtr](1 -bor ($scan -shl 16) -bor 0xC0000000)
    [void][PostType]::PostMessage($hwnd, 0x0100, [IntPtr]$vk, $down)
    Start-Sleep -Milliseconds 45
    [void][PostType]::PostMessage($hwnd, 0x0101, [IntPtr]$vk, $up)
    Start-Sleep -Milliseconds 45
}

# VK_T opens the chat prompt. The slash is typed as a character like any other, so the caller passes
# the whole command including it.
Send-Vk 0x54
Start-Sleep -Milliseconds $SettleMs

foreach ($ch in $Text.ToCharArray()) {
    [void][PostType]::PostMessage($hwnd, 0x0102, [IntPtr][int][char]$ch, [IntPtr]1)  # WM_CHAR
    Start-Sleep -Milliseconds 30
}

if (-not $NoEnter) {
    Start-Sleep -Milliseconds 150
    Send-Vk 0x0D
}

Write-Output "typed '$Text' into pid=$($proc.ProcessId) hwnd=$hwnd"
