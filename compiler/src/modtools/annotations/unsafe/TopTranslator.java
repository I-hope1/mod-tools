package modtools.annotations.unsafe;

import com.sun.source.tree.ReturnTree;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.List;
import modtools.annotations.HopeReflect;
import modtools.annotations.PrintHelper.SPrinter;

import javax.tools.JavaFileObject;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.*;

import static modtools.annotations.PrintHelper.SPrinter.println;

public class TopTranslator extends TreeTranslator {
	final Log       log;
	final Names     names;
	final Symtab    symtab;
	final TreeMaker make;
	final Types     types;
	final Operators operators;

	public static final String LOCAL_PREFIX = "$$letexpr$$";

	private TopTranslator(Context context) {
		context.put(TopTranslator.class, this);
		log = Log.instance(context);
		names = Names.instance(context);
		symtab = Symtab.instance(context);
		make = TreeMaker.instance(context);
		types = Types.instance(context);
		operators = Operators.instance(context);

		addDefaultTodo();
	}

	/**
	 * 注册内置的兼容性改写：这些 API 在新版 JDK 里签名变了，但 mod 源码仍按旧签名调用。
	 */
	private void addDefaultTodo() {
		replaceMethod(symtab.classType.tsym, "componentType", "getComponentType");

		MethodSymbol listOfMethod       = findMethodSymbol(symtab.listType.tsym, names.fromString("of"), true);
		MethodSymbol arraysAsListMethod = findMethodSymbol(symtab.arraysType.tsym, names.fromString("asList"), true);

		if (listOfMethod != null && arraysAsListMethod != null) {
			replaceMethod(listOfMethod, arraysAsListMethod,
			 (originalInvocation, originalSym, targetSym) -> {
				 // List.of(args) -> Arrays.asList(args)：参数列表原样搬过去
				 List<JCExpression> newArgs = List.of(
					make.NewArray(make.Ident(symtab.objectType.tsym), List.nil(), originalInvocation.args).setType(types.makeArrayType(symtab.objectType)));
				 JCExpression newMethodSelect = makeSelect(make.QualIdent(targetSym.owner), targetSym.name, targetSym);
				 return make.at(originalInvocation.pos).App(
					newMethodSelect, newArgs
				 );
			 }
			);
		} else {
			println("Warning: Could not find List.of or Arrays.asList for replacement.");
		}
	}

	/**
	 * 按名字找第一个 public 的（静态/实例）方法。
	 * <p>
	 * 重载之间不做区分：{@link #isEquals} 只比 owner 和限定名，
	 * 所以拿到哪个重载对后续匹配没有影响。
	 */
	private MethodSymbol findMethodSymbol(TypeSymbol ownerSym, Name methodName, boolean isStatic) {
		return (MethodSymbol) ownerSym.members().findFirst(methodName,
		 s -> s.isPublic() && s instanceof MethodSymbol ms && ms.isStatic() == isStatic);
	}


	/**
	 * 新的replaceMethod，接受精确的MethodSymbol和转换函数。
	 * @param originalMethodSymbol 要替换的原方法的MethodSymbol
	 * @param targetMethodSymbol   目标方法的MethodSymbol
	 * @param transformer          一个函数，接收原始方法调用、原始方法符号、目标方法符号，返回新的方法调用
	 */
	private void replaceMethod(MethodSymbol originalMethodSymbol,
	                           MethodSymbol targetMethodSymbol,
	                           TriFunction<JCMethodInvocation, MethodSymbol, MethodSymbol, JCMethodInvocation> transformer) {
		if (originalMethodSymbol == null || targetMethodSymbol == null) {
			println("Error: originalMethodSymbol or targetMethodSymbol is null. Skipping replacement.");
			return;
		}

		addToDo(new ToTranslate(JCMethodInvocation.class,
		 (methodInvocation) -> {
			 Symbol methodSym = TreeInfo.symbolFor(methodInvocation.meth);
			 // 确保方法符号匹配我们指定的originalMethodSymbol
			 return methodSym != null && isEquals(methodSym, originalMethodSymbol);
		 },
		 (methodInvocation) -> {
			 JCMethodInvocation newMethodInvocation = transformer.apply(
				methodInvocation, originalMethodSymbol, targetMethodSymbol
			 );

			 // 这棵树不再经过 Attr：类型必须在这里补齐，否则后续 Check/Lower 读到 null type。
			 newMethodInvocation.type = targetMethodSymbol.type.getReturnType();
			 newMethodInvocation.meth.type = targetMethodSymbol.type;

			 Symbol newMethodSelectSym = TreeInfo.symbolFor(newMethodInvocation.meth);
			 if (newMethodSelectSym != null) {
				 newMethodSelectSym.type = targetMethodSymbol.type;
			 }
			 return newMethodInvocation;
		 }
		));
	}

