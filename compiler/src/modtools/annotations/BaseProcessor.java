package modtools.annotations;

import arc.struct.Seq;
import arc.util.Log;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.jvm.*;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.processing.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import java.util.Set;
import java.util.function.Predicate;

public abstract class BaseProcessor<T extends Element> extends AbstractProcessor implements TreeUtils, AnnotationUtils {
	public static JavacElements elements;
	public static JavacTrees trees;
	public static TreeMaker  mMaker;
	public static Names      names;
	public static Types         types;
	public static ParserFactory parsers;
	public static Symtab        mSymtab;
	public static ClassFinder   classFinder;
	public static JavacFiler    mFiler;
	public static ClassWriter   classWriter;
	public static Gen           __gen__;

	public static Context __context;

	public static Type        stringType;
	public static ClassSymbol timeSymbol;

	private boolean firstFinished, second;
	public boolean isFirstFinished() {
		return firstFinished;
	}
	public void process2() {}
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		if (firstFinished) {
			if (!second) {
				second = true;
				process2();
			}
			return true;
		}
		firstFinished = true;
		for (TypeElement annotation : annotations) {
			for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
				try {
					dealElement((T) element);
				} catch (Throwable e) {Log.err(e);}
			}
		}
		try {
			process();
		} catch (Throwable e) {
			Log.err(e);
		}
		return true;
	}
	public void process() throws Throwable {}
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

	public static String simpleName(String name) {
		return name.substring(name.replace('$', '.').lastIndexOf('.') + 1);
	}

	public synchronized void init(ProcessingEnvironment env) {
		super.init(env);
		try {
			init();
		} catch (Throwable e) {Log.err(e);}
		if (elements != null) return;

		__context = ((JavacProcessingEnvironment) processingEnv).getContext();
		elements = JavacElements.instance(__context);
		trees = JavacTrees.instance(__context);
		mMaker = TreeMaker.instance(__context);
		names = Names.instance(__context);
		types = Types.instance(__context);
		parsers = ParserFactory.instance(__context);
		mSymtab = Symtab.instance(__context);
		classFinder = ClassFinder.instance(__context);
		mFiler = (JavacFiler) env.getFiler();
		classWriter = ClassWriter.instance(__context);
		__gen__ = Gen.instance(__context);

		stringType = mSymtab.stringType;
	}
	public void init() throws Throwable {}


	public static Element findSibling(Element sibling, String name, ElementKind kind) {
		return findChild(sibling.getEnclosingElement(), name, kind);
	}

	public static Element findChild(Element parent, String name, ElementKind kind) {
		for (Element member : parent.getEnclosedElements()) {
			if (member.getKind() == kind && member.getSimpleName().contentEquals(name)) {
				return member;
			}
		}
		return null;
	}
	public static Seq<Element> findAllChild(Element parent, String name, ElementKind kind) {
		Seq<Element> set = new Seq<>();
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
	public static <T extends JCTree> Seq<T> findAllChild(JCClassDecl parent, Tag tag, Predicate<T> predicate) {
		Seq<T> seq = new Seq<>();
		for (JCTree def : parent.defs) {
			if (def.getTag() == tag && predicate.test((T) def)) {
				seq.add((T) def);
			}
		}
		return seq;
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

	static {
		try {
			Class.forName("modtools.annotations.HopeReflect");
		} catch (Throwable e) {
			Log.err(e);
		}
	}
}
