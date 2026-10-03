package testCompB;

public class CompB {
    static void useA(int a) {}
    static void useB(int a, int b) {}
    static void tagA(int b) {}
    static void tagB(int b) {}

    void build() {
        int a = 1, b = 2;
        // Chain A deleted
        // Chain B (3 levels, Leaf modified to capture (a, b))
        Time.run(20, () -> {
            tagB(b);
            Time.run(15, () -> {
                tagB(b);
                Time.run(2, () -> useB(a, b));
            });
        });
    }
}
