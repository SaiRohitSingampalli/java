/*
 * Lab:       API design between services: ETags (304, 412, 428), offset vs cursor pagination, Jackson evolution failures
 * Chapters:  31 (API design between services)
 * Run:       java -cp "$JACKSON_CP" ApiDemos.java
 * Needs:     JDK 21 or newer; Jackson 2.12+ (jackson-databind, jackson-core, jackson-annotations)
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   RFC 9110 §13 (conditional requests); RFC 6585 (428 Precondition Required)
 * Output:    deterministic. Starts a local HTTP server on a free port.
 */
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;

public class ApiDemos {
    // ---------- ETags
    record Product(String id, String name, long priceCents, int version) {}
    static final Object LOCK = new Object();
    static Product product = new Product("p-1", "Coffee grinder", 8900, 1);
    static final ObjectMapper JSON = new ObjectMapper();
    static String etag(Product p) { return "\"v" + p.version() + "\""; }

    static void handle(HttpExchange ex) throws java.io.IOException {
        synchronized (LOCK) {
            switch (ex.getRequestMethod()) {
                case "GET" -> {
                    ex.getResponseHeaders().add("ETag", etag(product));
                    if (etag(product).equals(ex.getRequestHeaders().getFirst("If-None-Match"))) { ex.sendResponseHeaders(304, -1); return; }
                    byte[] body = JSON.writeValueAsBytes(product);
                    ex.getResponseHeaders().add("Cache-Control", "no-cache");         // may store, must revalidate
                    ex.sendResponseHeaders(200, body.length);
                    try (var out = ex.getResponseBody()) { out.write(body); }
                }
                case "PUT" -> {
                    String ifMatch = ex.getRequestHeaders().getFirst("If-Match");
                    if (ifMatch == null) { ex.sendResponseHeaders(428, -1); return; }     // Precondition Required
                    if (!etag(product).equals(ifMatch)) { ex.sendResponseHeaders(412, -1); return; }  // lost update prevented
                    long newPrice = Long.parseLong(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim());
                    product = new Product(product.id(), product.name(), newPrice, product.version() + 1);
                    ex.getResponseHeaders().add("ETag", etag(product));
                    ex.sendResponseHeaders(204, -1);
                }
                default -> ex.sendResponseHeaders(405, -1);
            }
        }
    }

    static void etags() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/products/p-1", ApiDemos::handle);
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/products/p-1");
        try (HttpClient http = HttpClient.newHttpClient()) {
            show(http, "GET, no validator", HttpRequest.newBuilder(uri).GET().build());
            show(http, "GET, If-None-Match: \"v1\"", HttpRequest.newBuilder(uri).header("If-None-Match", "\"v1\"").GET().build());
            show(http, "PUT 9900, If-Match: \"v1\" (client A)", HttpRequest.newBuilder(uri).header("If-Match", "\"v1\"").PUT(HttpRequest.BodyPublishers.ofString("9900")).build());
            show(http, "PUT 7900, If-Match: \"v1\" (client B, stale)", HttpRequest.newBuilder(uri).header("If-Match", "\"v1\"").PUT(HttpRequest.BodyPublishers.ofString("7900")).build());
            show(http, "PUT 7900, no If-Match", HttpRequest.newBuilder(uri).PUT(HttpRequest.BodyPublishers.ofString("7900")).build());
            show(http, "GET, If-None-Match: \"v1\"", HttpRequest.newBuilder(uri).header("If-None-Match", "\"v1\"").GET().build());
        } finally { server.stop(0); }
    }
    static void show(HttpClient http, String label, HttpRequest req) throws Exception {
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.printf("%-44s -> %d  ETag %-5s body %d bytes%n", label, r.statusCode(), r.headers().firstValue("ETag").orElse("-"), r.body().length());
    }

    // ---------- pagination
    record Order(long id, long createdAt) {}
    static List<Order> table = new ArrayList<>();
    static List<Order> sorted() { return table.stream().sorted(Comparator.comparingLong(Order::createdAt).reversed().thenComparing(Comparator.comparingLong(Order::id).reversed())).toList(); }
    static List<Long> offsetPage(int offset, int size) { return sorted().stream().skip(offset).limit(size).map(Order::id).toList(); }
    static List<Order> keysetPage(Order after, int size) {        // WHERE (created_at, id) < (:createdAt, :id) ORDER BY created_at DESC, id DESC LIMIT :size
        return sorted().stream().filter(o -> after == null || o.createdAt() < after.createdAt() || (o.createdAt() == after.createdAt() && o.id() < after.id())).limit(size).toList();
    }
    static String encodeCursor(Order last) { return Base64.getUrlEncoder().withoutPadding().encodeToString((last.createdAt() + ":" + last.id()).getBytes(StandardCharsets.UTF_8)); }
    static Order decodeCursor(String cursor) {
        String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":");
        return new Order(Long.parseLong(parts[1]), Long.parseLong(parts[0]));
    }
    static void pagination() {
        for (String change : List.of("insert", "delete")) {
            table = new ArrayList<>(); for (long i = 1; i <= 10; i++) table.add(new Order(i, 1000 + i));
            List<Long> p1 = offsetPage(0, 3);
            List<Order> k1 = keysetPage(null, 3);
            String cursor = encodeCursor(k1.getLast());
            if (change.equals("insert")) table.add(new Order(11, 1011)); else table.removeIf(o -> o.id() == 9);
            List<Long> p2 = offsetPage(3, 3);
            List<Long> k2 = keysetPage(decodeCursor(cursor), 3).stream().map(Order::id).toList();
            System.out.printf("%-6s between pages | offset: page1=%s page2=%s | keyset: page1=%s page2=%s (cursor %s)%n", change, p1, p2,
                    k1.stream().map(Order::id).toList(), k2, cursor);
        }
    }

    // ---------- evolution with Jackson
    record OrderV1(String id, long totalCents) {}
    enum Status { PENDING, PAID, @JsonEnumDefaultValue UNKNOWN }
    record Payment(String id, Status status) {}
    static void jackson() {
        String fromNewerProducer = "{\"id\":\"o-1\",\"totalCents\":1299,\"currency\":\"EUR\"}";
        try { new ObjectMapper().readValue(fromNewerProducer, OrderV1.class); }
        catch (Exception e) { System.out.println("strict reader : " + e.getMessage().lines().findFirst().orElse("")); }
        try {
            OrderV1 o = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue(fromNewerProducer, OrderV1.class);
            System.out.println("tolerant reader: " + o);
        } catch (Exception e) { throw new RuntimeException(e); }
        String newEnumValue = "{\"id\":\"pay-1\",\"status\":\"REFUNDED\"}";
        try { new ObjectMapper().readValue(newEnumValue, Payment.class); }
        catch (Exception e) { System.out.println("new enum value: " + e.getMessage().lines().findFirst().orElse("")); }
        try {
            Payment p = new ObjectMapper().configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true).readValue(newEnumValue, Payment.class);
            System.out.println("with @JsonEnumDefaultValue: " + p);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== ETags =="); etags();
        System.out.println("\n== pagination =="); pagination();
        System.out.println("\n== JSON evolution =="); jackson();
    }
}