	private void replaceMethod(TypeSymbol thisSymbol, String originalMethodName, String targetMethodName) {
		MethodSymbol originalSymbol = (MethodSymbol) thisSymbol.members().findFirst(names.fromString(originalMethodName),
		 s -> s.isPublic() && s instanceof MethodSymbol ms && ms.params.isEmpty());
		MethodSymbol targetSymbol = (MethodSymbol) thisSymbol.members().findFirst(names.fromString(targetMethodName),
		 s -> s.isPublic() && s instanceof MethodSymbol ms && ms.params.isEmpty());
		if (originalSymbol != null && targetSymbol != null) {
			replaceMethod(originalSymbol, targetSymbol, (originalInvocation, originalSym, targetSym) -> {
				// 只改方法名和符号，参数不变
				JCExpression meth = originalInvocation.meth;
				if (meth instanceof JCFieldAccess access) {
					access.name = targetSym.name;
					access.sym = targetSym;
					meth.type = targetSym.type; // 重要
				} else if (meth instanceof JCIdent ident) {
					ident.name = targetSym.name;
					ident.sym = targetSym;
					meth.type = targetSym.type; // 重要
				}
				return originalInvocation;
			});
		} else {
			println("Warning: Old replaceMethod could not find symbols for " + originalMethodName + " or " + targetMethodName);
		}
	}

	private final ArrayList<ToTranslate> todos = new ArrayList<>();

	public void addToDo(ToTranslate todo) {
		if (todo.translator != null) throw new IllegalStateException("todo.translator != null");
		todo.translator = this;
		todos.add(todo);
	}

	public static boolean isEquals(Symbol i, Symbol element) {
		return i != null && element != null && i.owner == element.owner && i.getQualifiedName().contentEquals(element.getQualifiedName());
	}

	public boolean inAnnotation(Class<? extends Annotation> annoType) {
		return (currentMethod != null && currentMethod.sym.getAnnotation(annoType) != null)
		       || (currentClass != null && currentClass.sym.getAnnotation(annoType) != null);
	}

	public JCExpression makeClassExpr(ClassSymbol classSymbol, Symbol owner) {
		if (classSymbol.isPublic()) return make.ClassLiteral(classSymbol);

		MethodSymbol forName = (MethodSymbol) symtab.classType.tsym.members().findFirst(names.fromString("forName"),
		 s -> s.isPublic() && s instanceof MethodSymbol ms && ms.params.size() == 1 && ms.params.get(0).type.tsym == symtab.stringType.tsym);
		JCMethodInvocation apply = make.App(
		 makeSelect(make.Ident(symtab.classType.tsym),
			names.fromString("forName"), forName), List.of(makeString(classSymbol.flatName().toString()))
		);
		// 局部变量名带上类名，同一个方法里多次调用不会撞名
		VarSymbol localSym = new VarSymbol(0, names.fromString(LOCAL_PREFIX + classSymbol.name), symtab.classType, owner);
		localSym.flags_field |= Flags.LocalVarFlags;
		JCVariableDecl local = make.VarDef(localSym, apply);

		// 非 public 的类拿不到 ClassLiteral（常量池里的类引用会被访问检查拦下），
		// 所以退化成 Class.forName，用 let 表达式把结果塞进一个局部变量里用。
		return make.LetExpr(List.of(local), make.Ident(local)).setType(symtab.classType);
	}

	public static class ToTranslate {
		public TopTranslator            translator;
		public Predicate<JCTree>        predicate;
		public Function<JCTree, JCTree> treeTrans;

		public ToTranslate(Predicate<JCTree> predicate, Function<? extends JCTree, JCTree> treeTrans) {
			this.predicate = predicate;
			this.treeTrans = (Function) treeTrans;
		}

		public <T extends JCTree> ToTranslate(Class<T> cls, Predicate<T> predicate, Function<T, JCTree> treeTrans) {
			this(tree -> cls.isInstance(tree) && predicate.test((T) tree), treeTrans);
		}

		/** 此时tree的symbol都是有的，不能用符号表查找，要用{@link TreeInfo#symbolFor(JCTree)} */
		public <T extends JCTree> ToTranslate(Class<T> cls, Function<T, JCTree> treeTrans) {
			this(cls::isInstance, treeTrans);
		}

		/** 访问字段：包括Ident和FieldAccess，不包括作Assign.lhs的 */
		public static ToTranslate AccessField(Symbol expectSym, BiFunction<JCTree, Symbol, JCTree> treeTrans) {
			ToTranslate toTranslate[] = {null};
			return toTranslate[0] = new ToTranslate(_ -> true, (tree) -> {
				if (toTranslate[0].translator.inAssignLHS) return null;
				Symbol symbol;
				if (tree instanceof JCFieldAccess access) {
					symbol = access.sym;
				} else if (tree instanceof JCIdent ident) {
					symbol = ident.sym;
				} else {
					return null;
				}
				if (!isEquals(symbol, expectSym)) return null;
				return treeTrans.apply(tree, symbol);
			});
		}

