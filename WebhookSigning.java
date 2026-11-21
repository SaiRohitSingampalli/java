/*
 * Lab:       Webhook security on the sender's side: signatures a receiver can check, and endpoint addresses we refuse
 * Chapters:  38 (webhook delivery case study), 25 §4 (SSRF), 33 Mock 2
 * Run:       java WebhookSigning.java
 * Needs:     JDK 21 or newer, nothing else (no network: every address is a literal, plus "localhost" from /etc/hosts)
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   Standard Webhooks specification: https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md
 *            OWASP SSRF Prevention Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html
 *            RFC 6890 (special-purpose IP address registries)
 * Output:    deterministic (fixed secrets and a fixed clock). Exits with status 1 if a check fails.
 */
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class WebhookSigning {

    static final long TOLERANCE_SECONDS = 300;   // the specification's suggested 5 minutes
    static boolean ok = true;

    public static void main(String[] args) throws Exception {
        signing();
        System.out.println();
        addresses();
        if (!ok) {
            System.out.println("\nSELF-CHECK FAILED");
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 1. signatures

    record Delivery(String id, long timestamp, String body, String signatureHeader) {}

    static void signing() throws Exception {
        byte[] oldSecret = secret("old"), newSecret = secret("new");
        long now = 1_795_000_000L;   // a fixed clock, in Unix seconds
        String body = "{\"type\":\"payment.succeeded\",\"data\":{\"payment_id\":\"pay_123\",\"amount\":100}}";
        String id = "msg_2Qx7Lk9c";

        Delivery genuine = new Delivery(id, now, body, sign(List.of(newSecret), id, now, body));
        Delivery altered = new Delivery(id, now, body.replace("\"amount\":100", "\"amount\":900"), genuine.signatureHeader());
        Delivery newId = new Delivery("msg_9Zz1Aa0b", now, body, genuine.signatureHeader());
        Delivery bothKeys = new Delivery(id, now, body, sign(List.of(oldSecret, newSecret), id, now, body));

        System.out.println("1. Signatures (Standard Webhooks: HMAC-SHA256 over \"id.timestamp.body\", 5-minute tolerance)");
        System.out.printf("   %-62s %s%n", "case", "receiver's verdict");
        check("genuine delivery", verify(genuine, List.of(newSecret), now), "accepted");
        check("body altered in transit (\"amount\":100 -> 900)", verify(altered, List.of(newSecret), now), "rejected: no signature matches");
        check("captured and replayed 10 minutes later", verify(genuine, List.of(newSecret), now + 600), "rejected: timestamp outside tolerance");
        check("same body and signature under a new webhook-id", verify(newId, List.of(newSecret), now), "rejected: no signature matches");
        check("rotation: signed with old and new secrets, receiver has old", verify(bothKeys, List.of(oldSecret), now), "accepted");
        check("rotation: signed with old and new secrets, receiver has new", verify(bothKeys, List.of(newSecret), now), "accepted");
        check("after rotation: signed with new only, receiver still has old", verify(genuine, List.of(oldSecret), now), "rejected: no signature matches");
        System.out.println("   Header sent with the genuine delivery:");
        System.out.println("     webhook-id: " + genuine.id());
        System.out.println("     webhook-timestamp: " + genuine.timestamp());
        System.out.println("     webhook-signature: " + genuine.signatureHeader());
    }

    static byte[] secret(String name) throws Exception {   // deterministic 32-byte secrets for the demo
        return MessageDigest.getInstance("SHA-256").digest(("northwind-demo-" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** One "v1,<base64>" entry per active secret, space-separated, as the specification allows during rotation. */
    static String sign(List<byte[]> secrets, String id, long timestamp, String body) throws Exception {
        StringJoiner header = new StringJoiner(" ");
        for (byte[] secret : secrets) header.add("v1," + Base64.getEncoder().encodeToString(hmac(secret, id, timestamp, body)));
        return header.toString();
    }

    static byte[] hmac(byte[] secret, String id, long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
    }

    static String verify(Delivery d, List<byte[]> receiverSecrets, long receiverNow) throws Exception {
        if (Math.abs(receiverNow - d.timestamp()) > TOLERANCE_SECONDS) return "rejected: timestamp outside tolerance";
        for (byte[] secret : receiverSecrets) {
            byte[] expected = hmac(secret, d.id(), d.timestamp(), d.body());
            for (String entry : d.signatureHeader().split(" ")) {
                if (!entry.startsWith("v1,")) continue;
                byte[] given = Base64.getDecoder().decode(entry.substring(3));
                if (MessageDigest.isEqual(expected, given)) return "accepted";   // constant-time comparison
            }
        }
        return "rejected: no signature matches";
    }

    static void check(String label, String actual, String expected) {
        boolean pass = actual.equals(expected);
        ok &= pass;
        System.out.printf("   %-62s %s%s%n", label, actual, pass ? "" : "   <-- expected " + expected);
    }

    // ---------------------------------------------------------------- 2. endpoint addresses (SSRF)

    record Case(String literal, String note, boolean shouldRefuse) {}

    static void addresses() throws Exception {
        List<Case> cases = List.of(
            new Case("8.8.8.8", "public", false),
            new Case("2606:4700:4700::1111", "public (IPv6)", false),
            new Case("localhost", "a name: resolved from /etc/hosts", true),
            new Case("127.1", "loopback, short form", true),
            new Case("2130706433", "loopback, one 32-bit number", true),
            new Case("10.0.0.5", "private", true),
            new Case("172.16.3.4", "private", true),
            new Case("192.168.1.20", "private", true),
            new Case("169.254.169.254", "cloud metadata service", true),
            new Case("::ffff:169.254.169.254", "metadata, as IPv4-mapped IPv6", true),
            new Case("100.64.0.1", "carrier-grade NAT, used inside some clouds", true),
            new Case("0.0.0.0", "unspecified: on Linux, a connect reaches this host", true),
            new Case("::1", "loopback (IPv6)", true),
            new Case("::", "unspecified (IPv6)", true),
            new Case("fd00::1", "unique local (IPv6 private)", true),
            new Case("fe80::1", "link-local (IPv6)", true),
            new Case("::127.0.0.1", "IPv4-compatible IPv6 (deprecated)", true),
            new Case("0177.0.0.1", "C's inet_aton reads 0177 as octal: 127.0.0.1", false));

        System.out.println("2. Endpoint addresses: a naive check (loopback or RFC 1918) vs a strict one (only global unicast)");
        System.out.printf("   %-23s %-28s %-15s %-47s %s%n", "endpoint host", "Java resolves it to", "naive check", "strict check", "what it is");
        int naiveMisses = 0;
        for (Case c : cases) {
            InetAddress a = InetAddress.getAllByName(c.literal())[0];
            boolean naiveRefuses = a.isLoopbackAddress() || a.isSiteLocalAddress();
            String reason = refuseReason(a);
            boolean strictRefuses = reason != null;
            if (c.shouldRefuse() && !naiveRefuses) naiveMisses++;
            boolean pass = strictRefuses == c.shouldRefuse();
            ok &= pass;
            String naive = naiveRefuses ? "refused" : c.shouldRefuse() ? "ALLOWED (miss)" : "allowed";
            System.out.printf("   %-23s %-28s %-15s %-47s %s%s%n", c.literal(), a.getHostAddress(),
                              naive, strictRefuses ? "refused: " + reason : "allowed",
                              c.note(), pass ? "" : "   <-- WRONG");
        }
        System.out.printf("   The naive check let through %d of the %d addresses that must be refused.%n", naiveMisses,
                          cases.stream().filter(Case::shouldRefuse).count());
        System.out.println("   0177.0.0.1: Java reads 177.0.0.1 (a public address), so connect to the address you checked, never re-parse the name.");
    }

    /** Null if the address is a global unicast address we may connect to; otherwise the reason to refuse it. */
    static String refuseReason(InetAddress a) {
        if (a.isAnyLocalAddress()) return "unspecified";
        if (a.isLoopbackAddress()) return "loopback";
        if (a.isLinkLocalAddress()) return "link-local";
        if (a.isSiteLocalAddress()) return "private (RFC 1918)";
        if (a.isMulticastAddress()) return "multicast";
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xFF, second = b[1] & 0xFF;
            if (first == 0) return "'this network' (0.0.0.0/8)";
            if (first == 100 && (second & 0xC0) == 64) return "carrier-grade NAT (100.64.0.0/10)";
            if (first == 192 && second == 0 && (b[2] & 0xFF) == 0) return "IETF protocol assignments (192.0.0.0/24)";
            if (first == 198 && (second & 0xFE) == 18) return "benchmarking (198.18.0.0/15)";
            if (first >= 240) return "reserved (240.0.0.0/4)";
            return null;
        }
        if ((b[0] & 0xE0) != 0x20) return "not global unicast (outside 2000::/3)";
        if (b[0] == 0x20 && b[1] == 0x02) return "6to4: embeds an IPv4 address";
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0) return "Teredo: embeds an IPv4 address";
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0D && (b[3] & 0xFF) == 0xB8) return "documentation (2001:db8::/32)";
        return null;
    }
}
