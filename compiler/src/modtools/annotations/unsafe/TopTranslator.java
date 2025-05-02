package modtools.annotations.unsafe;

import com.sun.source.doctree.*;
import com.sun.source.tree.ReturnTree;
import com.sun.source.util.DocTreePath;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.DCTree.DCReference;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.List;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.*;

public class TopTranslator extends TreeTranslator {
	final JavacTrees trees;
	final Log        log;
	final Names      names;
	final Symtab     symtab;
	final TreeMaker  maker;

	final String errorKey = "default.method.call";

	/** 待处理的方法 */
	public ArrayList<Todo> todos = new ArrayList<>();
	public static boolean isEquals(Symbol i, Symbol element) {
		return i.flatName().contentEquals(element.flatName());
	}
	public boolean inAnnotation(Class<? extends Annotation> annoType) {
		return currentMethod != null && currentMethod.sym.getAnnotation(annoType) != null
		       || currentClass != null && currentClass.sym.getAnnotation(annoType) != null;
	}
	// public static JCTree stripSign = new JCErroneous(List.nil()) { };
	public static class Todo {
		public Predicate<JCTree>        predicate;
		public Function<JCTree, JCTree> treeTrans;

		public Todo(Predicate<JCTree> predicate, Function<? extends JCTree, JCTree> treeTrans) {
			this.predicate = predicate;
			this.treeTrans = (Function) treeTrans;
		}
		public <T extends JCTree> Todo(Class<T> cls, Predicate<T> predicate, Function<T, JCTree> treeTrans) {
			this(tree -> cls.isInstance(tree) && predicate.test((T) tree), treeTrans);
		}
		public <T extends JCTree> Todo(Class<T> cls, Function<T, JCTree> treeTrans) {
			this(cls::isInstance, treeTrans);
		}
		/* public Todo(Symbol skipElement) {
			this(element -> true, tree -> {
				if (TopTranslator.isEquals(skipElement, TreeInfo.symbolFor(tree))) return stripSign;
				return null;
			});
		} */
	}

	private TopTranslator(Context context) {
		context.put(TopTranslator.class, this);
		trees = JavacTrees.instance(context);
		log = Log.instance(context);
		names = Names.instance(context);
		symtab = Symtab.instance(context);
		maker = TreeMaker.instance(context);

		Replace.bundles.put("compiler.err." + errorKey, "Default method call: {0}#{1}");
	}
	public static TopTranslator instance(Context context) {
		TopTranslator translator = context.get(TopTranslator.class);
		if (translator == null) return new TopTranslator(context);
		return translator;
	}
	public JCCompilationUnit toplevel;
	public JCClassDecl       currentClass;
	public JCMethodDecl      currentMethod;

	public void scanToplevel(JCCompilationUnit toplevel) {
		this.toplevel = toplevel;
		translate(toplevel);
	}
	public void visitClassDef(JCClassDecl tree) {
		currentClass = tree;
		super.visitClassDef(tree);
		currentClass = null;
	}
	public void visitMethodDef(JCMethodDecl tree) {
		currentMethod = tree;
		super.visitMethodDef(tree);
		currentMethod = null;
	}
	public void visitApply(JCMethodInvocation tree) {
		super.visitApply(tree);
		transTree(tree);
	}
	boolean inAssign = false;
	public void visitAssign(JCAssign tree) {
		inAssign = true;
		super.visitAssign(tree);
		inAssign = false;
		transTree(tree);
	}
	public void visitSelect(JCFieldAccess tree) {
		super.visitSelect(tree);
		if (!inAssign) transTree(tree);
	}
	public void visitExec(JCExpressionStatement tree) {
		super.visitExec(tree);
		transTree(tree);
	}
	private void transTree(JCTree tree) {
		todos.stream().filter(todo -> todo.predicate.test(tree))
		 .map(todo -> todo.treeTrans.apply(tree))
		 .filter(Objects::nonNull)
		 .limit(1)
		 .forEach(tree1 -> result = tree1);
	}

