package modtools.annotations.processors.asm;

import com.google.auto.service.AutoService;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import jdk.internal.org.objectweb.asm.*;
import modtools.annotations.asm.CopyConstValue;

import javax.annotation.processing.Processor;
import javax.lang.model.element.ElementKind;
import java.util.Set;
import java.util.function.Consumer;

@AutoService(Processor.class)
public class ASMProcessor extends BaseASMProc<VarSymbol> {
	public void process() { }

	public void dealElement(VarSymbol element) throws Throwable {
		DocReference seeReference = getSeeReference(CopyConstValue.class, element, ElementKind.FIELD);
		if (seeReference == null) return;
		VarSymbol field = (VarSymbol) seeReference.element();

		CompilationUnitTree unit = trees.getPath(element).getCompilationUnit();

		element.flags_field |= Flags.FINAL;
		var tree = ((JCVariableDecl) trees.getTree(element));
		tree.mods.annotations = List.from(tree.mods.annotations.stream()
		 .filter(a -> !a.annotationType.type.toString().equals(CopyConstValue.class.getName())).toList());
		tree.mods.flags |= Flags.FINAL;

		Consumer<Object> setConstantValue = value -> {
			tree.init = mMaker.Literal(value);
			element.type = element.type.constType(value);
			// println(element.type.constValue());
		};
		Object constantValue = field.getConstValue(); // 用field.getConstantValue()会出错
		if (constantValue != null) {
			setConstantValue.accept(constantValue);
		} else if (field.owner instanceof ClassSymbol cs) {
			new ClassReader(cs.classfile.openInputStream().readAllBytes()).accept(new ClassVisitor(Opcodes.ASM5) {
				public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
					if (field.name.toString().equals(name) && value != null) {
						setConstantValue.accept(value);
					}
					return super.visitField(access, name, descriptor, signature, value);
				}
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
				                                 String[] exceptions) {
					if ("<clinit>".equals(name)) {
						// println("clinit");
						return new MethodVisitor(Opcodes.ASM5) {
							Object lvalue;
							public void visitLdcInsn(Object value) {
								this.lvalue = value;
							}
							public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
								if (field.name.toString().equals(name) && opcode == Opcodes.PUTSTATIC) {
									try {
										setConstantValue.accept(lvalue);
									} catch (AssertionError e) {
										String s = "\"" + element + "\" 's reference (" + seeReference.reference() + ") is not constvalue (Got: " + lvalue + ")";
										log.useSource(unit.getSourceFile());
										log.error(tree, SPrinter.err(s));
										throw new RuntimeException(s, e);
									}
								}
							}
						};
					}
					return super.visitMethod(access, name, descriptor, signature, exceptions);
				}
			}, 0);
		}

		// println(tree.sym == element);
	}

	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(CopyConstValue.class);
	}
}
