public class Humongous { public static void main(String[] a) { long s=0; for (int i=0;i<200;i++){ byte[] b = new byte[600*1024]; s+=b.length; } System.out.println(s);} }
