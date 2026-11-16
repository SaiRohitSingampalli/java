import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Redis Cluster key-to-slot mapping: CRC16 (XMODEM) of the key, or of its {hash tag}, modulo 16,384.
 *
 *   java HashSlots.java              prints the slots
 *   java HashSlots.java 7000         also checks every slot against CLUSTER KEYSLOT on a
 *                                         cluster-enabled node, e.g.
 *                                         redis-server --port 7000 --cluster-enabled yes --save ''
 */
public class HashSlots {
    static int crc16(byte[] bytes) {
        int crc = 0;
        for (byte b : bytes) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) crc = ((crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1) & 0xFFFF;
        }
        return crc;
    }

    /** Only the part between the FIRST '{' and the first '}' after it is hashed, if that part is non-empty. */
    static int slot(String key) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        int open = indexOf(k, '{', 0);
        if (open >= 0) {
            int close = indexOf(k, '}', open + 1);
            if (close > open + 1) k = Arrays.copyOfRange(k, open + 1, close);
        }
        return crc16(k) % 16_384;
    }

    static int indexOf(byte[] b, char c, int from) {
        for (int i = from; i < b.length; i++) if (b[i] == c) return i;
        return -1;
    }

    public static void main(String[] args) throws IOException {
        if (crc16("123456789".getBytes(StandardCharsets.US_ASCII)) != 0x31C3) throw new AssertionError("CRC16 is wrong");
        List<String> keys = List.of("user:42", "user:43", "cart:user:42",
                "{user:42}:cart", "{user:42}:profile", "{user:42}:orders",
                "{}user:42", "user:{}42", "{user:42", "x{user:42}y{order:7}");
        Integer port = args.length > 0 ? Integer.valueOf(args[0]) : null;
        try (Socket s = port == null ? null : new Socket("localhost", port)) {
            for (String key : keys) {
                int mine = slot(key);
                String check = "";
                if (s != null) {
                    long theirs = keyslot(s, key);
                    check = theirs == mine ? "  = CLUSTER KEYSLOT" : "  MISMATCH: server says " + theirs;
                }
                System.out.printf("  %-22s slot %5d%s%n", key, mine, check);
            }
        }
    }

    /** Sends CLUSTER KEYSLOT and reads the integer reply (":<n>\r\n"). */
    static long keyslot(Socket s, String key) throws IOException {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("*3\r\n$7\r\nCLUSTER\r\n$7\r\nKEYSLOT\r\n$" + k.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(k);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = s.getInputStream();
        StringBuilder line = new StringBuilder();
        for (int c; (c = in.read()) != '\n'; ) if (c != '\r') line.append((char) c);
        if (line.charAt(0) != ':') throw new IOException("unexpected reply: " + line);
        return Long.parseLong(line.substring(1));
    }
}
