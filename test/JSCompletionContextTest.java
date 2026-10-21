import arc.util.Reflect;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.mod.Mods;
import mindustry.mod.Scripts;
import modtools.ui.comp.input.highlight.JSSyntax;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rhino.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What {@code JSSyntax.cursorObj} resolves to for member access in different expression shapes.
 * <p>
 * Completion takes its base object from there, so a wrong base means a popup full of unrelated
 * members. The caret is written as {@code ‸} and is moved to just before the '.' the way
 * {@code Tester} does while the popup is open.
 * <p>
 * Run with: gradlew test --tests JSCompletionContextTest
 */
class JSCompletionContextTest {
	private static final char CARET = '‸';

	private static Context    cx;
	private static Scriptable scope;
	private static Scriptable left, right, inner;

	@BeforeAll
	static void setUp() {
		initMindustryEnv();
		buildScope();
	}

	@Test
	@DisplayName("statement start: let v = left.xxx")
	void statementStart() {
		assertBase("let v = left.xxx‸;", left);
	}

	@Test
	@DisplayName("single argument: fn(left.xxx)")
	void singleArgument() {
		assertBase("fn(left.xxx‸)", left);
	}

	@Test
	@DisplayName("second argument: fn(left.xxx, right.yy)")
	void secondArgument() {
		assertBase("fn(left.xxx, right.yy‸)", right);
	}

	@Test
	@DisplayName("first argument: fn(left.xxx, right.yy)")
	void firstArgument() {
		assertBase("fn(left.xxx‸, right.yy)", left);
	}

	@Test
	@DisplayName("dot with empty prefix: fn(left.xxx, right.)")
	void emptyPrefix() {
		assertBase("fn(left.xxx, right.‸)", right);
	}

	@Test
	@DisplayName("nested call: fn(g(left.xxx), right.yy)")
	void nestedCall() {
		assertBase("fn(g(left.xxx‸), right.yy)", left);
	}

	@Test
	@DisplayName("member of a call result: fn(left.xxx(inner.yy))")
	void memberOfCallResult() {
		assertBase("fn(left.xxx(inner.yy‸))", inner);
	}

	@Test
	@DisplayName("array literal argument: fn([left.xxx, right.yy])")
	void arrayLiteralArgument() {
		assertBase("fn([left.xxx‸, right.yy])", left);
	}

	@Test
	@DisplayName("ternary argument: fn(a ? left.xxx : right.yy)")
	void ternaryArgument() {
		assertBase("fn(a ? left.xxx‸ : right.yy)", left);
	}

	/**
	 * 局部变量没有运行时对象，{@code let v = left; v.xxx} 的基对象退化成作用域本身
	 * （补全会列出全局名）。要修得从声明处推断类型，是另一件事，这里只钉住现状。
	 */
	@Test
	@DisplayName("known gap: local variable base is not the declared object")
	void knownGapLocalVariable() {
		Scriptable base = baseOf("let v = left;\nv.xxx‸");
		assertNotEquals(left, base, "local var base resolved to left, gap is gone");
		assertNotNull(base, "local var base is not null either");
	}

	private static void assertBase(String marked, Scriptable expected) {
		assertEquals(expected, baseOf(marked), "base object");
	}

	/** 光标停在 '.' 之前跑一遍高亮，取补全会用的基对象 */
	private static Scriptable baseOf(String marked) {
		int cursor = marked.indexOf(CARET);
		assertTrue(cursor >= 0, "case without a caret");
		String text = marked.replace(String.valueOf(CARET), "");

		JSSyntaxTest.MockDrawable drawable = new JSSyntaxTest.MockDrawable();
		drawable.text = text;
		drawable.cursor = dotBefore(text, cursor);

		JSSyntax syntax = new JSSyntax(drawable, scope);
		syntax.highlightingDraw(text);
		return syntax.cursorObj;
	}

	/** 光标前那个词左边是否是 '.'，是就返回点的下标——和 Tester 补全时的光标位置一致 */
	private static int dotBefore(String text, int cursor) {
		int i = cursor;
		while (i > 0 && Character.isJavaIdentifierPart(text.charAt(i - 1))) i--;
		return i > 0 && text.charAt(i - 1) == '.' ? i - 1 : cursor;
	}

	private static void buildScope() {
		left = cx.newObject(scope);
		left.put("xxx", left, cx.newObject(scope));
		left.put("yy", left, cx.newObject(scope));

		right = cx.newObject(scope);
		right.put("xxx", right, cx.newObject(scope));
		right.put("yy", right, cx.newObject(scope));

		inner = cx.newObject(scope);
		inner.put("yy", inner, cx.newObject(scope));

		scope.put("left", scope, left);
		scope.put("right", scope, right);
		scope.put("inner", scope, inner);
		scope.put("fn", scope, new BaseFunction() {
			public Object call(Context c, Scriptable s, Scriptable t, Object[] a) { return Undefined.instance; }
		});
		scope.put("g", scope, new BaseFunction() {
			public Object call(Context c, Scriptable s, Scriptable t, Object[] a) { return Undefined.instance; }
		});
	}

	private static void initMindustryEnv() {
		Version.number = 159;
		cx = Context.enter();
		scope = new ImporterTopLevel(cx);
		try {
			Vars.mods = (Mods) ihope_lib.MyReflect.unsafe.allocateInstance(Mods.class);
			Scripts scripts = (Scripts) ihope_lib.MyReflect.unsafe.allocateInstance(Scripts.class);
			Reflect.set(Scripts.class, scripts, "context", cx);
			Reflect.set(Scripts.class, scripts, "scope", scope);
			Reflect.set(Mods.class, Vars.mods, "scripts", scripts);
		} catch (Throwable t) {
			throw new IllegalStateException("Mindustry env setup failed", t);
		}
	}
}
