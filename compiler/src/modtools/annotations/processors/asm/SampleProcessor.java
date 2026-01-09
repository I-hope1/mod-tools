package modtools.annotations.processors.asm;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.*;
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

@AutoService({Processor.class})
public class SampleProcessor extends BaseProcessor<Symbol> {

	public static final String NAME_VISIT       = "visitEmitMethod";
	public static final String NAME_VISIT_FIELD = "visitEmitField";
	public static final String NAME_SET_FUNC    = "setFunc";
	public static final String _SUPER           = "_super";

	private       MethodSymbol                  superCallSymbol;
	private final Map<ClassSymbol, ClassSymbol> interfaceCache = new HashMap<>();

	@Override
	public void lazyInit() {
		ClassSymbol tempClass = findClassSymbol("modtools.annotations.asm.Sample$SampleTemp");
		superCallSymbol = findChild(tempClass, _SUPER, ElementKind.METHOD);
	}

	@Override
	public void dealElement(Symbol element) throws Throwable {
		if (!(element instanceof MethodSymbol methodSym)) return;

		ClassSymbol owner  = (ClassSymbol) methodSym.owner;
		Sample      sample = owner.getAnnotation(Sample.class);
		if (sample == null) {
			log.error(trees.getTree(methodSym).mods, SPrinter.err(
			 "@SampleForMethod / @SampleForInitializer is only allowed on methods within a class annotated with @Sample"
			));
			return;
		}

		JCCompilationUnit unit = (JCCompilationUnit) trees.getPath(methodSym).getCompilationUnit();

		// 确保接口已生成
		ClassSymbol generatedInterface = interfaceCache.computeIfAbsent(owner,
		 k -> makeInterface(owner, sample.openPackagePrivate(), unit, sample));

		// 转换 AST，处理 self 强转逻辑和 _super 调用
		transformMethodBody(methodSym, generatedInterface, unit);
	}

	/**
	 * 转换方法体：将 self 引用强转为生成的接口，并处理特殊的 _super 语法
	 */
	private void transformMethodBody(MethodSymbol methodSym, ClassSymbol interfaceSym, JCCompilationUnit unit) {
		JCMethodDecl methodDecl = trees.getTree(methodSym);
		VarSymbol    selfParam  = methodSym.params.head;

		new TreeTranslator() {
			private JCMethodInvocation currentSuperContext;

			private JCExpression castSelf() {
				return mMaker.TypeCast(mMaker.Type(interfaceSym.type), mMaker.Ident(selfParam));
			}

			@Override
			public void visitApply(JCMethodInvocation tree) {
				// 处理 _super(self) 的替换
				if (tree == currentSuperContext) {
					currentSuperContext = null;
					if (!(tree.args.get(0) instanceof JCIdent ident && ident.name.contentEquals(selfParam.name))) {
						log.error(tree.pos, SPrinter.err("_super 必须以 '_super(" + selfParam.name + ")' 形式使用"));
						return;
					}
					this.result = castSelf();
					return;
				}

				// 识别形如 _super(self).method() 的调用
				if (tree.meth instanceof JCFieldAccess fieldAccess &&
				    fieldAccess.selected instanceof JCMethodInvocation subInvoke &&
				    _SUPER.equals("" + subInvoke.meth) &&
				    getSymbol(unit, subInvoke) == superCallSymbol) {

					currentSuperContext = subInvoke;
					super.visitApply(tree);

					// 修正方法名为 super$$Name
					fieldAccess.selected = castSelf();
					fieldAccess.name = names.fromString(AConstants.SUPER_METHOD_PREFIX + fieldAccess.name);
					fieldAccess.sym = interfaceSym.members().findFirst(fieldAccess.name);
					// 添加super接口方法

					// println(fieldAccess);
				} else {
					super.visitApply(tree);
				}
			}

			@Override
			public void visitIdent(JCIdent tree) {
				super.visitIdent(tree);
				// 自动将带有 @SampleForAccess 的字段访问转为 getter 调用
				if (tree.sym instanceof VarSymbol varSym && getAnnotationByElement(SampleForAccess.class, varSym, true) != null) {
					this.result = mMaker.Apply(List.nil(), mMaker.Select(castSelf(), varSym.name), List.nil());
				}
			}

			@Override
			public void visitAssign(JCAssign tree) {
				super.visitAssign(tree);
				// 自动将带有 @SampleForAccess 的字段赋值转为 setter 调用
				if (tree.lhs instanceof JCIdent ident && ident.sym instanceof VarSymbol varSym &&
				    getAnnotationByElement(SampleForAccess.class, varSym, true) != null) {
					this.result = mMaker.Apply(List.nil(), mMaker.Select(castSelf(), varSym.name), List.of(tree.rhs));
				}
			}
		}.translate(methodDecl);
	}

