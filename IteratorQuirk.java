import java.util.*;

public class IteratorQuirk {
    public static void main(String[] args) {
        List<String> list = new ArrayList<>(List.of("a", "b", "c", "d"));
        List<String> visited = new ArrayList<>();
        for (String s : list) {
            visited.add(s);
            if (s.equals("c")) list.remove(s);    // no exception, and "d" is never visited!
        }
        System.out.println("visited=" + visited + "  list=" + list);
    }
}
