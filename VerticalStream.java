/*
 * Lab:       Streams process one element at a time through the pipeline, and short-circuit
 * Chapters:  07 (lambdas and streams)
 * Run:       java VerticalStream.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   java.util.stream package summary (laziness, short-circuiting)
 * Output:    deterministic.
 */
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
