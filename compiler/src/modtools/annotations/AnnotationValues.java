package modtools.annotations;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Name;
import sun.reflect.annotation.AnnotationType;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static modtools.annotations.BaseProcessor.trees;

/**
 * 把注解实参的语法树展开成常量值（字面量 / 常量引用 / {@code X.class} / 数组）。
 * <p>
 * 纯解析，不修改任何 javac 状态；需要已标注（attributed）的树，因为要靠 {@code trees.getElement} 找符号。
 */
public final class AnnotationValues {
	private AnnotationValues() { }

	/** 常量展开的最大深度：防止 {@code A = B; B = A} 这类自引用把 javac 的栈打爆 */
	public static final int MAX_DEPTH = 64;

	/** 标记数组结果，避免与普通 List 混淆 */
	private static final class MyList<T> extends ArrayList<T> { }

	public static Object treeToConstant(CompilationUnitTree unit, JCTree node) {
		return treeToConstant(unit, node, 0);
	}

	private static Object treeToConstant(CompilationUnitTree unit, JCTree node, int depth) {
		if (node == null || depth > MAX_DEPTH) return null;
		switch (node) {
			case JCLiteral literal -> {
				return literal.value;
			}
			case JCNewArray array -> {
				MyList<Object> values = new MyList<>();
				for (JCExpression elem : array.elems) {
					values.add(treeToConstant(unit, elem, depth + 1));
				}
				return values;
			}
			case JCFieldAccess field -> {
				Symbol symbol = getSymbolOrNull(unit, node);
				if (!(symbol instanceof VarSymbol v)) return null;

				if (symbol.name.contentEquals("class")) {
					List<com.sun.tools.javac.code.Type> typeArgs = symbol.type.getTypeArguments();
					return typeArgs.isEmpty() ? null : typeArgs.get(0);
				}
				// 形如 OtherClass.FIELD：先看显式选中的类，再看符号自身的定义
				if (field.selected instanceof JCIdent ident && ident.sym != symbol && ident.sym instanceof ClassSymbol cs) {
					JCClassDecl classDecl = trees.getTree(cs);
					if (classDecl == null) return null;
					JCVariableDecl child = findVarDef(classDecl, field.name);
					return child == null ? null : treeToConstant(unit, child.init, depth + 1);
				}
				JCVariableDecl decl = (JCVariableDecl) trees.getTree(v);
				return decl == null ? null : treeToConstant(unit, decl.init, depth + 1);
			}
			case JCIdent _ -> {
				Symbol symbol = getSymbolOrNull(unit, node);
				if (!(symbol instanceof VarSymbol v)) return null;
				JCVariableDecl decl = (JCVariableDecl) trees.getTree(v);
				return decl == null ? null : treeToConstant(unit, decl.init, depth + 1);
			}
			default -> {
				return null;
			}
		}
	}

	/**
	 * 注解的“默认值 + 本处显式实参”合并结果。
	 * <p>
	 * 注意：{@link AnnotationType#memberDefaults()} 是 javac 内部共享 Map，
	 * 直接写入会污染后续所有对同一注解的读取，必须先复制。
	 */
	public static Map<String, Object> genericAnnotation(Class<? extends Annotation> clazz,
	                                                    CompilationUnitTree unit,
	                                                    Tree node) {
		Map<String, Object> values = new LinkedHashMap<>(AnnotationType.getInstance(clazz).memberDefaults());
		for (JCExpression arg : ((JCAnnotation) node).args) {
			JCAssign assign = (JCAssign) arg;
			Object   value  = treeToConstant(unit, assign.rhs);
			if (value != null) values.put(assign.lhs.toString(), value);
		}
		return values;
	}

	private static JCVariableDecl findVarDef(JCClassDecl classDecl, Name name) {
		for (JCTree def : classDecl.defs) {
			if (def instanceof JCVariableDecl v && v.name.equals(name)) return v;
		}
		return null;
	}

	private static Symbol getSymbolOrNull(CompilationUnitTree unit, JCTree node) {
		TreePath path = trees.getPath(unit, node);
		return path == null ? null : trees.getElement(path);
	}
}
