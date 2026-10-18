package modtools.annotations;

import com.sun.tools.javac.util.Names;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BaseProcessor#initConst} 必须按 {@link com.sun.tools.javac.util.Context} 身份判断。
 * <p>
 * 原实现是 {@code if (_context != null) return;}：Gradle daemon / IDE 复用处理器类加载器时，
 * 第二轮编译会继续沿用上一轮已经失效的 Context 和符号，表现为 javac 各种莫名其妙的崩溃。
 */
class CompilerContextTest {

	@Test
	@DisplayName("换 Context 时重新绑定 javac 静态入口")
	void rebindsStaticsWhenContextChanges() throws Exception {
		try (JavacFixture first = JavacFixture.parseOnly("p.A", "package p; class A {}");
		     JavacFixture second = JavacFixture.parseOnly("p.B", "package p; class B {}")) {

			ProbeProcessor probe = new ProbeProcessor();

			probe.initConst(first.processingEnv);
			Object treesOfFirst = BaseProcessor.trees;
			assertSame(first.context, BaseProcessor._context);
			assertSame(Names.instance(first.context), BaseProcessor.names);

			probe.initConst(second.processingEnv);
			assertSame(second.context, BaseProcessor._context, "Context 必须指向新一轮");
			assertNotSame(treesOfFirst, BaseProcessor.trees, "不能继续复用上一轮的 JavacTrees");
			assertSame(Names.instance(second.context), BaseProcessor.names);
		}
	}

	@Test
	@DisplayName("同一个 Context 重复 init 不重复绑定")
	void sameContextIsNotRebound() throws Exception {
		try (JavacFixture fixture = JavacFixture.parseOnly("p.A", "package p; class A {}")) {
			ProbeProcessor probe = new ProbeProcessor();

			probe.initConst(fixture.processingEnv);
			Object trees = BaseProcessor.trees;

			probe.initConst(fixture.processingEnv);
			assertSame(trees, BaseProcessor.trees);
			assertSame(fixture.context, BaseProcessor._context);
		}
	}
}
