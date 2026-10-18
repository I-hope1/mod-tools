package modtools.annotations.processors;

import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;
import modtools.annotations.JavacFixture;
import modtools.annotations.ProbeProcessor;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.ElementKind;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SwitchMethods} 把 {@code @Switch} 字段翻译成 {@code hasSwitch()} / {@code switchKey()}。
 * <p>
 * 这两个方法是<b>合成</b>出来的，javac 不会替我们检查它们的形状：写错了只会表现为
 * 运行时 {@code AbstractMethodError}，或者更糟——{@code isSwitchOn()} 静默地永远返回 true。
 */
class SwitchMethodsTest {
	private static final String SOURCE = """
	 package p;

	 import modtools.annotations.settings.Switch;

	 interface I {
	   default boolean hasSwitch() { return false; }
	   default String switchKey() { return "d"; }
	   default String[] switchKeys() { return new String[]{switchKey()}; }
	 }

	 enum S implements I {
	   @Switch aaa(int.class),
	   @Switch(dependency = "bbb$enabled") bbb(int.class),
	   @Switch(dependency = {"ccc$enabled", "aaa"}) ddd(int.class),
	   ccc(int.class);

	   S(Class<?> c) { }
	 }
	 """;

	@Test
	void collectsOnlyAnnotatedFields() throws Exception {
		try (JavacFixture fx = fixture()) {
			SwitchMethods methods = collectAll(fx);
			assertFalse(methods.isEmpty());
			assertEquals(3, methods.size(), "aaa/bbb/ddd 有 @Switch，ccc 没有");
		}
	}

	@Test
	void collectIsIdempotentForTheSameField() throws Exception {
		try (JavacFixture fx = fixture()) {
			SwitchMethods methods = new SwitchMethods();
			VarSymbol    aaa     = enumConstant(findClass(fx, "S"), "aaa");
			methods.collect(aaa);
			methods.collect(aaa);
			assertEquals(1, methods.size(), "同一个字段重复收集不该产生两条分支");
		}
	}

	@Test
	void clearDropsEverything() throws Exception {
		try (JavacFixture fx = fixture()) {
			SwitchMethods methods = collectAll(fx);
			methods.clear();
			assertTrue(methods.isEmpty(), "换 Settings 类时必须彻底清空，否则上一类的开关会漏过来");
		}
	}

	@Test
	void buildIsANoOpWhenNothingWasCollected() throws Exception {
		try (JavacFixture fx = fixture()) {
			ClassDecl s      = findClass(fx, "S");
			int       before = s.decl.defs.size();

			new SwitchMethods().build(s.decl, s.sym, findClass(fx, "I").sym, maker(fx), symtab(fx));

			assertEquals(before, s.decl.defs.size(), "没有开关就不该生成任何方法");
		}
	}

	@Test
	void emitsHasSwitchWithOneBranchPerSwitch() throws Exception {
		try (JavacFixture fx = fixture()) {
			ClassDecl s = findClass(fx, "S");
			build(fx, s);

			JCMethodDecl hasSwitch = method(s.decl, "hasSwitch");
			assertTrue((hasSwitch.mods.flags & Flags.PUBLIC) != 0);

			// 三个 if + 兜底 return false
			assertEquals(4, hasSwitch.body.stats.size());
			JCReturn  fallback = (JCReturn) hasSwitch.body.stats.last();
			JCLiteral literal  = (JCLiteral) fallback.expr;
			// TreeMaker.Literal(false) 存的是 int 0，靠 typetag 区分 boolean / int
			assertEquals(TypeTag.BOOLEAN, literal.typetag);
			assertEquals(Integer.valueOf(0), literal.value);
		}
	}

	@Test
	void emitsSwitchKeyBranchOnlyForDeclaredDependencies() throws Exception {
		try (JavacFixture fx = fixture()) {
			ClassDecl s = findClass(fx, "S");
			build(fx, s);

			JCMethodDecl switchKey = method(s.decl, "switchKey");
			// bbb + ddd 写了 dependency（aaa 没有）→ 两个 if + 兜底
			assertEquals(3, switchKey.body.stats.size());

			JCIf first = (JCIf) switchKey.body.stats.head;
			assertEquals("bbb$enabled", ((JCLiteral) ((JCReturn) first.thenpart).expr).value);

			// 多依赖时 switchKey() 取第一个当代表
			JCIf second = (JCIf) switchKey.body.stats.get(1);
			assertEquals("ccc$enabled", ((JCLiteral) ((JCReturn) second.thenpart).expr).value);

			// 兜底必须是 ISettings.super.switchKey()：返回 null 会让 isSwitchOn() 永远 NPE/失效
			JCReturn         fallback = (JCReturn) switchKey.body.stats.last();
			JCMethodInvocation call   = (JCMethodInvocation) fallback.expr;
			JCFieldAccess      outer  = (JCFieldAccess) call.meth;
			JCFieldAccess      inner  = (JCFieldAccess) outer.selected;
			assertEquals("switchKey", outer.name.toString());
			assertEquals("super", inner.name.toString());
			assertTrue(inner.selected.toString().endsWith("I"), "实际是: " + inner.selected);
		}
	}

