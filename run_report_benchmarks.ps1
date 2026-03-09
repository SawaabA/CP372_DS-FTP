$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$senderDir = Join-Path $projectRoot "Sender"
$receiverDir = Join-Path $projectRoot "Receiver"

$benchDir = Join-Path $projectRoot "bench-report"
$logsDir = Join-Path $benchDir "logs"
$outputsDir = Join-Path $benchDir "outputs"

New-Item -ItemType Directory -Path $benchDir -Force | Out-Null
New-Item -ItemType Directory -Path $logsDir -Force | Out-Null
New-Item -ItemType Directory -Path $outputsDir -Force | Out-Null

Write-Host "Compiling Sender..."
Push-Location $senderDir
& javac *.java
if ($LASTEXITCODE -ne 0) {
    throw "Sender compile failed."
}
Pop-Location

Write-Host "Compiling Receiver..."
Push-Location $receiverDir
& javac *.java
if ($LASTEXITCODE -ne 0) {
    throw "Receiver compile failed."
}
Pop-Location

function New-RandomFile {
    param(
        [string]$Path,
        [int]$SizeBytes,
        [int]$Seed
    )

    $bytes = New-Object byte[] $SizeBytes
    $rng = [System.Random]::new($Seed)
    $rng.NextBytes($bytes)
    [System.IO.File]::WriteAllBytes($Path, $bytes)
}

