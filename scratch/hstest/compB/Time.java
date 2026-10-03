package testCompB;

public class Time {
    public static void run(int t, Runnable r) {
        if (r != null) r.run();
    }
}
