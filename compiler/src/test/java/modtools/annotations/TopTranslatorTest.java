package modtools.annotations;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import modtools.annotations.unsafe.TopTranslator;
import modtools.annotations.unsafe.TopTranslator.ToTranslate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code transTree} 是每个 AST 节点都会经过的热路径。
 * 改成索引循环后，必须保持原有的“按顺序尝试、第一个非 null 结果生效”语义。
 */
class TopTranslatorTest {

	@Test
	@DisplayName("按注册顺序尝试，第一个非 null 替换生效")
	void firstNonNullReplacementWins() throws Exception {
		try (JavacFixture f = JavacFixture.of("p.T", """
		 package p;
		 class T { static int x; static void m() { int y = x; } }
		 """)) {
			f.assertNoCompileErrors();
			new ProbeProcessor().initConst(f.processingEnv);

			TopTranslator translator = TopTranslator.instance(f.context);
			TreeMaker    make       = TreeMaker.instance(f.context);

			// 第一个匹配但放弃（返回 null）——循环必须继续
			translator.addToDo(new ToTranslate(JCIdent.class, (JCIdent ident) -> null));
			// 第二个匹配并替换
			translator.addToDo(new ToTranslate(JCIdent.class,
			 (JCIdent ident) -> ident.name.contentEquals("x") ? make.Literal(4242) : null));

			translator.scanToplevel((JCCompilationUnit) f.unit);

			JCVariableDecl local = findLocal(f.unit, "y");
			assertInstanceOf(JCLiteral.class, local.init, "返回 null 的 todo 不应吞掉后续替换");
			assertEquals(4242, ((JCLiteral) local.init).value);
		}
	}

	@Test
	@DisplayName("不匹配的 predicate 直接跳过")
	void predicateMismatchIsSkipped() throws Exception {
		try (JavacFixture f = JavacFixture.of("p.T", """
		 package p;
		 class T { static void m() { int y = 1; } }
		 """)) {
			f.assertNoCompileErrors();
			new ProbeProcessor().initConst(f.processingEnv);

			TopTranslator translator = TopTranslator.instance(f.context);
			translator.addToDo(new ToTranslate(JCIdent.class,
			 (JCIdent ident) -> TreeMaker.instance(f.context).Literal(4242)));

			translator.scanToplevel((JCCompilationUnit) f.unit);

			JCVariableDecl local = findLocal(f.unit, "y");
			assertInstanceOf(JCLiteral.class, local.init);
			assertEquals(1, ((JCLiteral) local.init).value);
		}
	}

	private static JCVariableDecl findLocal(CompilationUnitTree unit, String name) {
		for (Tree typeDecl : unit.getTypeDecls()) {
			if (!(typeDecl instanceof JCClassDecl decl)) continue;
			for (JCTree def : decl.defs) {
				if (!(def instanceof JCMethodDecl method) || method.body == null) continue;
				for (JCStatement stat : method.body.stats) {
					if (stat instanceof JCVariableDecl v && v.name.contentEquals(name)) return v;
				}
			}
		}
		throw new AssertionError("fixture 里找不到局部变量 " + name);
	}
}
