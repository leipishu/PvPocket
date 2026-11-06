param([string]$In)

# Prints the pixel size of an image, and the on-screen rectangles of the two dev clients.
#
# Separate from crop.ps1 for the same reason crop.ps1 is separate from the capture scripts:/n# reading geometry must never be able to touch window state. This only ever calls GetWindowRect.

Add-Type -AssemblyName System.Drawing

$src = [System.Drawing.Image]::FromFile($In)
Write-Output "image $($src.Width)x$($src.Height)"
$src.Dispose()
