package hope.magic.compiler;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.SeeTree;
import com.sun.source.util.DocTreePath;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.tree.DCTree.DCReference;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;

public abstract class BaseAccessorProc extends AbstractProcessor {
	public ProcessingEnvironment env;
	public Context context;
	public JavacElements elements;
	public JavacTrees trees;
	public TreeMaker mMaker;
	public Names names;
	public Types types;
	public Symtab mSymtab;
	public Log log;
	public Filer mFiler;
	public Messager messager;

	@Override
	public synchronized void init(ProcessingEnvironment processingEnv) {
		super.init(processingEnv);
		this.env = processingEnv;
		this.mFiler = processingEnv.getFiler();
		this.messager = processingEnv.getMessager();

		this.context = JavacHelper.getContext(processingEnv);
		this.elements = JavacElements.instance(context);
		this.trees = JavacTrees.instance(context);
		this.mMaker = TreeMaker.instance(context);
		this.names = Names.instance(context);
		this.types = Types.instance(context);
		this.mSymtab = Symtab.instance(context);
		this.log = Log.instance(context);
	}

	@Override
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}

	public <R extends Symbol> DocReference getSeeReference(
		Class<? extends Annotation> annotationClass,
		R element,
		ElementKind... expectKinds
	) {
		JCCompilationUnit unit = (JCCompilationUnit) trees.getPath(element).getCompilationUnit();
		JCTree pos = trees.getTree(element);
		DocCommentTree doc = trees.getDocCommentTree(element);
		if (doc == null) {
			messager.printMessage(Diagnostic.Kind.ERROR, "@" + annotationClass.getSimpleName() + " 标注的 " + element.getKind() + " 必须包含 Javadoc 文档注释", element);
			return null;
		}
		SeeTree seeTag = (SeeTree) doc.getBlockTags().stream().filter(t -> t instanceof SeeTree).findFirst().orElse(null);
		if (seeTag == null) {
			messager.printMessage(Diagnostic.Kind.ERROR, "@" + annotationClass.getSimpleName() + " 标注的 " + element.getKind() + " 必须包含 @see 引用标签", element);
			return null;
		}
		if (seeTag.getReference().isEmpty() || !(seeTag.getReference().get(0) instanceof DCReference reference)) {
			messager.printMessage(Diagnostic.Kind.ERROR, "@" + annotationClass.getSimpleName() + " 的 @see 内容必须为符号引用（例如: @see TargetClass#member）", element);
			return null;
		}
		return findReference(annotationClass, element, expectKinds, reference, unit, pos, doc);
	}

	private <R extends Symbol> DocReference findReference(
		Class<? extends Annotation> annotationClass,
		R element,
		ElementKind[] expectKinds,
		DCReference reference,
		JCCompilationUnit unit,
		JCTree pos,
		DocCommentTree doc
	) {
		Element ref = trees.getElement(new DocTreePath(new DocTreePath(trees.getPath(element), doc), reference));
		if (ref == null) {
			JCTree expressionCpy = reference.qualifierExpression;
			JCTree expression = expressionCpy;
			Name name = null;
			while (expression instanceof JCFieldAccess access) {
				if (access.selected instanceof JCIdent i) {
					name = i.name;
					break;
				}
				expression = access.selected;
			}
			if (name == null && expression instanceof JCIdent i) {
				name = i.name;
			}

			// 尝试根据 import 还原完整限定名
			if (name != null) {
				for (var imp : unit.getImports()) {
					if (!imp.isStatic() && imp.getQualifiedIdentifier() instanceof JCFieldAccess qualid) {
						if (qualid.name.contentEquals(name)) {
							if (expressionCpy instanceof JCFieldAccess && expression instanceof JCFieldAccess access) {
								access.selected = mMaker.Select(qualid.selected, name);
							} else {
								setReferenceQualifier(reference, mMaker.Select(qualid.selected, name));
							}
							ref = trees.getElement(new DocTreePath(new DocTreePath(trees.getPath(element), doc), reference));
							break;
						} else if (qualid.name.toString().equals("*")) {
							if (expressionCpy instanceof JCFieldAccess && expression instanceof JCFieldAccess access) {
								access.selected = mMaker.Select(qualid.selected, name);
							} else {
								setReferenceQualifier(reference, mMaker.Select(qualid.selected, name));
							}
							ref = trees.getElement(new DocTreePath(new DocTreePath(trees.getPath(element), doc), reference));
							if (ref != null) break;
						}
					}
				}
			}
		}

		if (ref == null) {
			messager.printMessage(Diagnostic.Kind.ERROR, "@" + annotationClass.getSimpleName() + ": 无法解析目标符号引用: " + reference, element);
			return null;
		}

		Element finalRef = ref;
		if (Arrays.stream(expectKinds).noneMatch(k -> k == finalRef.getKind())) {
			messager.printMessage(Diagnostic.Kind.ERROR, "@" + annotationClass.getSimpleName() + " 标注的 @see 目标必须是 " + Arrays.toString(expectKinds) + " 之一", element);
			return null;
		}
		return new DocReference(reference, ref);
	}

	private void setReferenceQualifier(DCReference reference, JCFieldAccess access) {
		try {
			Field f = DCReference.class.getDeclaredField("qualifierExpression");
			long off = JavacHelper.unsafe.objectFieldOffset(f);
			JavacHelper.unsafe.putObject(reference, off, access);
		} catch (Throwable ignored) {
		}
	}

	protected void writeClassBytes(JavaFileObject classfile, byte[] classBytes) throws IOException {
		try (OutputStream outputStream = classfile.openOutputStream()) {
			outputStream.write(classBytes);
		}
	}

	protected ClassSymbol classSymbol(String genClassName) {
		return mSymtab.enterClass(mSymtab.unnamedModule, names.fromString(genClassName));
	}

	public record DocReference(DCReference reference, Element element) {
	}
}
