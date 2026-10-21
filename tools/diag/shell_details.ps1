param([string]$Path)
# ASCII-only on purpose: PS 5.1 decodes BOM-less .ps1 as GBK; any non-ASCII
# byte in this file could break syntax. Chinese values come from file props.
$full = (Resolve-Path $Path).Path
$dir = Split-Path $full
$name = Split-Path $full -Leaf
$shell = New-Object -ComObject Shell.Application
$folder = $shell.NameSpace($dir)
$item = $folder.ParseName($name)
if ($item -eq $null) { Write-Output "ITEM NOT FOUND: $full"; exit 1 }
for ($i = 0; $i -lt 350; $i++) {
  $col = $folder.GetDetailsOf($null, $i)
  $val = $folder.GetDetailsOf($item, $i)
  if ($val -ne $null -and $val -ne "") {
    Write-Output ("{0}|{1}|{2}" -f $i, $col, $val)
  }
}
