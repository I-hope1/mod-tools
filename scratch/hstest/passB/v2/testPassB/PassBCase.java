package testPassB;

public class PassBCase {
    static void act1() {}
    static void act2() {}
    void build() {
        Runnable r = () -> {
            Runnable r2 = () -> act2();
        };
    }
}
