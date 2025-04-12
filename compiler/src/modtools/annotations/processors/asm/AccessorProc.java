package modtools.annotations.processors.asm;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import jdk.internal.org.objectweb.asm.*;
import modtools.annotations.asm.HAccessor.*;
import modtools.annotations.asm.Sample.AConstants;

import javax.annotation.processing.Processor;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.TypeKind;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

@AutoService(Processor.class)
public class AccessorProc extends BaseASMProc<MethodSymbol> {
	Map<Symbol, ClassWriter> classWriterMap = new HashMap<>();
	Map<ClassWriter, String> classNamesMap = new HashMap<>();
	public void dealElement(MethodSymbol element) throws Throwable {
		HField hField = element.getAnnotation(HField.class);
		if (hField != null) {
			HMarkMagic magic = getAnnotationByElement(HMarkMagic.class, element.owner, true);
			if (magic == null) {
				log.useSource(trees.getPath(element).getCompilationUnit().getSourceFile());
				log.error(trees.getTree(element.owner), SPrinter.err("@HField is only allowed on methods annotated with @HMarkMagic"));
				return;
			}
			setClassWriter(element, magic);
			process(element, hField);
			return;
		}
		HMethod hMethod = element.getAnnotation(HMethod.class);
		if (hMethod != null) {
			HMarkMagic magic = getAnnotationByElement(HMarkMagic.class, element.owner, true);
			if (magic == null) {
				log.useSource(trees.getPath(element).getCompilationUnit().getSourceFile());
				log.error(trees.getTree(element.owner), SPrinter.err("@HMethod is only allowed on methods annotated with @HMarkMagic"));
				return;
			}
			setClassWriter(element, magic);
			process(element, hMethod);
			return;
		}
		throw new IllegalArgumentException("No annotation found");
	}
	private void setClassWriter(MethodSymbol element, HMarkMagic magic) throws IOException, ClassNotFoundException {
		if (classWriterMap.containsKey(element.owner)) {
			classWriter = classWriterMap.get(element.owner);
			genClassName = classNamesMap.get(classWriter);
		} else {
			classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String s = magic.magicClass().getName();
			// String         delegator = AConstants.nextGenClassName();
			// JavaFileObject classFile = mFiler.createClassFile(s);
			// byte[] bytes;
			// try (OutputStream outputStream = classFile.openOutputStream()) {
			// 	ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			// 	cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, s.replace('.', '/'), null,
			// 	 delegator, null);
			// 	outputStream.write(bytes = cw.toByteArray());
			// } catch (IOException e) {
			// 	throw new RuntimeException(e);
			// }
			// JavaFileObject fileObject = HopeReflect.getAccess(Class.forName("com.sun.tools.javac.processing.JavacFiler$FilerOutputJavaFileObject"), classFile, "javaFileObject");
			// Path           path       = HopeReflect.getAccess(Class.forName("com.sun.tools.javac.file.PathFileObject$DirectoryFileObject"), fileObject, "userPackageRootDir");
			// println(path.getClass());
			// Map<String, JavaFileObject> map = mFiler.getGeneratedClasses().get(mSymtab.unnamedModule);
			// map.forEach((key, value) -> {
			// 	if (value == classFile) map.remove(key);
			// });
			// ClassSymbol classSymbol = mSymtab.enterClass(mSymtab.unnamedModule, ns(s));
			// classSymbol.classfile = classFile;
			// URI uri = URI.create(path.toUri() + delegator.replace('.', '/') + ".class");
			// try (var output = new FileOutputStream(new File(uri))) {
			// 	output.write(bytes);
			// }

			genClassName = AConstants.nextGenClassName();
			classWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, genClassName.replace('.', '/'), null,
			 s.replace('.', '/'), null);
			classWriterMap.put(element.owner, classWriter);
			classNamesMap.put(classWriter, genClassName);
		}
	}
	public void process() throws Throwable {
		for (Entry<Symbol, ClassWriter> entry : classWriterMap.entrySet()) {
			ClassWriter writer = entry.getValue();
			writeClassBytes(mFiler.createClassFile(classNamesMap.get(writer)), writer.toByteArray());
		}
	}
	public void init() throws Throwable {
		genClassName = null;
	}
	public void process(MethodSymbol methodSymbol, HField hField) {
		SeeReference reference  = getSeeReference(HField.class, methodSymbol, ElementKind.FIELD);
		VarSymbol    target     = (VarSymbol) reference.element();
		boolean      isGetter   = hField.isGetter();
		boolean      isStatic   = target.isStatic();
		JCMethodDecl methodDecl = trees.getTree(methodSymbol);
		// 检查方法返回值是否符合调用
		if (!methodSymbol.getReturnType().tsym.equals((isGetter ? target.type : mSymtab.voidType).tsym)) {
			log.useSource(trees.getPath(methodSymbol).getCompilationUnit().getSourceFile());
			log.error(methodDecl.restype, SPrinter.err("Field type mismatch the return type: " + (isGetter ? target.type : "void") + " != " + methodSymbol.getReturnType()));
			return;
		}
		// todo 检查方法参数是否符合调用

		// 生成的方法是: public static <FieldType> x0(<FieldOwner> owner) { return owner.<fieldName>; }
		// 或 public static void x0(<FieldOwner> owner, <FieldType> val) { owner.<fieldName> = val; }
		// (static) 或 public static <FieldType> x0() { return <StaticOwner>.<fieldName>; }
		// (static) 或 public static void x0(<FieldType> val) { <StaticOwner>.<fieldName> = val; }
		StringBuilder sb = new StringBuilder();
		sb.append('(');
		sb.append(isStatic ? "" : typeToDescriptor(target.owner.type))
		 .append(isGetter ? ")" + typeToDescriptor(target.type) : "V");
		String genMethodName = genMethodName();
		MethodVisitor mv = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, genMethodName,
		 sb.toString(), null, null);
		String owner      = dotToSlash(target.owner.type);
		String descriptor = typeToDescriptor(target.type);
		if (!isStatic) mv.visitVarInsn(Opcodes.ALOAD, 0); // load this
		if (isGetter) {
			mv.visitFieldInsn(isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD,
			 owner, target.name.toString(), descriptor);
			mv.visitInsn(Opcodes.ARETURN);
		} else {
			mv.visitFieldInsn(isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD,
			 owner, target.name.toString(), descriptor);
		}
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		classWriter.visitEnd();

		// 调用创建的方法: { return x0(owner); }
		// 或 { x0(owner, val); }
		// (static) 或 { return x0(); }
		// (static) 或 { x0(val); }
		List<JCExpression> args = List.from(methodDecl.params.stream().map(v -> mMaker.Ident(v)).toList());
		mMaker.at(methodDecl.body);
		JCMethodInvocation apply = mMaker.Apply(List.nil(), mMaker.Select(mMaker.QualIdent(classSymbol()), ns(genMethodName)),
		 args);
		methodDecl.body = PBlock(
		 isGetter ? mMaker.Return(apply) : mMaker.Exec(apply)
		);
		// println(methodDecl);
	}
	public void process(MethodSymbol methodSymbol, HMethod hMethod) {
		SeeReference reference    = getSeeReference(HMethod.class, methodSymbol, ElementKind.METHOD, ElementKind.CONSTRUCTOR);
		MethodSymbol targetMethod = (MethodSymbol) reference.element();

		JCMethodDecl methodDecl = trees.getTree(methodSymbol);
		String genMethodName = genMethodName();
		if (targetMethod.isStatic()/*  && !(hMethod.isSpecial() && targetMethod.isConstructor()) */) {
			if (hMethod.isSpecial()) {
				log.useSource(trees.getPath(methodSymbol).getCompilationUnit().getSourceFile());
				log.error(methodDecl.mods, SPrinter.err("Special method cannot be static"));
				return;
			}

			if (targetMethod.getParameters().size() != methodSymbol.params.size()) {
				log.useSource(trees.getPath(methodSymbol).getCompilationUnit().getSourceFile());
				log.error(methodDecl.params.get(0).pos, SPrinter.err("Method parameter count mismatch"));
				return;
			}

			for (int i = 0; i < targetMethod.getParameters().size(); i++) {
				VarSymbol targetParam = targetMethod.getParameters().get(i);
				VarSymbol param       = methodSymbol.params.get(i);
				if (!targetParam.type.tsym.equals(param.type.tsym)) {
					log.useSource(trees.getPath(methodSymbol).getCompilationUnit().getSourceFile());
					log.error(param.pos, SPrinter.err("Method parametertype mismatch"));
				}
			}

			if (!targetMethod.getReturnType().tsym.equals(methodSymbol.getReturnType().tsym)) {
				log.useSource(trees.getPath(methodSymbol).getCompilationUnit().getSourceFile());
				log.error(methodDecl.restype, SPrinter.err("Method return type mismatch"));
				return;
			}

			List<TypeSymbol> args = targetMethod.getParameters().map(v -> v.type.tsym);

			// 创建方法
			StringBuilder sb = new StringBuilder();
			sb.append('(');
			sb.append(targetMethod.getParameters().stream().map(v -> dotToSlash(v.type)).collect(Collectors.joining("")));
			sb.append(')');
			sb.append(typeToDescriptor(methodSymbol.getReturnType()));
			MethodVisitor mv = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, genMethodName,
			 sb.toString(), null, null);
			String owner      = dotToSlash(targetMethod.owner.type);
			String descriptor = typeToDescriptor(methodSymbol.getReturnType());
			for (int i = 0; i < args.size(); i++) {
				mv.visitVarInsn(loadOpcode(args.get(i)), i);
			}
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, targetMethod.name.toString(),
			 "(" + sb.substring(1, sb.length() - 1) + ")" + descriptor, false);
			mv.visitMaxs(100, 100); // todo
		} else {
			// methodSymbol第一个参数应该是targetMethod的this
			List<TypeSymbol> paramsL = methodSymbol.params.map(v -> v.type.tsym);
			List<TypeSymbol> paramsR = targetMethod.getParameters().map(v -> v.type.tsym).prepend((TypeSymbol) targetMethod.owner);
			if (paramsL.size() != paramsR.size()) {
				log.useSource(trees.getPath(methodSymbol).getCompilationUnit().getSourceFile());
				log.error(methodDecl.params.get(0).pos, SPrinter.err("Method parameter count mismatch"));
				return;
			}

			for (int i = 0; i < paramsL.size(); i++) {
				TypeSymbol targetParam = paramsR.get(i);
				TypeSymbol param       = paramsL.get(i);
				if (!targetParam.equals(param)) {
					log.useSource(trees.getPath(methodSymbol).getCompilationUnit().getSourceFile());
					log.error(methodDecl.params.get(i).pos, SPrinter.err("Method parametertype mismatch: " + param + " != " + targetParam));
				}
			}

			if (!targetMethod.getReturnType().tsym.equals(methodSymbol.getReturnType().tsym)) {
				log.useSource(trees.getPath(methodSymbol).getCompilationUnit().getSourceFile());
				log.error(methodDecl.restype, SPrinter.err("Method return type mismatch: " + methodSymbol.getReturnType() + " != " + targetMethod.getReturnType()));
				return;
			}

			// 创建方法
			StringBuilder sb = new StringBuilder();
			sb.append('(');
			sb.append(paramsL.stream().map(v -> typeToDescriptor(v.type)).collect(Collectors.joining("")));
			sb.append(')');
			sb.append(typeToDescriptor(methodSymbol.getReturnType()));
			// println(sb);
			MethodVisitor mv = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, genMethodName,
			 sb.toString(), null, null);
			String owner      = dotToSlash(targetMethod.owner.type);
			String descriptor = typeToDescriptor(methodSymbol.getReturnType());
			for (int i = 0; i < paramsL.size(); i++) {
				mv.visitVarInsn(loadOpcode(paramsL.get(i)), i);
			}
			mv.visitMethodInsn(hMethod.isSpecial() ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEVIRTUAL, owner, targetMethod.name.toString(),
			 targetMethod.getParameters().stream().map(v -> typeToDescriptor(v.type))
			  .collect(Collectors.joining("", "(", ")")) + descriptor, false);
			mv.visitInsn(returnOpcode(methodSymbol.getReturnType()));
			mv.visitMaxs(100, 100); // todo
		}

		JCMethodInvocation apply = mMaker.Apply(null, mMaker.Select(mMaker.QualIdent(classSymbol()), ns(genMethodName)), methodDecl.params.map(v -> mMaker.Ident(v)));
		methodDecl.body = PBlock(
		 methodDecl.getReturnType().type.getKind() == TypeKind.VOID ? mMaker.Exec(apply) : mMaker.Return(apply)
		);
	}
	private int returnOpcode(Type returnType) {
		return switch (returnType.getKind()) {
			case BOOLEAN, INT, CHAR, BYTE, SHORT -> Opcodes.IRETURN;
			case FLOAT -> Opcodes.FRETURN;
			case LONG -> Opcodes.LRETURN;
			case DOUBLE -> Opcodes.DRETURN;
			default -> Opcodes.ARETURN;
		};
	}


	public static int loadOpcode(TypeSymbol type) {
		return loadOpcode(type, () -> { });
	}
	public static int loadOpcode(TypeSymbol type, Runnable icrementor) {
		icrementor.run();
		return switch (type.getQualifiedName().toString()) {
			case "boolean", "int", "char", "byte", "short" -> Opcodes.ILOAD;
			case "float" -> Opcodes.FLOAD;
			case "long" -> {
				icrementor.run();
				yield Opcodes.LLOAD;
			}
			case "double" -> {
				icrementor.run();
				yield Opcodes.DLOAD;
			}
			default -> Opcodes.ALOAD;
		};
	}
	public static String dotToSlash(Type type) {
		String s = type.tsym.flatName().toString();
		return switch (s) {
			case "boolean" -> "Z";
			case "byte" -> "B";
			case "char" -> "C";
			case "short" -> "S";
			case "int" -> "I";
			case "long" -> "J";
			case "float" -> "F";
			case "double" -> "D";
			case "void" -> "V";
			default -> s.replace('.', '/');
		};
	}

	public static String typeToDescriptor(Type type) {
		if (type instanceof ArrayType arrayType) {
			int depth = 1;
			while (arrayType.elemtype instanceof ArrayType) {
				arrayType = (ArrayType) arrayType.elemtype;
				depth++;
			}
			return "[".repeat(depth) + typeToDescriptor(arrayType.elemtype);
		}
		String s = type.tsym.flatName().toString();
		return switch (s) {
			case "boolean" -> "Z";
			case "byte" -> "B";
			case "char" -> "C";
			case "short" -> "S";
			case "int" -> "I";
			case "long" -> "J";
			case "float" -> "F";
			case "double" -> "D";
			case "void" -> "V";
			default -> "L" + s.replace('.', '/') + ";";
		};
	}
	private int methodId;
	public String genMethodName() {
		return "x" + methodId++;
	}
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(HField.class, HMethod.class);
	}
}
