import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class Receiver {

    // ===== STUDENT CODE START =====
    // ------------- what we are doing --- receiver config ---
    // default recive window if env var not set
    private static final int DEFAULT_RECEIVE_WINDOW = 80;

    // tiny wrapper so ACK count can be passed around
    private static class AckCounter {
        int count = 0;
    }

    public static void main(String[] args) {
        // ------------- what we are doing --- receiver start + parse args ---
        // basic arg check first
        if (args.length != 5) {
            // wrong args count so show usage and stop
            printUsage();
            return;
        }

        try {
            // ------------- what we are doing --- read command values ---
            // parse cmd args into simple vars
            String senderIp = args[0];
            // sender ack port where we send all ACK packet
            int senderAckPort = Integer.parseInt(args[1]);
            // this receiver data listen port
            int receiverDataPort = Integer.parseInt(args[2]);
            // output file that we write received bytes into
            String outputFile = args[3];
            // reliability number for ACK drop rule
            int rn = Integer.parseInt(args[4]);

            // sender ip + ack port are used for all ACK replies
            InetAddress senderAddress = InetAddress.getByName(senderIp);
            // this window value used in out-of-order checks
            int receiveWindow = resolveReceiveWindow();

            try (
                DatagramSocket dataSocket = new DatagramSocket(receiverDataPort);
                FileOutputStream fileOut = new FileOutputStream(outputFile)
            ) {
                // ------------- what we are doing --- open socket and output file ---
                // keep count of every ack we try to send
                AckCounter ackCounter = new AckCounter();

                System.out.println("Receiver listening on port " + receiverDataPort);

                // ------------- what we are doing --- phase1 wait SOT ---
                // phase 1: wait SOT then ACK it
                int expectedSeq = waitForSot(
                    dataSocket,
                    senderAddress,
                    senderAckPort,
                    rn,
                    ackCounter
                );

                // ------------- what we are doing --- phase2 data + phase3 eot ---
                // phase 2 + 3: data loop and then EOT
                runReceiveLoop(
                    dataSocket,
                    fileOut,
                    senderAddress,
                    senderAckPort,
                    rn,
                    ackCounter,
                    expectedSeq,
                    receiveWindow
                );
            }
        } catch (NumberFormatException ex) {
            // maybe wrong number typed by us
            // user gave bad number in command args
            System.out.println("Invalid number in args.");
            printUsage();
        } catch (IOException ex) {
            // socket/file issues end up here
            // network/file write errors
            System.out.println("Receiver error: " + ex.getMessage());
        }
    }

    private static int resolveReceiveWindow() {
        // ------------- what we are doing --- pick receiver window size ---
        // receiver has no CLI window, so DSFTP_WINDOW can override
        int configured = DEFAULT_RECEIVE_WINDOW;
        String envValue = System.getenv("DSFTP_WINDOW");

        if (envValue != null && !envValue.trim().isEmpty()) {
            try {
                // parse env window first
                int parsed = Integer.parseInt(envValue.trim());

                // we need valid gbn window only
                // accept only valid DS-FTP window values
                if (parsed >= 4 && parsed <= 128 && parsed % 4 == 0) {
                    configured = parsed;
                } else {
                    // bad value -> go back default
                    configured = DEFAULT_RECEIVE_WINDOW;
                }
            } catch (NumberFormatException ex) {
                // bad env value -> fallback to default
                configured = DEFAULT_RECEIVE_WINDOW;
            }
        }

        // this number used for out-of-order buffer range
        return configured;
    }

    private static int waitForSot(
        DatagramSocket dataSocket,
        InetAddress senderAddress,
        int senderAckPort,
        int rn,
        AckCounter ackCounter
    ) throws IOException {
        // ------------- what we are doing --- keep waiting for first SOT ---
        // wait forever untill valid SOT arrives
        while (true) {
            DSPacket packet = receivePacket(dataSocket);
            if (packet == null) {
                // garbage packet -> ignore
                continue;
            }

            // handshake only accepts SOT seq 0
            if (packet.getType() == DSPacket.TYPE_SOT && packet.getSeqNum() == 0) {
                System.out.println("SOT recv seq=0");
                // send ack maybe dropped by chaos rn
                // ack sot (can be dropped by RN rule too)
                sendAckMaybeDrop(dataSocket, senderAddress, senderAckPort, 0, rn, ackCounter);
                // next expected data seq starts at 1
                return 1; // first data seq is 1
            }
        }
    }

    private static void runReceiveLoop(
        DatagramSocket dataSocket,
        FileOutputStream fileOut,
        InetAddress senderAddress,
        int senderAckPort,
        int rn,
        AckCounter ackCounter,
        int expectedSeq,
        int receiveWindow
    ) throws IOException {
        // ------------- what we are doing --- main receive loop ---
        // nextExpected = seq number we still need
        int nextExpected = expectedSeq;
        // key is seq, value is payload bytes
        Map<Integer, byte[]> buffer = new HashMap<>();

        // receive until we finish EOT
        while (true) {
            DSPacket packet = receivePacket(dataSocket);
            if (packet == null) {
                // bad packet parse, skip this one
                continue;
            }

            // read packet type + seq quick for branch logic
            byte type = packet.getType();
            int seq = packet.getSeqNum();

            if (type == DSPacket.TYPE_SOT) {
                // got sot again maybe sender timeouted before
                // if sender re-sends SOT, ack it again
                System.out.println("SOT recv again, send ACK 0");
                sendAckMaybeDrop(dataSocket, senderAddress, senderAckPort, 0, rn, ackCounter);
                // then continue wait for data/eot
                continue;
            }

            if (type == DSPacket.TYPE_EOT) {
                // ------------- what we are doing --- eot close handshake ---
                System.out.println("EOT recv seq=" + seq);

                // we only close after EOT ACK actualy gets sent
                boolean ackSent = sendAckMaybeDrop(
                    dataSocket,
                    senderAddress,
                    senderAckPort,
                    seq,
                    rn,
                    ackCounter
                );

                if (ackSent) {
                    // done only when EOT ack really sent
                    System.out.println("Receiver done.");
                    break;
                } else {
                    // if dropped we must stay alive for resend eot
                    // dont close yet if eot ack was dropped
                    System.out.println("EOT ACK dropped, wait for EOT resend");
                }
                continue;
            }

            // ignore unknown packet types
            if (type != DSPacket.TYPE_DATA) {
                // only DATA handled in this section
                continue;
            }

            if (seq == nextExpected) {
                // ------------- what we are doing --- in-order write path ---
                // in-order packet: write now
                writePayload(fileOut, packet.getPayload(), packet.getLength(), seq);
                // move expected pointer by 1
                nextExpected = (nextExpected + 1) % 128;

                // after writing, flush any next seq from buffer
                while (buffer.containsKey(nextExpected)) {
                    // if next seq already buffered, write it too
                    byte[] payload = buffer.remove(nextExpected);
                    fileOut.write(payload);
                    System.out.println("DATA write from buffer seq=" + nextExpected);
                    // keep moving expected while contiguous seq exists
                    nextExpected = (nextExpected + 1) % 128;
                }
            } else if (isWithinWindow(nextExpected, seq, receiveWindow)) {
                // ------------- what we are doing --- out-of-order buffer path ---
                // out-of-order but still inside recieve window
                if (!buffer.containsKey(seq)) {
                    // keep first copy only, no need to overwrite
                    byte[] payloadCopy = Arrays.copyOf(packet.getPayload(), packet.getLength());
                    buffer.put(seq, payloadCopy);
                    System.out.println("DATA buffer seq=" + seq);
                } else {
                    // duplicate out-of-order packet
                    System.out.println("DATA duplicate buffered seq=" + seq);
                }
            } else {
                // packet is old or too far so we throw it
                // old packet or way ahead -> drop it
                System.out.println("DATA discard seq=" + seq);
            }

            // ------------- what we are doing --- send cumulative ack ---
            // cumulative ACK = last contiguous seq delivered
            int cumulativeAckSeq = (nextExpected + 127) % 128;
            // this ack can also be dropped by ChaosEngine RN
            sendAckMaybeDrop(
                dataSocket,
                senderAddress,
                senderAckPort,
                cumulativeAckSeq,
                rn,
                ackCounter
            );
        }
    }

    private static boolean isWithinWindow(int expectedSeq, int seq, int receiveWindow) {
        // ------------- what we are doing --- check if seq in receive window ---
        // modulo distance from expected to this seq
        int distance = (seq - expectedSeq + 128) % 128;
        // must be ahead of expected and inside window size
        return distance > 0 && distance < receiveWindow;
    }

    private static void writePayload(FileOutputStream fileOut, byte[] payload, int length, int seq)
        throws IOException {
        // ------------- what we are doing --- write payload bytes to output file ---
        // write only real payload bytes
        fileOut.write(payload, 0, length);
        // console log for tracing
        System.out.println("DATA write seq=" + seq);
    }

    private static boolean sendAckMaybeDrop(
        DatagramSocket dataSocket,
        InetAddress senderAddress,
        int senderAckPort,
        int ackSeq,
        int rn,
        AckCounter ackCounter
    ) throws IOException {
        // ------------- what we are doing --- maybe drop or send ACK ---
        // every time we call this, we increment ack attempt count
        ackCounter.count++;

        // RN rule: drop every RN-th ack
        if (ChaosEngine.shouldDrop(ackCounter.count, rn)) {
            // this is fake packet loss from assignment
            System.out.println("ACK drop seq=" + ackSeq + " (count=" + ackCounter.count + ")");
            // return false so caller know ack was not sent
            return false;
        }

        // normal ACK send
        DSPacket ackPacket = new DSPacket(DSPacket.TYPE_ACK, ackSeq, null);
        // convert to fixed 128-byte datagram
        byte[] raw = ackPacket.toBytes();
        DatagramPacket datagram = new DatagramPacket(raw, raw.length, senderAddress, senderAckPort);
        // udp send
        dataSocket.send(datagram);
        System.out.println("ACK send seq=" + ackSeq);
        // ack really sent out
        return true;
    }

    private static DSPacket receivePacket(DatagramSocket socket) throws IOException {
        // ------------- what we are doing --- receive one packet + parse it ---
        // protocol says every datagram is 128 bytes
        byte[] buffer = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        // block untill one datagram comes
        socket.receive(datagram);

        // invalid packet format -> ignore
        try {
            return new DSPacket(datagram.getData());
        } catch (IllegalArgumentException ex) {
            // bad length field etc
            return null;
        }
    }

    private static void printUsage() {
        // ------------- what we are doing --- usage text ---
        System.out.println("Usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
    }
    // ===== STUDENT CODE END =====
}
