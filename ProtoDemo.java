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
