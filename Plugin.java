public class Plugin {
    public String name() { return "plugin"; }
    public int score(int a, int b) { return a * 31 + b; }
    public String describe(Object o) { return name() + ":" + o; }
}
