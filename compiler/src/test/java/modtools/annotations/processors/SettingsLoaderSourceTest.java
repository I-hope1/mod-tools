package modtools.annotations.processors;

import modtools.annotations.JavacFixture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SettingsLoaderSource} 生成的是要落盘成 {@code .java} 再编译的文本，
 * 写错了编译器不会替我们兜底——只会在下游 {@code :compileJava} 才炸。
 */
class SettingsLoaderSourceTest {
	private static final String LINE_PREFIX = "try { Class.forName(\"";
	private static final String LINE_SUFFIX = "\"); } catch (ClassNotFoundException ignored) {}";

	@Test
	void appendLoadEmitsOneTryCatchPerClass() {
		StringBuilder out = new StringBuilder();
		SettingsLoaderSource.appendLoad(out, "modtools.events.E_Blur");

		assertEquals(LINE_PREFIX + "modtools.events.E_Blur" + LINE_SUFFIX + "\n", out.toString());
	}

	@Test
	void appendLoadKeepsDollarSeparatedNestedNames() {
		// ClassSymbol.flatname 对嵌套类给出 a$b 形式的 binary name，Class.forName 要的正是它。
		// 这里如果退化成点号，运行时会找不到类，而且异常被 catch 吞掉，完全无声。
		StringBuilder out = new StringBuilder();
		SettingsLoaderSource.appendLoad(out, "modtools.ui.TopGroup$TSettings");

		assertTrue(out.toString().contains("Class.forName(\"modtools.ui.TopGroup$TSettings\")"), out.toString());
	}

	@Test
	void appendLoadKeepsDeclarationOrder() {
		StringBuilder out = new StringBuilder();
		SettingsLoaderSource.appendLoad(out, "a.A");
		SettingsLoaderSource.appendLoad(out, "b.B");

		String[] lines = out.toString().split("\n");
		assertEquals(2, lines.length);
		assertTrue(lines[0].contains("a.A"), lines[0]);
		assertTrue(lines[1].contains("b.B"), lines[1]);
	}

	@Test
	void bodyIndentsEveryLineByFourSpaces() {
		StringBuilder out = new StringBuilder();
		SettingsLoaderSource.appendLoad(out, "a.A");
		SettingsLoaderSource.appendLoad(out, "b.B");

		assertEquals(
		 "    " + LINE_PREFIX + "a.A" + LINE_SUFFIX + "\n"
		 + "    " + LINE_PREFIX + "b.B" + LINE_SUFFIX + "\n",
		 SettingsLoaderSource.body(out));
	}

	@Test
	void bodyDoesNotLeaveAWhitespaceOnlyLine() {
		StringBuilder out = new StringBuilder();
		SettingsLoaderSource.appendLoad(out, "a.A");

		String body = SettingsLoaderSource.body(out);
		String shown = body.replace(" ", "·").replace("\n", "\\n");

		assertTrue(body.endsWith("}\n"), "方法体应以换行结束: " + shown);
		assertFalse(body.contains("\n\n"), "不应出现空行: " + shown);
		assertFalse(body.lines().anyMatch(line -> !line.isEmpty() && line.isBlank()),
		 "不应出现只有空白的行: " + shown);
	}

	@Test
	void bodyIsEmptyWhenNothingWasAppended() {
		assertEquals("", SettingsLoaderSource.body(new StringBuilder()));
	}

	@Test
	void generatedBodyIsValidJava() throws Exception {
		StringBuilder out = new StringBuilder();
		SettingsLoaderSource.appendLoad(out, "java.lang.String");
		SettingsLoaderSource.appendLoad(out, "java.util.Map$Entry");

		String source = "class Loader {\n  static void load() {\n"
		                + SettingsLoaderSource.body(out)
		                + "  }\n}\n";

		try (JavacFixture fx = JavacFixture.of("Loader", source)) {
			fx.assertNoCompileErrors();
		}
	}
}
