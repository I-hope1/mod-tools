package hope.magic.compiler;

import com.sun.source.doctree.*;
import com.sun.source.util.DocTreePath;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.tree.DCTree.DCReference;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.Name;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.*;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.Arrays;

public abstract class BaseAccessorProc extends AbstractProcessor {
	public static final boolean DEBUG = true;

	public ProcessingEnvironment env;
	public Context               context;
	public JavacElements         elements;
	public JavacTrees            trees;
	public TreeMaker             mMaker;
	public Names                 names;
	public Types                 types;
	public Symtab                mSymtab;
	public Log                   log;
	public Filer                 mFiler;
	public Messager              messager;

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
		JCTree            pos  = trees.getTree(element);
		DocCommentTree    doc  = trees.getDocCommentTree(element);
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
			JCTree expression    = expressionCpy;
			Name   name          = null;
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
			Field f   = DCReference.class.getDeclaredField("qualifierExpression");
			long  off = JavacHelper.unsafe.objectFieldOffset(f);
			JavacHelper.unsafe.putObject(reference, off, access);
		} catch (Throwable ignored) {
		}
	}

	protected void writeClassBytes(JavaFileObject classfile, byte[] classBytes) throws IOException {
		if (DEBUG) {
			// Debugging code
			try (OutputStream outputStream = new FileOutputStream("F:/classes/" + getClassNameFast(classBytes) + ".class")) {
				outputStream.write(classBytes);
			} catch (IOException e) {
				messager.printMessage(Diagnostic.Kind.ERROR, "Failed to write debug class file: " + e.getMessage());
			}
		}
		try (OutputStream outputStream = classfile.openOutputStream()) {
			outputStream.write(classBytes);
		}
	}
	private static String getClassNameFast(byte[] bytes) {
		try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
			// 验证魔数是否为0xCAFEBABE
			if (dis.readInt() != 0xCAFEBABE) return null;
			dis.readInt(); // 跳过版本信息

			// 读取常量池大小并初始化相关数组
			int      cpCount = dis.readUnsignedShort();
			String[] strings = new String[cpCount];
			int[]    classes = new int[cpCount];

			// 遍历常量池，根据标签类型处理不同数据
			for (int i = 1; i < cpCount; i++) {
				int tag = dis.readUnsignedByte();
				switch (tag) {
					case 1:  // UTF8字符串
						strings[i] = dis.readUTF();
						break;
					case 7:  // 类引用
						classes[i] = dis.readUnsignedShort();
						break;
					case 5:
					case 6: // Long或Double（占用两个槽位）
						dis.skipBytes(8);
						i++;
						break;
					case 3:
					case 4:
						dis.skipBytes(4); // 跳过u4类型数据
						break;
					case 8:
					case 16:
						dis.skipBytes(2); // 跳过u2类型数据
						break;
					case 9:
					case 10:
					case 11:
					case 12:
					case 18:
						dis.skipBytes(4); // 跳过两个u2类型数据
						break;
					case 15: // MethodHandle
						dis.skipBytes(3);
						break;
					default:
						return null; // 遇到未知标签，视为非法格式
				}
			}

			dis.skipBytes(2); // 跳过访问标志
			int thisClassIdx = dis.readUnsignedShort(); // 获取当前类在常量池中的索引
			// 返回类名，并将斜杠替换为点号
			return strings[classes[thisClassIdx]].replace('/', '.');
		} catch (Exception e) {
			return null; // 发生异常时返回null
		}
	}

	protected ClassSymbol classSymbol(String genClassName) {
		if (genClassName.startsWith("java.")) {
			return mSymtab.enterClass(mSymtab.java_base, names.fromString(genClassName));
		}
		return mSymtab.enterClass(mSymtab.unnamedModule, names.fromString(genClassName));
	}

	public record DocReference(DCReference reference, Element element) {
	}
}
