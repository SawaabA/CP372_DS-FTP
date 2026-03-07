$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$senderDir = Join-Path $projectRoot "Sender"
$receiverDir = Join-Path $projectRoot "Receiver"
$artifactsDir = Join-Path $projectRoot "test-artifacts"
$logsDir = Join-Path $artifactsDir "logs"

New-Item -ItemType Directory -Path $artifactsDir -Force | Out-Null
New-Item -ItemType Directory -Path $logsDir -Force | Out-Null

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
        ForEach-Object {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
}

function Invoke-TransferTest {
    param(
        [string]$Name,
        [int]$InputSize,
        [int]$RN,
        [int]$TimeoutMs,
        [Nullable[int]]$WindowSize,
        [int]$SenderAckPort,
        [int]$ReceiverDataPort,
        [int]$ReceiverWindow,
        [bool]$ExpectSuccess
    )

    $inputFile = Join-Path $artifactsDir "$Name-input.bin"
    $outputFile = Join-Path $artifactsDir "$Name-output.bin"
    $senderLog = Join-Path $logsDir "$Name-sender.log"
    $receiverLog = Join-Path $logsDir "$Name-receiver.log"
    $senderErr = Join-Path $logsDir "$Name-sender.err.log"
    $receiverErr = Join-Path $logsDir "$Name-receiver.err.log"

    # clean old test receiver if it is still alive
    Stop-ReceiverByPorts -SenderAckPort $SenderAckPort -ReceiverDataPort $ReceiverDataPort

    if (Test-Path $outputFile) {
        $removed = $false
        for ($i = 0; $i -lt 5; $i++) {
            try {
                Remove-Item $outputFile -Force
                $removed = $true
                break
            } catch {
                Start-Sleep -Milliseconds 200
            }
        }

        if (-not $removed) {
            throw "Could not remove old output file: $outputFile"
        }
    }

    New-RandomFile -Path $inputFile -SizeBytes $InputSize -Seed ($InputSize + $RN + $SenderAckPort)

    $oldWindow = $env:DSFTP_WINDOW
    $env:DSFTP_WINDOW = "$ReceiverWindow"

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

    Start-Sleep -Milliseconds 500

    $senderArgs = @(
        "Sender",
        "127.0.0.1",
        "$ReceiverDataPort",
        "$SenderAckPort",
        "$inputFile",
        "$TimeoutMs"
    )

    if ($null -ne $WindowSize) {
        $senderArgs += "$WindowSize"
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
    $senderExit = $senderProc.ExitCode

    $forcedReceiverKill = $false
    $receiverStopped = $receiverProc.WaitForExit(5000)
    if (-not $receiverStopped -or -not $receiverProc.HasExited) {
        Stop-Process -Id $receiverProc.Id -Force -ErrorAction SilentlyContinue
        $receiverProc.WaitForExit() | Out-Null
        $forcedReceiverKill = $true
    }

    # extra cleanup in case java was re-spawned outside process object
    Stop-ReceiverByPorts -SenderAckPort $SenderAckPort -ReceiverDataPort $ReceiverDataPort

    if ($null -eq $oldWindow) {
        Remove-Item Env:DSFTP_WINDOW -ErrorAction SilentlyContinue
    } else {
        $env:DSFTP_WINDOW = $oldWindow
    }

    $senderText = ""
    if (Test-Path $senderLog) {
        $senderText = Get-Content -Path $senderLog -Raw
    }

    $successLine = $senderText -match "Total Transmission Time:"
    $failureLine = $senderText -match "Unable to transfer file\."

    $pass = $true
    $notes = @()

    if ($ExpectSuccess) {
        if (-not $successLine) {
            $pass = $false
            $notes += "missing sender success line"
        }

        if (-not (Test-Path $outputFile)) {
            $pass = $false
            $notes += "output file missing"
        } else {
            $inHash = (Get-FileHash -Path $inputFile -Algorithm SHA256).Hash
            $outHash = (Get-FileHash -Path $outputFile -Algorithm SHA256).Hash
            if ($inHash -ne $outHash) {
                $pass = $false
                $notes += "input/output hash mismatch"
            }
        }

        if ($forcedReceiverKill) {
            $pass = $false
            $notes += "receiver did not exit cleanly"
        }
    } else {
        if (-not $failureLine) {
            $pass = $false
            $notes += "missing sender failure line"
        }
    }

    if (-not [string]::IsNullOrWhiteSpace("$senderExit") -and $senderExit -ne 0) {
        $notes += "sender exit code $senderExit"
    }

    [PSCustomObject]@{
        Name = $Name
        Passed = $pass
        Notes = if ($notes.Count -eq 0) { "ok" } else { ($notes -join "; ") }
        SenderLog = $senderLog
        ReceiverLog = $receiverLog
    }
}

$tests = @(
    @{
        Name = "01_stop_wait_basic"
        InputSize = 600
        RN = 0
        TimeoutMs = 250
        WindowSize = $null
        SenderAckPort = 51001
        ReceiverDataPort = 52001
        ReceiverWindow = 80
        ExpectSuccess = $true
    },
    @{
        Name = "02_stop_wait_ack_loss"
        InputSize = 900
        RN = 5
        TimeoutMs = 250
        WindowSize = $null
        SenderAckPort = 51002
        ReceiverDataPort = 52002
        ReceiverWindow = 80
        ExpectSuccess = $true
    },
    @{
        Name = "03_empty_file"
        InputSize = 0
        RN = 0
        TimeoutMs = 250
        WindowSize = $null
        SenderAckPort = 51003
        ReceiverDataPort = 52003
        ReceiverWindow = 80
        ExpectSuccess = $true
    },
    @{
        Name = "04_gbn_window20"
        InputSize = 7000
        RN = 0
        TimeoutMs = 250
        WindowSize = 20
        SenderAckPort = 51004
        ReceiverDataPort = 52004
        ReceiverWindow = 20
        ExpectSuccess = $true
    },
    @{
        Name = "05_gbn_window40_ack_loss"
        InputSize = 14000
        RN = 5
        TimeoutMs = 250
        WindowSize = 40
        SenderAckPort = 51005
        ReceiverDataPort = 52005
        ReceiverWindow = 40
        ExpectSuccess = $true
    },
    @{
        Name = "06_gbn_window80_wrap"
        InputSize = 25000
        RN = 100
        TimeoutMs = 250
        WindowSize = 80
        SenderAckPort = 51006
        ReceiverDataPort = 52006
        ReceiverWindow = 80
        ExpectSuccess = $true
    },
    @{
        Name = "07_timeout_failure_rule"
        InputSize = 1200
        RN = 1
        TimeoutMs = 150
        WindowSize = $null
        SenderAckPort = 51007
        ReceiverDataPort = 52007
        ReceiverWindow = 80
        ExpectSuccess = $false
    }
)

$results = @()
foreach ($test in $tests) {
    Write-Host "Running $($test.Name)..."
    $result = Invoke-TransferTest @test
    $results += $result
    if ($result.Passed) {
        Write-Host "[PASS] $($result.Name)"
    } else {
        Write-Host "[FAIL] $($result.Name) -> $($result.Notes)"
    }
}

Write-Host ""
Write-Host "Test Summary"
Write-Host "------------"

$failCount = 0
foreach ($result in $results) {
    if ($result.Passed) {
        Write-Host "PASS  $($result.Name)  ($($result.Notes))"
    } else {
        Write-Host "FAIL  $($result.Name)  ($($result.Notes))"
        Write-Host "      sender log:   $($result.SenderLog)"
        Write-Host "      receiver log: $($result.ReceiverLog)"
        $failCount++
    }
}

if ($failCount -gt 0) {
    exit 1
}

exit 0
