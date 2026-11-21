/*
 * Lab:       Stack-machine bytecode, and the enhanced for loop after desugaring
 * Chapters:  09 (JVM internals)
 * Run:       javac -d /tmp/ch09 Bytecode.java && javap -c -p -cp /tmp/ch09 Bytecode
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JVMS chapter 6 (the instruction set); JLS §14.14.2 (enhanced for)
 * Output:    deterministic.
 */
public class Bytecode {
    static int max(int a, int b) { return a > b ? a : b; }
    int sumAll(int[] xs) { int s = 0; for (int x : xs) s += x; return s; }
    public static void main(String[] args) { System.out.println(max(3, 7) + " " + new Bytecode().sumAll(new int[]{1, 2, 3})); }
}
