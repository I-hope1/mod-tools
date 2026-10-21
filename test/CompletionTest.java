import arc.struct.Seq;
import modtools.ui.comp.completion.Completion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the completion helpers. Pure logic, no editor or Rhino needed.
 * <p>
 * Run with: gradlew test --tests CompletionTest
 */
class CompletionTest {
	@Test
	@DisplayName("prefixStart cuts the word left of the caret")
	void prefixStartCutsTheWord() {
		assertEquals(0, Completion.prefixStart("", 0), "empty text");
		assertEquals(0, Completion.prefixStart("Vars", 0), "caret at line start");
		assertEquals(0, Completion.prefixStart("Vars", 4), "caret at the end of the only word");
		assertEquals(5, Completion.prefixStart("Vars.map", 8), "caret inside member name");
		assertEquals(3, Completion.prefixStart("$p.mindustry", 5), "$ is an identifier part");
		assertEquals(0, Completion.prefixStart("abc", 99), "caret past the end is clamped");
		assertEquals(2, Completion.prefixStart("a b", 3), "caret right after a word");
	}

	@Test
	@DisplayName("isMemberAccess only looks at the char right before the word")
	void isMemberAccessLooksAtOneChar() {
		assertTrue(Completion.isMemberAccess("Vars.", 5), "trailing dot");
		assertTrue(Completion.isMemberAccess("Vars.ma", 5), "dot then prefix");
		assertTrue(Completion.isMemberAccess("a.b", 2), "single letter base");
		// A dot earlier in the line belongs to another expression.
		assertFalse(Completion.isMemberAccess("foo(bar", 4), "dot inside a call argument list");
		assertFalse(Completion.isMemberAccess("a + b", 4), "dot separated by whitespace");
		assertFalse(Completion.isMemberAccess("x", 0), "no dot at all");
		assertFalse(Completion.isMemberAccess("", 0), "empty text");
	}

	@Test
	@DisplayName("rank prefers exact case, then case-insensitive, then humps")
	void rankOrdersBuckets() {
		assertEquals(Completion.RANK_PREFIX_CASE, Completion.rank("getX", "get"), "same case prefix");
		assertEquals(Completion.RANK_PREFIX_IGNORE_CASE, Completion.rank("GetX", "get"), "case-only difference");
		assertEquals(Completion.RANK_HUMP, Completion.rank("getCursorPosition", "gcp"), "camel humps");
		assertEquals(Completion.RANK_HUMP, Completion.rank("my_field", "mf"), "underscore boundary");
		assertEquals(Completion.RANK_HUMP, Completion.rank("getX", "gx"), "uppercase hump");
		assertEquals(Completion.RANK_PREFIX_CASE, Completion.rank("anything", ""), "empty prefix matches all");
		assertEquals(Completion.NO_MATCH, Completion.rank("other", "get"), "unrelated name");
		assertEquals(Completion.NO_MATCH, Completion.rank("getX", "gt"), "letters inside a word are not humps");
	}

	@Test
	@DisplayName("collect dedupes, drops the exact prefix and orders by rank")
	void collectDedupesAndOrders() {
		Seq<String> out = new Seq<>();
		Completion.collect(out, "get", Seq.with("getX", "GetX", "get", "other", "getX"));
		assertEquals(Seq.with("getX", "GetX").toString(), out.toString(),
		 "rank buckets, dedupe, exact prefix dropped");

		Completion.collect(out, "get", Seq.with("getB", "getA"));
		assertEquals(Seq.with("getA", "getB").toString(), out.toString(), "alphabetical inside a bucket");

		Completion.collect(out, "", Seq.with("b", "a"));
		assertEquals(Seq.with("a", "b").toString(), out.toString(), "empty prefix lists everything");

		Completion.collect(out, "zzz", Seq.with("a", "b"));
		assertTrue(out.isEmpty(), "no match leaves the list empty");
	}

	@Test
	@DisplayName("collect merges several sources")
	void collectMergesSources() {
		Seq<String> out = new Seq<>();
		Completion.collect(out, "ma", Seq.with("map"), Seq.with("math"), Seq.with("map"));
		assertEquals(Seq.with("map", "math").toString(), out.toString(), "merged and deduped across sources");
	}
}
