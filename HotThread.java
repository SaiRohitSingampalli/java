import java.util.regex.Pattern;
public class HotThread {
    public static void main(String[] args) throws Exception {
        Pattern p = Pattern.compile("^(\\w+\\s?){1,100}$");                  // catastrophic backtracking (Chapter 03)
        Thread.ofPlatform().name("order-validator-1").start(() -> {
            while (true) p.matcher("pay the invoice for order number twelve now please ok!").matches();
        });
        for (int i = 0; i < 3; i++) Thread.ofPlatform().name("http-worker-" + i).start(() -> { while (true) { try { Thread.sleep(1000); } catch (InterruptedException e) { return; } } });
        Thread.sleep(120_000);
    }
}
