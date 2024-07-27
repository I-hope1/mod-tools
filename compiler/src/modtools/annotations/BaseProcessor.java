package modtools.annotations;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.jvm.ClassWriter;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.processing.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import modtools.annotations.processors.AINIT;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import java.util.List;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
public abstract class BaseProcessor<T extends Element> extends AbstractProcessor
 implements TreeUtils, AnnotationUtils, PrintHelper {

	public static JavacElements elements;
	public static JavacTrees    trees;
	public static TreeMaker     mMaker;
	public static Names         names;
	public static Types         types;
	public static ParserFactory parsers;
	public static Symtab        mSymtab;
	public static ClassFinder   classFinder;
	public static JavacFiler    mFiler;
	public static ClassWriter   classWriter;
	public static Attr          attr;
	public static JavaCompiler  compiler;

	public static Context _context;

	public static Type stringType;

	private   int              runTimes;
	protected RoundEnvironment roundEnv;
	public void process2() { }
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		if (!AINIT.hasMindustry) return true;
		this.roundEnv = roundEnv;
		runTimes++;
		if (runTimes > 2) {
			return true;
		}
		if (runTimes > 1) {
			try {
				process2();
			} catch (Throwable e) { err(e); }
			return true;
		}
		// 第一次
		for (TypeElement annotation : annotations) {
			for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
				try {
					dealElement((T) element);
				} catch (Throwable e) { err(e); }
			}
		}
		try {
			process();
		} catch (Throwable e) { err(e); }

		return true;
	}
	public void process() throws Throwable { }
	protected static StringBuilder getUnderlineName(String fieldName) {
		StringBuilder underlineName = new StringBuilder();
		underlineName.append(Character.toLowerCase(fieldName.charAt(0)));
		for (int i = 1, len = fieldName.length(); i < len; i++) {
			if (Character.isUpperCase(fieldName.charAt(i)) && !Character.isUpperCase(fieldName.charAt(i + 1)))
				underlineName.append("_");
			underlineName.append(Character.toLowerCase(fieldName.charAt(i)));
		}
		return underlineName;
	}

	public abstract void dealElement(T element) throws Throwable;

	public final synchronized void init(ProcessingEnvironment env) {
		super.init(env);
		// println("initialized");
		initConst(env);
		if (!AINIT.hasMindustry) return;
		try {
			init();
		} catch (Throwable e) { err(e); }
	}

	public final void initConst(ProcessingEnvironment env) {
		if (_context != null) return;

		_context = ((JavacProcessingEnvironment) processingEnv).getContext();
		elements = JavacElements.instance(_context);
		trees = JavacTrees.instance(_context);
		mMaker = TreeMaker.instance(_context);
		names = Names.instance(_context);
		types = Types.instance(_context);
		parsers = ParserFactory.instance(_context);
		mSymtab = Symtab.instance(_context);
		classFinder = ClassFinder.instance(_context);
		mFiler = (JavacFiler) env.getFiler();
		classWriter = ClassWriter.instance(_context);
		attr = Attr.instance(_context);
		compiler = JavaCompiler.instance(_context);

		stringType = mSymtab.stringType;
	}
	public void init() throws Throwable { }


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

	/** test_ui -> TestUI */
	public static String kebabToBigCamel(CharSequence kebab) {
		StringBuilder sb = new StringBuilder();
		sb.append(Character.toUpperCase(kebab.charAt(0)));
		boolean isUpper = false;
		for (int i = 1, len = kebab.length(); i < len; i++) {
			if (kebab.charAt(i) == '_') {
				isUpper = true;
				continue;
			}
			// test_ui_
			if (kebab.charAt(i - 1) != '_' && isUpper && (i + 1 < len && kebab.charAt(i + 1) != '_')) isUpper = false;
			sb.append(isUpper ? Character.toUpperCase(kebab.charAt(i)) : kebab.charAt(i));
		}
		return sb.toString();
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
	protected static VarSymbol getSymbol(CompilationUnitTree unit, JCVariableDecl tree) {
		return (VarSymbol) getSymbol(unit, (JCTree) tree);
	}
	protected static Symbol getSymbol(CompilationUnitTree unit, JCTree tree) {
		TreePath path = trees.getPath(unit, tree);
		return trees.getElement(path);
	}
	public abstract Set<Class<?>> getSupportedAnnotationTypes0();


	public static String kebabToCamel(String s) {
		StringBuilder result = new StringBuilder(s.length());

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c != '_' && c != '-') {
				if (i != 0 && (s.charAt(i - 1) == '_' || s.charAt(i - 1) == '-')) {
					result.append(Character.toUpperCase(c));
				} else {
					result.append(c);
				}
			}
		}

		return result.toString();
	}
	public static String capitalize(String s) {
		StringBuilder result = new StringBuilder(s.length());

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '_' || c == '-') {
				result.append(" ");
			} else if (i == 0 || s.charAt(i - 1) == '_' || s.charAt(i - 1) == '-') {
				result.append(Character.toUpperCase(c));
			} else {
				result.append(c);
			}
		}

		return result.toString();
	}
}
