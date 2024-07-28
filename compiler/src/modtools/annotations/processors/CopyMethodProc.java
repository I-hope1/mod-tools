package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.util.List;
import jdk.internal.org.objectweb.asm.*;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import javax.tools.JavaFileObject;
import java.io.*;
import java.lang.reflect.Modifier;
import java.util.*;

@AutoService(Processor.class)
public class CopyMethodProc extends BaseProcessor<MethodSymbol> {
	private void writeClassBytes(String className, byte[] classBytes) throws IOException {
		JavaFileObject file = mFiler.createClassFile(className);
		try (OutputStream outputStream = file.openOutputStream()) {
			outputStream.write(classBytes);
		}
	}
	public static String GEN_CLASS_NAME = "modtools.gen.GenMethod";
	public void dealElement(MethodSymbol method) throws Throwable {
		CopyMethodFrom anno;
		if ((anno = method.getAnnotation(CopyMethodFrom.class)) == null) return;
		String str_method = anno.method();
		// 然后解析方法: 完全限定类名#方法名({@link java.lang.invoke.MethodType})
		String[] parts      = str_method.split("#");
		String   methodName = parts[1].substring(0, parts[1].indexOf('('));
		JCMethodInvocation apply = mMaker.Apply(List.nil(),
		 mMaker.Select(mMaker.QualIdent(mSymtab.enterClass(mSymtab.unnamedModule, ns(GEN_CLASS_NAME))), ns(methodName)),
		 method.params.map(p -> mMaker.Ident(p.name))
		);
		trees.getTree(method).body = PBlock(
		 method.getReturnType() == mSymtab.voidType ? mMaker.Exec(apply) : mMaker.Return(apply)
		);
		todos.put(new String[]{parts[1].split("\\(")[0], method.name.toString()}, findClassSymbolAny(parts[0]));
	}

	final Map<String[], ClassSymbol> todos = new HashMap<>();
	public void process() throws IOException {
		ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

		todos.forEach((names, classSymbol) -> {
			try {
				process(classSymbol, names, classWriter);
			} catch (Exception e) {
				err(e);
			}
		});
		byte[] modifiedClassBytes = classWriter.toByteArray();

		// Write the modified bytes to the target class
		writeClassBytes(GEN_CLASS_NAME, modifiedClassBytes);
	}
	void process(ClassSymbol classSymbol, String [] names, ClassWriter classWriter) throws Exception {
		String originalMethodName = names[0];
		String targetMethodName = names[1];
		// Extract method from source class
		ClassReader classReader = new ClassReader(classSymbol.classfile.openInputStream().readAllBytes());

		ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM9, classWriter) {
			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				// name 是原始的类名，newClassName 是我们要设置的新类名
				super.visit(version, access, GEN_CLASS_NAME, signature, superName, interfaces);
			}
			public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
				return null;
			}
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
			                                 String[] exceptions) {
				if (originalMethodName.equals(name)) {
					return super.visitMethod(Modifier.PUBLIC | Modifier.STATIC, targetMethodName, descriptor, signature, exceptions);
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
