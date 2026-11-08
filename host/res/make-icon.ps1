# Generates res\app.ico (multi-size, PNG frames): blue rounded square with a white monitor.
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'
$sizes = 16, 20, 24, 32, 40, 48, 64, 128, 256
$pngs = @()

function RoundRect([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
  $p = New-Object System.Drawing.Drawing2D.GraphicsPath
  $d = [Math]::Max(1.0, $r * 2)
  $p.AddArc($x, $y, $d, $d, 180, 90)
  $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
  $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
  $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
  $p.CloseFigure()
  return $p
}

foreach ($s in $sizes) {
  $bmp = New-Object System.Drawing.Bitmap $s, $s, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $g.Clear([System.Drawing.Color]::Transparent)

  $bg = RoundRect 0 0 ($s - 0.5) ($s - 0.5) ($s * 0.23)
  $p0 = New-Object System.Drawing.PointF 0, 0
  $p1 = New-Object System.Drawing.PointF $s, $s
  $c0 = [System.Drawing.Color]::FromArgb(255, 37, 99, 235)
  $c1 = [System.Drawing.Color]::FromArgb(255, 6, 182, 212)
  $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $p0, $p1, $c0, $c1
  $g.FillPath($brush, $bg)

  $white = [System.Drawing.Brushes]::White
  $mx = $s * 0.17; $my = $s * 0.22; $mw = $s * 0.66; $mh = $s * 0.44
  $outer = RoundRect $mx $my $mw $mh ($s * 0.06)
  $g.FillPath($white, $outer)
  $t = [Math]::Max(1.2, $s * 0.075)
  $inner = RoundRect ($mx + $t) ($my + $t) ($mw - 2 * $t) ($mh - 2 * $t) ($s * 0.03)
  $g.FillPath($brush, $inner)
  # a small "cursor" inside the screen
  if ($s -ge 24) {
    $cx = $mx + $mw * 0.42; $cy = $my + $mh * 0.25; $cs = $mh * 0.5
    $pts = @(
      (New-Object System.Drawing.PointF $cx, $cy),
      (New-Object System.Drawing.PointF $cx, ($cy + $cs)),
      (New-Object System.Drawing.PointF ($cx + $cs * 0.28), ($cy + $cs * 0.74)),
      (New-Object System.Drawing.PointF ($cx + $cs * 0.62), ($cy + $cs * 0.74))
    )
    $g.FillPolygon($white, $pts)
  }
  # stand
  $g.FillRectangle($white, [float]($s * 0.45), [float]($my + $mh), [float]($s * 0.10), [float]($s * 0.09))
  $base = RoundRect ($s * 0.30) ($my + $mh + $s * 0.085) ($s * 0.40) ([Math]::Max(1.0, $s * 0.065)) ([Math]::Max(0.5, $s * 0.03))
  $g.FillPath($white, $base)

  $g.Dispose()
  $ms = New-Object System.IO.MemoryStream
  $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
  $pngs += , $ms.ToArray()
  $bmp.Dispose()
}

$out = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter $out
$bw.Write([UInt16]0); $bw.Write([UInt16]1); $bw.Write([UInt16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
  $s = $sizes[$i]
  $dim = if ($s -ge 256) { 0 } else { $s }
  $bw.Write([byte]$dim); $bw.Write([byte]$dim); $bw.Write([byte]0); $bw.Write([byte]0)
  $bw.Write([UInt16]1); $bw.Write([UInt16]32)
  $bw.Write([UInt32]$pngs[$i].Length); $bw.Write([UInt32]$offset)
  $offset += $pngs[$i].Length
}
foreach ($p in $pngs) { $bw.Write($p) }
$bw.Flush()
$target = Join-Path $PSScriptRoot 'app.ico'
[IO.File]::WriteAllBytes($target, $out.ToArray())
Write-Host "wrote $target"
