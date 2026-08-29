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
import hope.magic.annotation.HField;
import hope.magic.annotation.HMarkMagic;
import hope.magic.annotation.HMethod;
import org.objectweb.asm.ClassWriter;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
	private int methodId = 0;

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		if (roundEnv.processingOver()) {
			return false;
		}

		classWriterMap.clear();
		classNamesMap.clear();
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
			String magicSuperClass = getMagicSuperClassName(element.owner);
			if (magicSuperClass == null) {
				messager.printMessage(Diagnostic.Kind.ERROR, "@HField 仅允许在标注了 @HMarkMagic 的类中使用", element);
				return;
			}
			ClassWriter cw = getOrCreateClassWriter(element, magicSuperClass);
			processField(element, hField, cw);
			return;
		}

		HMethod hMethod = element.getAnnotation(HMethod.class);
		if (hMethod != null) {
			String magicSuperClass = getMagicSuperClassName(element.owner);
			if (magicSuperClass == null) {
				messager.printMessage(Diagnostic.Kind.ERROR, "@HMethod 仅允许在标注了 @HMarkMagic 的类中使用", element);
				return;
			}
			ClassWriter cw = getOrCreateClassWriter(element, magicSuperClass);
			processMethod(element, hMethod, cw);
		}
	}

	private ClassWriter getOrCreateClassWriter(MethodSymbol element, String magicSuperClass) {
		if (classWriterMap.containsKey(element.owner)) {
			return classWriterMap.get(element.owner);
		}

		ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		String genClassName = "hope.magic.gen.MagicGenX" + ID_GEN.incrementAndGet();
		classWriter.visit(
			Opcodes.V1_8,
			Opcodes.ACC_PUBLIC,
			genClassName.replace('.', '/'),
			null,
			magicSuperClass.replace('.', '/'),
			null
		);

		classWriterMap.put(element.owner, classWriter);
		classNamesMap.put(classWriter, genClassName);
		return classWriter;
	}

	private void processField(MethodSymbol methodSymbol, HField hField, ClassWriter classWriter) {
		DocReference reference = getSeeReference(HField.class, methodSymbol, ElementKind.FIELD);
		if (reference == null) return;

		VarSymbol target = (VarSymbol) reference.element();
		boolean isGetter = hField.isGetter();
		boolean isStatic = target.isStatic();
		JCMethodDecl methodDecl = trees.getTree(methodSymbol);

		// 检查返回值类型
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

		if (!isStatic) mv.visitVarInsn(Opcodes.ALOAD, 0); // 加载 this / owner

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

		// 修改原 AST 逻辑: 调用生成的静态辅助方法
		String genClassName = classNamesMap.get(classWriter);
		List<JCExpression> args = List.from(methodDecl.params.stream().map(v -> mMaker.Ident(v)).toList());
		mMaker.at(methodDecl.body);
		JCMethodInvocation apply = mMaker.Apply(
			List.nil(),
			mMaker.Select(mMaker.QualIdent(classSymbol(genClassName)), names.fromString(genMethodName)),
			args
		);

		methodDecl.body = mMaker.Block(0, List.of(
			isGetter ? mMaker.Return(apply) : mMaker.Exec(apply)
		));
	}

	private void processMethod(MethodSymbol methodSymbol, HMethod hMethod, ClassWriter classWriter) {
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

			if (targetMethod.getParameters().size() != methodSymbol.params.size()) {
				messager.printMessage(Diagnostic.Kind.ERROR, "参数个数与目标静态方法不匹配", methodSymbol);
				return;
			}

			for (int i = 0; i < targetMethod.getParameters().size(); i++) {
				VarSymbol targetParam = targetMethod.getParameters().get(i);
				VarSymbol param = methodSymbol.params.get(i);
				if (!types.isSameType(targetParam.type, param.type)) {
					messager.printMessage(Diagnostic.Kind.ERROR, "参数类型不匹配: " + param.type + " != " + targetParam.type, param);
				}
			}

			if (!types.isSameType(targetMethod.getReturnType(), methodSymbol.getReturnType())) {
				messager.printMessage(Diagnostic.Kind.ERROR, "返回类型与目标方法不匹配: " + methodSymbol.getReturnType() + " != " + targetMethod.getReturnType(), methodSymbol);
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
			List<TypeSymbol> paramsR = targetMethod.getParameters().map(v -> v.type.tsym).prepend((TypeSymbol) targetMethod.owner);
			if (paramsL.size() != paramsR.size()) {
				messager.printMessage(Diagnostic.Kind.ERROR, "实例方法参数个数不匹配（首个参数必须是目标类实例 this）", methodSymbol);
				return;
			}

			for (int i = 0; i < paramsL.size(); i++) {
				TypeSymbol targetParam = paramsR.get(i);
				TypeSymbol param = paramsL.get(i);
				if (!targetParam.equals(param)) {
					messager.printMessage(Diagnostic.Kind.ERROR, "参数类型不匹配: " + param + " != " + targetParam, methodSymbol);
				}
			}

			if (!types.isSameType(targetMethod.getReturnType(), methodSymbol.getReturnType())) {
				messager.printMessage(Diagnostic.Kind.ERROR, "返回类型与目标方法不匹配: " + methodSymbol.getReturnType() + " != " + targetMethod.getReturnType(), methodSymbol);
				return;
			}

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

		String genClassName = classNamesMap.get(classWriter);
		List<JCExpression> args = List.from(methodDecl.params.stream().map(v -> mMaker.Ident(v)).toList());
		mMaker.at(methodDecl.body);
		JCMethodInvocation apply = mMaker.Apply(
			null,
			mMaker.Select(mMaker.QualIdent(classSymbol(genClassName)), names.fromString(genMethodName)),
			args
		);

		methodDecl.body = mMaker.Block(0, List.of(
			methodDecl.getReturnType().type.getKind() == TypeKind.VOID ? mMaker.Exec(apply) : mMaker.Return(apply)
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
}
