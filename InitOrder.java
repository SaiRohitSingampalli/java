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
