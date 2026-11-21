/*
 * Lab:       Protocol Buffers evolution: unknown fields survive; a reused field number corrupts data
 * Chapters:  31 (API design between services)
 * Run:       protoc --proto_path=. --java_out=/tmp/pb order_v1.proto order_v2.proto order_v3.proto
 *            javac -cp "$PROTOBUF_JAR" -d /tmp/pb/classes $(find /tmp/pb -name '*.java') ProtoDemo.java
 *            java -cp "$PROTOBUF_JAR:/tmp/pb/classes" ProtoDemo
 * Needs:     JDK 21 or newer; protoc and protobuf-java of the same version (tested: 3.21.12)
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   Protocol Buffers Language Guide (proto3): updating a message type, reserved fields
 * Output:    deterministic.
 */
public class ProtoDemo {
    public static void main(String[] args) throws Exception {
        byte[] v2bytes = demo.v2.OrderV2Proto.Order.newBuilder()
                .setId("o-1").setCustomerEmail("ana@example.com").setQuantity(2).setCurrency("EUR").build().toByteArray();
        demo.v1.OrderV1Proto.Order seenByOldService = demo.v1.OrderV1Proto.Order.parseFrom(v2bytes);
        System.out.println("v1 reads v2 bytes   : id=" + seenByOldService.getId() + " quantity=" + seenByOldService.getQuantity()
                + " unknown fields kept=" + seenByOldService.getUnknownFields().asMap().keySet());
        byte[] forwardedByOldService = seenByOldService.toByteArray();
        System.out.println("v2 reads it back    : currency=" + demo.v2.OrderV2Proto.Order.parseFrom(forwardedByOldService).getCurrency());

        byte[] v3bytes = demo.v3.OrderV3Proto.Order.newBuilder()
                .setId("o-2").setCustomerEmail(42L).setDiscountPercent(15).build().toByteArray();
        demo.v1.OrderV1Proto.Order misread = demo.v1.OrderV1Proto.Order.parseFrom(v3bytes);
        System.out.println("v1 reads v3 bytes   : quantity=" + misread.getQuantity() + "   (v3 meant: discount_percent=15)");
        System.out.println("                      customer_email=\"" + misread.getCustomerEmail() + "\"   (wire type changed: value moved to unknown fields "
                + misread.getUnknownFields().asMap().keySet() + ")");
    }
}
