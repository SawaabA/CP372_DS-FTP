import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class Sender {

    // ===== STUDENT CODE START =====
    // max retrys before we stop transfer
    private static final int MAX_TIMEOUT_RETRIES = 3;

    public static void main(String[] args) {

        // basic arg check first
        if (args.length != 5 && args.length != 6) {
            // if arg count wrong we cant even start
            printUsage();
            return;
        }

        try {
            // read args from command line
            String receiverIp = args[0];
            // this is port where receiver listen DATA
            int receiverDataPort = Integer.parseInt(args[1]);
            // this is my local ACK listen port
            int senderAckPort = Integer.parseInt(args[2]);
            // input file path from user
            String inputFile = args[3];
            // socket timeout in ms
            int timeoutMs = Integer.parseInt(args[4]);

            // if we got window size then use GBN mode
            boolean useGbn = (args.length == 6);
            int windowSize = 1;

            if (useGbn) {
                // parse gbn window from user input
                windowSize = Integer.parseInt(args[5]);
                // window has to follow assingment rule
                if (windowSize <= 0 || windowSize > 128 || (windowSize % 4) != 0) {
                    // invalid window means we stop now
                    System.out.println("window_size must be multiple of 4 and <= 128");
                    return;
                }
            }

            // read whole file as binary bytes
            byte[] fileBytes = Files.readAllBytes(Paths.get(inputFile));
            // make packet objects from file chunks
            List<DSPacket> dataPackets = buildDataPackets(fileBytes);
            // this list is all DATA packets we will send later

            // EOT seq = last data seq + 1, or 1 for empty file
            int eotSeq = dataPackets.isEmpty()
                ? 1
                : ((dataPackets.get(dataPackets.size() - 1).getSeqNum() + 1) % 128);

            InetAddress receiverAddress = InetAddress.getByName(receiverIp);
            // this convert ip string -> InetAddress object

            try (DatagramSocket ackSocket = new DatagramSocket(senderAckPort)) {
                // timeout is for waiting ACK packet
                ackSocket.setSoTimeout(timeoutMs);

                // timer starts right before SOT send
                long startTimeNs = System.nanoTime();

                // phase 1: SOT handshake
                DSPacket sotPacket = new DSPacket(DSPacket.TYPE_SOT, 0, null);
                // if sot handshake fail after retrys -> stop transfer
                if (!sendAndWaitForSpecificAck(
                    ackSocket,
                    receiverAddress,
                    receiverDataPort,
                    sotPacket,
                    0,
                    "SOT"
                )) {
                    System.out.println("Unable to transfer file.");
                    return;
                }

                // phase 2: send file packets
                // stop-wait when no window, gbn when window exists
                boolean dataOk;
                if (useGbn) {
                    // gbn mode path
                    dataOk = runGoBackN(
                        ackSocket,
                        receiverAddress,
                        receiverDataPort,
                        dataPackets,
                        windowSize
                    );
                } else {
                    // stop-and-wait mode path
                    dataOk = runStopAndWait(
                        ackSocket,
                        receiverAddress,
                        receiverDataPort,
                        dataPackets
                    );
                }

                if (!dataOk) {
                    // this means timeout fail or socket error happend
                    System.out.println("Unable to transfer file.");
                    return;
                }

                // phase 3: EOT teardown
                DSPacket eotPacket = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
                // if eot ack never come -> fail as well
                if (!sendAndWaitForSpecificAck(
                    ackSocket,
                    receiverAddress,
                    receiverDataPort,
                    eotPacket,
                    eotSeq,
                    "EOT"
                )) {
                    // if no eot ack after retrys, transfer fail
                    System.out.println("Unable to transfer file.");
                    return;
                }

                // print total time as assignment asks
                double totalSeconds = (System.nanoTime() - startTimeNs) / 1_000_000_000.0;
                System.out.printf("Total Transmission Time: %.2f seconds%n", totalSeconds);
            }

        } catch (NumberFormatException ex) {
            // user typed non-number for port/timeout/window
            System.out.println("Invalid number in args.");
            printUsage();
        } catch (IOException ex) {
            // file/socket errors comes here
            System.out.println("Sender error: " + ex.getMessage());
        }
    }

    private static List<DSPacket> buildDataPackets(byte[] fileBytes) {
        // list that keep every DATA packet object
        List<DSPacket> packets = new ArrayList<>();
        // seq starts 1 by assignment
        int seq = 1;
        // where we are in source byte array
        int offset = 0;

        // split file into 124-byte chunks (last one can be smaller)
        while (offset < fileBytes.length) {
            // take up to max payload size
            int length = Math.min(DSPacket.MAX_PAYLOAD_SIZE, fileBytes.length - offset);
            // temp chunk for one packet payload
            byte[] chunk = new byte[length];
            // copy bytes from full file -> packet payload
            System.arraycopy(fileBytes, offset, chunk, 0, length);
            // create DATA packet with seq + chunk
            packets.add(new DSPacket(DSPacket.TYPE_DATA, seq, chunk));

            // seq goes 0..127 then wraps
            seq = (seq + 1) % 128;
            // move file pointer for next chunk
            offset += length;
        }

        // for empty file this list will be size 0
        return packets;
    }

    private static boolean runStopAndWait(
        DatagramSocket ackSocket,
        InetAddress receiverAddress,
        int receiverPort,
        List<DSPacket> dataPackets
    ) {
        // send each packet one by one
        for (DSPacket packet : dataPackets) {
            // stop and wait: send one pakcet then wait for its ack
            boolean ok = sendAndWaitForSpecificAck(
                ackSocket,
                receiverAddress,
                receiverPort,
                packet,
                packet.getSeqNum(),
                "DATA"
            );

            if (!ok) {
                // fail fast if one packet never gets valid ack
                return false;
            }
        }

        // all packets got acked
        return true;
    }

    private static boolean runGoBackN(
        DatagramSocket ackSocket,
        InetAddress receiverAddress,
        int receiverPort,
        List<DSPacket> dataPackets,
        int windowSize
    ) {
        // total DATA count to send
        int totalPackets = dataPackets.size();
        // oldest unacked index
        int base = 0;
        // next not-yet-sent index
        int nextToSend = 0;
        // timeout streak for same base packet
        int timeoutCountForBase = 0;

        // keep going until all packets acked
        while (base < totalPackets) {
            // right edge of sending window
            int windowEnd = Math.min(base + windowSize, totalPackets);

            // send new packets that fit in current window
            if (nextToSend < windowEnd) {
                try {
                    // send once, then we wait for ack movement
                    sendRangeWithChaos(
                        ackSocket,
                        receiverAddress,
                        receiverPort,
                        dataPackets,
                        nextToSend,
                        windowEnd,
                        "GBN SEND"
                    );
                    // now all currently possible unsent packets got sent
                    nextToSend = windowEnd;
                } catch (IOException ex) {
                    System.out.println("GBN send error: " + ex.getMessage());
                    return false;
                }
            }

            try {
                // block waiting for ACK packet
                DSPacket ackPacket = receiveAckPacket(ackSocket);
                if (ackPacket == null) {
                    // not a valid ACK packet, skip it
                    continue;
                }

                int ackSeq = ackPacket.getSeqNum();
                // map ack seq -> index inside in-flight range
                int ackIndex = findAckIndex(dataPackets, base, nextToSend, ackSeq);

                if (ackIndex >= base) {
                    // ack moved window forward
                    base = ackIndex + 1;
                    timeoutCountForBase = 0; // reset timeout streak
                    System.out.println("GBN ACK accepted seq=" + ackSeq + ", base now " + base);
                } else {
                    // old ack or not for current window
                    System.out.println("GBN ACK ignored seq=" + ackSeq);
                }

            } catch (SocketTimeoutException ex) {
                // timeout means no useful ack in timeout period
                timeoutCountForBase++;
                if (timeoutCountForBase >= MAX_TIMEOUT_RETRIES) {
                    // 3 timeouts on same base -> critical failure
                    return false;
                }

                // timeout: resend full window from base
                int resendEnd = Math.min(base + windowSize, totalPackets);
                try {
                    System.out.println("GBN timeout. resend from base seq=" + dataPackets.get(base).getSeqNum());
                    sendRangeWithChaos(
                        ackSocket,
                        receiverAddress,
                        receiverPort,
                        dataPackets,
                        base,
                        resendEnd,
                        "GBN RESEND"
                    );
                } catch (IOException ioEx) {
                    System.out.println("GBN resend error: " + ioEx.getMessage());
                    return false;
                }
            } catch (IOException ex) {
                System.out.println("GBN ACK error: " + ex.getMessage());
                return false;
            }
        }

        // base passed all packets, transfer data phase done
        return true;
    }

    private static int findAckIndex(List<DSPacket> dataPackets, int base, int nextToSend, int ackSeq) {
        // find ack seq inside outstanding packets
        for (int i = base; i < nextToSend; i++) {
            if (dataPackets.get(i).getSeqNum() == ackSeq) {
                // this packet index is acked cumulativey
                return i;
            }
        }
        // ack not matched in current outstanding range
        return -1;
    }

    private static void sendRangeWithChaos(
        DatagramSocket socket,
        InetAddress receiverAddress,
        int receiverPort,
        List<DSPacket> dataPackets,
        int fromIndex,
        int toIndex,
        String logPrefix
    ) throws IOException {
        // start from first index of range
        int index = fromIndex;

        while (index < toIndex) {
            // how many packets still left in this send call
            int remaining = toIndex - index;

            // only groups of 4 can be permuted by ChaosEngine
            if (remaining >= 4) {
                // build group [i, i+1, i+2, i+3]
                List<DSPacket> group = new ArrayList<>(4);
                group.add(dataPackets.get(index));
                group.add(dataPackets.get(index + 1));
                group.add(dataPackets.get(index + 2));
                group.add(dataPackets.get(index + 3));

                // send in i+2, i, i+3, i+1 order
                List<DSPacket> permuted = ChaosEngine.permutePackets(group);
                for (DSPacket packet : permuted) {
                    // each permuted packet sent normal UDP way
                    sendPacket(socket, receiverAddress, receiverPort, packet, logPrefix);
                }

                // jump by 4 because this group done
                index += 4;
            } else {
                // less then 4 left, send normal order
                sendPacket(socket, receiverAddress, receiverPort, dataPackets.get(index), logPrefix);
                // move to next single packet
                index++;
            }
        }
    }

    private static boolean sendAndWaitForSpecificAck(
        DatagramSocket ackSocket,
        InetAddress receiverAddress,
        int receiverPort,
        DSPacket packetToSend,
        int expectedAckSeq,
        String label
    ) {
        // count timeout retrys for this exact packet
        int timeoutCount = 0;

        // try same packet until ack comes or retries done
        while (timeoutCount < MAX_TIMEOUT_RETRIES) {
            try {
                // send packet first
                sendPacket(ackSocket, receiverAddress, receiverPort, packetToSend, "SEND " + label);

                // keep waiting until timeout or right ack comes
                while (true) {
                    DSPacket ackPacket = receiveAckPacket(ackSocket);
                    if (ackPacket == null) {
                        // ignore bad/non-ack packet and keep waiting
                        continue;
                    }

                    if (ackPacket.getSeqNum() == expectedAckSeq) {
                        // this is the ack we needed
                        System.out.println("ACK ok for " + label + " seq=" + expectedAckSeq);
                        return true;
                    }

                    // wrong ack, keep waiting untill timeout
                    System.out.println(
                        "ACK ignored for " + label
                            + " expected=" + expectedAckSeq
                            + " got=" + ackPacket.getSeqNum()
                    );
                }

            } catch (SocketTimeoutException ex) {
                timeoutCount++;
                // timeout count is for same packet only
                System.out.println(label + " timeout " + timeoutCount + "/3");
            } catch (IOException ex) {
                // socket read/send error
                System.out.println(label + " send/ack error: " + ex.getMessage());
                return false;
            }
        }

        // reached max timeout retrys
        return false;
    }

    private static DSPacket receiveAckPacket(DatagramSocket socket) throws IOException {
        // fixed 128 bytes every time by protocol
        byte[] buffer = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        // block untill one datagram received
        socket.receive(datagram);

        // bad packet bytes -> ignore
        DSPacket packet;
        try {
            packet = new DSPacket(datagram.getData());
        } catch (IllegalArgumentException ex) {
            return null;
        }

        // we only care about ACK packets here
        if (packet.getType() != DSPacket.TYPE_ACK) {
            // ignore SOT/DATA/EOT here
            return null;
        }

        return packet;
    }

    private static void sendPacket(
        DatagramSocket socket,
        InetAddress receiverAddress,
        int receiverPort,
        DSPacket packet,
        String logPrefix
    ) throws IOException {
        // convert packet object into raw 128-byte datagram
        byte[] raw = packet.toBytes();
        // target is receiver data port always
        DatagramPacket datagram = new DatagramPacket(raw, raw.length, receiverAddress, receiverPort);
        // actual UDP send call
        socket.send(datagram);
        // small logs help for demo video
        System.out.println(logPrefix + " seq=" + packet.getSeqNum());
    }

    private static void printUsage() {
        System.out.println(
            "Usage (Stop-and-Wait): java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms>"
        );
        System.out.println(
            "Usage (Go-Back-N): java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> <window_size>"
        );
    }
    // ===== STUDENT CODE END =====
}
