/*
 * Lab:       The plugin class that MetaspaceLeak.java loads again and again (not run on its own)
 * Chapters:  32 (case 4)
 * Run:       compiled together with MetaspaceLeak.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   none
 * Output:    none: it's a helper.
 */
public class Plugin {
    public String name() { return "plugin"; }
    public int score(int a, int b) { return a * 31 + b; }
    public String describe(Object o) { return name() + ":" + o; }
}
