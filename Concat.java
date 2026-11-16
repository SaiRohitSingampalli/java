public class Concat {
    static String concat(String a, String b, String c) { return a + b + c; }   // javap -c -v: invokedynamic makeConcatWithConstants
    public static void main(String[] args) { System.out.println(concat("a", "b", "c")); }
}