		public static ToTranslate AssignLHS(Predicate<Symbol> condition, BiFunction<JCAssign, Symbol, JCTree> treeTrans) {
			return new ToTranslate(JCAssign.class, (assign) -> {
				Symbol symbol;
				if (!(assign.lhs instanceof JCFieldAccess lhsAccess && condition.test(symbol = lhsAccess.sym)
				      || assign.lhs instanceof JCIdent ident && condition.test(symbol = ident.sym))) { return null; }
				return treeTrans.apply(assign, symbol);
			});
		}

	}

	public static TopTranslator instance(Context context) {
		TopTranslator translator = context.get(TopTranslator.class);
		if (translator == null) return new TopTranslator(context);
		return translator;
	}

	public  JCCompilationUnit toplevel;
	public  JCClassDecl       currentClass;
	public  JCMethodDecl      currentMethod;
	private Symbol            implCurrentMethod;

	public Symbol currentOwner() {
		return currentMethod != null ? currentMethod.sym :
		 implCurrentMethod != null ? implCurrentMethod : currentClass.sym;
	}

	public void scanToplevel(JCCompilationUnit toplevel) {
		this.toplevel = toplevel;
		translate(toplevel);
		this.toplevel = null;
	}

	//region visit
	public void visitClassDef(JCClassDecl tree) {
		JCClassDecl prev = currentClass;
		currentClass = tree;
		super.visitClassDef(tree);
		currentClass = prev;
	}

	public void visitMethodDef(JCMethodDecl tree) {
		var prev = currentMethod;
		currentMethod = tree;
		super.visitMethodDef(tree);
		currentMethod = prev;
	}

	public void visitBlock(JCBlock tree) {
		var prev = implCurrentMethod;
		if (currentClass != null && currentClass.defs.contains(tree)) {
			implCurrentMethod = currentClass.sym.members().findFirst(tree.isStatic() ? names.clinit : names.init);
		}
		super.visitBlock(tree);
		implCurrentMethod = prev;
	}

	public void visitApply(JCMethodInvocation tree) {
		super.visitApply(tree);
		transTree(tree);
	}

	public void visitLambda(JCLambda tree) {
		super.visitLambda(tree);
		transTree(tree);
	}
	public void visitReference(JCMemberReference tree) {
		super.visitReference(tree);
		transTree(tree);
	}
	public void visitReturn(JCReturn tree) {
		super.visitReturn(tree);
		transTree(tree);
	}

	boolean inAssignLHS = false;

	public void visitAssign(JCAssign tree) {
		var prev = inAssignLHS;
		inAssignLHS = true;
		tree.lhs = translate(tree.lhs);
		inAssignLHS = prev;
		tree.rhs = translate(tree.rhs);
		result = tree;
		transTree(tree);
	}

	public void visitSelect(JCFieldAccess tree) {
		super.visitSelect(tree);
		transTree(tree);
	}

	public void visitTypeCast(JCTypeCast tree) {
		super.visitTypeCast(tree);
		transTree(tree);
	}

	public void visitIdent(JCIdent tree) {
		super.visitIdent(tree);
		transTree(tree);
	}

	public void visitExec(JCExpressionStatement tree) {
		super.visitExec(tree);
		transTree(tree);
	}

	private void transTree(JCTree tree) {
		// 每个 AST 节点都会走这里，别用 stream（每次都要分配 4 个中间对象）
		for (int i = 0, size = todos.size(); i < size; i++) {
			ToTranslate todo = todos.get(i);
			if (!todo.predicate.test(tree)) continue;
			JCTree replaced = todo.treeTrans.apply(tree);
			if (replaced != null) {
				result = replaced;
				return;
			}
		}
	}
	//endregion visit

	public JCLiteral makeNullLiteral() {
		return make.Literal(TypeTag.BOT, null).setType(symtab.botType);
	}

	public JCExpression makeLit(Type type, Object value) {
		return make.Literal(type.getTag(), value).setType(type.constType(value));
	}
	public JCExpression makeString(String s) {
		return makeLit(symtab.stringType, s);
	}

	/**
	 * {@code Operators.resolveBinary} 不是公开 API，只能反射拿。
	 * <p>
	 * 构造出来的 {@link JCBinary} 不会再走 Attr，{@code operator} 字段得自己填，
	 * 否则 Lower 阶段读到 null 直接崩。
	 */
	private static final Method OPERATOR_METHOD = HopeReflect.nl(
	 () -> Operators.class.getDeclaredMethod("resolveBinary", DiagnosticPosition.class, Tag.class, Type.class, Type.class));

