package hope.magic.compiler;

import com.google.auto.service.AutoService;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.util.List;
import hope.magic.annotation.AccessMode;
import hope.magic.annotation.HField;
import hope.magic.annotation.HMarkMagic;
import hope.magic.annotation.HMethod;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static hope.magic.compiler.TypeUtils.*;

@AutoService(Processor.class)
@SupportedAnnotationTypes({
	"hope.magic.annotation.HField",
	"hope.magic.annotation.HMethod"
})
public class AccessorProcessor extends BaseAccessorProc {
	private static final AtomicInteger ID_GEN = new AtomicInteger(0);

	private final Map<Symbol, ClassWriter> classWriterMap = new LinkedHashMap<>();
	private final Map<ClassWriter, String> classNamesMap = new LinkedHashMap<>();
	private final Map<ClassWriter, java.util.List<Consumer<MethodVisitor>>> clinitInits = new LinkedHashMap<>();
	private int methodId = 0;

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		if (roundEnv.processingOver()) {
			return false;
		}

		classWriterMap.clear();
		classNamesMap.clear();
		clinitInits.clear();
		methodId = 0;

		for (TypeElement annotation : annotations) {
			for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
				if (!(element instanceof MethodSymbol methodSymbol)) {
					continue;
				}

				try {
					TreePath path = trees.getPath(element);
					if (path != null) {
						log.useSource(path.getCompilationUnit().getSourceFile());
						mMaker.toplevel = (JCCompilationUnit) path.getCompilationUnit();
					}

					dealElement(methodSymbol);
				} catch (Throwable e) {
					messager.printMessage(Diagnostic.Kind.ERROR, "处理失败: " + e.getMessage(), element);
				} finally {
					mMaker.toplevel = null;
					log.useSource(null);
				}
			}
		}

		// 为包含 static 初始化任务的类生成 <clinit>
		for (Map.Entry<ClassWriter, java.util.List<Consumer<MethodVisitor>>> entry : clinitInits.entrySet()) {
			ClassWriter cw = entry.getKey();
			java.util.List<Consumer<MethodVisitor>> inits = entry.getValue();
			if (!inits.isEmpty()) {
				MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
				mv.visitCode();
				for (Consumer<MethodVisitor> init : inits) {
					init.accept(mv);
				}
				mv.visitInsn(Opcodes.RETURN);
				mv.visitMaxs(0, 0);
				mv.visitEnd();
			}
		}

		// 写入所有生成的字节码
		for (Map.Entry<Symbol, ClassWriter> entry : classWriterMap.entrySet()) {
			ClassWriter writer = entry.getValue();
			String className = classNamesMap.get(writer);
			try {
				writeClassBytes(mFiler.createClassFile(className, entry.getKey()), writer.toByteArray());
			} catch (Throwable e) {
				messager.printMessage(Diagnostic.Kind.ERROR, "无法写入生成的类文件 " + className + ": " + e.getMessage(), entry.getKey());
			}
		}

		return true;
	}

	private void dealElement(MethodSymbol element) {
		HField hField = element.getAnnotation(HField.class);
		if (hField != null) {
			AccessMode mode = resolveMode(element, hField.mode());
			String magicSuperClass = getMagicSuperClassName(element.owner);
			ClassWriter cw = getOrCreateClassWriter(element, mode, magicSuperClass);
			if (mode == AccessMode.MAGIC_ACCESSOR) {
				processFieldMagic(element, hField, cw);
			} else {
				processFieldUnsafe(element, hField, cw);
			}
			return;
		}

		HMethod hMethod = element.getAnnotation(HMethod.class);
		if (hMethod != null) {
			AccessMode mode = resolveMode(element, hMethod.mode());
			String magicSuperClass = getMagicSuperClassName(element.owner);
			ClassWriter cw = getOrCreateClassWriter(element, mode, magicSuperClass);
			if (mode == AccessMode.MAGIC_ACCESSOR) {
				processMethodMagic(element, hMethod, cw);
			} else if (mode == AccessMode.UNSAFE_AND_METHODHANDLE) {
				processMethodHandle(element, hMethod, cw);
			} else {
				// UNSAFE_AND_INDY (默认及 UNSAFE_AND_LINKTO 兼容)
				processMethodIndy(element, hMethod, cw);
			}
		}
	}

	private AccessMode resolveMode(MethodSymbol element, AccessMode memberMode) {
		if (memberMode != null && memberMode != AccessMode.AUTO) {
			return memberMode;
		}
		HMarkMagic mark = element.owner.getAnnotation(HMarkMagic.class);
		if (mark != null && mark.mode() != AccessMode.AUTO) {
			return mark.mode();
		}
		return AccessMode.UNSAFE_AND_INDY;
	}

	private ClassWriter getOrCreateClassWriter(MethodSymbol element, AccessMode mode, String magicSuperClass) {
		if (classWriterMap.containsKey(element.owner)) {
			return classWriterMap.get(element.owner);
		}

		ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		String genClassName = "hope.magic.gen.MagicGenX" + ID_GEN.incrementAndGet();
		String superClassName = (mode == AccessMode.MAGIC_ACCESSOR && magicSuperClass != null)
			? magicSuperClass.replace('.', '/')
			: "java/lang/Object";

		classWriter.visit(
			Opcodes.V1_8,
			Opcodes.ACC_PUBLIC,
			genClassName.replace('.', '/'),
			null,
			superClassName,
			null
		);

		// 默认构造器
		MethodVisitor mv = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superClassName, "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();

		classWriterMap.put(element.owner, classWriter);
		classNamesMap.put(classWriter, genClassName);
		clinitInits.put(classWriter, new ArrayList<>());
		return classWriter;
	}

	// ======================== 方案 1: MagicAccessorImpl (经典特权方案) ========================

	private void processFieldMagic(MethodSymbol methodSymbol, HField hField, ClassWriter classWriter) {
		DocReference reference = getSeeReference(HField.class, methodSymbol, ElementKind.FIELD);
		if (reference == null) return;

		VarSymbol target = (VarSymbol) reference.element();
		boolean isGetter = hField.isGetter();
		boolean isStatic = target.isStatic();
		JCMethodDecl methodDecl = trees.getTree(methodSymbol);

		if (!types.isSameType(methodSymbol.getReturnType(), (isGetter ? target.type : mSymtab.voidType))) {
			messager.printMessage(Diagnostic.Kind.ERROR, "字段类型与访问器返回类型不匹配: " + (isGetter ? target.type : "void") + " != " + methodSymbol.getReturnType(), methodSymbol);
			return;
		}

		String methodDesc = '(' +
			(isStatic ? "" : typeToDescriptor(target.owner.type)) +
			(isGetter ? ")" + typeToDescriptor(target.type) : typeToDescriptor(target.type) + ")V");

		String genMethodName = "x" + (methodId++);
		MethodVisitor mv = classWriter.visitMethod(
			Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
			genMethodName,
			methodDesc,
			null,
			null
		);

		String owner = dotToSlash(target.owner.type);
		String descriptor = typeToDescriptor(target.type);

		if (!isStatic) mv.visitVarInsn(Opcodes.ALOAD, 0);

		if (isGetter) {
			mv.visitFieldInsn(isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD, owner, target.name.toString(), descriptor);
			mv.visitInsn(returnOpcode(target.type));
		} else {
			if (!isStatic) {
				mv.visitVarInsn(Opcodes.ALOAD, 0);
				mv.visitVarInsn(loadOpcode(target.type.tsym), 1);
			} else {
				mv.visitVarInsn(loadOpcode(target.type.tsym), 0);
			}
			mv.visitFieldInsn(isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD, owner, target.name.toString(), descriptor);
			mv.visitInsn(Opcodes.RETURN);
		}
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		rewriteMethodBody(methodDecl, classNamesMap.get(classWriter), genMethodName, isGetter);
	}

	private void processMethodMagic(MethodSymbol methodSymbol, HMethod hMethod, ClassWriter classWriter) {
		DocReference reference = getSeeReference(HMethod.class, methodSymbol, ElementKind.METHOD, ElementKind.CONSTRUCTOR);
		if (reference == null) return;

		MethodSymbol targetMethod = (MethodSymbol) reference.element();
		JCMethodDecl methodDecl = trees.getTree(methodSymbol);
		String genMethodName = "x" + (methodId++);

		if (targetMethod.isStatic()) {
			if (hMethod.isSpecial()) {
				messager.printMessage(Diagnostic.Kind.ERROR, "静态方法不能标注 isSpecial = true", methodSymbol);
				return;
			}

			List<TypeSymbol> args = targetMethod.getParameters().map(v -> v.type.tsym);
			String methodDesc = targetMethod.getParameters().stream()
				.map(v -> typeToDescriptor(v.type))
				.collect(Collectors.joining("", "(", ")"))
				+ typeToDescriptor(methodSymbol.getReturnType());

			MethodVisitor mv = classWriter.visitMethod(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				genMethodName,
				methodDesc,
				null,
				null
			);
			String owner = dotToSlash(targetMethod.owner.type);
			int slot = 0;
			for (TypeSymbol arg : args) {
				mv.visitVarInsn(loadOpcode(arg), slot);
				slot += typeSize(arg, mSymtab);
			}
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, targetMethod.name.toString(), methodDesc, false);
			mv.visitInsn(returnOpcode(methodSymbol.getReturnType()));
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		} else {
			List<TypeSymbol> paramsL = methodSymbol.params.map(v -> v.type.tsym);
			String methodDesc = paramsL.stream().map(v -> typeToDescriptor(v.type)).collect(Collectors.joining("", "(", ")")) +
				typeToDescriptor(methodSymbol.getReturnType());

			MethodVisitor mv = classWriter.visitMethod(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				genMethodName,
				methodDesc,
				null,
				null
			);
			String owner = dotToSlash(targetMethod.owner.type);
			String descriptor = typeToDescriptor(methodSymbol.getReturnType());
			int slot = 0;
			for (TypeSymbol typeSymbol : paramsL) {
				mv.visitVarInsn(loadOpcode(typeSymbol), slot);
				slot += typeSize(typeSymbol, mSymtab);
			}

			boolean isInterface = targetMethod.owner.isInterface();
			mv.visitMethodInsn(
				hMethod.isSpecial() ? Opcodes.INVOKESPECIAL : (isInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL),
				owner,
				targetMethod.name.toString(),
				targetMethod.getParameters().stream().map(v -> typeToDescriptor(v.type)).collect(Collectors.joining("", "(", ")")) + descriptor,
				isInterface
			);
			mv.visitInsn(returnOpcode(methodSymbol.getReturnType()));
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		rewriteMethodBody(methodDecl, classNamesMap.get(classWriter), genMethodName, methodDecl.getReturnType().type.getKind() != TypeKind.VOID);
	}

	// ======================== 方案 2: Unsafe 字段访问 ========================

	private void processFieldUnsafe(MethodSymbol methodSymbol, HField hField, ClassWriter classWriter) {
		DocReference reference = getSeeReference(HField.class, methodSymbol, ElementKind.FIELD);
		if (reference == null) return;

		VarSymbol target = (VarSymbol) reference.element();
		boolean isGetter = hField.isGetter();
		boolean isStatic = target.isStatic();
		JCMethodDecl methodDecl = trees.getTree(methodSymbol);

		if (!types.isSameType(methodSymbol.getReturnType(), (isGetter ? target.type : mSymtab.voidType))) {
			messager.printMessage(Diagnostic.Kind.ERROR, "字段类型与访问器返回类型不匹配: " + (isGetter ? target.type : "void") + " != " + methodSymbol.getReturnType(), methodSymbol);
			return;
		}

		String genClassName = classNamesMap.get(classWriter);
		String genMethodName = "x" + (methodId++);
		String offsetFieldName = "OFF_" + methodId;
		String baseFieldName = "BASE_" + methodId;

		// 1. 添加静态字段存放 offset 和 base
		classWriter.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, offsetFieldName, "J", null, null).visitEnd();
		if (isStatic) {
			classWriter.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, baseFieldName, "Ljava/lang/Object;", null, null).visitEnd();
		}

		// 2. 注册 <clinit> 初始化
		clinitInits.get(classWriter).add(mv -> {
			pushClass(mv, target.owner.type);
			mv.visitLdcInsn(target.name.toString());
			if (isStatic) {
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, "hope/magic/runtime/LinkerHelper", "getStaticFieldOffset", "(Ljava/lang/Class;Ljava/lang/String;)J", false);
				mv.visitFieldInsn(Opcodes.PUTSTATIC, genClassName.replace('.', '/'), offsetFieldName, "J");

				pushClass(mv, target.owner.type);
				mv.visitLdcInsn(target.name.toString());
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, "hope/magic/runtime/LinkerHelper", "getStaticFieldBase", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;", false);
				mv.visitFieldInsn(Opcodes.PUTSTATIC, genClassName.replace('.', '/'), baseFieldName, "Ljava/lang/Object;");
			} else {
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, "hope/magic/runtime/LinkerHelper", "getFieldOffset", "(Ljava/lang/Class;Ljava/lang/String;)J", false);
				mv.visitFieldInsn(Opcodes.PUTSTATIC, genClassName.replace('.', '/'), offsetFieldName, "J");
			}
		});

		// 3. 生成访问器方法
		String methodDesc = '(' +
			(isStatic ? "" : typeToDescriptor(target.owner.type)) +
			(isGetter ? ")" + typeToDescriptor(target.type) : typeToDescriptor(target.type) + ")V");

		MethodVisitor mv = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, genMethodName, methodDesc, null, null);
		mv.visitCode();

		mv.visitFieldInsn(Opcodes.GETSTATIC, "hope/magic/runtime/LinkerHelper", "UNSAFE", "Lsun/misc/Unsafe;");
		if (isStatic) {
			mv.visitFieldInsn(Opcodes.GETSTATIC, genClassName.replace('.', '/'), baseFieldName, "Ljava/lang/Object;");
		} else {
			mv.visitVarInsn(Opcodes.ALOAD, 0); // target obj
		}
		mv.visitFieldInsn(Opcodes.GETSTATIC, genClassName.replace('.', '/'), offsetFieldName, "J");

		if (isGetter) {
			String unsafeGetter = unsafeGetterMethodName(target.type);
			String unsafeDesc = unsafeGetterMethodDesc(target.type);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "sun/misc/Unsafe", unsafeGetter, unsafeDesc, false);
			if (unsafeGetter.equals("getObject")) {
				mv.visitTypeInsn(Opcodes.CHECKCAST, dotToSlash(target.type));
			}
			mv.visitInsn(returnOpcode(target.type));
		} else {
			int valSlot = isStatic ? 0 : 1;
			mv.visitVarInsn(loadOpcode(target.type.tsym), valSlot);
			String unsafeSetter = unsafeSetterMethodName(target.type);
			String unsafeDesc = unsafeSetterMethodDesc(target.type);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "sun/misc/Unsafe", unsafeSetter, unsafeDesc, false);
			mv.visitInsn(Opcodes.RETURN);
		}
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		rewriteMethodBody(methodDecl, genClassName, genMethodName, isGetter);
	}

	// ======================== 方案 2A: invokedynamic (indy) 动态调用点 ========================

	private void processMethodIndy(MethodSymbol methodSymbol, HMethod hMethod, ClassWriter classWriter) {
		DocReference reference = getSeeReference(HMethod.class, methodSymbol, ElementKind.METHOD, ElementKind.CONSTRUCTOR);
		if (reference == null) return;

		MethodSymbol targetMethod = (MethodSymbol) reference.element();
		JCMethodDecl methodDecl = trees.getTree(methodSymbol);
		String genClassName = classNamesMap.get(classWriter);
		String genMethodName = "x" + (methodId++);

		boolean isStatic = targetMethod.isStatic();
		boolean isPrivate = targetMethod.isPrivate();
		boolean isSpecial = hMethod.isSpecial() || isPrivate;
		boolean isInterface = targetMethod.owner.isInterface();

		int flags = (isStatic ? 1 : 0) | (isSpecial ? 2 : 0) | (isInterface ? 4 : 0);

		// 生成方法体 (直接发射 INVOKEDYNAMIC 指令)
		List<TypeSymbol> paramsL = methodSymbol.params.map(v -> v.type.tsym);
		String methodDesc = paramsL.stream().map(v -> typeToDescriptor(v.type)).collect(Collectors.joining("", "(", ")")) +
			typeToDescriptor(methodSymbol.getReturnType());

		MethodVisitor mv = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, genMethodName, methodDesc, null, null);
		mv.visitCode();

		int slot = 0;
		for (TypeSymbol typeSymbol : paramsL) {
			mv.visitVarInsn(loadOpcode(typeSymbol), slot);
			slot += typeSize(typeSymbol, mSymtab);
		}

		Handle bsmHandle = new Handle(
			Opcodes.H_INVOKESTATIC,
			"hope/magic/runtime/LinkerHelper",
			"bootstrap",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;",
			false
		);

		mv.visitInvokeDynamicInsn(
			targetMethod.name.toString(),
			methodDesc,
			bsmHandle,
			org.objectweb.asm.Type.getType(typeToDescriptor(targetMethod.owner.type)),
			targetMethod.name.toString(),
			flags
		);

		mv.visitInsn(returnOpcode(methodSymbol.getReturnType()));
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		rewriteMethodBody(methodDecl, genClassName, genMethodName, methodDecl.getReturnType().type.getKind() != TypeKind.VOID);
	}

	// ======================== 方案 2B: MethodHandle.invokeExact (Android ART / 跨平台通用) ========================

	private void processMethodHandle(MethodSymbol methodSymbol, HMethod hMethod, ClassWriter classWriter) {
		DocReference reference = getSeeReference(HMethod.class, methodSymbol, ElementKind.METHOD, ElementKind.CONSTRUCTOR);
		if (reference == null) return;

		MethodSymbol targetMethod = (MethodSymbol) reference.element();
		JCMethodDecl methodDecl = trees.getTree(methodSymbol);
		String genClassName = classNamesMap.get(classWriter);
		String genMethodName = "x" + (methodId++);
		String mhFieldName = "MH_" + methodId;

		boolean isStatic = targetMethod.isStatic();
		boolean isSpecial = hMethod.isSpecial();

		// 1. 添加静态 MethodHandle 字段
		classWriter.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, mhFieldName, "Ljava/lang/invoke/MethodHandle;", null, null).visitEnd();

		// 2. 注册 <clinit> 初始化 MethodHandle
		clinitInits.get(classWriter).add(mv -> {
			pushClass(mv, targetMethod.owner.type);
			mv.visitLdcInsn(targetMethod.name.toString());
			pushClass(mv, targetMethod.getReturnType());

			// 创建 Class[] 参数数组
			List<VarSymbol> params = targetMethod.getParameters();
			mv.visitIntInsn(Opcodes.BIPUSH, params.size());
			mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
			for (int i = 0; i < params.size(); i++) {
				mv.visitInsn(Opcodes.DUP);
				mv.visitIntInsn(Opcodes.BIPUSH, i);
				pushClass(mv, params.get(i).type);
				mv.visitInsn(Opcodes.AASTORE);
			}

			mv.visitInsn(isStatic ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
			mv.visitInsn(isSpecial ? Opcodes.ICONST_1 : Opcodes.ICONST_0);

			mv.visitMethodInsn(
				Opcodes.INVOKESTATIC,
				"hope/magic/runtime/LinkerHelper",
				"getMethodHandle",
				"(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;[Ljava/lang/Class;ZZ)Ljava/lang/invoke/MethodHandle;",
				false
			);
			mv.visitFieldInsn(Opcodes.PUTSTATIC, genClassName.replace('.', '/'), mhFieldName, "Ljava/lang/invoke/MethodHandle;");
		});

		// 3. 生成基于 invokeExact 的方法调用
		List<TypeSymbol> paramsL = methodSymbol.params.map(v -> v.type.tsym);
		String methodDesc = paramsL.stream().map(v -> typeToDescriptor(v.type)).collect(Collectors.joining("", "(", ")")) +
			typeToDescriptor(methodSymbol.getReturnType());

		MethodVisitor mv = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, genMethodName, methodDesc, null, null);
		mv.visitCode();

		// 加载 MethodHandle
		mv.visitFieldInsn(Opcodes.GETSTATIC, genClassName.replace('.', '/'), mhFieldName, "Ljava/lang/invoke/MethodHandle;");

		// 加载所有入参
		int slot = 0;
		for (TypeSymbol typeSymbol : paramsL) {
			mv.visitVarInsn(loadOpcode(typeSymbol), slot);
			slot += typeSize(typeSymbol, mSymtab);
		}

		String invokeExactDesc = paramsL.stream().map(v -> typeToDescriptor(v.type)).collect(Collectors.joining("", "(", ")"))
			+ typeToDescriptor(methodSymbol.getReturnType());

		mv.visitMethodInsn(
			Opcodes.INVOKEVIRTUAL,
			"java/lang/invoke/MethodHandle",
			"invokeExact",
			invokeExactDesc,
			false
		);
		mv.visitInsn(returnOpcode(methodSymbol.getReturnType()));
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		rewriteMethodBody(methodDecl, genClassName, genMethodName, methodDecl.getReturnType().type.getKind() != TypeKind.VOID);
	}

	private void rewriteMethodBody(JCMethodDecl methodDecl, String genClassName, String genMethodName, boolean hasReturn) {
		List<JCExpression> args = List.from(methodDecl.params.stream().map(v -> mMaker.Ident(v)).toList());
		mMaker.at(methodDecl.body);
		JCMethodInvocation apply = mMaker.Apply(
			List.nil(),
			mMaker.Select(mMaker.QualIdent(classSymbol(genClassName)), names.fromString(genMethodName)),
			args
		);

		methodDecl.body = mMaker.Block(0, List.of(
			hasReturn ? mMaker.Return(apply) : mMaker.Exec(apply)
		));
	}

	private String getMagicSuperClassName(Symbol owner) {
		for (AnnotationMirror am : owner.getAnnotationMirrors()) {
			if (am.getAnnotationType().asElement().getSimpleName().contentEquals("HMarkMagic")) {
				for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : am.getElementValues().entrySet()) {
					if (entry.getKey().getSimpleName().contentEquals("magicClass")) {
						Object val = entry.getValue().getValue();
						if (val instanceof TypeMirror tm) {
							return tm.toString();
						}
					}
				}
				return "hope.magic.runtime.MAGICIMPL";
			}
		}

		try {
			HMarkMagic mark = owner.getAnnotation(HMarkMagic.class);
			if (mark != null) {
				try {
					return mark.magicClass().getName();
				} catch (MirroredTypeException mte) {
					return mte.getTypeMirror().toString();
				}
			}
		} catch (Throwable ignored) {
		}

		return null;
	}

	private void pushClass(MethodVisitor mv, com.sun.tools.javac.code.Type type) {
		switch (type.getKind()) {
			case BOOLEAN -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TYPE", "Ljava/lang/Class;");
			case BYTE -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Byte", "TYPE", "Ljava/lang/Class;");
			case CHAR -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Character", "TYPE", "Ljava/lang/Class;");
			case SHORT -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Short", "TYPE", "Ljava/lang/Class;");
			case INT -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;");
			case LONG -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Long", "TYPE", "Ljava/lang/Class;");
			case FLOAT -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Float", "TYPE", "Ljava/lang/Class;");
			case DOUBLE -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Double", "TYPE", "Ljava/lang/Class;");
			case VOID -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Void", "TYPE", "Ljava/lang/Class;");
			default -> mv.visitLdcInsn(org.objectweb.asm.Type.getType(typeToDescriptor(type)));
		}
	}
}
