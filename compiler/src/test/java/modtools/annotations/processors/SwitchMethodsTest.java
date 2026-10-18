package modtools.annotations.processors;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
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
	 }

	 enum S implements I {
	   @Switch aaa(int.class),
	   @Switch(dependency = "bbb$enabled") bbb(int.class),
	   ccc(int.class);

	   S(Class<?> c) { }
	 }
	 """;

	@Test
	void collectsOnlyAnnotatedFields() throws Exception {
		try (JavacFixture fx = fixture()) {
			SwitchMethods methods = collectAll(fx);
			assertFalse(methods.isEmpty());
			assertEquals(2, methods.size(), "aaa/bbb 有 @Switch，ccc 没有");
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

			// 两个 if + 兜底 return false
			assertEquals(3, hasSwitch.body.stats.size());
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
			// 只有 bbb 写了 dependency → 一个 if + 兜底
			assertEquals(2, switchKey.body.stats.size());

			JCIf first = (JCIf) switchKey.body.stats.head;
			assertEquals("bbb$enabled", ((JCLiteral) ((JCReturn) first.thenpart).expr).value);

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
	void synthesizedMethodsCarryARealMethodType() throws Exception {
		try (JavacFixture fx = fixture()) {
			ClassDecl s = findClass(fx, "S");
			build(fx, s);

			Symtab symtab = symtab(fx);
			assertMethodType(method(s.decl, "hasSwitch").sym, symtab.booleanType);
			assertMethodType(method(s.decl, "switchKey").sym, symtab.stringType);
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
}
