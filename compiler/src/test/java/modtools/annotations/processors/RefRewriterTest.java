package modtools.annotations.processors;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.TreeScanner;
import modtools.annotations.JavacFixture;
import modtools.annotations.ProbeProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RefRewriter#refTarget} 决定一个以 {@code R_} 开头的标识符算不算 settings 引用。
 * <p>
 * 判错的代价很高：原来直接 {@code (ClassSymbol) i.sym}，用户只要有个叫 {@code R_x} 的变量，
 * 整个编译就会 ClassCastException。
 */
class RefRewriterTest {

	@Test
	void resolvesTheClassNamedByRefOf() throws Exception {
		String source = """
		 import modtools.annotations.settings.RefOf;
		 class Foo { }
		 @RefOf(Foo.class) class R_bar { static int x; }
		 class P { static int a = R_bar.x; }
		 """;
		try (JavacFixture fx = JavacFixture.of("P", source)) {
			fx.assertNoCompileErrors();
			new ProbeProcessor().initConst(fx.processingEnv);

			ClassSymbol target = RefRewriter.refTarget(findIdent(fx, "R_bar"));
			assertNotNull(target, "@RefOf 指向的类应该被解析出来");
			assertEquals("Foo", target.getSimpleName().toString());
		}
	}

	@Test
	void ignoresIdentifierThatIsNotAClass() throws Exception {
		// 变量名叫 R_x：名字前缀对上了，但符号不是类
		try (JavacFixture fx = JavacFixture.of("P", "class P { static int R_x = 1; static int a = R_x; }")) {
			fx.assertNoCompileErrors();
			new ProbeProcessor().initConst(fx.processingEnv);

			JCIdent ident = findIdent(fx, "R_x");
			assertInstanceOf(VarSymbol.class, ident.sym);

			assertNull(RefRewriter.refTarget(ident), "变量名以 R_ 开头不该被当成 settings 引用");
		}
	}

	@Test
	void ignoresClassWithoutRefOf() throws Exception {
		try (JavacFixture fx = JavacFixture.of("P",
		 "class P { static class R_y { static int x; } static int a = R_y.x; }")) {
			fx.assertNoCompileErrors();
			new ProbeProcessor().initConst(fx.processingEnv);

			assertNull(RefRewriter.refTarget(findIdent(fx, "R_y")));
		}
	}

	private static JCIdent findIdent(JavacFixture fx, String name) {
		JCIdent[] found = {null};
		((JCCompilationUnit) fx.unit).accept(new TreeScanner() {
			@Override
			public void visitIdent(JCIdent tree) {
				if (found[0] == null && tree.name.contentEquals(name)) found[0] = tree;
			}
		});
		assertNotNull(found[0], "fixture 里没有找到标识符 " + name);
		return found[0];
	}
}
