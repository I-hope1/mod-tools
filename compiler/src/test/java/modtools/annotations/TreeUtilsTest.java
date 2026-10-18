package modtools.annotations;

import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TreeUtils#findField} 是“只探测不报错”的那一半：{@code checkField} 的报错
 * 由调用方决定时机，而“往上找外层类”的场景里找不到是正常情况。
 */
class TreeUtilsTest {
	private final TreeUtils utils = new TreeUtils() {};

	@Test
	void findFieldFindsDeclaredField() throws Exception {
		try (JavacFixture fx = JavacFixture.parseOnly("P", "class P { int a; String b; }")) {
			JCClassDecl decl = classDeclOf(fx);

			assertNotNull(utils.findField(decl, "a"));
			assertNotNull(utils.findField(decl, "b"));
		}
	}

	@Test
	void findFieldReturnsNullWithoutComplaining() throws Exception {
		try (JavacFixture fx = JavacFixture.parseOnly("P", "class P { int a; }")) {
			assertNull(utils.findField(classDeclOf(fx), "missing"));
		}
	}

	private static JCClassDecl classDeclOf(JavacFixture fx) {
		return (JCClassDecl) ((JCCompilationUnit) fx.unit).defs.head;
	}
}
