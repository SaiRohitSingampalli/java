/*
 * Lab:       The synthetic bridge method the compiler adds for a generic override
 * Chapters:  05 (generics)
 * Run:       javac -d /tmp/ch05 IntNode.java && javap -p -cp /tmp/ch05 IntNode
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   The Java Tutorials, Effects of Type Erasure and Bridge Methods
 * Output:    deterministic.
 */
class Node<T> {
    T data;
    void setData(T data) { this.data = data; }
}
class IntNode extends Node<Integer> {
    @Override void setData(Integer data) { super.setData(data); }
}
