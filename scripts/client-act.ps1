param(
    [string]$Username = "",
    [string]$Keys = "",
    [int]$SettleMs = 900,
    [string]$Out = "",
    [switch]$Window
)

# Test harness for the two dev clients.
#
# Lessons baked in, each learned the hard way:
#   - Do NOT resize or restore the game window. ShowWindow(SW_RESTORE) on a GLFW window whose
#     framebuffer has to be recreated took both clients down with a native crash (0xC0000409) in
#     under a second. Nothing in this script touches window geometry any more.
#   - Do NOT capture the window rect. Both clients launch at the same default position, so one
#     always covers the other and CopyFromScreen silently returns the wrong client's frame. The
#     full desktop is captured instead; both windows are in it, and which one is on top no longer
#     changes what the screenshot means.
#   - Do NOT use the game's F2 key. It is not dispatched while a custom screen is open, which is
#     exactly when a screenshot is wanted.

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

if ($Username -ne "") {
    $proc = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like "*--username $Username*" } |
        Select-Object -First 1
    if (-not $proc) {
        Write-Error "no client process found for $Username"
        exit 1
    }
    $shell = New-Object -ComObject WScript.Shell
    [void]$shell.AppActivate($proc.ProcessId)
    Start-Sleep -Milliseconds 400
    if ($Keys -ne "") {
        [System.Windows.Forms.SendKeys]::SendWait($Keys)
        Start-Sleep -Milliseconds $SettleMs
    }
}

$bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
if ($Window -and $Username -ne "") {
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public class W {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
}
"@
    $hwnd = (Get-Process -Id $proc.ProcessId).MainWindowHandle
    # Raise to the top of the z-order without moving, resizing or activating. Both dev clients sit at
    # the same default position, so without this the capture returns whichever window happens to be
    # on top. NOMOVE|NOSIZE|NOACTIVATE leaves the window geometry untouched — geometry changes on a
    # GLFW window are what crashed the clients earlier.
    [void][W]::SetWindowPos($hwnd, [IntPtr]::Zero, 0, 0, 0, 0, 0x0013)
    Start-Sleep -Milliseconds 250
    $rect = New-Object W+RECT
    [void][W]::GetWindowRect($hwnd, [ref]$rect)
    if (($rect.R - $rect.L) -gt 0 -and ($rect.B - $rect.T) -gt 0) {
        $bounds = New-Object System.Drawing.Rectangle $rect.L, $rect.T, ($rect.R - $rect.L), ($rect.B - $rect.T)
    }
}

$bmp = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($bounds.Left, $bounds.Top, 0, 0, $bmp.Size)
$g.Dispose()

if ($Out -eq "") {
    $dir = Join-Path $PSScriptRoot "..\shots"
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    $tag = if ($Username -ne "") { $Username } else { "desk" }
    $Out = Join-Path $dir "$tag-$(Get-Date -Format 'HHmmss').png"
}
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output $Out
