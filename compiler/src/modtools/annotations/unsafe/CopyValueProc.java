package modtools.annotations.unsafe;

import com.sun.source.doctree.*;
import com.sun.source.util.*;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.DCTree.DCReference;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeTranslator;
import jdk.internal.org.objectweb.asm.*;
import modtools.annotations.asm.CopyConstValue;

import javax.lang.model.element.Element;
import java.util.function.Consumer;

import static modtools.annotations.unsafe.Replace.trees;

/** TODO: xxx  */
@Deprecated
public class CopyValueProc extends TreeTranslator {
	public JCCompilationUnit toplevel;
	public JCClassDecl classDecl;

	/** TODO: 这里似乎不应该用{@link com.sun.tools.javac.api.JavacTrees#getElement(TreePath)}  */
	public void visitVarDef(JCVariableDecl tree) {
		if (tree.mods.annotations != null && tree.mods.annotations.nonEmpty()) {
			tree.mods.annotations.stream().anyMatch(a -> {
				Element element;
				if (!a.annotationType.toString().equals(CopyConstValue.class.getSimpleName())) {
					element = trees.getElement(trees.getPath(toplevel, a.annotationType));
					if (element.toString().equals(CopyConstValue.class.getName())) {
						return true;
					}
				}
				return false;
			});
		}
		super.visitVarDef(tree);
	}
	public void dealElement(VarSymbol element) throws Throwable {
		DocCommentTree doc    = trees.getDocCommentTree(element);
		SeeTree        seeTag = (SeeTree) doc.getBlockTags().stream().filter(t -> t instanceof SeeTree).findFirst().orElse(null);
		if (seeTag == null) {
			// log.error("@CopyConstValue 标注的field必须有@see");
			return;
		}
		if (!(seeTag.getReference().get(0) instanceof DCReference reference)) {
			// log.error("@CopyConstValue 标注的field的@see必须为引用");
			return;
		}
		element.flags_field |= Flags.FINAL;

		VarSymbol field = (VarSymbol) trees.getElement(new DocTreePath(new DocTreePath(trees.getPath(element), doc), reference));
		var       tree  = ((JCVariableDecl) trees.getTree(element));
		tree.mods.flags |= Flags.FINAL;

		Consumer<Object> setConstantValue = value -> {
			tree.init = Replace.maker.Literal(value);
			element.type = element.type.constType(value);
			// println(element.type.constValue());
		};
		Object constantValue = field.getConstantValue();
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
										String s = "\"" + element + "\" 's reference (" + reference + ") is not constvalue (Got: " + lvalue + ")";
										// log.error(s);
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

	public void translateTopLevelClass(JCCompilationUnit unit) {
		try {
			toplevel = unit;
			translate(unit);
		} finally {
			this.classDecl = null;
			toplevel = null;
		}
	}
}
