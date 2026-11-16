import java.util.function.Function;

public class LambdaBytecode {
    public static void main(String[] args) {
        int base = 10;
        Function<Integer, Integer> add = x -> x + base;
        System.out.println(add.apply(5));
    }
}
