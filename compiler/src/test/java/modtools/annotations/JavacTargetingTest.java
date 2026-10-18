package modtools.annotations;

import com.sun.tools.javac.code.Preview;
import com.sun.tools.javac.comp.Resolve;
import com.sun.tools.javac.jvm.StringConcat;
import com.sun.tools.javac.jvm.Target;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Options;
import modtools.annotations.unsafe.ContextPatcher;
import modtools.annotations.unsafe.JavacTargeting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static modtools.annotations.HopeReflect.getAccess;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * {@link JavacTargeting} 的选项解析，以及 {@link ContextPatcher} 的键名推导。
 * <p>
 * 这两块都是“改错了不会报错、只会让字节码或 javac 行为悄悄变形”的地方，所以宁可锁死。
 */
class JavacTargetingTest {
	/** JDK 25 里 Target 已经是 Context 组件，读它得先拿到 targetKey */
	private static Target currentTarget(Context context) {
		Context.Key<Target> key = getAccess(Target.class, null, "targetKey");
		return context.get(key);
	}

	@Test
	void forceJavaVersionSwitchesTargetAndDropsOption() throws Exception {
		try (JavacFixture fx = JavacFixture.parseOnly("P", "class P {}")) {
			Options options = Options.instance(fx.context);
			options.put(JavacTargeting.FORCE_JAVA_VERSION + "=8", "");

			JavacTargeting.forceJavaVersion(fx.context);

			assertSame(Target.lookup("8"), currentTarget(fx.context), "目标版本应该切到 8");
			assertFalse(options.keySet().contains("-AtargetVersion=8"),
			 "处理器自己的选项不该留在 Options 里: " + options.keySet());
		}
	}

	@Test
	void forceJavaVersionWithoutOptionIsNoOp() throws Exception {
		try (JavacFixture fx = JavacFixture.parseOnly("P", "class P {}")) {
			Target before = Target.instance(fx.context);

			JavacTargeting.forceJavaVersion(fx.context);

			assertSame(before, currentTarget(fx.context));
		}
	}

	@Test
	void forceJavaVersionIgnoresUnknownValue() throws Exception {
		try (JavacFixture fx = JavacFixture.parseOnly("P", "class P {}")) {
			Options options = Options.instance(fx.context);
			options.put("-AtargetVersion=banana", "");
			Target before = Target.instance(fx.context);

			// 写错一个版本号不该让整个编译挂掉
			assertDoesNotThrow(() -> JavacTargeting.forceJavaVersion(fx.context));

			assertSame(before, currentTarget(fx.context));
			assertFalse(options.keySet().contains("-AtargetVersion=banana"));
		}
	}

	@Test
	void findOptionOnlyMatchesOptionWithValue() throws Exception {
		try (JavacFixture fx = JavacFixture.parseOnly("P", "class P {}")) {
			Options options = Options.instance(fx.context);
			options.put("-AtargetVersionX=9", "");
			options.put("-AtargetVersion", "");

			assertNull(JavacTargeting.findOption(options, "-AtargetVersion="),
			 "没有 '=' 的同前缀选项不算命中");

			options.put("-AtargetVersion=8", "");
			assertEquals("-AtargetVersion=8", JavacTargeting.findOption(options, "-AtargetVersion="));
		}
	}

	@Test
	void removeKeyInstallsNewInstance() throws Exception {
		try (JavacFixture fx = JavacFixture.parseOnly("P", "class P {}")) {
			Target marker = Target.lookup("11");

			ContextPatcher.removeKey(fx.context, Target.class, () -> marker);

			assertSame(marker, currentTarget(fx.context));
		}
	}

	static Stream<Arguments> contextComponents() {
		return Stream.of(
		 arguments(Resolve.class, "resolveKey"),
		 arguments(Log.class, "logKey"),
		 arguments(Names.class, "namesKey"),
		 arguments(Target.class, "targetKey"),
		 arguments(Preview.class, "previewKey"),
		 arguments(StringConcat.class, "stringConcatKey")
		);
	}

	/** 键名推错了 {@code removeKey} 会抛 NoSuchFieldException，所以必须和 javac 的写法对齐 */
	@ParameterizedTest(name = "{0} -> {1}")
	@MethodSource("contextComponents")
	void keyNameOfMatchesJavacNaming(Class<?> component, String expected) {
		assertEquals(expected, ContextPatcher.keyNameOf(component));
	}
}
