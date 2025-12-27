package modtools.annotations.processors.asm;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.List;
import modtools.annotations.BaseProcessor;
import modtools.annotations.asm.Sample;

import javax.annotation.processing.Processor;
import javax.lang.model.element.ElementKind;
import javax.tools.JavaFileObject;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

import static modtools.annotations.asm.Sample.*;
import static modtools.annotations.processors.asm.BaseASMProc.className;

@AutoService({Processor.class})
public class SampleProcessor extends BaseProcessor<MethodSymbol> {

	public static final String       NAME_VISIT    = "visitEmitMethod";
	public static final String       NAME_SET_FUNC = "setFunc";
	public              MethodSymbol _superCall;
	public void lazyInit() {
		ClassSymbol classSymbol = findClassSymbol("modtools.annotations.asm.Sample$SampleTemp");
		_superCall = findChild(classSymbol, "_super", ElementKind.METHOD);
	}
	/** 通过Sample的定义生成对应的接口 */
	public Map<ClassSymbol, ClassSymbol> interfaces = new HashMap<>();
	public void dealElement(MethodSymbol element) throws Throwable {
		JCCompilationUnit unit = (JCCompilationUnit) trees.getPath(element).getCompilationUnit();

		ClassSymbol owner  = ((ClassSymbol) element.owner);
		Sample      sample = owner.getAnnotation(Sample.class);
		if (sample == null) {
			log.error(trees.getTree(element).mods, SPrinter.err("@SampleForMethod / @SampleForInitializer is only allowed on methods annotated with @Sample"));
			return;
		}

		final boolean openPackagePrivate = sample.openPackagePrivate();

		/* 创建接口类型  */
		if (!interfaces.containsKey(owner)) {
			trees.getTree(owner).mods.annotations = List.nil();
			ClassSymbol _interface = makeInterface(owner, openPackagePrivate, unit, sample);

			interfaces.put(owner, _interface);
		}

		// SampleForMethod annotation = getAnnotationByElement(SampleForMethod.class, element, true);

		JCMethodDecl  methodDecl    = trees.getTree(element);
		StringBuilder superDeclared = new StringBuilder();
		new TreeTranslator() {
			public JCMethodInvocation _super;
			public void visitApply(JCMethodInvocation tree) {
				if (tree == _super) {
					_super = null;
					if (!(tree.args.get(0) instanceof JCIdent i && i.name.contentEquals(element.params.get(0).name))) {
						log.error(tree.pos, SPrinter.err("_super is only allowed to be used as '_super(" + element.params.get(0).name + ")'."));
						return;
					}
					// println(tree);
					result = mMaker.TypeCast(mMaker.Type(interfaces.get(owner).type), tree.args.get(0));
					return;
				}

				if (tree.meth instanceof JCFieldAccess fa && fa.selected instanceof JCMethodInvocation mi && getSymbol(unit, mi) == _superCall) {
					_super = mi;
					super.visitApply(tree);
					fa.name = names.fromString(AConstants.SUPER_METHOD_PREFIX + fa.name);
					fa.sym = interfaces.get(owner).members().findFirst(fa.name);
				} else {
					super.visitApply(tree);
				}
			}
		}.translate(methodDecl);

		// todos.get(owner).accept(superDeclared);
		// println(methodDecl);
	}
	private ClassSymbol makeInterface(ClassSymbol owner, boolean openPackagePrivate, JCCompilationUnit unit,
	                                  Sample sample) {
		final String var_myClass = "myClass";
		final String var_class   = "className";
		final String ownerName   = owner.name.toString();

		JavaFileObject file;
		try {
			file = mFiler.createSourceFile(owner.getQualifiedName().toString() + AConstants.INTERFACE_SUFFIX, owner);
		} catch (IOException e) {
			ClassSymbol classSymbol = mSymtab.enterClass(owner.packge().modle, names.fromString(ownerName + AConstants.INTERFACE_SUFFIX));
			if (classSymbol.exists()) {
				return classSymbol;
			}
			throw new RuntimeException(e);
		}
		ClassSymbol   _interface          = new ClassSymbol(Flags.PUBLIC | Flags.INTERFACE, names.fromString(ownerName + AConstants.INTERFACE_SUFFIX), owner.owner);
		StringBuilder packagePrivateSb    = new StringBuilder();
		StringBuilder superMethodDeclared = new StringBuilder();
		StringBuilder methodVisitSb       = new StringBuilder();

		methodVisitSb.append("String ").append(var_class).append(" = ByteCodeTools.nativeName(").append(ownerName).append(".class);\n");


		SampleForMethod      sm;
		SampleForInitializer si;
		for (Symbol symbol : owner.members().getSymbols()) {
			if (!(symbol instanceof MethodSymbol ms)) continue;
			sm = getAnnotationByElement(SampleForMethod.class, symbol, true);
			si = getAnnotationByElement(SampleForInitializer.class, symbol, true);
			if (sm == null && si == null) { continue; }
			if (ms.params.isEmpty()) {
				log.error(trees.getTree(ms).mods, SPrinter.err("@SampleForMethod / @SampleForInitializer is only allowed on methods with parameters"));
			}

			JCMethodDecl         tree       = trees.getTree(ms);
			JCBlock              prevbody   = tree.body;
			JCModifiers          prevmods   = tree.mods;
			Name                 prevname   = tree.name;
			List<JCVariableDecl> prevparams = tree.params;
			tree.body = null;
			tree.mods = mMaker.Modifiers(Flags.PUBLIC, List.nil());
			tree.name = names.fromString(AConstants.SUPER_METHOD_PREFIX + tree.name);
			tree.params = tree.params.tail;
			_interface.members_field = WriteableScope.create(_interface);
			MethodSymbol methodSymbol = new MethodSymbol(Flags.PUBLIC, tree.name, tree.type, _interface);
			// methodSymbol.params = tree.sym.params;
			_interface.members().enter(methodSymbol);
			superMethodDeclared.append(tree);
			// 类型下届
			// 代码转换：if (Object.class.isAssignableFrom(clazz)
			methodVisitSb.append("if (").append(className(ms.params.head.type)).append(".isAssignableFrom(clazz)) ");
			// 类型上界
			Class<?>[] upperBoundOfClasses = sm != null ? sm.upperBoundClasses() : si.upperBoundClasses();
			if (upperBoundOfClasses.length > 0) {
				methodVisitSb.append(Arrays.stream(upperBoundOfClasses).map(c -> "clazz.isAssignable(" + c.getSimpleName() + ".class)")
				 .collect(Collectors.joining(" || ", "if (", ")")));
			}
			Name name = si != null ? names.init : ms.name;

			// 代码转换：myClass.visitEmitMethod("acceptItem", new Class<?>[]{Building.class, Building.class, Item.class}, boolean.class, className);
			methodVisitSb.append(var_myClass).append(".").append(NAME_VISIT).append("(");
			methodVisitSb.append("\"").append(name).append("\", ");
			if (si != null) methodVisitSb.append("\"").append(ms.name).append("\", ");
			methodVisitSb.append("new Class<?>[]{")
			 .append(ms.params.stream().map(BaseASMProc::className).collect(Collectors.joining(", "))).append("}, ")
			 .append(className(ms.getReturnType())).append(", ").append(var_class).append(");\n");

			if (openPackagePrivate) {
				packagePrivateSb.append("if (").append(className(ms.params.head.type)).append(".isAssignableFrom(clazz)) ");
				// 代码转换：myClass.setFunc("acceptItem", (Func2) null, Modifier.PUBLIC, boolean.class, Building.class, Building.class, Item.class)
				packagePrivateSb.append(var_myClass).append(".").append(NAME_SET_FUNC)
				 .append("(\"").append(name).append("\", (Func2) null, Modifier.PUBLIC, ")
				 .append(className(ms.getReturnType())).append(", ")
				 .append(ms.params.stream().skip(1).map(BaseASMProc::className).collect(Collectors.joining(", "))).append(");\n");
			}

			tree.mods = prevmods;
			tree.body = prevbody;
			tree.name = prevname;
			tree.params = prevparams;
		}

		String builderCall = "if (builder != null) builder.get(myClass);";

		String myclassInit = openPackagePrivate ? "new MyClass(Sample.AConstants.legalName(clazz.getName()) + \"i\", " + var_myClass + ".define())" : "new MyClass(" + var_myClass + ".define()/* 使类public化 */, \"i\")";
		String s = STR."""
package \{owner.packge().getQualifiedName().toString()};

\{unit.getImports().stream().reduce("", (a, b) -> a + b, (a, b) -> a + b)}
import arc.func.Func2;
import arc.func.Cons;
import modtools.utils.ByteCodeTools;
import modtools.utils.ByteCodeTools.MyClass;
import modtools.utils.Tools;
import modtools.utils.reflect.ClassUtils;

import java.lang.reflect.*;
import java.util.*;

public interface \{ownerName}\{AConstants.INTERFACE_SUFFIX} {

\{superMethodDeclared}

Map<Class<?>, Class<?>> cache = new HashMap<>();

public static Class<?> visit(Class<?> clazz) {
	return visit(clazz, null);
}
public static Class<?> visit(Class<?> clazz, Cons<MyClass<?>> builder) {
	\{(sample.defaultClass().isBlank() ? "" : "if (clazz == Object.class) clazz = ClassUtils.forName(\"" + sample.defaultClass() + "\");")}
	if (cache.containsKey(clazz)) {
		return cache.get(clazz);
	}
	if (\{ownerName}\{AConstants.INTERFACE_SUFFIX}.class.isAssignableFrom(clazz)) return clazz;

	var \{var_myClass} = new MyClass(clazz, "\{AConstants.GEN_CLASS_NAME_SUFFIX}");
	\{packagePrivateSb}
	\{builderCall}

	\{var_myClass} = \{myclassInit};
	\{methodVisitSb}
	\{var_myClass}.addInterface(\{ownerName}\{AConstants.INTERFACE_SUFFIX}.class);
	\{builderCall}
	var newClass = \{var_myClass}.define(\{ownerName}.class);
	cache.put(clazz, newClass);
	return newClass;
}


public static <T> T changeClass(T obj) {
	Class<?> clazz = visit(obj.getClass());
	return Tools.newInstance(obj, clazz);
}
public static <T> T newInstance(Class<T> c) {
	Class<?> clazz = visit(c);
	try {
		return (T) clazz.getDeclaredConstructor().newInstance();
	} catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
		throw new RuntimeException(e);
	}
}

}""";
		try (Writer writer = file.openWriter()) {
			writer.append(s);
			writer.flush();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		_interface.sourcefile = file;
		_interface.complete();
		return _interface;
	}
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(SampleForMethod.class/* , SampleForInitializer.class *//* TODO */);
	}
}
