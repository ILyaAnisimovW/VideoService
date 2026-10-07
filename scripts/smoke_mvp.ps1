param(
    [Parameter(Mandatory = $true)][string]$VideoPath,
    [string]$ApiBaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$token = $env:VIDEO_TEST_JWT
if ([string]::IsNullOrWhiteSpace($token)) { throw 'Set VIDEO_TEST_JWT to an ACTIVE user access token' }
$file = Get-Item -LiteralPath $VideoPath
if ($file.Length -lt 1 -or $file.Length -gt 16777216) { throw 'Smoke fixture must be 1..16777216 bytes (one multipart part)' }

$headers = @{ Authorization = "Bearer $token"; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$video = Invoke-RestMethod -Uri "$ApiBaseUrl/api/v1/videos" -Method Post -Headers $headers -ContentType 'application/json' -Body '{"title":"MVP smoke","visibility":"PRIVATE"}'
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$session = Invoke-RestMethod -Uri "$ApiBaseUrl/api/v1/videos/$($video.id)/upload-sessions" -Method Post -Headers $headers -ContentType 'application/json' -Body (@{ sizeBytes = $file.Length; contentType = 'video/mp4' } | ConvertTo-Json -Compress)
if ($session.partCount -ne 1) { throw 'Expected a one-part smoke upload' }

$headers.Remove('Idempotency-Key')
$grant = Invoke-RestMethod -Uri "$ApiBaseUrl/api/v1/upload-sessions/$($session.id)/parts/1/url" -Method Post -Headers $headers
$putHeaders = @{}
foreach ($property in $grant.requiredHeaders.PSObject.Properties) {
    if ($property.Name -ne 'content-length') { $putHeaders[$property.Name] = $property.Value }
}
$upload = Invoke-WebRequest -Uri $grant.url -Method Put -Headers $putHeaders -InFile $file.FullName -ContentType 'application/octet-stream'
$etag = $upload.Headers.ETag
if ([string]::IsNullOrWhiteSpace($etag)) { throw 'Storage did not expose ETag' }

$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$completeBody = @{ parts = @(@{ partNumber = 1; etag = $etag }) } | ConvertTo-Json -Depth 5 -Compress
$job = Invoke-RestMethod -Uri "$ApiBaseUrl/api/v1/upload-sessions/$($session.id)/complete" -Method Post -Headers $headers -ContentType 'application/json' -Body $completeBody
$headers.Remove('Idempotency-Key')

$deadline = (Get-Date).AddMinutes(10)
do {
    Start-Sleep -Seconds 3
    $job = Invoke-RestMethod -Uri "$ApiBaseUrl/api/v1/processing-jobs/$($job.id)" -Headers $headers
    if ($job.state -eq 'FAILED' -or $job.state -eq 'CANCELLED') { throw "Job ended in $($job.state): $($job.lastErrorCode)" }
} until ($job.state -eq 'SUCCEEDED' -or (Get-Date) -ge $deadline)
if ($job.state -ne 'SUCCEEDED') { throw 'Processing did not finish within 10 minutes' }

$playback = Invoke-RestMethod -Uri "$ApiBaseUrl/api/v1/videos/$($video.id)/playback-sessions" -Method Post -Headers $headers
$master = (Invoke-WebRequest -Uri $playback.manifestUrl).Content
$variantUrl = ($master -split "`n" | Where-Object { $_ -match '^https?://' } | Select-Object -First 1).Trim()
if ([string]::IsNullOrWhiteSpace($variantUrl)) { throw 'Master playlist does not contain an API variant URL' }
$variant = (Invoke-WebRequest -Uri $variantUrl).Content
$segmentUrl = ($variant -split "`n" | Where-Object { $_ -match '^https?://' } | Select-Object -First 1).Trim()
if ([string]::IsNullOrWhiteSpace($segmentUrl)) { throw 'Variant playlist does not contain a signed segment URL' }
$segment = Invoke-WebRequest -Uri $segmentUrl -Method Get
if ($segment.StatusCode -ne 200 -or $segment.RawContentLength -lt 1) { throw 'Segment is not readable via signed URL' }

Write-Output "MVP smoke passed: video $($video.id), job $($job.id), playback manifest and segment"
