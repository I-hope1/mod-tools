package modtools.annotations.processors;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import modtools.annotations.JavacFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RefSourceGenerator} 拼的是“处理器自己生成的源码”。
 * 一旦少个分号或者包声明拼错，症状是“处理器输出编译不过”，现场很难定位到哪一行，
 * 所以这里既查文本形状，也把生成的源码真正编译一遍。
 */
class RefSourceGeneratorTest {
	private static final String SETTINGS_SOURCE = """
	 package p;
	 import java.util.List;
	 public class E_Demo {
	 \tpublic static boolean enabled;
	 \tpublic static int scale_level;
	 }
	 """;

	@Test
	void rendersTheRefClass(@TempDir Path outDir) throws Exception {
		try (JavacFixture fx = JavacFixture.of("p.E_Demo", SETTINGS_SOURCE)) {
			fx.assertNoCompileErrors();
			ClassSymbol settings = (ClassSymbol) fx.processingEnv.getElementUtils().getTypeElement("p.E_Demo");
			assertNotNull(settings);

			String text = RefSourceGenerator.render((JCCompilationUnit) fx.unit, settings, "Demo", fields(settings));

			assertTrue(text.contains("package p;"), text);
			assertTrue(text.contains("import p.E_Demo.*;"), text);
			assertTrue(text.contains("@modtools.annotations.settings.RefOf(p.E_Demo.class)"), text);
			assertTrue(text.contains("public class R_Demo {"), text);
			assertTrue(text.contains("public static boolean enabled;"), text);
			assertTrue(text.contains("public static int scale_level;"), text);

			// 最关键的一条：生成出来的东西得能编译
			List<String> errors = compile(outDir, Map.of("p.E_Demo", SETTINGS_SOURCE, "p.R_Demo", text));
			assertTrue(errors.isEmpty(), "生成的源码编译失败: " + errors);
		}
	}

	private static Map<VarSymbol, String> fields(ClassSymbol settings) {
		Map<VarSymbol, String> fields = new LinkedHashMap<>();
		for (var member : settings.getEnclosedElements()) {
			if (member instanceof VarSymbol var) {
				fields.put(var, var.type.toString());
			}
		}
		assertFalse(fields.isEmpty(), "fixture 里应该有字段");
		return fields;
	}

	/** 把若干份源码一起编译，返回错误诊断 */
	private static List<String> compile(Path outDir, Map<String, String> sources) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
			List<JavaFileObject> files = sources.entrySet().stream().map(e -> (JavaFileObject) new SimpleJavaFileObject(
			 URI.create("string:///" + e.getKey().replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
				@Override
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
					return e.getValue();
				}
			}).toList();

			compiler.getTask(null, fm, diagnostics,
			 List.of("-proc:none", "-d", outDir.toString(),
				"-classpath", System.getProperty("java.class.path")),
			 null, files).call();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return diagnostics.getDiagnostics().stream()
		 .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
		 .map(Object::toString)
		 .toList();
	}
}