	/**
	 * 生成接口源码及其对应的 ClassSymbol
	 */
	private ClassSymbol makeInterface(ClassSymbol owner, boolean openPkgPriv, JCCompilationUnit unit, Sample sample) {
		String ownerName     = owner.name.toString();
		String interfaceName = ownerName + AConstants.INTERFACE_SUFFIX;

		JavaFileObject sourceFile;
		try {
			sourceFile = mFiler.createSourceFile(owner.getQualifiedName() + AConstants.INTERFACE_SUFFIX, owner);
		} catch (IOException e) {
			ClassSymbol existing = mSymtab.enterClass(owner.packge().modle, names.fromString(interfaceName));
			if (existing != null && existing.exists()) return existing;
			throw new RuntimeException(e);
		}

		ClassSymbol interfaceSym = new ClassSymbol(Flags.PUBLIC | Flags.INTERFACE, names.fromString(interfaceName), owner.owner);
		interfaceSym.members_field = WriteableScope.create(interfaceSym);

		CodeBuffer buffer = new CodeBuffer(ownerName);

		Set<MethodSymbol> superMethodsToBridge = new LinkedHashSet<>();
		for (Symbol s : owner.members().getSymbols()) {
			if (s instanceof MethodSymbol ms) {
				findSuperCallsInMethod(ms, unit, superMethodsToBridge);
			}
		}
		Set<MethodSymbol> sampleMethods = new LinkedHashSet<>();
		for (Symbol member : owner.members().getSymbols()) {
			processMember(member, interfaceSym, buffer, sampleMethods, openPkgPriv);
		}
		/* Set<MethodType> methodTypes =  new LinkedHashSet<>();
		for (MethodSymbol symbol : superMethodsToBridge) {
			methodTypes.add((MethodType) symbol.type);
		}
		for (MethodSymbol symbol : sampleMethods) {
			methodTypes.add(new MethodType(symbol.params.tail.map(p -> p.type), symbol.getReceiverType(), symbol.getThrownTypes(), mSymtab.methodClass));
		} */

		for (MethodSymbol superMethod : superMethodsToBridge) {
			boolean isEssentiallySample = sampleMethods.stream()
			 .anyMatch(sm -> isEquivalent(sm, superMethod));

			// 如果本质是 sampleMethods 有的，跳过 bridge 执行
			addSuperBridgeToInterface(superMethod, interfaceSym, buffer, false, isEssentiallySample);
		}
		for (MethodSymbol superMethod : sampleMethods) {
			addSuperBridgeToInterface(superMethod, interfaceSym, buffer, true, true);
		}

		String source = generateInterfaceSource(owner, unit, sample, buffer);
		writeSource(sourceFile, source);

		interfaceSym.sourcefile = sourceFile;
		interfaceSym.complete();
		return interfaceSym;
	}
	/**
	 * 判断 Sample 静态方法是否等效于某个实例方法
	 * @param sampleMethod Sample 类中的静态方法 (self, p1, p2...)
	 * @param superMethod  目标类中的实例方法 (p1, p2...)
	 */
	private boolean isEquivalent(MethodSymbol sampleMethod, MethodSymbol superMethod) {
		if (!sampleMethod.name.equals(superMethod.name)) return false;

		// Sample 方法必须至少有一个 self 参数
		if (sampleMethod.params.isEmpty()) return false;

		List<VarSymbol> sParams = sampleMethod.params.tail; // 去掉 self
		List<VarSymbol> mParams = superMethod.params;

		if (sParams.size() != mParams.size()) return false;

		// 比对参数类型（使用 erasure 避免泛型干扰）
		for (int i = 0; i < sParams.size(); i++) {
			if (!types.isSameType(types.erasure(sParams.get(i).type), types.erasure(mParams.get(i).type))) {
				return false;
			}
		}

		// 比对返回类型
		return types.isSameType(types.erasure(sampleMethod.getReturnType()), types.erasure(superMethod.getReturnType()));
	}

