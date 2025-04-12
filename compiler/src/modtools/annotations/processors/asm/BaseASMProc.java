package modtools.annotations.processors.asm;

import com.sun.source.doctree.*;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.DocTreePath;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.*;
import com.sun.tools.javac.tree.DCTree.DCReference;
import com.sun.tools.javac.tree.JCTree;
import jdk.internal.org.objectweb.asm.ClassWriter;
import modtools.annotations.BaseProcessor;
import modtools.annotations.asm.Sample.AConstants;

import javax.lang.model.element.*;
import javax.tools.JavaFileObject;
import java.io.*;
import java.lang.annotation.Annotation;
import java.util.Arrays;

public abstract class BaseASMProc<T extends Element> extends BaseProcessor<T> {
	public static final boolean OUTPUT_CLASS_FILE = false;

	public String      genClassName;
	public ClassWriter classWriter;
	public static <R extends Symbol> SeeReference
	getSeeReference(Class<? extends Annotation> annotationClass,
	                R element, ElementKind... expectKinds) {
		CompilationUnitTree unit = trees.getPath(element).getCompilationUnit();
		JCTree              pos  = trees.getTree(element);
		DocCommentTree      doc  = trees.getDocCommentTree(element);
		if (doc == null) {
			log.useSource(unit.getSourceFile());
			log.error(pos, SPrinter.err("@" + annotationClass.getSimpleName() + " 标注的" + element.getKind() + "必须有文档注释"));
			return null;
		}
		SeeTree seeTag = (SeeTree) doc.getBlockTags().stream().filter(t -> t instanceof SeeTree).findFirst().orElse(null);
		if (seeTag == null) {
			log.useSource(unit.getSourceFile());
			log.error(pos, SPrinter.err("@" + annotationClass.getSimpleName() + " 标注的" + element.getKind() + "必须有@see"));
			return null;
		}
		if (!(seeTag.getReference().get(0) instanceof DCReference reference)) {
			log.useSource(unit.getSourceFile());
			log.error(pos, SPrinter.err("@" + annotationClass.getSimpleName() + " 标注的" + element.getKind() + "的@see必须为引用"));
			return null;
		}
		// println(reference);

		Element ref = trees.getElement(new DocTreePath(new DocTreePath(trees.getPath(element), doc), reference));
		if (ref == null) {
			log.useSource(unit.getSourceFile());
			log.error(pos, SPrinter.err(element + ": " + element.getKind() + " is null."));
			return null;
		}
		if (Arrays.stream(expectKinds).noneMatch(k -> k == ref.getKind())) {
			log.useSource(unit.getSourceFile());
			log.error(pos, SPrinter.err("@" + annotationClass.getSimpleName() + " 标注的" + element.getKind() + "的@see必须为" + Arrays.stream(expectKinds).map(k -> k.name().toLowerCase()).toList() + "之一"));
			return null;
		}
		return new SeeReference(reference, ref);
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
		if (OUTPUT_CLASS_FILE) {
			try (OutputStream fileOutput = new FileOutputStream("F:/" + genClassName + ".class")) {
				fileOutput.write(classBytes);
			}
		}
	}
	/** 用法: mMaker.QualIdent(classSymbol())  */
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
	public record SeeReference(
	 DCReference reference, Element element) {
	}
}
