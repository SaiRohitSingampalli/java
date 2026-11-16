import java.util.*;
public class Churn {
    public static void main(String[] a) throws Exception {
        List<byte[]> retained = new ArrayList<>();
        long sink = 0;
        for (int i = 0; i < 400_000; i++) {
            byte[] garbage = new byte[2048];            // short-lived
            sink += garbage.length;
            if (i % 50 == 0) retained.add(new byte[1024]);  // slowly growing live set
            if (retained.size() > 4000) retained.subList(0, 2000).clear();
        }
        System.out.println("done " + sink + " " + retained.size());
    }
}