	private void processMember(Symbol member, ClassSymbol interfaceSym, CodeBuffer buffer,
	                           Set<MethodSymbol> sampleMethods, boolean openPkgPriv) {
		SampleForMethod      sm = getAnnotationByElement(SampleForMethod.class, member, true);
		SampleForInitializer si = getAnnotationByElement(SampleForInitializer.class, member, true);
		SampleForAccess      sa = getAnnotationByElement(SampleForAccess.class, member, true);

		if (sm == null && si == null && sa == null) return;

		if (member instanceof MethodSymbol methodSym) {
			handleMethodMember(methodSym, interfaceSym, buffer, sm, si, openPkgPriv);
			sampleMethods.add(methodSym);
		} else if (member instanceof VarSymbol varSym) {
			handleFieldMember(varSym, interfaceSym, buffer);
		}
	}

	private void handleMethodMember(MethodSymbol methodSym, ClassSymbol interfaceSym, CodeBuffer buffer,
	                                SampleForMethod sm, SampleForInitializer si, boolean openPkgPriv) {
		if (methodSym.params.isEmpty()) {
			log.error(trees.getTree(methodSym).mods, SPrinter.err("方法的参数列表不能为空"));
			return;
		}

		JCMethodDecl tree = trees.getTree(methodSym);

		// 临时修改 AST 以便利用 tree.toString() 生成接口方法签名
		List<JCVariableDecl> oldParams      = tree.params;
		JCBlock              oldBody        = tree.body;
		var                  oldAnnotations = tree.mods.annotations;
		tree.mods.flags &= ~Flags.STATIC; // 移除 static
		tree.params = tree.params.tail; // 移除第一个参数 (self)
		tree.body = null; // 移除方法体
		tree.mods.annotations = List.nil(); // 移除注解
		buffer.interfaceMethods.append("    ").append(tree.toString().replace(";", "")).append(";\n");
		tree.mods.flags |= Flags.STATIC;
		tree.params = oldParams;
		tree.body = oldBody;
		tree.mods.annotations = oldAnnotations;

		// 生成访问代码 (Visit Code)
		String firstParamType = BaseASMProc.classAccess(methodSym.params.head.type);
		buffer.visitLogic.append(STR."if (\{firstParamType}.isAssignableFrom(clazz)) ");

		Class<?>[] bounds = (sm != null) ? sm.upperBoundClasses() : si.upperBoundClasses();
		if (bounds.length > 0) {
			String check = Arrays.stream(bounds)
			 .map(c -> "clazz.isAssignable(" + c.getSimpleName() + ".class)")
			 .collect(Collectors.joining(" || ", "if (", ") "));
			buffer.visitLogic.append(check);
		}

		Name   targetName = (si != null) ? names.init : methodSym.name;
		String paramTypes = methodSym.params.stream().map(BaseASMProc::classAccess).collect(Collectors.joining(", "));

		buffer.visitLogic.append(STR."myClass.\{NAME_VISIT}(\"\{targetName}\", ");
		if (si != null) buffer.visitLogic.append(STR."\"\{methodSym.name}\", ");
		buffer.visitLogic.append(STR."new Class<?>[]{\{paramTypes}}, \{BaseASMProc.classAccess(methodSym.getReturnType())}, className);\n");

		// 处理 Package-Private 开放
		if (openPkgPriv) {
			String subParams = methodSym.params.stream().skip(1).map(BaseASMProc::classAccess).collect(Collectors.joining(", "));
			buffer.packagePrivateLogic.append(STR."    if (\{firstParamType}.isAssignableFrom(clazz)) ");
			buffer.packagePrivateLogic.append(STR."myClass.\{NAME_SET_FUNC}(\"\{targetName}\", (Func2) null, Modifier.PUBLIC, \{BaseASMProc.classAccess(methodSym.getReturnType())}, \{subParams});\n");
		}
	}

