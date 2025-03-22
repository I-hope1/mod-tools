package modtools.annotations.processors.asm;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.*;
import modtools.annotations.BaseProcessor;
import modtools.annotations.asm.Sample;

import javax.annotation.processing.Processor;
import javax.lang.model.element.ElementKind;
import javax.tools.JavaFileObject;
import java.io.Writer;
import java.util.*;
import java.util.stream.Collectors;

import static modtools.annotations.asm.Sample.*;

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
			log.useSource(unit.sourcefile);
			log.error("@SampleForMethod is only allowed on methods annotated with @Sample");
			return;
		}

		final boolean openPackagePrivate = sample.openPackagePrivate();

		/* 创建接口类型  */
		if (!interfaces.containsKey(owner)) {
			trees.getTree(owner).mods.annotations = List.nil();
			final String   var_myClas       = "myClass";
			final String   var_class        = "className";
			final String   ownerName        = owner.name.toString();
			ClassSymbol    _interface       = new ClassSymbol(Flags.PUBLIC | Flags.INTERFACE, names.fromString(ownerName + INTERFACE_SUFFIX), owner.owner);
			JavaFileObject file             = mFiler.createSourceFile(owner.getQualifiedName().toString() + INTERFACE_SUFFIX, owner);
			Writer         writer           = file.openWriter();
			StringBuilder  methodDeclaredSb = new StringBuilder();
			StringBuilder  packagePrivateSb = new StringBuilder();
			StringBuilder  methodVisitSb    = new StringBuilder();

			methodVisitSb.append("String ").append(var_class).append(" = ByteCodeTools.nativeName(").append(ownerName).append(".class);\n");


			for (Symbol symbol : owner.members().getSymbols()) {
				if (symbol instanceof MethodSymbol ms && symbol.getAnnotation(SampleForMethod.class) != null) {
					JCMethodDecl         tree       = trees.getTree(ms);
					JCBlock              prevbody   = tree.body;
					JCModifiers          prevmods   = tree.mods;
					Name                 prevname   = tree.name;
					List<JCVariableDecl> prevparams = tree.params;
					tree.body = null;
					tree.mods = mMaker.Modifiers(Flags.PUBLIC, List.nil());
					tree.name = names.fromString(SUPER_METHOD_PREFIX + tree.name);
					tree.params = tree.params.tail;
					_interface.members_field = WriteableScope.create(_interface);
					MethodSymbol methodSymbol = new MethodSymbol(Flags.PUBLIC, tree.name, tree.type, _interface);
					// methodSymbol.params = tree.sym.params;
					_interface.members().enter(methodSymbol);
					methodDeclaredSb.append(tree);
					// 代码转换：if (Object.class.isAssignableFrom(clazz)
					methodVisitSb.append("if (").append(className(ms.params.head.type)).append(".isAssignableFrom(clazz)) ");
					// 代码转换：myClass.visitEmitMethod("acceptItem", new Class<?>[]{Building.class, Building.class, Item.class}, boolean.class, className);
					methodVisitSb.append(var_myClas).append(".").append(NAME_VISIT)
					 .append("(\"").append(ms.name).append("\", new Class<?>[]{")
					 .append(ms.params.stream().map(this::className).collect(Collectors.joining(", "))).append("}, ")
					 .append(className(ms.getReturnType())).append(", ").append(var_class).append(");\n");

					if (openPackagePrivate) {
						packagePrivateSb.append("if (").append(className(ms.params.head.type)).append(".isAssignableFrom(clazz)) ");
						// 代码转换：myClass.setFunc("acceptItem", (Func2) null, Modifier.PUBLIC, boolean.class, Building.class, Building.class, Item.class)
						packagePrivateSb.append(var_myClas).append(".").append(NAME_SET_FUNC)
						 .append("(\"").append(ms.name).append("\", (Func2) null, Modifier.PUBLIC, ")
						 .append(className(ms.getReturnType())).append(", ")
						 .append(ms.params.stream().skip(1).map(this::className).collect(Collectors.joining(", "))).append(");\n");
					}

					tree.mods = prevmods;
					tree.body = prevbody;
					tree.name = prevname;
					tree.params = prevparams;
				}
			}

			String myclassInit = openPackagePrivate ? "new MyClass(ByteCodeTools.legalName(clazz.getName()) + \"i\", " + var_myClas + ".define())" : "new MyClass(" + var_myClas + ".define()/* 使类public化 */, \"i\")";
			writer.append(STR."""
package \{owner.packge().getQualifiedName().toString()};

\{unit.getImports().stream().reduce("", (a, b) -> a + b, (a, b) -> a + b)}
import arc.func.Func2;
import modtools.utils.ByteCodeTools;
import modtools.utils.ByteCodeTools.MyClass;
import modtools.utils.Tools;
import modtools.utils.reflect.ClassUtils;

import java.lang.reflect.Modifier;
import java.util.*;

public interface \{ownerName}\{INTERFACE_SUFFIX} {

\{methodDeclaredSb}

Map<Class<?>, Class<?>> cache = new HashMap<>();
public static Class<?> visit(Class<?> clazz) {
	if (clazz == Object.class) clazz = ClassUtils.forName("\{sample.defaultClass()}");
  if (cache.containsKey(clazz)) {
    return cache.get(clazz);
  }

  var \{var_myClas} = new MyClass(clazz, "\{GEN_CLASS_NAME_SUFFIX}");
  \{packagePrivateSb}
  \{var_myClas} = \{myclassInit};
  \{methodVisitSb}
  // \{var_myClas}.visit(\{ownerName}.class);
  \{var_myClas}.addInterface(\{ownerName}\{INTERFACE_SUFFIX}.class);
  var newClass = \{var_myClas}.define(\{ownerName}.class);
  cache.put(clazz, newClass);
  return newClass;
}

public static <T> T changeClass(T obj) {
	Class<?> clazz = visit(obj.getClass());
	return Tools.newInstance(obj, clazz);
}

}""");
			writer.close();
			_interface.sourcefile = file;
			_interface.complete();

			interfaces.put(owner, _interface);
		}

		SampleForMethod annotation = getAnnotationByElement(SampleForMethod.class, element, true);


		JCMethodDecl methodDecl = trees.getTree(element);
		new TreeTranslator() {
			public JCMethodInvocation _super;
			public void visitApply(JCMethodInvocation tree) {
				if (tree == _super) {
					_super = null;
					if (!(tree.args.get(0) instanceof JCIdent i && i.name.contentEquals(element.params.get(0).name))) {
						log.useSource(unit.sourcefile);
						log.error("_super is only allowed to be used as '_super(" + element.params.get(0).name + ")'.");
						return;
					}
					// println(tree);
					result = mMaker.TypeCast(mMaker.Type(interfaces.get(owner).type), tree.args.get(0));
					return;
				}

				if (tree.meth instanceof JCFieldAccess fa && fa.selected instanceof JCMethodInvocation mi && getSymbol(unit, mi) == _superCall) {
					_super = mi;
					super.visitApply(tree);
					fa.name = names.fromString(SUPER_METHOD_PREFIX + fa.name);
					fa.sym = interfaces.get(owner).members().findFirst(fa.name);
				} else {
					super.visitApply(tree);
				}
			}
		}.translate(methodDecl);
		// println(methodDecl);
	}
	private String className(VarSymbol v) {
		return className(v.type);
	}
	private String className(Type type) {
		if (!(type instanceof ArrayType arrayType)) {
			return type.tsym.name + ".class";
		}

		int depth = 1;
		while (arrayType.elemtype instanceof ArrayType) {
			arrayType = (ArrayType) arrayType.elemtype;
			depth++;
		}
		// println(arrayType.elemtype);
		return "Object" + "[]".repeat(depth) + ".class";
	}
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(SampleForMethod.class);
	}
}
