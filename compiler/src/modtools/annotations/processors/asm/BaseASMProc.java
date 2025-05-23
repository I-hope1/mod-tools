package modtools.annotations.processors.asm;

import com.sun.source.doctree.*;
import com.sun.source.util.DocTreePath;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.tree.DCTree.DCReference;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.Name;
import jdk.internal.org.objectweb.asm.ClassWriter;
import modtools.annotations.*;
import modtools.annotations.asm.Sample.AConstants;
import modtools.annotations.unsafe.TopTranslator;

import javax.lang.model.element.*;
import javax.tools.JavaFileObject;
import java.io.*;
import java.lang.annotation.Annotation;
import java.util.*;
import java.util.List;

public abstract class BaseASMProc<T extends Element> extends BaseProcessor<T> {
	public static final boolean OUTPUT_CLASS_FILE = false;

	public TopTranslator translator;
	public void lazyInit() throws Throwable {
		super.lazyInit();
		translator = TopTranslator.instance(_context);
	}
	public String      genClassName;
	public ClassWriter classWriter;
	public <R extends Symbol> DocReference
	getSeeReference(Class<? extends Annotation> annotationClass,
	                R element, ElementKind... expectKinds) {
		JCCompilationUnit unit = (JCCompilationUnit) trees.getPath(element).getCompilationUnit();
		JCTree            pos  = trees.getTree(element);
		DocCommentTree    doc  = trees.getDocCommentTree(element);
		if (doc == null) {
			log.error(pos, SPrinter.err("@" + annotationClass.getSimpleName() + " 标注的" + element.getKind() + "必须有文档注释"));
			return null;
		}
		for (DocTree tree : doc.getFirstSentence()) {
			if (tree instanceof LinkTree l) {
				SPrinter.println(l.getLabel().getFirst());
			}
		}
		SeeTree seeTag = (SeeTree) doc.getBlockTags().stream().filter(t -> t instanceof SeeTree).findFirst().orElse(null);
		if (seeTag == null) {
			log.error(pos, SPrinter.err("@" + annotationClass.getSimpleName() + " 标注的" + element.getKind() + "必须有@see"));
			return null;
		}
		if (!(seeTag.getReference().get(0) instanceof DCReference reference)) {
			log.error(pos, SPrinter.err("@" + annotationClass.getSimpleName() + " 标注的" + element.getKind() + "的@see必须为引用"));
			return null;
		}
		return findReference(annotationClass, element, expectKinds, reference, unit, pos, doc);
	}
	public  <R extends Symbol> List<Pair<String, DocReference>>
	getLinkReference(Class<? extends Annotation> annotationClass,
	                 R element, ElementKind... expectKinds) {
		JCCompilationUnit unit = (JCCompilationUnit) trees.getPath(element).getCompilationUnit();
		JCTree            pos  = trees.getTree(element);
		DocCommentTree    doc  = trees.getDocCommentTree(element);
		if (doc == null) {
			log.error(pos, SPrinter.err("@" + annotationClass.getSimpleName() + " 标注的" + element.getKind() + "必须有文档注释"));
			return null;
		}
		return doc.getFirstSentence().stream().filter(t -> t instanceof LinkTree l && !l.getLabel().isEmpty())
		 .map(t -> Pair.of(((LinkTree) t).getLabel().getFirst().toString(),
			findReference(annotationClass, element, expectKinds, (DCReference) ((LinkTree) t).getReference(), unit, pos, doc)))
		 .toList();
	}
	public <R extends Symbol> List<Pair<String, DocReference>>
	getLinkReference(Class<? extends Annotation> annotationClass,
	                 R element, ElementKind expectKind, String name) {
		return getLinkReference(annotationClass, element, expectKind).stream().filter(p -> p.fst.equals(name)).toList();
	}
	private <R extends Symbol> DocReference findReference(Class<? extends Annotation> annotationClass, R element,
	                                                             ElementKind[] expectKinds, DCReference reference,
	                                                             JCCompilationUnit unit, JCTree pos,
	                                                             DocCommentTree doc) {

		Element ref = trees.getElement(new DocTreePath(new DocTreePath(trees.getPath(element), doc), reference));
		l:
		if (ref == null) {

			JCTree expressionCpy = reference.qualifierExpression;
			JCTree expression    = expressionCpy;
			Name   name          = null;
			while (expression instanceof JCFieldAccess access) {
				if (access.selected instanceof JCIdent i) {
					name = i.name;
					break;
				}
				expression = access.selected;
			}
			if (name == null && expression instanceof JCIdent i) name = i.name;

			// SPrinter.println("access=" + expression);
			// 尝试从import 中查找
			for (var i : unit.getImports()) {
				if (i.qualid.name.contentEquals(name)) {
					if (expressionCpy instanceof JCFieldAccess && expression instanceof JCFieldAccess access) {
						access.selected = mMaker.Select(i.qualid.selected, name);
					} else {
						JCFieldAccess m = mMaker.Select(i.qualid.selected, name);
						HopeReflect.setAccess(DCReference.class, reference, "qualifierExpression", m);
					}

					ref = trees.getElement(new DocTreePath(new DocTreePath(trees.getPath(element), doc), reference));
					break l;
				}

				if (!i.isStatic() && i.qualid.name.toString().equals("*")) {
					if (expressionCpy instanceof JCFieldAccess && expression instanceof JCFieldAccess access) {
						access.selected = mMaker.Select(i.qualid.selected, name);
					} else {
						JCFieldAccess m = mMaker.Select(i.qualid.selected, name);
						HopeReflect.setAccess(DCReference.class, reference, "qualifierExpression", m);
					}

					ref = trees.getElement(new DocTreePath(new DocTreePath(trees.getPath(element), doc), reference));
					if (ref != null) break l;
				}
			}


			log.error(pos, SPrinter.err("@" + annotationClass.getSimpleName() + ": Couldn't find symbol: " + reference));
			return null;
		}

		Element finalRef = ref;
		if (Arrays.stream(expectKinds).noneMatch(k -> k == finalRef.getKind())) {
			log.error(pos, SPrinter.err("@" + annotationClass.getSimpleName() + " 标注的" + element.getKind() + "的@see必须为" + Arrays.stream(expectKinds).map(k -> k.name().toLowerCase()).toList() + "之一"));
			return null;
		}
		return new DocReference(reference, ref);
	}
	/** 默认直接写入字节码 */
	public void process() throws Throwable {
		writeClassBytes();
	}
	protected void writeClassBytes() throws IOException {
		writeClassBytes(classWriter.toByteArray());
	}
	protected void writeClassBytes(byte[] classBytes) throws IOException {
		JavaFileObject classfile = mFiler.createClassFile(genClassName);
		writeClassBytes(classfile, classBytes);
	}
	protected void writeClassBytes(JavaFileObject classfile, byte[] classBytes) throws IOException {
		try (OutputStream outputStream = classfile.openOutputStream()) {
			outputStream.write(classBytes);
		}
		logClassFile(classBytes, genClassName);
	}
	public static void logClassFile(byte[] classBytes, String className) throws IOException {
		if (OUTPUT_CLASS_FILE) {
			try (OutputStream fileOutput = new FileOutputStream(targetFilePath(className))) {
				fileOutput.write(classBytes);
			}
		}
	}
	public static String targetFilePath(String genClassName) {
		return "F:/gen/" + genClassName + ".class";
	}
	/** 用法: mMaker.QualIdent(classSymbol()) */
	protected ClassSymbol classSymbol() {
		return mSymtab.enterClass(mSymtab.unnamedModule, ns(genClassName));
	}
	public void init() throws Throwable {
		genClassName = AConstants.nextGenClassName();
		classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
	}
	public static String className(VarSymbol v) {
		return className(v.type);
	}
	public static String className(Type type) {
		if (!(type instanceof ArrayType arrayType)) {
			return type.tsym.name + ".class";
		}

		int depth = 1;
		while (arrayType.elemtype instanceof ArrayType) {
			arrayType = (ArrayType) arrayType.elemtype;
			depth++;
		}
		// println(arrayType.elemtype);
		return "Object" + "[]".repeat(depth) + ".class";
	}
	public record DocReference(
	 DCReference reference, Element element) {
	}
}