function Stop-ReceiverByPorts {
    param(
        [int]$SenderAckPort,
        [int]$ReceiverDataPort
    )

    $matchText = " Receiver 127.0.0.1 $SenderAckPort $ReceiverDataPort "
    Get-CimInstance Win32_Process -Filter "name = 'java.exe'" |
        Where-Object { $_.CommandLine -like "*$matchText*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Invoke-BenchmarkRun {
    param(
        [string]$Protocol,
        [Nullable[int]]$Window,
        [int]$RN,
        [string]$FileLabel,
        [string]$InputFile,
        [int]$TimeoutMs,
        [int]$RunIndex,
        [int]$SenderAckPort,
        [int]$ReceiverDataPort
    )

    $windowLabel = if ($null -eq $Window) { "NA" } else { "$Window" }
    $safeProtocol = ($Protocol -replace '[^A-Za-z0-9\-_]', '_')
    $safeFileLabel = ($FileLabel -replace '[^A-Za-z0-9\-_]', '_')
    $slug = "$safeProtocol-win$windowLabel-$safeFileLabel-rn$RN-run$RunIndex"

    $outputFile = Join-Path $outputsDir "$slug.bin"
    $senderLog = Join-Path $logsDir "$slug-sender.log"
    $receiverLog = Join-Path $logsDir "$slug-receiver.log"
    $senderErr = Join-Path $logsDir "$slug-sender.err.log"
    $receiverErr = Join-Path $logsDir "$slug-receiver.err.log"

    Stop-ReceiverByPorts -SenderAckPort $SenderAckPort -ReceiverDataPort $ReceiverDataPort
    if (Test-Path $outputFile) { Remove-Item $outputFile -Force }

    $oldWindow = $env:DSFTP_WINDOW
    if ($null -eq $Window) {
        Remove-Item Env:DSFTP_WINDOW -ErrorAction SilentlyContinue
    } else {
        $env:DSFTP_WINDOW = "$Window"
    }

    $receiverArgs = @(
        "Receiver",
        "127.0.0.1",
        "$SenderAckPort",
        "$ReceiverDataPort",
        "$outputFile",
        "$RN"
    )

    $receiverProc = Start-Process `
        -FilePath "java" `
        -ArgumentList $receiverArgs `
        -WorkingDirectory $receiverDir `
        -NoNewWindow `
        -PassThru `
        -RedirectStandardOutput $receiverLog `
        -RedirectStandardError $receiverErr

    Start-Sleep -Milliseconds 400

    $senderArgs = @(
        "Sender",
        "127.0.0.1",
        "$ReceiverDataPort",
        "$SenderAckPort",
        "$InputFile",
        "$TimeoutMs"
    )

    if ($null -ne $Window) {
        $senderArgs += "$Window"
    }

    $senderProc = Start-Process `
        -FilePath "java" `
        -ArgumentList $senderArgs `
        -WorkingDirectory $senderDir `
        -NoNewWindow `
        -PassThru `
        -RedirectStandardOutput $senderLog `
        -RedirectStandardError $senderErr

    $senderProc.WaitForExit()

    $receiverStopped = $receiverProc.WaitForExit(12000)
    if (-not $receiverStopped -or -not $receiverProc.HasExited) {
        Stop-Process -Id $receiverProc.Id -Force -ErrorAction SilentlyContinue
    }
    Stop-ReceiverByPorts -SenderAckPort $SenderAckPort -ReceiverDataPort $ReceiverDataPort

    if ($null -eq $oldWindow) {
        Remove-Item Env:DSFTP_WINDOW -ErrorAction SilentlyContinue
    } else {
        $env:DSFTP_WINDOW = $oldWindow
    }

    $senderText = if (Test-Path $senderLog) { Get-Content -Path $senderLog -Raw } else { "" }
    $isSuccessLine = $senderText -match "Total Transmission Time:\s*([0-9]+\.[0-9]+)\s*seconds"
    $isFailureLine = $senderText -match "Unable to transfer file\."

    $timeSeconds = $null
    if ($isSuccessLine) {
        $timeSeconds = [double]::Parse($matches[1], [System.Globalization.CultureInfo]::InvariantCulture)
    }

    $hashMatch = $false
    if ($isSuccessLine -and (Test-Path $outputFile)) {
        $inHash = (Get-FileHash -Path $InputFile -Algorithm SHA256).Hash
        $outHash = (Get-FileHash -Path $outputFile -Algorithm SHA256).Hash
        $hashMatch = ($inHash -eq $outHash)
    }

    [PSCustomObject]@{
        Protocol = $Protocol
        Window = if ($null -eq $Window) { 0 } else { $Window }
        FileLabel = $FileLabel
        RN = $RN
        RunIndex = $RunIndex
        SenderAckPort = $SenderAckPort
        ReceiverDataPort = $ReceiverDataPort
        TimeSeconds = $timeSeconds
        Success = ($isSuccessLine -and $hashMatch)
        FailureLine = $isFailureLine
        SenderLog = $senderLog
        ReceiverLog = $receiverLog
    }
}

# ---- create report input files ----
$smallFile = Join-Path $benchDir "report_small_2kb.bin"     # < 4 KB
$largeFile = Join-Path $benchDir "report_large_256kb.bin"   # 0.25 MB
New-RandomFile -Path $smallFile -SizeBytes 2048 -Seed 372
New-RandomFile -Path $largeFile -SizeBytes 262144 -Seed 2372

$fileCases = @(
    @{ Label = "Small(<4KB)"; Path = $smallFile },
    @{ Label = "Large(0.25MB)"; Path = $largeFile }
)

$protocolCases = @(
    @{ Name = "Stop-and-Wait"; Window = $null; TimeoutMs = 300 },
    @{ Name = "GBN-20"; Window = 20; TimeoutMs = 300 },
    @{ Name = "GBN-40"; Window = 40; TimeoutMs = 300 },
    @{ Name = "GBN-80"; Window = 80; TimeoutMs = 300 }
)

$rns = @(0, 5, 100)
$results = @()
$runCounter = 0

foreach ($protocol in $protocolCases) {
    foreach ($fileCase in $fileCases) {
        foreach ($rn in $rns) {
            foreach ($run in 1..3) {
                $runCounter++
                $senderAckPort = 54000 + ($runCounter * 2)
                $receiverDataPort = $senderAckPort + 1

                Write-Host ("Running {0} | {1} | RN={2} | run={3}" -f $protocol.Name, $fileCase.Label, $rn, $run)
                $result = Invoke-BenchmarkRun `
                    -Protocol $protocol.Name `
                    -Window $protocol.Window `
                    -RN $rn `
                    -FileLabel $fileCase.Label `
                    -InputFile $fileCase.Path `
                    -TimeoutMs $protocol.TimeoutMs `
                    -RunIndex $run `
                    -SenderAckPort $senderAckPort `
                    -ReceiverDataPort $receiverDataPort

                $results += $result

                if (-not $result.Success) {
                    Write-Host ("[WARN] failed run -> {0}, RN={1}, run={2}" -f $protocol.Name, $rn, $run)
                }
            }
        }
    }
}

$rawCsv = Join-Path $benchDir "report_results_raw.csv"
$results | Export-Csv -Path $rawCsv -NoTypeInformation

$avgRows = @()
$groups = $results | Group-Object Protocol, Window, FileLabel, RN
foreach ($g in $groups) {
    $first = $g.Group[0]
    $times = $g.Group | Where-Object { $null -ne $_.TimeSeconds } | Select-Object -ExpandProperty TimeSeconds
    $avg = if ($times.Count -gt 0) {
        [Math]::Round((($times | Measure-Object -Average).Average), 2)
    } else {
        $null
    }

    $successRuns = ($g.Group | Where-Object { $_.Success }).Count

    $avgRows += [PSCustomObject]@{
        Protocol = $first.Protocol
        Window = $first.Window
        FileSize = $first.FileLabel
        RN = $first.RN
        Run1 = $g.Group | Where-Object { $_.RunIndex -eq 1 } | Select-Object -ExpandProperty TimeSeconds
        Run2 = $g.Group | Where-Object { $_.RunIndex -eq 2 } | Select-Object -ExpandProperty TimeSeconds
        Run3 = $g.Group | Where-Object { $_.RunIndex -eq 3 } | Select-Object -ExpandProperty TimeSeconds
        Average = $avg
        SuccessRuns = $successRuns
    }
}

$ordered = $avgRows | Sort-Object `
    @{Expression = { if ($_.Protocol -eq "Stop-and-Wait") { 0 } elseif ($_.Protocol -eq "GBN-20") { 1 } elseif ($_.Protocol -eq "GBN-40") { 2 } else { 3 } }}, `
    @{Expression = { if ($_.FileSize -like "Small*") { 0 } else { 1 } }}, `
    @{Expression = { $_.RN }}

$avgCsv = Join-Path $benchDir "report_results_avg.csv"
$ordered | Export-Csv -Path $avgCsv -NoTypeInformation

Write-Host ""
Write-Host "Averages ready:"
$ordered | Format-Table -AutoSize
Write-Host ""
Write-Host "Raw CSV: $rawCsv"
Write-Host "Avg CSV: $avgCsv"
