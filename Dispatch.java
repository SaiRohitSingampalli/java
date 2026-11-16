import java.util.*;

public class Dispatch {
    static void m(Object o)  { System.out.println("m(Object)"); }
    static void m(String s)  { System.out.println("m(String)"); }
    static void m(Integer i) { System.out.println("m(Integer)"); }
    static void f(long x)    { System.out.println("f(long)"); }
    static void f(Integer x) { System.out.println("f(Integer)"); }
    static void f(int... xs) { System.out.println("f(int...)"); }
    static void g(Object o)  { System.out.println("g(Object)"); }
    static void g(int... xs) { System.out.println("g(int...)"); }
    static String classify(Collection<?> c) { return "Collection"; }
    static String classify(List<?> l)       { return "List"; }

    @SuppressWarnings("static-access")
    public static void main(String[] args) {
        Animal a = new Dog();
        System.out.println("a.name         = " + a.name);            // fields: static type
        System.out.println("a.kind()       = " + a.kind());          // static methods: static type
        System.out.println("a.speak()      = " + a.speak());         // instance methods: runtime type
        System.out.println("((Dog) a).name = " + ((Dog) a).name);
        m("hi");            // String
        m(42);              // Integer: phase 2 (boxing); Integer is more specific than Object
        // m(null);         // uncomment: "reference to m is ambiguous"
        f(1);               // long: widening (phase 1) beats boxing and varargs
        g(1);               // Object: boxing (phase 2) beats varargs (phase 3)
        Collection<?> c = new ArrayList<>();
        System.out.println("classify(c)    = " + classify(c));     // chosen from the static type
    }
}

class Animal {
    String name = "animal";
    static String kind() { return "Animal.kind"; }
    String speak()       { return "..."; }
}

class Dog extends Animal {
    String name = "dog";                           // hides Animal.name
    static String kind() { return "Dog.kind"; }    // hides Animal.kind()
    @Override String speak() { return "woof"; }
}
