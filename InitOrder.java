/*
 * Lab:       Static and instance initialisation order, and a virtual call from a constructor that sees a null field
 * Chapters:  01 (platform fundamentals)
 * Run:       java InitOrder.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JLS §12.4.2 (class initialisation) and §12.5 (creating instances)
 * Output:    deterministic.
 */
public class InitOrder {
    public static void main(String[] args) {
        new Child();
        System.out.println("--- second instance ---");
        new Child();
    }
}

class Parent {
    static { System.out.println("1. Parent static block"); }
    { System.out.println("3. Parent instance block"); }
    Parent() {
        System.out.println("4. Parent constructor");
        describe();                        // 💣 virtual call from a constructor
    }
    void describe() { System.out.println("Parent.describe"); }
}

class Child extends Parent {
    static { System.out.println("2. Child static block"); }
    private String name = "child";
    { System.out.println("5. Child instance block, name=" + name); }
    Child() {
        super();
        System.out.println("6. Child constructor");
    }
    @Override void describe() { System.out.println("   Child.describe sees name=" + name); }
}
