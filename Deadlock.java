public class Deadlock {
    static final class Account {
        final String id; long balance;
        Account(String id, long balance) { this.id = id; this.balance = balance; }
    }
    static void transfer(Account from, Account to, long amount) throws InterruptedException {
        synchronized (from) {
            Thread.sleep(50);                  // widen the race window
            synchronized (to) {
                from.balance -= amount; to.balance += amount;
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Account a = new Account("A", 100), b = new Account("B", 100);
        Thread t1 = new Thread(() -> { try { transfer(a, b, 10); } catch (InterruptedException e) {} }, "transfer-A-to-B");
        Thread t2 = new Thread(() -> { try { transfer(b, a, 20); } catch (InterruptedException e) {} }, "transfer-B-to-A");
        t1.start(); t2.start();
        t1.join();
    }
}
