package modtools.annotations.unsafe;

import com.sun.source.doctree.*;
import com.sun.source.tree.ReturnTree;
import com.sun.source.util.DocTreePath;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.comp.Resolve;
import com.sun.tools.javac.tree.DCTree.DCReference;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.List;
import modtools.annotations.PrintHelper.SPrinter;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.*;

import static modtools.annotations.PrintHelper.SPrinter.println;

public class TopTranslator extends TreeTranslator {
	final JavacTrees trees;
	final Log        log;
	final Names      names;
	final Symtab     symtab;
	final TreeMaker  maker;
	final Resolve    resolve;

	public static final String LOCAL_PREFIX = "$$letexpr$$";

	static final boolean DEBUG_LOG = false;


	final String errorKey = "default.method.call";

	private TopTranslator(Context context) {
		context.put(TopTranslator.class, this);
		trees = JavacTrees.instance(context);
		log = Log.instance(context);
		names = Names.instance(context);
		symtab = Symtab.instance(context);
		maker = TreeMaker.instance(context);
		resolve = Resolve.instance(context);

		addDefaultTodo();
		Replace.bundles.put("compiler.err." + errorKey, "Default method call: {0}#{1}");
	}

	public void addDefaultTodo() {
		// 你的旧方法，现在可能可以简化或移除
		replaceMethod(symtab.classType.tsym, "componentType", "getComponentType");

		// --- 新的替换方式示例 ---
		// 1. List.of(...) -> Arrays.asList(...)
		// 首先，我们精确找到 List.of 和 Arrays.asList 的 MethodSymbol
		// List.of是静态方法，通常接受 Object... 或具体的类型数组
		MethodSymbol listOfMethod       = findMethodSymbol(symtab.listType.tsym, names.fromString("of"), -1, true); // -1 for varargs
		MethodSymbol arraysAsListMethod = findMethodSymbol(symtab.arraysType.tsym, names.fromString("asList"), -1, true);

		if (listOfMethod != null && arraysAsListMethod != null) {
			replaceMethod(listOfMethod, arraysAsListMethod,
			 (originalInvocation, originalSym, targetSym) -> {
				 // List.of(args) -> Arrays.asList(args)
				 // 参数列表保持不变
				 List<JCExpression> newArgs = originalInvocation.args;
				 // 方法选择表达式：Arrays.asList
				 JCExpression newMethodSelect = makeSelect(maker.QualIdent(targetSym.owner), targetSym.name, targetSym);
				 return maker.at(originalInvocation.pos).App(
					newMethodSelect, newArgs
				 );
			 }
			);
		} else {
			println("Warning: Could not find List.of or Arrays.asList for replacement.");
		}


		/* // 查找 String.repeat(int)
		MethodSymbol stringRepeatMethod = findMethodSymbol(symtab.stringType.tsym, names.fromString("repeat"), 1, false);
		// 查找 Strings.repeat(String, int)
		MethodSymbol stringsRepeatMethod = findMethodSymbol(symtab.enterClass(symtab.unnamedModule, names.fromString("arc.util.Strings")), names.fromString("repeat"), 2, true); // True for static

		if (stringRepeatMethod != null && stringsRepeatMethod != null) {
			replaceMethod(stringRepeatMethod, stringsRepeatMethod,
			 (originalInvocation, originalSym, targetSym) -> {
				 // Original: "ap".repeat(13) -> JCMethodInvocation (meth=JCFieldAccess("ap".repeat), args=[13])
				 // Target:   Strings.repeat("ap", 13) -> JCMethodInvocation (meth=JCFieldAccess(Strings.repeat), args=["ap", 13])

				 // 1. 获取原始调用的接收者 ("ap")
				 JCExpression originalReceiver = null;
				 if (originalInvocation.meth instanceof JCFieldAccess originalFieldAccess) {
					 originalReceiver = originalFieldAccess.selected;
				 } else if (originalInvocation.meth instanceof JCIdent) {
					 // This case means it's a direct call on 'this' or a static import, less common for 'string.repeat'
					 // If 'repeat' was called like 'repeat(13)' without receiver, it implies 'this.repeat(13)'
					 // We would need 'maker.This(currentClass.sym.type)' as receiver.
					 originalReceiver = maker.This(currentClass.sym.type); // Or whatever 'this' refers to
				 }

				 // 2. 构造新的参数列表，将原始接收者作为第一个参数
				 ListBuffer<JCExpression> newArgsBuffer = new ListBuffer<>();
				 if (originalReceiver != null) {
					 newArgsBuffer.add(originalReceiver);
				 }
				 newArgsBuffer.appendList(originalInvocation.args);
				 List<JCExpression> newArgs = newArgsBuffer.toList();

				 // 3. 构造新的方法选择表达式 (Strings.repeat)
				 JCExpression newMethodSelect = maker.Select(maker.QualIdent(targetSym.owner), targetSym.name);

				 // 4. 创建新的方法调用
				 return maker.at(originalInvocation.pos).Apply(
					List.nil(), // 泛型参数
					newMethodSelect,
					newArgs
				 );
			 }
			);
		} else {
			println("Warning: Could not find String.repeat or arc.util.Strings.repeat for replacement.");
		} */
	}

