/**
 * 计算 1..n 之间所有偶数的和，并打印。
 * 预期：sumEven(10) = 2+4+6+8+10 = 30
 */
public class EvenSum {

    static int sumEven(int n) {
        int sum = 0;
        for (int i = 1; i < n; i++) {
            if (i % 2 == 0) {
                sum += i;
            }
        }
        return sum;
    }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        System.out.println("even sum of 1.." + n + " = " + sumEven(n));
    }
}
