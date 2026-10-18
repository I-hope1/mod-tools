package modtools.annotations;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.util.Context;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.*;
import java.net.URI;
import java.util.List;

/**
 * 在进程内编译一段源码，拿到真实的 javac {@link Context} 与已标注的 AST。
 * <p>
 * 用真实 javac 而不是 mock：这里要验证的正是“改完之后 javac 还能不能正常工作”。
 */
public final class JavacFixture implements AutoCloseable {
	private final StandardJavaFileManager                          fileManager;
	private final DiagnosticCollector<JavaFileObject>              diagnostics = new DiagnosticCollector<>();
	private final com.sun.source.util.JavacTask                    task;

	public final Context                context;
	public final CompilationUnitTree    unit;
	public final ProcessingEnvironment  processingEnv;

	private JavacFixture(String className, String source, boolean analyze) throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		fileManager = compiler.getStandardFileManager(diagnostics, null, null);

		JavaFileObject file = new SimpleJavaFileObject(
		 URI.create("string:///" + className.replace('.', '/') + ".java"),
		 JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return source;
			}
		};

		List<String> options = List.of(
		 "-proc:none",
		 "-classpath", System.getProperty("java.class.path")
		);
		task = (com.sun.source.util.JavacTask) compiler.getTask(null, fileManager, diagnostics, options, null, List.of(file));
		unit = task.parse().iterator().next();
		if (analyze) task.analyze();

		context = ((BasicJavacTask) task).getContext();
		processingEnv = JavacProcessingEnvironment.instance(context);
	}

	public static JavacFixture of(String className, String source) throws Exception {
		return new JavacFixture(className, source, true);
	}

	/** 解析但不标注（用于只关心语法结构的场景） */
	public static JavacFixture parseOnly(String className, String source) throws Exception {
		return new JavacFixture(className, source, false);
	}

	public void assertNoCompileErrors() {
		List<String> errors = diagnostics.getDiagnostics().stream()
		 .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
		 .map(Object::toString)
		 .toList();
		if (!errors.isEmpty()) {
			throw new AssertionError("fixture 本身编译失败: " + errors);
		}
	}

	@Override
	public void close() throws Exception {
		fileManager.close();
	}
}
