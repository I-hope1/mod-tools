/**
 * Fixture A, base version -- ONLY the leaf's capture list changes.
 *
 *   build() { int a = 1, b = 2;
 *       Time.run(10, () -> { tag(b); Time.run(5, () -> use(a)); }); }
 *
 * The outer lambda's body IS the whole nested construction expression, so the moment b
 * appears anywhere inside it the outer captures b too. Making the outer reference b itself
 * (tag(b)) keeps its capture list constant across V1/V2, confining the descriptor change to
 * the leaf.
 *
 * Verified on all three real javac versions (this is javac-implementation dependent, so it is
 * measured, not assumed):
 *   jdk-1.8        V1 lambda$build$1(int, int) + lambda$null$0(int)
 *                  V2 lambda$build$1(int, int) + lambda$null$0(int, int)
 *   jdk-17.0.2     V1 lambda$build$1(int, int) + lambda$build$0(int)
 *                  V2 lambda$build$1(int, int) + lambda$build$0(int, int)
 *   openjdk-21.0.2 same as 17
 *
 * NOTE: keep these comments ASCII. javac reads sources as GBK on this machine by default and
 * fails on non-ASCII bytes unless -encoding UTF-8 is passed; the suite does pass it, but ASCII
 * here keeps the fixture compilable by hand as well.
 */
public class FixtureA1 {
	static void use(int a) {}
	static void tag(int b) {}

	void build() {
		int a = 1, b = 2;
		Time.run(10, () -> {
			tag(b);
			Time.run(5, () -> use(a));
		});
	}
}