	/**
	 * 辅助方法：根据方法名、参数数量和是否静态查找MethodSymbol。
	 * 优化了根据参数数量查找特定重载。
	 * @param ownerSym   方法的拥有者 TypeSymbol
	 * @param methodName 方法名
	 * @param paramCount 期望的参数数量，-1 表示不关心数量或变长参数
	 * @param isStatic   是否期望是静态方法
	 * @return 匹配的 MethodSymbol，如果找不到则为 null
	 */
	private MethodSymbol findMethodSymbol(TypeSymbol ownerSym, Name methodName, int paramCount, boolean isStatic) {
		return (MethodSymbol) ownerSym.members().findFirst(methodName,
		 s -> s.isPublic() && s instanceof MethodSymbol ms && (isStatic == ms.isStatic()) && ((BooleanSupplier) () -> {
			 if (paramCount == -1) { // 变长参数或不关心参数数量
				 return true;
			 }
			 // 对于精确参数数量匹配，考虑变长参数的实际参数数量。
			 // Javac在解析时会把变长参数的多个实参包装成一个数组实参。
			 // 但在这里我们看的是方法的定义，所以直接比较params.size()
			 // 或者检查 isVarArgs 并比较除最后一个（数组）参数外的参数数量
			 if (ms.isVarArgs() && paramCount >= ms.params.size() - 1) { // if varargs, allow more params
				 return true;
			 }
			 return ms.params.size() == paramCount;
		 }).getAsBoolean());
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
			 // 使用传入的transformer函数进行转换
			 JCMethodInvocation newMethodInvocation = transformer.apply(
				methodInvocation, originalMethodSymbol, targetMethodSymbol
			 );

			 // 更新类型信息（非常重要！）
			 // 这里我们假设 transformer 已经构建了一个有效的 JCMethodInvocation，
			 // 其内部的 `meth` 和 `args` 已经正确设置。
			 // 此时，需要确保新生成的方法调用及其子节点拥有正确的类型信息。
			 newMethodInvocation.type = targetMethodSymbol.type.getReturnType();
			 newMethodInvocation.meth.type = targetMethodSymbol.type;

			 // 对于 maker.Select 或 maker.Ident，其 sym 字段的 type 也需要更新
			 Symbol newMethodSelectSym = TreeInfo.symbolFor(newMethodInvocation.meth);
			 if (newMethodSelectSym != null) {
				 newMethodSelectSym.type = targetMethodSymbol.type;
			 }

			 if (DEBUG_LOG) println("Transformed: " + methodInvocation + " to " + newMethodInvocation);
			 return newMethodInvocation;
		 }
		));
	}


	// === 以下是你原本已有的辅助方法和类，保持不变或略作调整 ===
	// (为了代码完整性，我保留了你的大部分代码，只增加了上述修改和相关的辅助方法)

	private void replaceMethod(TypeSymbol thisSymbol, String originalMethodName, String targetMethodName) {
		// 这个旧方法现在可能不太适用你的新需求，因为它查找的是无参方法。
		// 你可以根据需要更新它，或者只使用新的 replaceMethod。
		MethodSymbol originalSymbol = (MethodSymbol) thisSymbol.members().findFirst(names.fromString(originalMethodName),
		 s -> s.isPublic() && s instanceof MethodSymbol ms && ms.params.isEmpty());
		MethodSymbol targetSymbol = (MethodSymbol) thisSymbol.members().findFirst(names.fromString(targetMethodName),
		 s -> s.isPublic() && s instanceof MethodSymbol ms && ms.params.isEmpty());
		if (originalSymbol != null && targetSymbol != null) {
			replaceMethod(originalSymbol, targetSymbol, (originalInvocation, originalSym, targetSym) -> {
				// 这是一个简单的替换，只改方法名和符号，参数不变
				// 类似于你旧方法中的逻辑，但现在由lambda处理
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
				return originalInvocation; // 返回修改后的原invocation
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
		return currentMethod != null && currentMethod.sym.getAnnotation(annoType) != null
		       || currentClass != null && currentClass.sym.getAnnotation(annoType) != null;
	}

	public JCExpression makeClassExpr(ClassSymbol classSymbol, Symbol owner) {
		if (classSymbol.isPublic()) return maker.ClassLiteral(classSymbol);

		// loadClass(String)
		MethodSymbol forName = (MethodSymbol) symtab.classType.tsym.members().findFirst(names.fromString("forName"),
		 s -> s.isPublic() && s instanceof MethodSymbol ms && ms.params.size() == 1 && ms.params.get(0).type.tsym == symtab.stringType.tsym);
		JCMethodInvocation apply = maker.App(
		 makeSelect(maker.Ident(symtab.classType.tsym),
			names.fromString("forName"), forName), List.of(makeString(classSymbol.flatName().toString()))
		);
		// 创建一个局部变量 %LOCAL_PREFIX%
		VarSymbol localSym = new VarSymbol(0, names.fromString(LOCAL_PREFIX + classSymbol.name), symtab.classType, owner);
		localSym.flags_field |= Flags.LocalVarFlags;
		JCVariableDecl local = maker.VarDef(localSym, apply);

		// let (local = %moduleRep[0]%.class.getClassLoader().loadClass("%classSymbol.name%");) in local
		return maker.LetExpr(List.of(local), maker.Ident(local)).setType(symtab.classType);
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

		/** 此时tree的symbol都是有的，不能用{@link #getSymbol(JCTree)}，要用{@link TreeInfo#symbolFor(JCTree)} */
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
		todos.stream().filter(todo -> todo.predicate.test(tree))
		 .map(todo -> todo.treeTrans.apply(tree))
		 .filter(Objects::nonNull)
		 .limit(1)
		 .forEach(tree2 -> result = tree2);
	}
	//endregion visit

	public JCLiteral makeNullLiteral() {
		return maker.Literal(TypeTag.BOT, null).setType(symtab.botType);
	}

	public JCExpression makeString(String s) {
		return Replace.desugarStringTemplate.makeString(s);
	}

	/**
	 * 用到{@link ToTranslate}时，make.Binary需要改为用这个
	 */
	public JCBinary makeBinary(Tag tag, JCExpression lhs, JCExpression rhs) {
		return Replace.desugarStringTemplate.makeBinary(tag, lhs, rhs);
	}

	public JCFieldAccess makeSelect(ClassType owner, Name name) {
		return makeSelect((ClassSymbol) owner.tsym, name);
	}

	public JCFieldAccess makeSelect(ClassSymbol owner, Name name) {
		return makeSelect(maker.Ident(owner), name, owner);
	}

	/**
	 * 用到{@link ToTranslate}时，make.Select需要改为用这个
	 */
	public JCFieldAccess makeSelect(JCExpression selected, Name name, TypeSymbol owner) {
		JCFieldAccess select  = maker.Select(selected, name);
		var           symbols = List.from(owner.members().getSymbolsByName(name));
		if (symbols.size() > 1) {
			log.useSource(toplevel.sourcefile);
			log.error(SPrinter.err("Find more than one symbol for " + owner + "." + name + ": " + symbols));
			// throw new CheckException(""); // 生产环境不应抛出，而是更优雅地处理
			// 为避免编译中断，这里尝试选择一个，但应警告开发者
			select.sym = (Symbol) symbols.head; // 尝试选择第一个，可能不准确
		} else if (symbols.head == null) {
			log.useSource(toplevel.sourcefile);
			log.error(SPrinter.err("can't resolve symbol for " + owner + "." + name + ":" + selected));
			// throw new CheckException(""); // 生产环境不应抛出
			select.sym = symtab.errSymbol; // 设置为错误符号
		} else {
			select.sym = (Symbol) symbols.head;
		}
		select.type = select.sym.type;
		return select;
	}

	/**
	 * 用到{@link ToTranslate}时，make.Select需要改为用这个
	 */
	public JCFieldAccess makeSelect(JCExpression selected, Name name, MethodSymbol ms) {
		JCFieldAccess select = maker.Select(selected, name);
		if (ms == null) {
			log.useSource(toplevel.sourcefile);
			log.error(SPrinter.err("can't resolve method symbol for " + name + ":" + selected));
			// throw new CheckException("");
			select.sym = symtab.errSymbol;
		} else {
			select.sym = ms;
		}
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
		maker.at(block);
		ListBuffer<JCStatement> defs      = new ListBuffer<>();
		VarSymbol               varSymbol = new VarSymbol(0, names.fromString(LOCAL_PREFIX + nextLocalVarIndex()), returnType, owner);
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

	public ClassSymbol getClassSymbolByDoc(JCIdent i) {
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
		if (tree instanceof LetExpr) return null;
		return trees.getElement(trees.getPath(toplevel, tree));
	}

	public static class CheckException extends RuntimeException {
		public CheckException(String message) {
			super(message);
		}
	}
}

// 定义一个三元函数式接口
@FunctionalInterface
interface TriFunction<T, U, V, R> {
	R apply(T t, U u, V v);
}
