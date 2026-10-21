import arc.struct.Seq;
import modtools.ui.comp.completion.Completion;

/**
 * Regression tests for the completion helpers. Pure logic, no editor or Rhino needed.
 * Run with: gradlew runTest -PmainClass=CompletionTest
 */
public class CompletionTest {
	private static int passed, failed;

	public static void main(String[] args) {
		run("prefixStart cuts the word left of the caret", () -> {
			assertEq(0, Completion.prefixStart("", 0), "empty text");
			assertEq(0, Completion.prefixStart("Vars", 0), "caret at line start");
			assertEq(0, Completion.prefixStart("Vars", 4), "caret at the end of the only word");
			assertEq(5, Completion.prefixStart("Vars.map", 8), "caret inside member name");
			assertEq(3, Completion.prefixStart("$p.mindustry", 5), "$ is an identifier part");
			assertEq(0, Completion.prefixStart("abc", 99), "caret past the end is clamped");
			assertEq(2, Completion.prefixStart("a b", 3), "caret right after a word");
		});

		run("isMemberAccess only looks at the char right before the word", () -> {
			assertTrue(Completion.isMemberAccess("Vars.", 5), "trailing dot");
			assertTrue(Completion.isMemberAccess("Vars.ma", 5), "dot then prefix");
			assertTrue(Completion.isMemberAccess("a.b", 2), "single letter base");
			// A dot earlier in the line belongs to another expression.
			assertFalse(Completion.isMemberAccess("foo(bar", 4), "dot inside a call argument list");
			assertFalse(Completion.isMemberAccess("a + b", 4), "dot separated by whitespace");
			assertFalse(Completion.isMemberAccess("x", 0), "no dot at all");
			assertFalse(Completion.isMemberAccess("", 0), "empty text");
		});

		run("rank prefers exact case, then case-insensitive, then humps", () -> {
			assertEq(Completion.RANK_PREFIX_CASE, Completion.rank("getX", "get"), "same case prefix");
			assertEq(Completion.RANK_PREFIX_IGNORE_CASE, Completion.rank("GetX", "get"), "case-only difference");
			assertEq(Completion.RANK_HUMP, Completion.rank("getCursorPosition", "gcp"), "camel humps");
			assertEq(Completion.RANK_HUMP, Completion.rank("my_field", "mf"), "underscore boundary");
			assertEq(Completion.RANK_HUMP, Completion.rank("getX", "gx"), "uppercase hump");
			assertEq(Completion.RANK_PREFIX_CASE, Completion.rank("anything", ""), "empty prefix matches all");
			assertEq(Completion.NO_MATCH, Completion.rank("other", "get"), "unrelated name");
			assertEq(Completion.NO_MATCH, Completion.rank("getX", "gt"), "letters inside a word are not humps");
		});

		run("collect dedupes, drops the exact prefix and orders by rank", () -> {
			Seq<String> out = new Seq<>();
			Completion.collect(out, "get", Seq.with("getX", "GetX", "get", "other", "getX"));
			assertEq(Seq.with("getX", "GetX").toString(), out.toString(), "rank buckets, dedupe, exact prefix dropped");

			Completion.collect(out, "get", Seq.with("getB", "getA"));
			assertEq(Seq.with("getA", "getB").toString(), out.toString(), "alphabetical inside a bucket");

			Completion.collect(out, "", Seq.with("b", "a"));
			assertEq(Seq.with("a", "b").toString(), out.toString(), "empty prefix lists everything");

			Completion.collect(out, "zzz", Seq.with("a", "b"));
			assertTrue(out.isEmpty(), "no match leaves the list empty");
		});

		run("collect merges several sources", () -> {
			Seq<String> out = new Seq<>();
			Completion.collect(out, "ma", Seq.with("map"), Seq.with("math"), Seq.with("map"));
			assertEq(Seq.with("map", "math").toString(), out.toString(), "merged and deduped across sources");
		});

		System.out.println("============================================");
		System.out.printf("TEST RESULTS: %d Passed, %d Failed%n", passed, failed);
		System.out.println("============================================");

		if (failed > 0) System.exit(1);
	}

	private static void run(String name, Runnable body) {
		System.out.print(name + "... ");
		try {
			body.run();
			System.out.println("PASSED");
			passed++;
		} catch (Throwable t) {
			System.out.println("FAILED: " + t.getMessage());
			t.printStackTrace(System.out);
			failed++;
		}
	}

	private static void assertEq(Object expected, Object actual, String what) {
		if (!expected.equals(actual)) {
			throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean value, String what) {
		if (!value) throw new AssertionError(what + ": expected true");
	}

	private static void assertFalse(boolean value, String what) {
		if (value) throw new AssertionError(what + ": expected false");
	}
}
