public class ExprDemo {
    sealed interface Expr permits Num, Add, Mul, Neg {}
    record Num(int value)            implements Expr {}
    record Add(Expr left, Expr right) implements Expr {}
    record Mul(Expr left, Expr right) implements Expr {}
    record Neg(Expr operand)          implements Expr {}

    static int eval(Expr e) {
        return switch (e) {                                   // exhaustive over the sealed hierarchy: no default
            case Num n             -> n.value();
            case Add(var l, var r) -> eval(l) + eval(r);      // record pattern: deconstruct in place
            case Mul(var l, var r) -> eval(l) * eval(r);
            case Neg(var x)        -> -eval(x);
        };
    }

    static Expr simplify(Expr e) {
        return switch (e) {
            case Mul(Num(var n), var x) when n == 1 -> simplify(x);   // NESTED pattern + guard
            case Mul(var x, Num(var n)) when n == 1 -> simplify(x);
            case Mul(Num(var n), var x) when n == 0 -> new Num(0);
            case Add(Num(var n), var x) when n == 0 -> simplify(x);
            case Neg(Neg(var x))                    -> simplify(x);   // double negation
            case Add(var l, var r)                  -> new Add(simplify(l), simplify(r));
            case Mul(var l, var r)                  -> new Mul(simplify(l), simplify(r));
            case Neg(var x)                         -> new Neg(simplify(x));
            case Num n                              -> n;
        };
    }

    public static void main(String[] args) {
        Expr e = new Add(new Num(2), new Mul(new Num(3), new Num(4)));
        System.out.println(e + " = " + eval(e));
        Expr messy = new Mul(new Num(1), new Neg(new Neg(new Add(new Num(0), new Num(7)))));
        System.out.println("simplify(" + messy + ") = " + simplify(messy));
    }
}
