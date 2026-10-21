import arc.struct.Seq;
import modtools.ui.comp.completion.Completion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Completion behaviour on realistic JS expressions: strings, comments, template literals,
 * optional chaining, chains continued on the next line, decimal literals, regex.
 * <p>
 * The caret is written as {@code ‸} inside the snippet so each case reads like the code
 * the user actually typed.
 * <p>
 * Run with: gradlew test --tests CompletionExpressionTest
 */
class CompletionExpressionTest {
	private static final char CARET = '‸';

	/**
	 * {snippet, expected prefix left of the caret, member access, in code, what}
	 */
	private static final Object[][] CASES = {
	 {"Vars.content.blocks().get(0).na‸me", "na", true, true, "chain through a call result and an index"},
	 {"Vars.content.blocks().‸", "", true, true, "dot right after a call"},
	 {"Vars.content.blocks()‸", "", false, true, "closing paren is not a member access"},
	 {"Log.info(\"a.b‸\")", "b", false, false, "dot inside a double-quoted string"},
	 {"Log.info('a.b‸')", "b", false, false, "dot inside a single-quoted string"},
	 {"Log.info(\"a\\\"b.c‸\")", "c", false, false, "escaped quote does not end the string"},
	 {"// Vars.ma‸\nVars.map", "ma", false, false, "dot inside a line comment"},
	 {"/* Vars.ma‸ */", "ma", false, false, "dot inside a block comment"},
	 {"/* a */ Vars.ma‸", "ma", true, true, "code resumes after a closed block comment"},
	 {"// a\nVars.ma‸", "ma", true, true, "code resumes on the next line"},
	 {"`x.${Vars.ma‸}`", "ma", true, true, "template interpolation is code again"},
	 {"`x.y‸`", "y", false, false, "plain template text is a string"},
	 {"`a${ b ? `x.${c.d‸}` : '' }`", "d", true, true, "template nested inside an interpolation"},
	 {"Vars?.ma‸", "ma", true, true, "optional chaining"},
	 {"arr[i].le‸n", "le", true, true, "subscript base"},
	 {"Vars .ma‸", "ma", true, true, "spaces around the dot"},
	 {"Vars. ma‸", "ma", true, true, "space after the dot"},
	 {"Vars\n\t.ma‸", "ma", true, true, "chain continued on the next line"},
	 {"x = 3.1‸4", "1", false, true, "decimal literal is not a member access"},
	 {"x = 1.‸", "", false, true, "trailing dot of a number"},
	 {"x = 1.5.toFix‸ed(2)", "toFix", true, true, "method call on a decimal literal"},
	 {"const re = /a.b‸/;", "b", false, false, "dot inside a regex literal"},
	 {"x = a / b.ma‸", "ma", true, true, "division, then a member access"},
	 {"x = \"a\" / 2; y.ma‸", "ma", true, true, "division after a string is not a regex"},
	 {"if (a.b) { c.d‸ }", "d", true, true, "member access inside a block"},
	 {"let f = x => x.he‸", "he", true, true, "arrow body"},
	 {"foo(bar‸", "bar", false, true, "bare argument"},
	 {"a ? b.c : d‸", "d", false, true, "else branch is a bare name"},
	 {"[1,2,3].ma‸p", "ma", true, true, "array literal base"},
	 {"Vars.content.blocks().each(b => b.hea‸lth > 0)", "hea", true, true, "arrow parameter inside a call"},
	 {"String.valueOf(1).le‸", "le", true, true, "java interop call chain"},
	 {"变量.名‸", "名", true, true, "unicode identifiers"},
	 {"Vars.\r\n\t.ma‸", "ma", true, true, "CRLF chain continuation"},
	};

	@Test
	@DisplayName("word boundary and member access on real snippets")
	void snippets() {
		for (Object[] c : CASES) {
			String snippet = (String) c[0];
			String expectPrefix = (String) c[1];
			boolean expectMember = (Boolean) c[2];
			boolean expectCode = (Boolean) c[3];
			String what = (String) c[4];

			int cursor = snippet.indexOf(CARET);
			assertTrue(cursor >= 0, "case without a caret: " + snippet);
			String text = snippet.replace(String.valueOf(CARET), "");

			Completion.Context ctx = Completion.contextAt(text, cursor);

			assertEquals(expectPrefix, ctx.prefix, what + " (prefix)");
			assertEquals(expectMember, ctx.member, what + " (member access)");
			assertEquals(expectCode, ctx.inCode, what + " (code context)");
			assertEquals(cursor - expectPrefix.length(), ctx.start, what + " (prefix start)");
			// 点号下标供高亮截止用，必须真的指向 '.'，不能是前缀前的空白
			assertEquals(expectMember, ctx.dot >= 0 && text.charAt(ctx.dot) == '.', what + " (dot index)");
		}
	}

	@Test
	@DisplayName("real member names sort into predictable buckets")
	void rankingOnRealNames() {
		Seq<String> ids = Seq.with(
		 "getX", "getY", "getClass", "getName", "getMod", "Globals",
		 "getWidth", "getHeight", "getHealth", "getCursorPosition", "grab", "gravity");

		Seq<String> out = new Seq<>();
		Completion.collect(out, "getH", ids);
		assertEquals(Seq.with("getHealth", "getHeight").toString(), out.toString(),
		 "exact-case prefix first, alphabetical inside the bucket");

		Completion.collect(out, "gc", ids);
		assertEquals(Seq.with("getClass", "getCursorPosition").toString(), out.toString(),
		 "hump abbreviations land in the last bucket, still alphabetical");

		Completion.collect(out, "g", ids);
		assertEquals(Seq.with("getClass", "getCursorPosition", "getHealth", "getHeight", "getMod",
		 "getName", "getWidth", "getX", "getY", "grab", "gravity", "Globals").toString(),
		 out.toString(), "single letter keeps every name, exact case before Globals");

		// 整段小写也能命中：走的是忽略大小写前缀，不是驼峰
		Completion.collect(out, "getcursor", ids);
		assertEquals(Seq.with("getCursorPosition").toString(), out.toString(), "lowercased camel name");

		Completion.collect(out, "Vars", ids);
		assertTrue(out.isEmpty(), "no candidate when nothing matches");
	}

	@Test
	@DisplayName("collect is deterministic regardless of source order")
	void determinism() {
		Seq<String> a = new Seq<>(), b = new Seq<>();
		Completion.collect(a, "g", Seq.with("getX", "getY", "Grab", "getMod"));
		Completion.collect(b, "g", Seq.with("getMod", "Grab", "getY", "getX"));
		assertEquals(a.toString(), b.toString(), "same set, different order, same result");

		Completion.collect(a, "get", Seq.with("getX", "GetX", "gEtX", "getX"));
		assertEquals(Seq.with("getX", "GetX", "gEtX").toString(), a.toString(),
		 "case-only differences are distinct candidates, exact case first");
	}

	/**
	 * Current, imperfect behaviour. Kept as an assertion so a change shows up as a failure
	 * instead of passing silently.
	 */
	@Test
	@DisplayName("known gaps stay visible")
	void knownGaps() {
		// 前缀必须连续命中或按词首缩写：跳过中间一个词再接着敲，匹配不上
		assertEquals(Completion.NO_MATCH, Completion.rank("getCursorPosition", "getpos"),
		 "skipping a word in the middle");
		// 词首之后的数字不是 word start，所以 gx2 匹配不到 getX2
		assertEquals(Completion.NO_MATCH, Completion.rank("getX2", "gx2"),
		 "digit after a hump");
	}
}