	private void handleFieldMember(VarSymbol varSym, ClassSymbol interfaceSym, CodeBuffer buffer) {
		String typeName  = BaseASMProc.classAccess(varSym.type);
		String fieldName = varSym.name.toString();

		// Getter
		MethodSymbol getter = new MethodSymbol(Flags.PUBLIC, varSym.name,
		 new MethodType(List.nil(), varSym.type, List.nil(), mSymtab.methodClass), interfaceSym);
		interfaceSym.members().enter(getter);
		buffer.interfaceMethods.append(STR."    \{typeName} \{fieldName}();\n");

		// Setter
		MethodSymbol setter = new MethodSymbol(Flags.PUBLIC, varSym.name,
		 new MethodType(List.of(varSym.type), mSymtab.voidType, List.nil(), mSymtab.methodClass), interfaceSym);
		interfaceSym.members().enter(setter);
		buffer.interfaceMethods.append(STR."    void \{fieldName}(\{typeName} value);\n");

		// Visit Field
		buffer.visitLogic.append(STR."    myClass.\{NAME_VISIT_FIELD}(\"\{fieldName}\", \{typeName}.class);\n");
	}
	/**
	 * 为 super 调用生成接口声明和字节码桥接逻辑
	 */
	private void addSuperBridgeToInterface(MethodSymbol targetMethod, ClassSymbol interfaceSym,
	                                       CodeBuffer buffer, boolean stripFirst, boolean skipBridge) {
		String superMethodName = AConstants.SUPER_METHOD_PREFIX + targetMethod.name.toString();

		// 避免重复生成
		if (interfaceSym.members().findFirst(ns(superMethodName)) != null) return;

		// 向接口 Symbol Table 添加方法
		MethodSymbol bridgeSym = new MethodSymbol(Flags.PUBLIC, ns(superMethodName), targetMethod.type, interfaceSym);
		interfaceSym.members().enter(bridgeSym);

		// 生成接口源码声明
		List<VarSymbol> params1 = targetMethod.params;
		if (stripFirst) {
			params1 = params1.tail;
			// println(params1);
		}
		String returnType = BaseASMProc.className(types.erasure(targetMethod.getReturnType()));
		String params = params1.stream()
		 .map(p -> BaseASMProc.className(types.erasure(p.type)) + " " + p.name)
		 .collect(Collectors.joining(", "));
		String throw_str = targetMethod.type.getThrownTypes().isEmpty() ? "" :
		 " throws " + targetMethod.type.getThrownTypes().stream()
			.map(t -> BaseASMProc.className(types.erasure(t)))
			.collect(Collectors.joining(", "));
		buffer.interfaceMethods.append(STR."  \{returnType} \{superMethodName}(\{params})\{throw_str};\n");


		// 生成 visit 逻辑中的字节码桥接调用: myClass.buildSuperFunc("super$$onKey", "onKey", void.class, View.class, ...)
		if (!skipBridge) {
			String paramClasses = params1.stream()
			 .map(p -> BaseASMProc.classAccess(types.erasure(p.type)))
			 .collect(Collectors.joining(", "));

			// 注意：如果是从 sampleMethods 来的，targetMethod.owner 是 Sample 类，
			// 但 buildSuperFunc 的第三个参数应该是被注入类的基类（即 targetMethod.params.head.type）
			String ownerClassAccess = stripFirst ?
			 BaseASMProc.classAccess(targetMethod.params.head.type) :
			 BaseASMProc.classAccess(targetMethod.owner.type);

			buffer.visitLogic.append(STR."        myClass.buildSuperFunc(\"\{superMethodName}\", \"\{targetMethod.name}\", \{ownerClassAccess}, \{BaseASMProc.classAccess(targetMethod.getReturnType())}\{paramClasses.isEmpty() ? "" : ", " + paramClasses});\n");
		}
	}
	/**
	 * 扫描方法体，寻找 _super(self).methodName() 并记录 methodName 的 Symbol
	 */
	private void findSuperCallsInMethod(MethodSymbol ms, JCCompilationUnit unit, Set<MethodSymbol> results) {
		JCMethodDecl tree = trees.getTree(ms);
		if (tree == null || tree.body == null) return;

		tree.accept(new TreeScanner() {
			@Override
			public void visitApply(JCMethodInvocation tree) {
				if (tree.meth instanceof JCFieldAccess fieldAccess &&
				    fieldAccess.selected instanceof JCMethodInvocation subInvoke &&
				    _SUPER.equals("" + subInvoke.meth) &&
				    getSymbol(unit, subInvoke) == superCallSymbol) {

					if (fieldAccess.sym instanceof MethodSymbol targetMethod) {
						results.add(targetMethod);
					}
				}
				super.visitApply(tree);
			}
		});
	}

