package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import jdk.internal.org.objectweb.asm.*;
import modtools.annotations.asm.CopyMethodFrom;
import modtools.annotations.processors.asm.BaseASMProc;

import javax.annotation.processing.Processor;
import javax.lang.model.element.ElementKind;
import java.util.*;

@AutoService(Processor.class)
public class CopyMethodProc extends BaseASMProc<MethodSymbol> {
	public static final String PREFIX = "-";

	public void dealElement(MethodSymbol method) throws Throwable {
		CopyMethodFrom anno;
		if ((anno = method.getAnnotation(CopyMethodFrom.class)) == null) return;

		JCMethodDecl methodDecl   = trees.getTree(method);
		DocReference seeReference = getSeeReference(CopyMethodFrom.class, method, ElementKind.METHOD);
		if (seeReference == null) return;
		MethodSymbol targetMethod = (MethodSymbol) seeReference.element();


		String insertBefore = anno.insertBefore();

		todos.put(new String[]{PREFIX + targetMethod.name, "" + targetMethod.name, insertBefore},
		 new Symbol[]{targetMethod.owner, method});

		JCClassDecl classDecl = (JCClassDecl) trees.getTree(method.owner);

		if (!insertBefore.isEmpty()) {
			classDecl.defs = classDecl.defs.append(mMaker.MethodDef(methodDecl.mods, ns(PREFIX + method.name), methodDecl.restype,
			 methodDecl.typarams, methodDecl.params, methodDecl.thrown,
			 methodDecl.body, null));
		}

		List<JCExpression> args = method.params.map(p -> mMaker.Ident(p.name));
		if (!method.isStatic()) args = args.prepend(mMaker.This(method.owner.type));
		JCMethodInvocation apply = mMaker.Apply(List.nil(),
		 mMaker.Select(mMaker.QualIdent(classSymbol()), method.name),
		 args
		);
		methodDecl.body = PBlock(
		 method.getReturnType() == mSymtab.voidType ? mMaker.Exec(apply) : mMaker.Return(apply)
		);
		// println(apply);
	}

	final Map<String[], Symbol[]> todos = new HashMap<>();

	public void process() throws Throwable {
		todos.forEach((names, symbols) -> {
			try {
				process(symbols, names);
			} catch (Exception e) {
				err(e);
			}
		});

		// 写入修改后的类字节码
		super.process();
	}

	void process(Symbol[] symbols, String[] names) throws Exception {
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
				super.visit(version, access, genClassName.replace('.', '/'), signature, superName, interfaces);
			}
			public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
				return null;
			}
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
			                                 String[] exceptions) {
				if (!targetMethodName.equals(name)) return null;

				// 不是静态的话，修改descriptor
				if (!originalMethod.isStatic()) {
					descriptor = "(L" + originalMethod.owner.getQualifiedName().toString().replace('.', '/') + ";" + descriptor.substring(1);
				}
				// 去除private和protected，加上public
				access = access & ~Opcodes.ACC_PRIVATE & ~Opcodes.ACC_PROTECTED | Opcodes.ACC_PUBLIC;
				return insertBefore.isEmpty() ? super.visitMethod(access, name, descriptor, signature, exceptions) :
				 // 将当前的方法（methodTree）编译成字节码插入到指定位置（insertBefore）
				 new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
					 private boolean inserted = false;

					 @Override
					 public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
					                             boolean isInterface) {
						 super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
					 }
				 };
			}
		};

		classReader.accept(classVisitor, 0);
	}

	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(CopyMethodFrom.class);
	}
}
