public class Bytecode {
    static int max(int a, int b) { return a > b ? a : b; }
    int sumAll(int[] xs) { int s = 0; for (int x : xs) s += x; return s; }
    public static void main(String[] args) { System.out.println(max(3, 7) + " " + new Bytecode().sumAll(new int[]{1, 2, 3})); }
}
