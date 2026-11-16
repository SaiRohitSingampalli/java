import java.util.*;
public class Hot {
    // Deliberately inefficient "request handler": compiles a regex per call and concatenates in a loop
    static boolean isValidEmail(String s) { return s.matches("^[\\w.+-]+@[\\w-]+\\.[\\w.]+$"); }
    static String render(List<String> items) { String out = ""; for (String i : items) out += i + ","; return out; }
    public static void main(String[] args) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 200; i++) items.add("item-" + i);
        String[] emails = new String[1000];
        for (int i = 0; i < emails.length; i++) emails[i] = "user" + i + "@example.com";
        long ok = 0, len = 0, n = 0, end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            for (int k = 0; k < 20; k++) if (isValidEmail(emails[(int) (n++ % emails.length)])) ok++;
            len += render(items).length();
        }
        System.out.println(ok + " " + len);
    }
}
