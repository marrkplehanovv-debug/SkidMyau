$ErrorActionPreference = 'Stop'
$root = 'D:\OpenMyau-main'
$src = Join-Path $root 'src'
$exts = @('.java', '.json', '.info', '.html', '.xml', '.cfg', '.txt', '.md', '.properties', '.kts')

$utf8 = New-Object System.Text.UTF8Encoding($false)
$changed = 0

Get-ChildItem -Path $src -Recurse -File | Where-Object { $exts -contains $_.Extension.ToLower() } | ForEach-Object {
    $p = $_.FullName
    $text = [System.IO.File]::ReadAllText($p)
    $new = $text.Replace('openmyau', '__SKID_TMP__')
    $new = $new.Replace('skidmyau', 'skidmyau')
    $new = $new.Replace('__SKID_TMP__', 'skidmyau')
    $new = $new.Replace('SkidMyau', 'SkidMyau')
    if ($new -ne $text) {
        [System.IO.File]::WriteAllText($p, $new, $utf8)
        $changed++
    }
}

# Rename package directory, main class file and mixin config
Move-Item -LiteralPath (Join-Path $src 'main\java\skidmyau') -Destination (Join-Path $src 'main\java\skidmyau')
Move-Item -LiteralPath (Join-Path $src 'main\java\skidmyau\SkidMyau.java') -Destination (Join-Path $src 'main\java\skidmyau\SkidMyau.java')
Move-Item -LiteralPath (Join-Path $src 'main\resources\mixins.skidmyau.json') -Destination (Join-Path $src 'main\resources\mixins.skidmyau.json')

Write-Output "Updated files: $changed"