	private String generateInterfaceSource(ClassSymbol owner, JCCompilationUnit unit, Sample sample, CodeBuffer buffer) {
		String pkg           = owner.packge().getQualifiedName().toString();
		String imports       = unit.getImports().stream().map(Objects::toString).collect(Collectors.joining());
		String ownerName     = owner.name.toString();
		String interfaceName = ownerName + AConstants.INTERFACE_SUFFIX;

		String defaultClassLogic = sample.defaultClass().isBlank() ? "" :
		 STR."if (clazz == Object.class) clazz = ClassUtils.forName(\"\{sample.defaultClass()}\");";

		String myClassInit = sample.openPackagePrivate()
		 ? STR."new MyClass(Sample.AConstants.legalName(clazz.getName()) + \"i\", myClass.define())"
		 : "new MyClass(myClass.define(), \"i\")";

		return STR."""
package \{pkg};

\{imports}
import arc.func.Func2;
import arc.func.Cons;
import modtools.utils.ByteCodeTools;
import modtools.utils.ByteCodeTools.MyClass;
import modtools.utils.Tools;
import modtools.utils.reflect.ClassUtils;
import java.lang.reflect.*;
import java.util.*;

public interface \{interfaceName} {

\{buffer.interfaceMethods}

    Map<Class<?>, Class<?>> cache = new HashMap<>();

    static Class<?> visit(Class<?> clazz) {
        return visit(clazz, null);
    }

    static Class<?> visit(Class<?> clazz, Cons<MyClass<?>> builder) {
        \{defaultClassLogic}
        if (cache.containsKey(clazz)) return cache.get(clazz);
        if (\{interfaceName}.class.isAssignableFrom(clazz)) return clazz;

        var myClass = new MyClass(clazz, "\{AConstants.GEN_CLASS_NAME_SUFFIX}");
        \{buffer.packagePrivateLogic}
        if (builder != null) builder.get(myClass);

        String className = ByteCodeTools.nativeName(\{ownerName}.class);
        myClass = \{myClassInit};
        \{buffer.visitLogic}
        myClass.addInterface(\{interfaceName}.class);

        if (builder != null) builder.get(myClass);
        var newClass = myClass.define(\{ownerName}.class);
        cache.put(clazz, newClass);
        return newClass;
    }

    static <T> T changeClass(T obj) {
        return Tools.newInstance(obj, visit(obj.getClass()));
    }

    @SuppressWarnings("unchecked")
    static <T> T newInstance(Class<T> c) {
        try {
            return (T) visit(c).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
""";
	}

	private void writeSource(JavaFileObject file, String content) {
		try (Writer writer = file.openWriter()) {
			writer.write(content);
		} catch (IOException e) {
			throw new RuntimeException("Failed to write generated source", e);
		}
	}

	/**
	 * 辅助类：用于暂存生成的各部分代码块
	 */
	private static class CodeBuffer {
		final StringBuilder interfaceMethods    = new StringBuilder();
		final StringBuilder packagePrivateLogic = new StringBuilder();
		final StringBuilder visitLogic          = new StringBuilder();

		CodeBuffer(String ownerName) {
			// 初始化 visit 逻辑
		}
	}

	@Override
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(SampleForMethod.class, SampleForAccess.class);
	}
}