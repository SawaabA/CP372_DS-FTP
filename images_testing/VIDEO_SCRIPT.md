# Assignment 2 Video Script (<= 5 mins)

Use this as your speaking script + action checklist.

## 0) Before Recording (30 sec prep)

- Open **2 terminals** side by side.
- Terminal A will be `Receiver`.
- Terminal B will be `Sender`.
- Go to project root in both:

```powershell
cd C:\Users\Sawaa\Documents\CP372-NETOWRKS-2\CP372_DS-FTP
```

## 1) Start Recording + Intro (15-20 sec)

Say:
"This is our CP372 Assignment 2 demo for DS-FTP over UDP. We will show Stop-and-Wait, Go-Back-N, ACK loss handling, and proper teardown."

## 2) Compile Both Sides (20-30 sec)

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

Say:
"Both Sender and Receiver compile successfully in the default package."

## 3) Demo 1: Stop-and-Wait (RN=0) (60-75 sec)

Terminal A (Receiver):

```powershell
java Receiver 127.0.0.1 51011 52011 ..\out_video_sw.txt 0
```

Terminal B (Sender):

```powershell
java Sender 127.0.0.1 52011 51011 ..\sample_small.txt 300
```

Point to these lines:
- Receiver: `SOT recv seq=0`, `ACK send seq=0`, `Receiver done.`
- Sender: `SEND SOT`, `ACK ok for SOT`, `SEND DATA`, `SEND EOT`, `Total Transmission Time`

Say:
"This shows handshake, one-by-one Stop-and-Wait delivery, and clean EOT teardown."

## 4) Demo 2: Go-Back-N + ACK Loss (RN=5) (90-120 sec)

Terminal A (Receiver):

```powershell
$env:DSFTP_WINDOW="40"
java Receiver 127.0.0.1 51012 52012 ..\out_video_gbn.txt 5
```

Terminal B (Sender):

```powershell
java Sender 127.0.0.1 52012 51012 ..\sample_big.txt 300 40
```

Point to these lines:
- Receiver: `ACK drop seq=...`
- Receiver: `DATA buffer seq=...` / `DATA write from buffer seq=...` (if shown)
- Sender: `GBN SEND seq=...`, `GBN ACK accepted...`, `GBN timeout...` (if shown), `Total Transmission Time`

Say:
"Here we are in GBN mode with window size 40. ACK loss is simulated with RN=5. We can see dropped ACKs and protocol recovery while transfer still completes."

## 5) Prove Output Matches Input (30-45 sec)

From project root (any terminal):

```powershell
cd ..
Get-FileHash .\sample_small.txt
Get-FileHash .\out_video_sw.txt
Get-FileHash .\sample_big.txt
Get-FileHash .\out_video_gbn.txt
```

Say:
"Matching SHA256 hashes confirm the received files are identical to the originals."

## 6) Close (10 sec)

Say:
"This demo showed successful handshake, Stop-and-Wait transfer, Go-Back-N transfer, ACK loss handling, and correct teardown."

---

## Fast Troubleshooting (if something goes wrong during recording)

- If sender says file not found:
  - Make sure file path is `..\sample_small.txt` or `..\sample_big.txt` from `Sender` folder.
- If receiver is still running after a test:
  - Press `Ctrl + C` in Receiver terminal.
- If a port is busy:
  - Change both sender/receiver to a new pair, e.g. `51021` and `52021`.
