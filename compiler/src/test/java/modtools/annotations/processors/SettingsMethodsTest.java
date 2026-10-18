package modtools.annotations.processors;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type.MethodType;
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
 * {@link SettingsMethods} 负责往 Settings 类里塞 {@code set(Object)}、静态写回块和 fire 调用。
 * <p>
 * 关键约束是「一个类里只能有一个 {@code set(Object)}」：以前 {@code @FlushField} 和
 * {@code fireEvent} 各自 append 一个新方法，两者同时出现时会生成两个同签名方法，
 * 一直到写 class 文件才报错。
 */
class SettingsMethodsTest {
	private static final String SOURCE = """
	 package p;

	 import modtools.annotations.settings.FlushField;
	 import java.util.function.Function;

	 interface I {
	   default void set(Object o) { }
	 }

	 class E {
	   static void fire(Object o) { }
	 }

	 enum S implements I {
	   @FlushField aaa(float.class, it -> it),
	   @FlushField bbb(int.class, it -> it),
	   ccc(int.class, it -> it);

	   S(Class<?> c, Function<Object, Object> f) { }
	 }
	 """;

	@Test
	void ignoresSymbolsWithoutTheAnnotation() throws Exception {
		try (JavacFixture fx = fixture()) {
			Target       t       = target(fx);
			SettingsMethods methods = newMethods(fx, t);
			int          before  = t.decl.defs.size();

			methods.flushField(classType(fx, t), access(fx), constant(t, "ccc"));

			assertFalse(methods.hasFlushAssignments(), "ccc 没有 @FlushField");
			assertEquals(before, t.decl.defs.size(), "不该凭空造出 set(Object)");
		}
	}

	@Test
	void flushFieldAddsOneAssignmentAndOneSetMethod() throws Exception {
		try (JavacFixture fx = fixture()) {
			Target       t       = target(fx);
			SettingsMethods methods = newMethods(fx, t);

			methods.flushField(classType(fx, t), access(fx), constant(t, "aaa"));

			assertTrue(methods.hasFlushAssignments());
			assertEquals(1, methods.flushAssignments().size());

			List<JCMethodDecl> sets = setMethods(t.decl);
			assertEquals(1, sets.size());
			// ISettings.super.set(val); + if (this == aaa) 写回
			assertEquals(2, sets.get(0).body.stats.size());
		}
	}

	@Test
	void twoFlushFieldsShareASingleSetMethod() throws Exception {
		try (JavacFixture fx = fixture()) {
			Target       t       = target(fx);
			SettingsMethods methods = newMethods(fx, t);

			methods.flushField(classType(fx, t), access(fx), constant(t, "aaa"));
			methods.flushField(classType(fx, t), access(fx), constant(t, "bbb"));

			assertEquals(2, methods.flushAssignments().size());

			List<JCMethodDecl> sets = setMethods(t.decl);
			assertEquals(1, sets.size(), "第二个字段必须复用第一个建出来的 set(Object)");
			assertEquals(3, sets.get(0).body.stats.size());
		}
	}

	@Test
	void fireEventCreatesSetMethodWithTheFireCall() throws Exception {
		try (JavacFixture fx = fixture()) {
			Target       t       = target(fx);
			SettingsMethods methods = newMethods(fx, t);

			methods.fireEvent();

			List<JCMethodDecl> sets = setMethods(t.decl);
			assertEquals(1, sets.size());
			assertEquals(2, sets.get(0).body.stats.size());

			JCExpressionStatement last = (JCExpressionStatement) sets.get(0).body.stats.last();
			JCFieldAccess         call = (JCFieldAccess) ((JCMethodInvocation) last.expr).meth;
			assertEquals("fire", call.name.toString());
		}
	}

	@Test
	void fireEventReusesTheSetMethodCreatedByFlushField() throws Exception {
		try (JavacFixture fx = fixture()) {
			Target       t       = target(fx);
			SettingsMethods methods = newMethods(fx, t);

			methods.flushField(classType(fx, t), access(fx), constant(t, "aaa"));
			methods.fireEvent();

			List<JCMethodDecl> sets = setMethods(t.decl);
			assertEquals(1, sets.size(),
			 "同时用 @FlushField 和 fireEvent 时只能有一个 set(Object)，否则 class 文件非法");
			// super.set + 写回分支 + fire
			assertEquals(3, sets.get(0).body.stats.size());
		}
	}

