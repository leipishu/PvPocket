param([string]$In)

# Prints the pixel size of an image.

Add-Type -AssemblyName System.Drawing

$src = [System.Drawing.Image]::FromFile($In)
Write-Output "image $($src.Width)x$($src.Height)"
$src.Dispose()
