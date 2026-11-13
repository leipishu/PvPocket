param(
    [string]$In = "",
    [string]$Out = "",
    [int]$X = 0,
    [int]$Y = 0,
    [int]$W = 0,
    [int]$H = 0,
    # Double rather than int: a 5120-wide desktop has to be scaled down, which an int cannot do.
    [double]$Scale = 1
)

# Crops a region out of a screenshot and optionally scales it up.

Add-Type -AssemblyName System.Drawing

$src = [System.Drawing.Image]::FromFile($In)
try {
    if ($W -le 0) { $W = $src.Width - $X }
    if ($H -le 0) { $H = $src.Height - $Y }
    $dst = New-Object System.Drawing.Bitmap ([int]($W * $Scale)), ([int]($H * $Scale))
    $g = [System.Drawing.Graphics]::FromImage($dst)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, 0, $dst.Width, $dst.Height),
        (New-Object System.Drawing.Rectangle $X, $Y, $W, $H),
        [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
    $dst.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
    $dst.Dispose()
    Write-Output "$Out ($($W)x$($H) -> $([int]($W*$Scale))x$([int]($H*$Scale)))"
} finally {
    $src.Dispose()
}