	@Test
	void setMethodCarriesARealMethodTypeAndIsEnteredIntoMembers() throws Exception {
		try (JavacFixture fx = fixture()) {
			Target       t       = target(fx);
			SettingsMethods methods = newMethods(fx, t);

			methods.fireEvent();

			JCMethodDecl set = setMethods(t.decl).get(0);
			assertInstanceOf(MethodType.class, set.sym.type);
			MethodType mt = (MethodType) set.sym.type;
			assertEquals(1, mt.getParameterTypes().size());
			assertEquals(Symtab.instance(fx.context).objectType, mt.getParameterTypes().get(0));
			assertNotNull(t.sym.members().findFirst(Names.instance(fx.context).fromString("set")),
			 "不进 members() 的话，后续解析 this.set(x) 会找不到符号");
		}
	}

	/* ---------- helpers ---------- */

	private static SettingsMethods newMethods(JavacFixture fx, Target t) {
		return new SettingsMethods(t.decl, t.sym, t.iSym, t.eSym,
		 TreeMaker.instance(fx.context), Symtab.instance(fx.context));
	}

	private static List<JCMethodDecl> setMethods(JCClassDecl decl) {
		List<JCMethodDecl> out = new ArrayList<>();
		for (JCTree def : decl.defs) {
			if (def instanceof JCMethodDecl m && m.name.contentEquals("set")) out.add(m);
		}
		return out;
	}

	/** 从 fixture 里借一个现成的 {@code float.class} */
	private static JCFieldAccess classType(JavacFixture fx, Target t) {
		for (JCTree def : t.decl.defs) {
			if (def instanceof JCVariableDecl v && v.name.contentEquals("aaa") && v.init instanceof JCNewClass c) {
				return (JCFieldAccess) c.args.get(0);
			}
		}
		throw new AssertionError("fixture 里没有 aaa(float.class, ...)");
	}

	/** 写回目标：随便造个 {@code Vars.minZoom} 形状的字段访问，不需要被标注 */
	private static JCFieldAccess access(JavacFixture fx) {
		TreeMaker maker = TreeMaker.instance(fx.context);
		Names     names = Names.instance(fx.context);
		return (JCFieldAccess) maker.Select(maker.Ident(names.fromString("Vars")),
		 names.fromString("minZoom"));
	}

	private static VarSymbol constant(Target t, String name) {
		for (JCTree def : t.decl.defs) {
			if (def instanceof JCVariableDecl v && v.name.contentEquals(name)
			    && v.sym != null && v.sym.getKind() == ElementKind.ENUM_CONSTANT) {
				return (VarSymbol) v.sym;
			}
		}
		throw new AssertionError("fixture 里没有常量 " + name);
	}

	private record Target(JCClassDecl decl, ClassSymbol sym, ClassSymbol iSym, ClassSymbol eSym) { }

	private static Target target(JavacFixture fx) {
		ClassSymbol s = null, i = null, e = null;
		JCClassDecl decl = null;
		for (JCTree def : ((JCCompilationUnit) fx.unit).defs) {
			if (!(def instanceof JCClassDecl c)) continue;
			if (c.name.contentEquals("S")) {
				decl = c;
				s = c.sym;
			} else if (c.name.contentEquals("I")) {
				i = c.sym;
			} else if (c.name.contentEquals("E")) {
				e = c.sym;
			}
		}
		assertNotNull(decl, "fixture 里没有类 S");
		assertNotNull(i, "fixture 里没有接口 I");
		assertNotNull(e, "fixture 里没有类 E");
		return new Target(decl, s, i, e);
	}

	private static JavacFixture fixture() throws Exception {
		JavacFixture fx = JavacFixture.of("p.S", SOURCE);
		fx.assertNoCompileErrors();
		new ProbeProcessor().initConst(fx.processingEnv);
		return fx;
	}
}
