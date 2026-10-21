import arc.util.Reflect;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.mod.Mods;
import mindustry.mod.Scripts;
import modtools.ui.comp.input.highlight.JSSyntax;
import rhino.*;

/**
 * What {@code JSSyntax.cursorObj} resolves to for member access in different expression shapes.
 * <p>
 * Completion takes its base object from there, so a wrong base means a popup full of unrelated
 * members. The caret is written as {@code ‸} and is moved to just before the '.' the way
 * {@code Tester} does while the popup is open.
 * <p>
 * Run with: gradlew runTest -PmainClass=JSCompletionContextTest
 */
public class JSCompletionContextTest {
	private static final char CARET = '‸';

	private static int passed, failed;

	private static Context    cx;
	private static Scriptable scope;
	private static Scriptable left, right, inner;

	public static void main(String[] args) {
		initMindustryEnv();
		buildScope();

		run("statement start: let v = left.xxx", "let v = left.xxx‸;", left);
		run("single argument: fn(left.xxx)", "fn(left.xxx‸)", left);
		run("second argument: fn(left.xxx, right.yy)", "fn(left.xxx, right.yy‸)", right);
		run("first argument: fn(left.xxx‸, right.yy)", "fn(left.xxx‸, right.yy)", left);
		run("dot with empty prefix: fn(left.xxx, right.‸)", "fn(left.xxx, right.‸)", right);
		run("nested call: fn(g(left.xxx‸), right.yy)", "fn(g(left.xxx‸), right.yy)", left);
		run("member of a call result: fn(left.xxx(inner.yy‸))", "fn(left.xxx(inner.yy‸))", inner);
		run("array literal argument: fn([left.xxx‸, right.yy])", "fn([left.xxx‸, right.yy])", left);
		run("ternary argument: fn(a ? left.xxx‸ : right.yy)", "fn(a ? left.xxx‸ : right.yy)", left);
		knownGaps();

		System.out.println("============================================");
		System.out.printf("TEST RESULTS: %d Passed, %d Failed%n", passed, failed);
		System.out.println("============================================");

		if (failed > 0) System.exit(1);
	}

	/**
	 * 局部变量没有运行时对象，{@code let v = left; v.xxx} 的基对象退化成作用域本身
	 * （补全会列出全局名）。要修得从声明处推断类型，是另一件事，这里只钉住现状。
	 */
	private static void knownGaps() {
		System.out.print("known gap: local variable base is not the declared object... ");
		try {
			Scriptable base = baseOf("let v = left;\nv.xxx‸");
			if (base == left) throw new AssertionError("local var base resolved to left, gap is gone");
			assertTrue(base != null, "local var base is not null either");
			System.out.println("PASSED (base = " + name(base) + ")");
			passed++;
		} catch (Throwable t) {
			System.out.println("FAILED: " + t.getMessage());
			failed++;
		}
	}

	private static void run(String name, String marked, Scriptable expected) {
		System.out.print(name + "... ");
		try {
			Scriptable actual = baseOf(marked);
			if (actual != expected) {
				throw new AssertionError("base object: expected " + name(expected) + " but was " + name(actual));
			}
			System.out.println("PASSED");
			passed++;
		} catch (Throwable t) {
			System.out.println("FAILED: " + t.getMessage());
			failed++;
		}
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

	private static String name(Scriptable s) {
		if (s == null) return "null";
		for (String n : new String[]{"left", "right", "inner"}) {
			Object o = scope.get(n, scope);
			if (o == s) return n;
		}
		return s.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(s));
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
		ImporterTopLevel top = new ImporterTopLevel(cx);
		scope = top;
		try {
			Vars.mods = (Mods) ihope_lib.MyReflect.unsafe.allocateInstance(Mods.class);
			Scripts scripts = (Scripts) ihope_lib.MyReflect.unsafe.allocateInstance(Scripts.class);
			Reflect.set(Scripts.class, scripts, "context", cx);
			Reflect.set(Scripts.class, scripts, "scope", scope);
			Reflect.set(Mods.class, Vars.mods, "scripts", scripts);
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	private static void assertTrue(boolean value, String what) {
		if (!value) throw new AssertionError(what);
	}
}
