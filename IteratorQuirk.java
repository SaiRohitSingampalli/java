/*
 * Lab:       Removing the second-to-last element during iteration: no exception, an element skipped
 * Chapters:  06 (collections)
 * Run:       java IteratorQuirk.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   OpenJDK source: java.util.ArrayList.Itr
 * Output:    deterministic.
 */
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