	public JCTree makeString(String s) {
		return Replace.desugarStringTemplate.makeString(s);
	}
	public JCBinary makeBinary(Tag tag, JCExpression lhs, JCExpression rhs) {
		return Replace.desugarStringTemplate.makeBinary(tag, lhs, rhs);
	}
	public JCFieldAccess makeSelect(JCExpression selected, Name name, TypeSymbol owner) {
		JCFieldAccess select = maker.Select(selected, name);
		Symbol        sym    = owner.members().findFirst(name);
		if (sym == null) {
			log.useSource(toplevel.sourcefile);
			log.error("cant.resolve.location", names.fromString("modtools.events.ISettings"), name, List.nil(), owner);
			throw new CheckException("");
		}
		select.sym = sym;
		select.type = select.sym.type;
		return select;
	}
	public JCExpression defaultValue(Type type) {
		if (type.isPrimitive()) {
			TypeTag tag = type.getTag();
			return switch (tag) {
				case BYTE -> maker.Literal((byte) 0);
				case CHAR -> maker.Literal((char) 0);
				case SHORT -> maker.Literal((short) 0);
				case LONG -> maker.Literal(0L);
				case FLOAT -> maker.Literal(0F);
				case INT -> maker.Literal(0);
				case DOUBLE -> maker.Literal(0D);
				case BOOLEAN -> maker.Literal(false);
				case BOT -> maker.Literal(tag, null);
				default -> throw new IllegalStateException("Unexpected value: " + tag);
			};
		}
		return maker.Literal(TypeTag.BOT, null);
	}
	public int localVarIndex = 0;
	public LetExpr translateMethodBlockToLetExpr(JCMethodDecl methodDecl, Symbol owner) {
		return translateBlockToLetExpr(methodDecl.body, methodDecl.sym.getReturnType(), owner);
	}
	/** 必须在Attr之后 */
	public LetExpr translateBlockToLetExpr(JCBlock block, Type returnType, Symbol owner) {
		if (returnType == symtab.voidType) {
			throw new IllegalArgumentException("methodDecl is void method");
		}
		maker.at(block);
		ListBuffer<JCStatement> defs      = new ListBuffer<>();
		VarSymbol               varSymbol = new VarSymbol(0, names.fromString("$$letexper$$" + ++localVarIndex), returnType, owner);
		defs.add(maker.VarDef(varSymbol, defaultValue(returnType)));
		defs.appendList(new TreeCopier<Void>(maker) {
			public JCTree visitReturn(ReturnTree node, Void unused) {
				return maker.Exec(maker.Assign(maker.Ident(varSymbol), (JCExpression) node.getExpression()));
			}
		}.copy(block.stats));
		LetExpr letExpr = maker.LetExpr(defs.toList(), maker.Ident(varSymbol));
		letExpr.type = returnType;
		return letExpr;
	}

	public ClassSymbol getEventClassSymbol(JCIdent i) {
		DocCommentTree doc    = trees.getDocCommentTree(i.sym);
		SeeTree        seeTag = (SeeTree) doc.getBlockTags().stream().filter(t -> t instanceof SeeTree).findFirst().orElseThrow();
		if (!(seeTag.getReference().get(0) instanceof DCReference reference)) { return null; }

		return (ClassSymbol) trees.getElement(new DocTreePath(new DocTreePath(trees.getPath(i.sym), doc), reference));
	}

	private void checkDefault(JCMethodInvocation tree) {
		// 检查没有实现默认方法却调用了默认方法
		MethodSymbol method = (MethodSymbol) getSymbol(tree);
		l:
		if (method.isDefault() && method.owner.isInterface() /* && ((ClassSymbol) method.owner).sourcefile == null */) {
			ClassSymbol owner = (ClassSymbol) method.owner;
			if (owner.classfile == owner.sourcefile) break l;
			// DocCommentTree doc = trees.getDocCommentTree(method);
			// if (doc == null) break l;
			// SinceTree since = (SinceTree) doc.getBlockTags().stream().filter(t -> t.getKind() == Kind.SINCE).findFirst().orElse(null);
			// if (since == null || Source.lookup(since.getBody().toString()).compareTo(Source.JDK8) < 0) break l;
			// DocTreePath path = treesDocTreePath.getPath(trees.getPath(method), doc, since);/
			log.useSource(toplevel.sourcefile);
			log.error(tree.pos, errorKey, method.owner.name, method);
			throw new CheckException(errorKey);
		}
	}
	private Symbol getSymbol(JCTree tree) {
		return trees.getElement(trees.getPath(toplevel, tree));
	}

	public static class CheckException extends RuntimeException {
		public CheckException(String message) {
			super(message);
		}
	}
}
