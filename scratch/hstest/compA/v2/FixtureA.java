/** Fixture A variant: leaf additionally captures b. Same class name as v1 so align accepts it. */
class FixtureA {
    static void use(int a, int b) {}
    static void tag(int b) {}
    void build() { int a = 1, b = 2;
        Time.run(10, () -> { tag(b); Time.run(5, () -> use(a, b)); }); }
}