	@Test
	void switchKeysReturnsEveryDependencyInOrder() throws Exception {
		try (JavacFixture fx = fixture()) {
			ClassDecl s = findClass(fx, "S");
			build(fx, s);

			JCMethodDecl switchKeys = method(s.decl, "switchKeys");
			// bbb（1 个依赖）+ ddd（2 个依赖）→ 两个 if + 兜底
			assertEquals(3, switchKeys.body.stats.size());

			JCNewArray bbb = (JCNewArray) ((JCReturn) ((JCIf) switchKeys.body.stats.head).thenpart).expr;
			assertEquals(1, bbb.elems.size());
			assertEquals("bbb$enabled", ((JCLiteral) bbb.elems.head).value);

			JCNewArray ddd = (JCNewArray) ((JCReturn) ((JCIf) switchKeys.body.stats.get(1)).thenpart).expr;
			assertEquals(2, ddd.elems.size());
			assertEquals("ccc$enabled", ((JCLiteral) ddd.elems.get(0)).value);
			assertEquals("aaa", ((JCLiteral) ddd.elems.get(1)).value);
			assertTrue(ddd.elemtype.toString().endsWith("String"), "实际是: " + ddd.elemtype);
		}
	}

	@Test
	void switchKeysFallsBackToSuperForFieldsWithoutDependencies() throws Exception {
		try (JavacFixture fx = fixture()) {
			ClassDecl s = findClass(fx, "S");
			build(fx, s);

			JCMethodDecl switchKeys = method(s.decl, "switchKeys");
			JCReturn      fallback = (JCReturn) switchKeys.body.stats.last();
			JCFieldAccess outer    = (JCFieldAccess) ((JCMethodInvocation) fallback.expr).meth;
			// aaa 没有 dependency → 不生成分支，落到 ISettings.super.switchKeys()（= 自身的 $enabled）
			assertEquals("switchKeys", outer.name.toString());
			assertEquals("super", ((JCFieldAccess) outer.selected).name.toString());
		}
	}

	@Test
	void synthesizedMethodsCarryARealMethodType() throws Exception {
		try (JavacFixture fx = fixture()) {
			ClassDecl s = findClass(fx, "S");
			build(fx, s);

			Symtab symtab = symtab(fx);
			assertMethodType(method(s.decl, "hasSwitch").sym, symtab.booleanType);
			assertMethodType(method(s.decl, "switchKey").sym, symtab.stringType);

			MethodSymbol switchKeys = method(s.decl, "switchKeys").sym;
			assertInstanceOf(MethodType.class, switchKeys.type);
			Type ret = ((MethodType) switchKeys.type).getReturnType();
			assertInstanceOf(ArrayType.class, ret, "switchKeys() 必须返回 String[]");
			assertEquals(symtab.stringType, ((ArrayType) ret).elemtype);
		}
	}

	@Test
	void methodsAreEnteredIntoTheClassScope() throws Exception {
		try (JavacFixture fx = fixture()) {
			ClassDecl s = findClass(fx, "S");
			build(fx, s);

			// 只挂进 defs、不进 members()，之后 Attr 解析 this.hasSwitch() 时会找不到符号
			assertNotNull(s.sym.members().findFirst(Names.instance(fx.context).fromString("hasSwitch")));
			assertNotNull(s.sym.members().findFirst(Names.instance(fx.context).fromString("switchKey")));
			assertNotNull(s.sym.members().findFirst(Names.instance(fx.context).fromString("switchKeys")));
		}
	}

	/* ---------- validate ---------- */

	@Test
	void validateAcceptsExistingDependencies() throws Exception {
		try (JavacFixture fx = fixture()) {
			ClassDecl s = findClass(fx, "S");
			// bbb$enabled → bbb；ccc$enabled → ccc；aaa 是普通常量名
			assertEquals(0, collectAll(fx).validate(s.decl, s.sym, log(fx), trees(fx)));
			assertTrue(fx.errors().isEmpty(), fx.errors().toString());
		}
	}

	@Test
	void validateRejectsUnknownDependency() throws Exception {
		assertSingleError("""
		 @Switch(dependency = "nope") aaa(int.class);
		 """, "nope");
	}

	@Test
	void validateRejectsUnknownDependencyWithEnabledSuffix() throws Exception {
		assertSingleError("""
		 @Switch(dependency = "nope$enabled") aaa(int.class);
		 """, "nope");
	}

