package testCompB;

public class CompB {
    static void useA(int a) {}
    static void useB(int a) {}
    static void tagA(int b) {}
    static void tagB(int b) {}

    void build() {
        int a = 1, b = 2;
        // Chain A (3 levels)
        Time.run(10, () -> {
            tagA(b);
            Time.run(5, () -> {
                tagA(b);
                Time.run(1, () -> useA(a));
            });
        });

        // Chain B (3 levels)
        Time.run(20, () -> {
            tagB(b);
            Time.run(15, () -> {
                tagB(b);
                Time.run(2, () -> useB(a));
            });
        });
    }
}
