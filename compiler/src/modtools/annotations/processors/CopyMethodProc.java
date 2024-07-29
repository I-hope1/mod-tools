package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import jdk.internal.org.objectweb.asm.*;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import javax.tools.JavaFileObject;
import java.io.*;
import java.util.*;

@AutoService(Processor.class)
public class CopyMethodProc extends BaseProcessor<MethodSymbol> {
	private void writeClassBytes(String className, byte[] classBytes) throws IOException {
		JavaFileObject file = mFiler.createClassFile(className);
		try (OutputStream outputStream = file.openOutputStream()) {
			outputStream.write(classBytes);
		}
	}

	public static final String PREFIX         = "-";
	public static       String GEN_CLASS_NAME = "modtools.gen.GenMethod";

	public void dealElement(MethodSymbol method) throws Throwable {
		CopyMethodFrom anno;
		if ((anno = method.getAnnotation(CopyMethodFrom.class)) == null) return;
		String strMethod    = anno.method();
		String insertBefore = anno.insertBefore();

		// 解析方法: 完全限定类名#方法名({@link java.lang.invoke.MethodType})
		String[] parts      = strMethod.split("#");
		String   methodName = parts[1].substring(0, parts[1].indexOf('('));

		todos.put(new String[]{PREFIX + parts[1].split("\\(")[0], methodName, insertBefore},
		 new Symbol[]{findClassSymbolAny(parts[0]), method});

		JCClassDecl  classDecl  = (JCClassDecl) trees.getTree(method.owner);
		JCMethodDecl methodDecl = trees.getTree(method);

		if (!insertBefore.isEmpty())
			classDecl.defs = classDecl.defs.append(mMaker.MethodDef(methodDecl.mods, ns(PREFIX + method.name), methodDecl.restype,
			 methodDecl.typarams, methodDecl.params, methodDecl.thrown,
			 methodDecl.body, null));

		List<JCExpression> args = method.params.map(p -> mMaker.Ident(p.name));
		if (!method.isStatic()) args = args.prepend(mMaker.This(method.owner.type));
		JCMethodInvocation apply = mMaker.Apply(List.nil(),
		 mMaker.Select(mMaker.QualIdent(mSymtab.enterClass(mSymtab.unnamedModule, ns(GEN_CLASS_NAME))), ns(methodName)),
		 args
		);
		methodDecl.body = PBlock(
		 method.getReturnType() == mSymtab.voidType ? mMaker.Exec(apply) : mMaker.Return(apply)
		);
	}

	final Map<String[], Symbol[]> todos = new HashMap<>();

	public void process() throws IOException {
		ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

		todos.forEach((names, symbols) -> {
			try {
				process(symbols, names, classWriter);
			} catch (Exception e) {
				err(e);
			}
		});
		byte[] modifiedClassBytes = classWriter.toByteArray();

		// 写入修改后的类字节码
		writeClassBytes(GEN_CLASS_NAME, modifiedClassBytes);
	}

	void process(Symbol[] symbols, String[] names, ClassWriter classWriter) throws Exception {
		ClassSymbol  classSymbol        = (ClassSymbol) symbols[0];
		MethodSymbol originalMethod     = (MethodSymbol) symbols[1];
		String       originalMethodName = names[0];
		String       targetMethodName   = names[1];
		String       insertBefore       = names[2];

		// 提取源类中的方法
		ClassReader classReader = new ClassReader(classSymbol.classfile.openInputStream().readAllBytes());

		ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM9, classWriter) {
			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				super.visit(version, access, GEN_CLASS_NAME.replace('.', '/'), signature, superName, interfaces);
			}
			public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
				return null;
			}
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
			                                 String[] exceptions) {
				if (targetMethodName.equals(name)) {
					// 不是静态的话，修改descriptor
					if (!originalMethod.isStatic())
						descriptor = "(L" + originalMethod.owner.getQualifiedName().toString().replace('.', '/') + ";" + descriptor.substring(1);
					// 去除private和protected，加上public
					access = access & ~Opcodes.ACC_PRIVATE & ~Opcodes.ACC_PROTECTED | Opcodes.ACC_PUBLIC;
					return insertBefore.isEmpty() ? super.visitMethod(access, name, descriptor, signature, exceptions) :
					 new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
						 private boolean inserted = false;

						 @Override
						 public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
						                             boolean isInterface) {
							 super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
						 }
					 };
				}
				return null;
			}
		};

		classReader.accept(classVisitor, 0);
	}

	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(CopyMethodFrom.class);
	}
}
