import java.util.*;
import java.util.stream.*;

public class VerticalStream {
    public static void main(String[] args) {
        Optional<String> first = Stream.of("a", "bb", "ccc", "dddd")
                .filter(s -> { System.out.println("filter " + s); return s.length() > 1; })
                .map(s -> { System.out.println("map " + s); return s.toUpperCase(); })
                .findFirst();
        System.out.println(first);
    }
}
