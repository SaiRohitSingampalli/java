import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class StartupApp {
    public record Line(String sku, int qty, long priceCents) {}
    public record Order(String id, String customer, List<Line> lines, String createdAt) {}

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", exchange -> {
            Order o = new Order("o-1", "acme", List.of(new Line("sku-1", 2, 1299), new Line("sku-2", 1, 499)),
                    DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            byte[] body = mapper.writeValueAsBytes(o);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/orders");
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
            Order order = mapper.readValue(response.body(), Order.class);
            long total = order.lines().stream().mapToLong(l -> l.qty() * l.priceCents()).sum();
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            var root = doc.createElement("invoice");
            root.setAttribute("order", order.id());
            root.setTextContent(Long.toString(total));
            doc.appendChild(root);
            StringWriter xml = new StringWriter();
            TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(xml));
            boolean valid = Pattern.compile("^o-\\d+$").matcher(order.id()).matches();
            System.out.printf("ready: first request served in %d ms (total=%d, valid=%b, xml=%d chars)%n",
                    (System.nanoTime() - t0) / 1_000_000, total, valid, xml.toString().length());
        } finally {
            server.stop(0);
        }
    }
}
