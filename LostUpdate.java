public class LostUpdate {
    static int count = 0;
    public static void main(String[] args) throws InterruptedException {
        Runnable work = () -> { for (int i = 0; i < 1_000_000; i++) count++; };
        Thread t1 = new Thread(work), t2 = new Thread(work);
        t1.start(); t2.start(); t1.join(); t2.join();
        System.out.println("expected 2000000, got " + count);
    }
}
