public class OrderStateDemo {
    public static void main(String[] args) {
        OrderState s = OrderState.DRAFT.on(OrderState.Event.SUBMIT).on(OrderState.Event.PAY).on(OrderState.Event.SHIP);
        System.out.println("DRAFT -> SUBMIT -> PAY -> SHIP = " + s);
        try { s.on(OrderState.Event.REFUND); }
        catch (IllegalStateException e) { System.out.println("rejected: " + e.getMessage()); }
    }
}

enum OrderState {
    DRAFT     { public OrderState on(Event e) { return switch (e) { case SUBMIT -> SUBMITTED; case CANCEL -> CANCELLED; default -> reject(e); }; } },
    SUBMITTED { public OrderState on(Event e) { return switch (e) { case PAY -> PAID; case CANCEL -> CANCELLED; default -> reject(e); }; } },
    PAID      { public OrderState on(Event e) { return switch (e) { case SHIP -> SHIPPED; case REFUND -> REFUNDED; default -> reject(e); }; } },
    SHIPPED   { public OrderState on(Event e) { return reject(e); } },
    CANCELLED { public OrderState on(Event e) { return reject(e); } },
    REFUNDED  { public OrderState on(Event e) { return reject(e); } };

    public enum Event { SUBMIT, PAY, SHIP, CANCEL, REFUND }
    public abstract OrderState on(Event e);
    OrderState reject(Event e) { throw new IllegalStateException(e + " is not allowed in state " + this); }
}
