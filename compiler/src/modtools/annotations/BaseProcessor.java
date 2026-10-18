package modtools.annotations;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.jvm.ClassWriter;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import modtools.annotations.processors.AINIT;
import modtools.annotations.unsafe.ProcessingEnvContext;
import modtools.annotations.unsafe.Replace;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import java.util.*;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
public abstract class BaseProcessor<T extends Element> extends AbstractProcessor
 implements TreeUtils, AnnotationUtils, PrintHelper {

	public static JavacElements elements;
	public static JavacTrees    trees;
	public static TreeMaker     mMaker;
	public static TreeMaker     nullMaker;
	public static Resolve       resolve;
	public static Enter         enter;
	public static Names         names;
	public static Types         types;
	public static ParserFactory parsers;
	public static Symtab        mSymtab;
	public static Log           log;
	public static JavacMessages messages;
	public static ClassFinder   classFinder;
	public static Filer         mFiler;
	public static ClassWriter   mClassWriter;
	public static Attr          attr;
	public static JavaCompiler  compiler;

	public static Context _context;

	public static Type stringType;

	/** 只处理前两轮：第一轮改写元素，第二轮处理新生成的源码 */
	private static final int MAX_ROUNDS = 2;

	private   int              runTimes;
	protected RoundEnvironment roundEnv;
	public void process2() { }

	public static final boolean RECORD_TIME = false;

	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		if (!AINIT.hasMindustry) return true;
		this.roundEnv = roundEnv;

		if (++runTimes > MAX_ROUNDS) return true;

		switch (runTimes) {
			case 1 -> firstRound(annotations, roundEnv);
			default -> secondRound(annotations, roundEnv);
		}
		return true;
	}

	private void firstRound(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		if (RECORD_TIME) Times.mark();
		runGuarded(this::lazyInit, "lazyInit");
		dealWithRound(annotations, roundEnv);
		runGuarded(this::process, "process");
		if (RECORD_TIME) Times.printElapsed("@ms for @", this);
	}

	private void secondRound(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		dealWithRound(annotations, roundEnv);
		runGuarded(this::process2, "process2");
	}

	/**
	 * 处理器里的异常统一走 {@link PrintHelper#err(Throwable)}：
	 * 打印堆栈并按既有策略中止编译，不让 javac 带着半成品继续跑。
	 */
	private void runGuarded(ThrowingRunnable action, String phase) {
		try {
			action.run();
		} catch (Throwable e) {
			PrintHelper.errs("[" + phase + "] processor phase failed:");
			err(e);
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Throwable;
	}
	private void dealWithRound(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		for (TypeElement annotation : annotations) {
			for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
				try {
					TreePath path = trees.getPath(element);
					if (path != null) {
						log.useSource(path.getCompilationUnit().getSourceFile());
						mMaker.toplevel = (JCCompilationUnit) path.getCompilationUnit();
					}
					dealElement((T) element);
				} catch (Throwable e) {
					err(e);
					// getMessage() 可能为 null，直接塞进诊断会让 javac 的格式化出问题
					log.error(SPrinter.err(e.getMessage() != null ? e.getMessage() : e.toString()));
				} finally {
					mMaker.toplevel = null;
					log.useSource(null);
				}
			}
		}
	}
	/** 在{@link #dealElement(Element)}之后执行 */
	public void process() throws Throwable { }

	public abstract void dealElement(T element) throws Throwable;

	public ProcessingEnvironment env;
	public final synchronized void init(ProcessingEnvironment env) {
		super.init(env);
		this.env = env;
		// println("initialized");
		initConst(env);
		if (!AINIT.hasMindustry) return;
		try {
			init();
		} catch (Throwable e) { err(e); }
	}

	public final void initConst(ProcessingEnvironment env) {
		// 同一 JVM 内可能连续跑多轮编译（Gradle daemon / IDE 会复用处理器类加载器）。
		// 必须按 Context 身份判断，否则第二轮会继续沿用上一轮已失效的 Context 与符号。
		Context ctx = ProcessingEnvContext.of(env);
		if (ctx == _context) return;

		_context = ctx;
		// 换 Context 意味着上一轮的所有符号都作废，先清掉跨轮静态缓存
		resetCrossCompilationCaches();

		elements = JavacElements.instance(_context);
		trees = JavacTrees.instance(_context);
		mMaker = TreeMaker.instance(_context);
		nullMaker = mMaker.forToplevel(null);
		resolve = Resolve.instance(_context);
		enter = Enter.instance(_context);
		names = Names.instance(_context);
		types = Types.instance(_context);
		log = Log.instance(_context);
		messages = JavacMessages.instance(_context);
		parsers = ParserFactory.instance(_context);
		mSymtab = Symtab.instance(_context);
		classFinder = ClassFinder.instance(_context);
		mFiler = env.getFiler();
		mClassWriter = ClassWriter.instance(_context);
		attr = Attr.instance(_context);
		compiler = JavaCompiler.instance(_context);

		stringType = mSymtab.stringType;
	}

	/** 清理绑定在旧 {@link Context} 上的静态缓存，避免跨编译轮次持有失效符号 */
	private static void resetCrossCompilationCaches() {
		try {
			DataUtils.resetSymbols();
		} catch (Throwable e) { PrintHelper.errs(e); }
		try {
			Replace.resetContextCaches();
		} catch (Throwable e) { PrintHelper.errs(e); }
	}
	public void init() throws Throwable { }

	public void lazyInit() throws Throwable { }


	public final SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}
	public final Set<String> getSupportedAnnotationTypes() {
		if (!AINIT.hasMindustry) return Set.of();
		try {
			return getSupportedAnnotationTypes0().stream()
			 .map(Class::getCanonicalName).collect(Collectors.toSet());
		} catch (Throwable e) {
			return Set.of();
		}
	}
	public abstract Set<Class<?>> getSupportedAnnotationTypes0();


	// region utils
	public static Element findSibling(Element sibling, String name, ElementKind kind) {
		return findChild(sibling.getEnclosingElement(), name, kind);
	}

	public static <T extends Element> T findChild(Element parent, String name, ElementKind kind) {
		for (Element member : parent.getEnclosedElements()) {
			if (member.getKind() == kind && member.getSimpleName().contentEquals(name)) {
				return (T) member;
			}
		}
		return null;
	}
	public static List<Element> findAllChild(Element parent, String name, ElementKind kind) {
		List<Element> set = new ArrayList<>();
		for (Element member : parent.getEnclosedElements()) {
			if (member.getKind() == kind && (name == null || member.getSimpleName().contentEquals(name))) {
				set.add(member);
			}
		}
		return set;
	}
	public static <T extends JCTree> T findChild(JCClassDecl parent, Tag tag, Predicate<T> predicate) {
		for (JCTree def : parent.defs) {
			if (def.getTag() == tag && predicate.test((T) def)) {
				return (T) def;
			}
		}
		return null;
	}

	public static Class<?> classOrThrow(String name) {
		try {
			return Class.forName(name);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}
	public static Class<?> classOrNull(String name) {
		return classOrNull(name, HopeReflect.class.getClassLoader());
	}

	public static Class<?> classOrNull(String name, ClassLoader loader) {
		try {
			return loader.loadClass(name);
		} catch (ClassNotFoundException e) {
			try {
				return Class.forName(name);
			} catch (ClassNotFoundException ex) {
				return null;
			}
		}
	}
	protected static VarSymbol getSymbol(CompilationUnitTree unit, JCVariableDecl tree) {
		return (VarSymbol) getSymbol(unit, (JCTree) tree);
	}
	protected static Symbol getSymbol(CompilationUnitTree unit, JCTree tree) {
		TreePath path = trees.getPath(unit, tree);
		return trees.getElement(path);
	}

	// endregion utils
}
