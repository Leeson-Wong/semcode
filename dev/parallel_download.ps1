# 8 连接分片下载 model.safetensors（绕过单连接限速）
# 用法: powershell -File dev\parallel_download.ps1
$ErrorActionPreference = 'Stop'
$url = "https://huggingface.co/nomic-ai/nomic-embed-text-v2-moe/resolve/main/model.safetensors"
$outFile = "E:\opensource\semcode\dev\model_v2_moe\model.safetensors"
$partsDir = "E:\opensource\semcode\dev\model_v2_moe\parts"
$n = 8

# 精确文件大小（跟随重定向后的最终响应）
$resp = Invoke-WebRequest -Uri $url -Method Head -UseBasicParsing
$size = [int64]$resp.Headers['Content-Length']
Write-Output "total size: $size bytes ($([math]::Round($size/1MB)) MB)"

New-Item -ItemType Directory -Force $partsDir | Out-Null

# 已有前缀 P 作为 base，剩余部分分 8 片
$prefix = [int64](Get-Item $outFile -ErrorAction SilentlyContinue).Length
if (-not $prefix) { $prefix = 0 }
Write-Output "existing prefix: $prefix bytes"
$remaining = $size - $prefix
$chunk = [math]::Ceiling($remaining / $n)

$jobs = @()
for ($i = 0; $i -lt $n; $i++) {
    $start = $prefix + $i * $chunk
    $end = [math]::Min($size - 1, $start + $chunk - 1)
    if ($start -gt $end) { break }
    $partFile = "$partsDir\part_$i"
    $expected = $end - $start + 1
    # 已完整的分片跳过
    if ((Test-Path $partFile) -and ((Get-Item $partFile).Length -eq $expected)) {
        Write-Output "part $i complete (cached)"
        continue
    }
    Write-Output "part ${i}: range $start-$end ($([math]::Round($expected/1MB)) MB)"
    $jobs += Start-Process curl.exe -ArgumentList @(
        "-L","-s","--retry","30","--retry-all-errors","--retry-delay","2",
        "-r","$start-$end","-o",$partFile,$url
    ) -PassThru -NoNewWindow
}
Write-Output "waiting for $($jobs.Count) parallel connections..."
$jobs | Wait-Process

# 校验每个分片
$ok = $true
for ($i = 0; $i -lt $n; $i++) {
    $start = $prefix + $i * $chunk
    $end = [math]::Min($size - 1, $start + $chunk - 1)
    if ($start -gt $end) { break }
    $partFile = "$partsDir\part_$i"
    $expected = $end - $start + 1
    $actual = (Get-Item $partFile -ErrorAction SilentlyContinue).Length
    if ($actual -ne $expected) {
        Write-Output "MISMATCH part ${i}: expected $expected got $actual"
        $ok = $false
    }
}
if (-not $ok) { Write-Output "PART FAILURE - rerun this script (completed parts are cached)"; exit 1 }

# 拼接：base + parts -> final
$final = "$outFile.final"
$in = [System.IO.File]::OpenRead($outFile)
$out = [System.IO.File]::Create($final)
$in.CopyTo($out); $in.Close()
for ($i = 0; $i -lt $n; $i++) {
    $partFile = "$partsDir\part_$i"
    if (Test-Path $partFile) {
        $in = [System.IO.File]::OpenRead($partFile)
        $in.CopyTo($out); $in.Close()
    }
}
$out.Close()
$finalLen = (Get-Item $final).Length
Write-Output "assembled: $finalLen bytes (expected $size)"
if ($finalLen -ne $size) { Write-Output "SIZE MISMATCH"; exit 1 }
Move-Item $final $outFile -Force
Remove-Item $partsDir -Recurse -Force
Write-Output "DONE: $outFile"
