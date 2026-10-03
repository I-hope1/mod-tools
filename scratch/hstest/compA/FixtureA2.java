/**
 * Fixture A, variant -- the ONLY difference from FixtureA1 is that the leaf captures b as well.
 *
 *   V1: Time.run(10, () -> { tag(b); Time.run(5, () -> use(a)); });
 *   V2: Time.run(10, () -> { tag(b); Time.run(5, () -> use(a, b)); });   <-- this file
 *
 * Aligned with FixtureA1: the outer descriptor is (int, int) on both sides, and only the leaf
 * goes from (int) to (int, int). So "the leaf has no candidate" is the single variable, and if
 * the middle or outer loses its old name the only possible cause is that hasUnmatchedChild
 * blocked it because its child never settled.
 *
 * This is the case `settled` is meant to fix. It is a different class from fixture B (where the
 * whole chain's descriptors change, which is the designed degradation), so the two must not be
 * conflated.
 *
 * Keep comments ASCII: javac on this machine defaults to GBK and rejects non-ASCII without
 * -encoding UTF-8.
 */
public class FixtureA2 {
	static void use(int a, int b) {}
	static void tag(int b) {}

	void build() {
		int a = 1, b = 2;
		Time.run(10, () -> {
			tag(b);
			Time.run(5, () -> use(a, b));
		});
	}
}