	@Test
	void validateRejectsEmptyDependency() throws Exception {
		assertSingleError("""
		 @Switch(dependency = "") aaa(int.class);
		 """, "空字符串");
	}

	@Test
	void validateIsANoOpWhenNothingWasCollected() throws Exception {
		try (JavacFixture fx = fixture()) {
			ClassDecl s = findClass(fx, "S");
			assertEquals(0, new SwitchMethods().validate(s.decl, s.sym, log(fx), trees(fx)));
			assertTrue(fx.errors().isEmpty(), fx.errors().toString());
		}
	}

	/** 一个只含该枚举常量的 Settings，校验应当报出一条错误并带上关键字 */
	private static void assertSingleError(String constant, String keyword) throws Exception {
		String source = """
		 package p;

		 import modtools.annotations.settings.Switch;

		 interface I { default boolean hasSwitch() { return false; } }

		 enum S implements I {
		 %s
		   S(Class<?> c) { }
		 }
		 """.formatted(constant);

		try (JavacFixture fx = JavacFixture.of("p.S", source)) {
			fx.assertNoCompileErrors();
			new ProbeProcessor().initConst(fx.processingEnv);

			ClassDecl s = findClass(fx, "S");
			assertEquals(1, collectAll(fx).validate(s.decl, s.sym, log(fx), trees(fx)));

			List<String> errors = fx.errors();
			assertEquals(1, errors.size(), errors.toString());
			assertTrue(errors.get(0).contains(keyword), "错误信息里应当提到 " + keyword + "，实际是: " + errors.get(0));
		}
	}

	/* ---------- helpers ---------- */

	private static void assertMethodType(MethodSymbol ms, Type expected) {
		assertInstanceOf(MethodType.class, ms.type,
		 ms.name + " 的 type 必须是 MethodType，不能拿返回类型顶替");
		MethodType mt = (MethodType) ms.type;
		assertTrue(mt.getParameterTypes().isEmpty(), ms.name + " 不该有参数");
		assertEquals(expected, mt.getReturnType());
	}

	private static JavacFixture fixture() throws Exception {
		JavacFixture fx = JavacFixture.of("p.S", SOURCE);
		fx.assertNoCompileErrors();
		new ProbeProcessor().initConst(fx.processingEnv);
		return fx;
	}

	private static void build(JavacFixture fx, ClassDecl s) {
		collectAll(fx).build(s.decl, s.sym, findClass(fx, "I").sym, maker(fx), symtab(fx));
	}

	private static SwitchMethods collectAll(JavacFixture fx) {
		SwitchMethods methods = new SwitchMethods();
		for (JCVariableDecl def : enumConstants(findClass(fx, "S").decl)) {
			methods.collect((VarSymbol) def.sym);
		}
		return methods;
	}

	private static List<JCVariableDecl> enumConstants(JCClassDecl decl) {
		List<JCVariableDecl> out = new ArrayList<>();
		for (JCTree def : decl.defs) {
			if (def instanceof JCVariableDecl v && v.sym != null && v.sym.getKind() == ElementKind.ENUM_CONSTANT) {
				out.add(v);
			}
		}
		assertFalse(out.isEmpty(), "fixture 里没有枚举常量");
		return out;
	}

	private static VarSymbol enumConstant(ClassDecl s, String name) {
		return enumConstants(s.decl).stream()
		 .filter(v -> v.name.contentEquals(name))
		 .map(v -> (VarSymbol) v.sym)
		 .findFirst()
		 .orElseThrow(() -> new AssertionError("fixture 里没有常量 " + name));
	}

	private record ClassDecl(JCClassDecl decl, ClassSymbol sym) { }

	private static ClassDecl findClass(JavacFixture fx, String simpleName) {
		for (JCTree def : ((JCCompilationUnit) fx.unit).defs) {
			if (def instanceof JCClassDecl c && c.name.contentEquals(simpleName)) {
				assertNotNull(c.sym, "类 " + simpleName + " 没有被标注（需要 analyze）");
				return new ClassDecl(c, c.sym);
			}
		}
		throw new AssertionError("fixture 里没有类 " + simpleName);
	}

	private static JCMethodDecl method(JCClassDecl decl, String name) {
		for (JCTree def : decl.defs) {
			if (def instanceof JCMethodDecl m && m.name.contentEquals(name)) return m;
		}
		throw new AssertionError("没有生成方法 " + name);
	}

	private static TreeMaker maker(JavacFixture fx) {
		return TreeMaker.instance(fx.context);
	}

	private static Symtab symtab(JavacFixture fx) {
		return Symtab.instance(fx.context);
	}

	private static Log log(JavacFixture fx) {
		return Log.instance(fx.context);
	}

	private static JavacTrees trees(JavacFixture fx) {
		return JavacTrees.instance(fx.context);
	}
}
