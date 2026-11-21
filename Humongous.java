/*
 * Lab:       Humongous allocations in G1, and the collections they trigger
 * Chapters:  10 (memory and GC)
 * Run:       java -Xmx64m -XX:G1HeapRegionSize=1m -Xlog:gc Humongous.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   HotSpot Virtual Machine Garbage Collection Tuning Guide (G1: humongous objects)
 * Output:    a GC log with "G1 Humongous Allocation" collections; counts vary.
 */
public class Humongous { public static void main(String[] a) { long s=0; for (int i=0;i<200;i++){ byte[] b = new byte[600*1024]; s+=b.length; } System.out.println(s);} }
