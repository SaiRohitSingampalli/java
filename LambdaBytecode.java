/*
 * Lab:       A lambda is invokedynamic plus LambdaMetafactory plus a private synthetic method
 * Chapters:  07 (lambdas and streams)
 * Run:       javac -d /tmp/ch07 LambdaBytecode.java && javap -c -p -v -cp /tmp/ch07 LambdaBytecode
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   Brian Goetz, Translation of Lambda Expressions (2012); java.lang.invoke.LambdaMetafactory
 * Output:    deterministic.
 */
import java.util.function.Function;

public class LambdaBytecode {
    public static void main(String[] args) {
        int base = 10;
        Function<Integer, Integer> add = x -> x + base;
        System.out.println(add.apply(5));
    }
}