	public JCBinary makeBinary(Tag tag, JCExpression lhs, JCExpression rhs) {
		JCBinary tree = make.Binary(tag, lhs, rhs);
		tree.operator = HopeReflect.iv(OPERATOR_METHOD, operators, tree, tag, lhs.type, rhs.type);
		tree.type = tree.operator.type.getReturnType();
		return tree;
	}

	public JCFieldAccess makeSelect(ClassType owner, Name name) {
		return makeSelect((ClassSymbol) owner.tsym, name);
	}

	public JCFieldAccess makeSelect(ClassSymbol owner, Name name) {
		return makeSelect(make.Ident(owner), name, owner);
	}

	/**
	 * 用到{@link ToTranslate}时，make.Select需要改为用这个
	 */
	public JCFieldAccess makeSelect(JCExpression selected, Name name, TypeSymbol owner) {
		JCFieldAccess select  = make.Select(selected, name);
		var           symbols = List.from(owner.members().getSymbolsByName(name));
		if (symbols.size() > 1) {
			errorAtToplevel(selected, "Find more than one symbol for " + owner + "." + name + ": " + symbols);
			// 重载之间无法从名字判断意图，先取第一个让编译继续，错误已经报出去了
			select.sym = symbols.head;
		} else if (symbols.head == null) {
			errorAtToplevel(selected, "can't resolve symbol for " + owner + "." + name + ":" + selected);
			select.sym = symtab.errSymbol;
		} else {
			select.sym = symbols.head;
		}
		select.type = select.sym.type;
		return select;
	}

	/**
	 * 用到{@link ToTranslate}时，make.Select需要改为用这个
	 */
	public JCFieldAccess makeSelect(JCExpression selected, Name name, MethodSymbol ms) {
		JCFieldAccess select = make.Select(selected, name);
		if (ms == null) {
			errorAtToplevel(selected, "can't resolve method symbol for " + name + ":" + selected);
			select.sym = symtab.errSymbol;
		} else {
			select.sym = ms;
		}
		select.type = select.sym.type;
		return select;
	}

	/**
	 * 在源码位置上报错。
	 * <p>
	 * {@code Log.source} 是全局可变状态：不还原的话，后面所有诊断都会被记到这个文件上，
	 * 真实出错的位置就再也看不到了。
	 */
	private void errorAtToplevel(DiagnosticPosition pos, String message) {
		JavaFileObject prev = log.useSource(toplevel == null ? null : toplevel.sourcefile);
		try {
			log.error(pos, SPrinter.err(message));
		} finally {
			log.useSource(prev);
		}
	}

	public JCExpression defaultValue(Type type) {
		if (type.isPrimitive()) {
			TypeTag tag = type.getTag();
			return switch (tag) {
				case BYTE -> make.Literal((byte) 0);
				case CHAR -> make.Literal((char) 0);
				case SHORT -> make.Literal((short) 0);
				case LONG -> make.Literal(0L);
				case FLOAT -> make.Literal(0F);
				case INT -> make.Literal(0);
				case DOUBLE -> make.Literal(0D);
				case BOOLEAN -> make.Literal(false);
				case BOT -> make.Literal(tag, null);
				default -> throw new IllegalStateException("Unexpected value: " + tag);
			};
		}
		return make.Literal(TypeTag.BOT, null);
	}

	int localVarIndex = 0;

	public int nextLocalVarIndex() {
		return ++localVarIndex;
	}

	public LetExpr translateMethodBlockToLetExpr(JCMethodDecl methodDecl, Symbol owner) {
		return translateBlockToLetExpr(methodDecl.body, methodDecl.sym.getReturnType(), owner);
	}

	/**
	 * 必须在Attr之后
	 */
	public LetExpr translateBlockToLetExpr(JCBlock block, Type returnType, Symbol owner) {
		if (returnType == symtab.voidType) {
			throw new IllegalArgumentException("methodDecl is void method");
		}
		make.at(block);
		ListBuffer<JCStatement> defs      = new ListBuffer<>();
		VarSymbol               varSymbol = new VarSymbol(0, names.fromString(LOCAL_PREFIX + nextLocalVarIndex()), returnType, owner);
		defs.add(make.VarDef(varSymbol, defaultValue(returnType)));
		defs.appendList(new TreeCopier<Void>(make) {
			public JCTree visitReturn(ReturnTree node, Void unused) {
				return make.Exec(make.Assign(make.Ident(varSymbol), (JCExpression) node.getExpression()));
			}
		}.copy(block.stats));
		LetExpr letExpr = make.LetExpr(defs.toList(), make.Ident(varSymbol));
		letExpr.type = returnType;
		return letExpr;
	}

	public static class CheckException extends RuntimeException {
		public CheckException(String message) {
			super(message);
		}
	}
}

@FunctionalInterface
interface TriFunction<T, U, V, R> {
	R apply(T t, U u, V v);
}
