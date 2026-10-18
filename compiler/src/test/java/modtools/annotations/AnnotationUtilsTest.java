package modtools.annotations;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sun.reflect.annotation.AnnotationType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用真实 javac 验证注解实参的常量展开与读取：
 * 既不能崩（栈溢出 / NPE / 越界），也不能改坏 javac 的共享状态。
 */
class AnnotationUtilsTest {

	private static void bind(JavacFixture fixture) {
		new ProbeProcessor().initConst(fixture.processingEnv);
	}

	@Test
	@DisplayName("常量链正常展开")
	void resolvesConstantChain() throws Exception {
		try (JavacFixture f = JavacFixture.of("p.Fixture", constFixtureSource())) {
			f.assertNoCompileErrors();
			bind(f);

			assertEquals(7, AnnotationValues.treeToConstant(f.unit, field(f.unit, "CHAIN2").init));
		}
	}

	@Test
	@DisplayName("自引用/超长常量链被深度上限截断，不栈溢出")
	void depthLimitStopsRunawayRecursion() throws Exception {
		try (JavacFixture f = JavacFixture.of("p.Fixture", constFixtureSource())) {
			f.assertNoCompileErrors();
			bind(f);

			// V0 -> V1 -> ... -> V70 -> DEEP，远超 AnnotationValues.MAX_DEPTH
			assertNull(AnnotationValues.treeToConstant(f.unit, field(f.unit, "DEEP").init));
		}
	}

	@Test
	@DisplayName("null 节点返回 null 而不是 NPE")
	void nullNode() throws Exception {
		try (JavacFixture f = JavacFixture.of("p.Fixture", constFixtureSource())) {
			bind(f);
			assertNull(AnnotationValues.treeToConstant(f.unit, null));
		}
	}

	@Test
	@DisplayName("数组字面量展开为 List")
	void arrayLiteral() throws Exception {
		try (JavacFixture f = JavacFixture.of("p.Fixture", constFixtureSource())) {
			f.assertNoCompileErrors();
			bind(f);

			Object value = AnnotationValues.treeToConstant(f.unit, field(f.unit, "ARR").init);
			assertInstanceOf(List.class, value);
			assertEquals(List.of(1, 2, 3), value);
		}
	}

	@Test
	@DisplayName("X.class 不再因 getTypeArguments().get(0) 越界")
	void classLiteralDoesNotThrow() throws Exception {
		try (JavacFixture f = JavacFixture.of("p.Fixture", constFixtureSource())) {
			f.assertNoCompileErrors();
			bind(f);

			assertDoesNotThrow(() -> AnnotationValues.treeToConstant(f.unit, field(f.unit, "CLS").init));
		}
	}

	@Test
	@DisplayName("genericAnnotation 不得写脏 AnnotationType.memberDefaults()")
	void genericAnnotationDoesNotMutateSharedDefaults() throws Exception {
		try (JavacFixture f = JavacFixture.of("p.Annotated", """
		 package p;
		 @modtools.annotations.FixtureAnno(9)
		 class Annotated {}
		 """)) {
			f.assertNoCompileErrors();
			bind(f);

			Map<String, Object> before = new HashMap<>(AnnotationType.getInstance(FixtureAnno.class).memberDefaults());

			JCClassDecl decl = (JCClassDecl) f.unit.getTypeDecls().get(0);
			JCAnnotation ann  = decl.mods.annotations.get(0);

			Map<String, Object> parsed = AnnotationValues.genericAnnotation(FixtureAnno.class, f.unit, ann);
			assertEquals(9, ((Number) parsed.get("value")).intValue());

			assertEquals(before, AnnotationType.getInstance(FixtureAnno.class).memberDefaults(),
			 "memberDefaults() 是 javac 共享 Map，写入会污染后续所有注解读取");
		}
	}

	// region helpers
	private static JCVariableDecl field(CompilationUnitTree unit, String name) {
		for (Tree t : unit.getTypeDecls()) {
			if (!(t instanceof JCClassDecl decl)) continue;
			for (JCTree def : decl.defs) {
				if (def instanceof JCVariableDecl v && v.name.contentEquals(name)) return v;
			}
		}
		throw new AssertionError("fixture 里找不到字段 " + name);
	}

	private static String constFixtureSource() {
		StringBuilder sb = new StringBuilder("package p;\nclass Fixture {\n");
		sb.append("  static final int LEAF = 7;\n");
		sb.append("  static final int CHAIN2 = LEAF;\n");
		sb.append("  static final Class<?> CLS = String.class;\n");
		sb.append("  static final int[] ARR = {1, 2, 3};\n");
		sb.append("  static final int V0 = 1;\n");
		for (int i = 1; i <= 70; i++) {
			sb.append("  static final int V").append(i).append(" = V").append(i - 1).append(";\n");
		}
		sb.append("  static final int DEEP = V70;\n");
		sb.append("}\n");
		return sb.toString();
	}
	// endregion
}
