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

import java.util.HashSet;
import java.util.Set;

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

	/** 局部变量没有运行时对象，但右值是能解析出来的，声明时记下来即可 */
	@Test
	@DisplayName("local variable takes its base object from the initializer")
	void localVariableBase() {
		assertBase("let v = left;\nv.xxx‸", left);
	}

	@Test
	@DisplayName("const takes its base object from the initializer")
	void constBase() {
		assertBase("const v = right;\nv.xxx‸", right);
	}

	/** 一个声明里多个变量，各绑各的右值 */
	@Test
	@DisplayName("multiple declarators bind their own initializers")
	void multipleDeclarators() {
		assertBase("let v = left, w = right;\nw.xxx‸", right);
		assertBase("let v = left, w = right;\nv.xxx‸", left);
	}

	/** 绑的是右值最终解析出来的对象，不是表达式开头的那个 */
	@Test
	@DisplayName("initializer binds the final object of the expression")
	void initializerBindsFinalObject() {
		assertBase("let v = left.xxx;\nv.yy‸", (Scriptable) left.get("xxx", left));
	}

	/**
	 * 数组字面量里 currentObject 只是最后一个元素，不是数组本身；逗号也不是声明项分隔符。
	 * 这种情况宁可不绑（补全退回作用域），也不要绑成 right。
	 */
	@Test
	@DisplayName("array initializer does not bind one of its elements")
	void arrayInitializerDoesNotBind() {
		Scriptable base = baseOf("let v = [left, right];\nv.xxx‸");
		assertNotEquals(right, base, "bound to the last element of the array");
		assertNotEquals(left, base, "bound to the first element of the array");
	}

	/** 箭头函数参数的类型只能从被调方法的泛型实参推出来：dialog(Cons<Thing>) → Thing */
	@Test
	@DisplayName("arrow parameter takes its type from the callee's generic argument")
	void arrowParameter() {
		Set<String> ids = idsOf(baseOf("$.dialog(d => {\n\td.‸\n})"));
		assertTrue(ids.contains("heal"), "expected members of Thing, got " + ids);
	}

	/** 参数位置要对上：第一个实参是 int，回调在第二个位置 */
	@Test
	@DisplayName("arrow parameter in a later argument position")
	void arrowParameterSecondArgument() {
		Set<String> ids = idsOf(baseOf("$.both(1, d => {\n\td.‸\n})"));
		assertTrue(ids.contains("heal"), "expected members of Thing, got " + ids);
	}

	/** 被调对象不是 Java 方法时推不出类型，宁可补不出成员，也不要补出别人的成员 */
	@Test
	@DisplayName("arrow parameter without an inferable type stays unresolved")
	void arrowParameterWithoutType() {
		Set<String> ids = idsOf(baseOf("fn(d => {\n\td.‸\n})"));
		assertFalse(ids.contains("heal"), "resolved a type out of a JS function, got " + ids);
	}

	private static void assertBase(String marked, Scriptable expected) {
		assertEquals(expected, baseOf(marked), "base object");
	}

	/** 补全实际看到的候选名：基对象是 Scriptable 时走 getPropertyIds */
	private static Set<String> idsOf(Scriptable base) {
		if (base == null) return Set.of();
		Object[] ids = base instanceof ScriptableObject ? ScriptableObject.getPropertyIds(base) : base.getIds();
		Set<String> names = new HashSet<>();
		for (Object id : ids) names.add(String.valueOf(id));
		return names;
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
		// auto_complement 依赖 js_prop，真实补全时这个开关是开的，属性链才会解析
		syntax.js_prop = true;
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
		// $.dialog(d => {...}) 的形状：Java 方法 + 泛型回调参数
		scope.put("$", scope, Context.javaToJS(new Api(), scope));
	}

	/** 回调参数的类型只出现在泛型实参里，运行时对象根本不存在 */
	public static class Thing {
		public int  health = 1;
		public void heal() {}
	}

	public static class Api {
		public void dialog(arc.func.Cons<Thing> cons) {}
		public void both(int n, arc.func.Cons<Thing> cons) {}
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
