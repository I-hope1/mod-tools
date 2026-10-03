/** Fixture A base: ONLY the leaf's capture list changes (package-private so both versions share the class name). */
class FixtureA {
    static void use(int a) {}
    static void tag(int b) {}
    void build() { int a = 1, b = 2;
        Time.run(10, () -> { tag(b); Time.run(5, () -> use(a)); }); }
}
