# DS-FTP Testing Guide

This file has both:
- automatic testing (`run_feature_tests.ps1`)
- manual testing (2 terminals, exact commands)

## 1) Automatic Testing

Run from project root:

```powershell
.\run_feature_tests.ps1
```

What this script does:
- compiles `Sender/*.java` and `Receiver/*.java`
- runs each test with separate ports
- compares input/output file hashes for success tests
- checks failure message for timeout-failure test

### Automatic Test Meaning

1. `01_stop_wait_basic`
- Goal: basic handshake + stop-and-wait + EOT.
- Pass: transfer completes and output hash matches input.

1. `02_stop_wait_ack_loss`
- Goal: verify retransmission when ACKs are dropped (`RN=5`).
- Pass: transfer still completes and output hash matches input.

1. `03_empty_file`
- Goal: verify empty-file case (no DATA packets, EOT seq is 1).
- Pass: output is empty and sender completes cleanly.

1. `04_gbn_window20`
- Goal: verify GBN with window size 20 and 4-packet chaos permutation.
- Pass: transfer completes with correct output.

1. `05_gbn_window40_ack_loss`
- Goal: verify GBN cumulative ACK + buffering with ACK loss (`RN=5`).
- Pass: transfer completes with correct output.

1. `06_gbn_window80_wrap`
- Goal: verify larger GBN window and sequence wrap-around behavior.
- Pass: transfer completes with correct output.

1. `07_timeout_failure_rule`
- Goal: verify critical failure after 3 consecutive timeouts for same packet.
- Pass: sender prints `Unable to transfer file.`

## 2) Manual Testing 

Use Terminal A for Receiver and Terminal B for Sender.

### Step A: Compile once

Terminal A:

```powershell
cd Receiver
javac *.java
```

Terminal B:

```powershell
cd Sender
javac *.java
```

### Step B: Make input files

Terminal B:

```powershell
"small quick test file" | Set-Content ..\sample.txt
1..400 | ForEach-Object { "line $_ for bigger test data" } | Set-Content ..\sample_big.txt
New-Item ..\empty.bin -ItemType File -Force
```

### Step C: Run manual tests

#### Test M1: Stop-and-Wait basic (`RN=0`)

Terminal A:

```powershell
java Receiver 127.0.0.1 51001 52001 ..\out_sw.txt 0
```

Terminal B:

```powershell
java Sender 127.0.0.1 52001 51001 ..\sample.txt 300
```

Meaning:
- checks clean handshake, DATA, teardown with no simulated ACK loss.

#### Test M2: Stop-and-Wait with ACK loss (`RN=5`)

Terminal A:

```powershell
java Receiver 127.0.0.1 51002 52002 ..\out_sw_loss.txt 5
```

Terminal B:

```powershell
java Sender 127.0.0.1 52002 51002 ..\sample_big.txt 300
```

Meaning:
- every 5th ACK is dropped, sender should timeout then resend same DATA seq.

#### Test M3: Empty file

Terminal A:

```powershell
java Receiver 127.0.0.1 51003 52003 ..\out_empty.bin 0
```

Terminal B:

```powershell
java Sender 127.0.0.1 52003 51003 ..\empty.bin 300
```

Meaning:
- verifies no DATA packets are sent and EOT flow still works.

#### Test M4: GBN window 20

Terminal A:

```powershell
$env:DSFTP_WINDOW="20"
java Receiver 127.0.0.1 51004 52004 ..\out_gbn20.txt 0
```

Terminal B:

```powershell
java Sender 127.0.0.1 52004 51004 ..\sample_big.txt 300 20
```

Meaning:
- checks GBN sliding window + chaos packet order for 4-packet groups.

#### Test M5: GBN window 40 + ACK loss

Terminal A:

```powershell
$env:DSFTP_WINDOW="40"
java Receiver 127.0.0.1 51005 52005 ..\out_gbn40_loss.txt 5
```

Terminal B:

```powershell
java Sender 127.0.0.1 52005 51005 ..\sample_big.txt 300 40
```

Meaning:
- checks GBN buffering/cumulative ACK behavior while ACKs are dropped.

#### Test M6: GBN window 80 + wrap-around stress

Terminal A:

```powershell
$env:DSFTP_WINDOW="80"
java Receiver 127.0.0.1 51006 52006 ..\out_gbn80.txt 100
```

Terminal B:

```powershell
java Sender 127.0.0.1 52006 51006 ..\sample_big.txt 300 80
```

Meaning:
- checks bigger window behavior and sequence wrap path under longer transfer.

#### Test M7: Critical timeout failure

Terminal A:

```powershell
java Receiver 127.0.0.1 51007 52007 ..\out_fail.txt 1
```

Terminal B:

```powershell
java Sender 127.0.0.1 52007 51007 ..\sample_big.txt 150
```

Expected:
- sender prints `Unable to transfer file.`
- receiver may keep waiting (stop it with `Ctrl + C` after sender fails)

## 3) Hash Check Command (manual success tests)

Run from project root:

```powershell
Get-FileHash .\sample_big.txt
Get-FileHash .\out_sw_loss.txt
```

If hashes match, file content transfer is correct.
