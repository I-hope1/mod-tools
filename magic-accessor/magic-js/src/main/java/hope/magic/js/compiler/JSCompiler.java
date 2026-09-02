package hope.magic.js.compiler;

import hope.magic.js.ast.*;
import hope.magic.js.parser.*;
import hope.magic.js.runtime.*;
import hope.magic.runtime.Magic;
import org.objectweb.asm.*;

import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class JSCompiler {
	private static final AtomicInteger SCRIPT_ID = new AtomicInteger(0);

	private static final Handle BSM_GET_PROP = new Handle(
		Opcodes.H_INVOKESTATIC,
		Type.getInternalName(JSLinker.class),
		"bootstrapGetProp",
		MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class, String.class).toMethodDescriptorString(),
		false
	);

	private static final Handle BSM_GET_PROP_INT = new Handle(
		Opcodes.H_INVOKESTATIC,
		Type.getInternalName(JSLinker.class),
		"bootstrapGetPropInt",
		MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class, String.class).toMethodDescriptorString(),
		false
	);

	private static final Handle BSM_GET_PROP_DOUBLE = new Handle(
		Opcodes.H_INVOKESTATIC,
		Type.getInternalName(JSLinker.class),
		"bootstrapGetPropDouble",
		MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class, String.class).toMethodDescriptorString(),
		false
	);

	private static final Handle BSM_GET_PROP_LONG = new Handle(
		Opcodes.H_INVOKESTATIC,
		Type.getInternalName(JSLinker.class),
		"bootstrapGetPropLong",
		MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class, String.class).toMethodDescriptorString(),
		false
	);

	private static final Handle BSM_SET_PROP = new Handle(
	 Opcodes.H_INVOKESTATIC,
	 Type.getInternalName(JSLinker.class),
	 "bootstrapSetProp",
	 MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class, String.class).toMethodDescriptorString(),
	 false
	);

	private static final Handle BSM_INVOKE = new Handle(
	 Opcodes.H_INVOKESTATIC,
	 Type.getInternalName(JSLinker.class),
	 "bootstrapInvoke",
	 MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class, String.class).toMethodDescriptorString(),
	 false
	);

	private static final Handle BSM_NEW = new Handle(
	 Opcodes.H_INVOKESTATIC,
	 Type.getInternalName(JSLinker.class),
	 "bootstrapNew",
	 MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class).toMethodDescriptorString(),
	 false
	);

	private static final Handle BSM_BINARY_OP = new Handle(
	 Opcodes.H_INVOKESTATIC,
	 Type.getInternalName(JSLinker.class),
	 "bootstrapBinaryOp",
	 MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class, String.class).toMethodDescriptorString(),
	 false
	);

	private static final Handle BSM_GET_INDEX = new Handle(
	 Opcodes.H_INVOKESTATIC,
	 Type.getInternalName(JSLinker.class),
	 "bootstrapGetIndex",
	 MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class).toMethodDescriptorString(),
	 false
	);

	private static final Handle BSM_SET_INDEX = new Handle(
	 Opcodes.H_INVOKESTATIC,
	 Type.getInternalName(JSLinker.class),
	 "bootstrapSetIndex",
	 MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class).toMethodDescriptorString(),
	 false
	);

	public static JSScript compile(String code) throws Exception {
		JSLexer      lexer   = new JSLexer(code);
		JSParser     parser  = new JSParser(lexer.tokenize());
		Node.Program program = parser.parse();
		return JSCompiler.compile(program);
	}


	public static JSScript compile(Node.Program program) throws Exception {
		Node.Program foldedProgram = ConstantFolder.fold(program);
		String className = "hope/magic/gen/MagicJSScript_" + SCRIPT_ID.incrementAndGet();
		byte[] classBytes = generateScriptBytecode(className, foldedProgram);

		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		if (loader == null) loader = JSCompiler.class.getClassLoader();
		Class<?> loadedClass = Magic.defineClass(loader, classBytes);
		return (JSScript) loadedClass.getDeclaredConstructor().newInstance();
	}

	private static byte[] generateScriptBytecode(String className, Node.Program program) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, className, null, Type.getInternalName(JSScript.class), null);

		// 默认构造函数 <init>()
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(JSScript.class), "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();

		// public Object run(JSContext cx)
		MethodVisitor runMv = cw.visitMethod(
		 Opcodes.ACC_PUBLIC,
		 "run",
		 "(L" + Type.getInternalName(JSContext.class) + ";)Ljava/lang/Object;",
		 null,
		 new String[]{"java/lang/Throwable"}
		);
		runMv.visitCode();

		CompileContext ctx = new CompileContext(runMv, className, program);
		registerTryCatchBlocks(program, runMv, ctx.tryCatchMap);
		preScanVariables(program, ctx);

		// 顶层函数提升 (Hoisting)
		List<Node.FunctionDecl> topFuncDecls = new ArrayList<>();
		collectFunctionDecls(program, topFuncDecls);
		for (Node.FunctionDecl fd : topFuncDecls) {
			String funcClass = generateFunctionClass(fd.name, fd.params, fd.body);
			int slot = JSContext.getGlobalSlot(fd.name);
			runMv.visitVarInsn(Opcodes.ALOAD, 1); // cx
			pushInt(runMv, slot);
			runMv.visitTypeInsn(Opcodes.NEW, funcClass);
			runMv.visitInsn(Opcodes.DUP);
			runMv.visitVarInsn(Opcodes.ALOAD, 1);
			runMv.visitMethodInsn(Opcodes.INVOKESPECIAL, funcClass, "<init>", "(L" + Type.getInternalName(JSContext.class) + ";)V", false);
			runMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSContext.class), "setSlot", "(ILjava/lang/Object;)V", false);
		}

		// 遍历顶层语句
		boolean hasReturned = false;
		for (int i = 0; i < program.body.size(); i++) {
			Node    stmt   = program.body.get(i);
			boolean isLast = (i == program.body.size() - 1);
			if (isLast && (stmt instanceof Node.ExprStmt exprStmt)) {
				compileNode(exprStmt.expr, ctx, true);
				runMv.visitInsn(Opcodes.ARETURN);
				hasReturned = true;
			} else {
				compileNode(stmt, ctx, false);
			}
		}

		if (!hasReturned) {
			runMv.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(JSUndefined.class), "INSTANCE", "L" + Type.getInternalName(JSUndefined.class) + ";");
			runMv.visitInsn(Opcodes.ARETURN);
		}

		runMv.visitMaxs(0, 0);
		runMv.visitEnd();

		for (int i = 0; i < ctx.nextSiteId; i++) {
			for (int s = 0; s < 3; s++) {
				cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "$shape_" + i + "_" + s, "L" + Type.getInternalName(JSShape.class) + ";", null, null).visitEnd();
				cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "$offset_" + i + "_" + s, "I", null, null).visitEnd();
			}
		}

		cw.visitEnd();
		return cw.toByteArray();
	}

	enum VarType {
		OBJECT,
		DOUBLE,
		LONG,
		INT;

		boolean isPrimitive() { return this != OBJECT; }
	}

	record LocalVar(int slot, VarType type) {
		boolean isInt() { return type == VarType.INT; }
		boolean isLong() { return type == VarType.LONG; }
		boolean isDouble() { return type == VarType.DOUBLE; }
		boolean isPrimitive() { return type != VarType.OBJECT; }
	}

	private static class TryCatchLabels {
		final Label tryStart;
		final Label tryEnd;
		final Label catchHandler;
		final Label afterTryCatch;

		TryCatchLabels(Label tryStart, Label tryEnd, Label catchHandler, Label afterTryCatch) {
			this.tryStart = tryStart;
			this.tryEnd = tryEnd;
			this.catchHandler = catchHandler;
			this.afterTryCatch = afterTryCatch;
		}
	}

	private static class CompileContext {
		final MethodVisitor         mv;
		final String                className;
		final Node                  rootNode;
		final Map<String, LocalVar> locals = new LinkedHashMap<>();
		final Map<String, VarType>  preInferredTypes = new LinkedHashMap<>();
		final Map<Node.TryStmt, TryCatchLabels> tryCatchMap = new IdentityHashMap<>();
		int nextLocalSlot = 2; // Slot 0 is 'this', Slot 1 is 'cx' (JSContext)
		int nextSiteId = 0;
		int tempVarCounter = 0;
		boolean isFunction = false;
		String functionName = null;

		final Deque<Label> breakTargets    = new ArrayDeque<>();
		final Deque<Label> continueTargets = new ArrayDeque<>();

		CompileContext(MethodVisitor mv, String className, Node rootNode) {
			this.mv = mv;
			this.className = className;
			this.rootNode = rootNode;
		}

		int allocTempSlot() {
			return nextLocalSlot++;
		}

		int allocDoubleTempSlot() {
			int slot = nextLocalSlot;
			nextLocalSlot += 2;
			return slot;
		}

		LocalVar declareLocal(String name, VarType type) {
			int slot = nextLocalSlot;
			nextLocalSlot += (type == VarType.LONG || type == VarType.DOUBLE ? 2 : 1);
			LocalVar var = new LocalVar(slot, type);
			locals.put(name, var);
			return var;
		}

		LocalVar getLocal(String name) {
			return locals.get(name);
		}
	}

	private static VarType inferVarType(Node node, CompileContext ctx) {
		if (node == null) return VarType.OBJECT;
		if (node instanceof Node.LiteralExpr lit) {
			Object val = lit.value;
			if (val instanceof Integer || val instanceof Short || val instanceof Byte) {
				return VarType.INT;
			}
			if (val instanceof Long lVal) {
				if (lVal >= Integer.MIN_VALUE && lVal <= Integer.MAX_VALUE) {
					return VarType.INT;
				}
				return VarType.LONG;
			}
			if (val instanceof Number num) {
				double d = num.doubleValue();
				if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE && d == Math.floor(d) && !Double.isInfinite(d)) {
					return VarType.INT;
				}
				if (d == Math.floor(d) && !Double.isInfinite(d)) {
					return VarType.LONG;
				}
				return VarType.DOUBLE;
			}
			if (val instanceof Boolean) {
				return VarType.OBJECT;
			}
		}
		if (node instanceof Node.IdentifierExpr ident) {
			if (ctx != null) {
				LocalVar var = ctx.getLocal(ident.name);
				if (var != null) return var.type;
				VarType pre = ctx.preInferredTypes.get(ident.name);
				if (pre != null) return pre;
			}
		}
		if (node instanceof Node.BinaryExpr bin) {
			TokenType op = bin.op;
			VarType left = inferVarType(bin.left, ctx);
			VarType right = inferVarType(bin.right, ctx);
			if (left == VarType.INT && right == VarType.INT) {
				if (op == TokenType.SLASH) return VarType.DOUBLE;
				return VarType.INT;
			}
			if (left == VarType.LONG && right == VarType.LONG && op != TokenType.SLASH) {
				return VarType.LONG;
			}
			// JS 规范：* / - % 是纯数值运算，只要不是纯整型，统一推断为 DOUBLE
			if (op == TokenType.STAR || op == TokenType.SLASH || op == TokenType.MINUS || op == TokenType.PERCENT) {
				return VarType.DOUBLE;
			}
			// 如果 + 且两侧均为数值类型，则为 DOUBLE
			if (op == TokenType.PLUS && !isStringExpr(bin.left) && !isStringExpr(bin.right)) {
				if (left.isPrimitive() && right.isPrimitive()) return VarType.DOUBLE;
				if ((isNumeric(left) && isNumeric(right)) || (isNumericExpr(bin.left) && isNumericExpr(bin.right))) {
					return VarType.DOUBLE;
				}
			}
		}
		if (node instanceof Node.UnaryExpr un) {
			TokenType op = un.op;
			if (op == TokenType.PLUS_PLUS || op == TokenType.MINUS_MINUS) {
				VarType inner = inferVarType(un.expr, ctx);
				return inner.isPrimitive() ? inner : VarType.INT;
			}
			if (op == TokenType.MINUS) {
				return inferVarType(un.expr, ctx);
			}
			if (op == TokenType.NOT) {
				return VarType.INT;
			}
		}
		if (node instanceof Node.TernaryExpr ternary) {
			VarType thenType = inferVarType(ternary.thenExpr, ctx);
			VarType elseType = inferVarType(ternary.elseExpr, ctx);
			if (thenType == elseType) return thenType;
			if (thenType.isPrimitive() && elseType.isPrimitive()) return VarType.DOUBLE;
			return VarType.OBJECT;
		}
		return VarType.OBJECT;
	}

	private static boolean isStringExpr(Node node) {
		if (node == null) return false;
		if (node instanceof Node.LiteralExpr lit && lit.value instanceof String) return true;
		if (node instanceof Node.BinaryExpr bin && bin.op == TokenType.PLUS) {
			return isStringExpr(bin.left) || isStringExpr(bin.right);
		}
		return false;
	}

	private static boolean isNumeric(VarType t) {
		return t == VarType.INT || t == VarType.LONG || t == VarType.DOUBLE;
	}

	private static boolean isNumericExpr(Node node) {
		if (node == null) return false;
		if (node instanceof Node.LiteralExpr lit && lit.value instanceof Number) return true;
		if (node instanceof Node.BinaryExpr bin) {
			if (bin.op == TokenType.STAR || bin.op == TokenType.SLASH || bin.op == TokenType.MINUS || bin.op == TokenType.PERCENT) return true;
			if (bin.op == TokenType.PLUS && isNumericExpr(bin.left) && isNumericExpr(bin.right)) {
				return true;
			}
		}
		if (node instanceof Node.UnaryExpr un && (un.op == TokenType.MINUS || un.op == TokenType.PLUS_PLUS || un.op == TokenType.MINUS_MINUS)) return true;
		return false;
	}

	private static VarType mergeTypes(VarType t1, VarType t2) {
		if (t1 == null) return t2;
		if (t2 == null) return t1;
		if (t1 == t2) return t1;
		if (isNumeric(t1) && isNumeric(t2)) {
			if (t1 == VarType.DOUBLE || t2 == VarType.DOUBLE) return VarType.DOUBLE;
			if (t1 == VarType.LONG || t2 == VarType.LONG) return VarType.LONG;
			return VarType.INT;
		}
		return VarType.OBJECT;
	}

	private static void preScanVariables(Node root, CompileContext ctx) {
		if (root == null || ctx == null) return;
		List<Node.VarDecl> varDecls = new ArrayList<>();
		collectVarDecls(root, varDecls);

		for (Node.VarDecl decl : varDecls) {
			VarType init = decl.init != null ? inferVarType(decl.init, ctx) : null;
			ctx.preInferredTypes.put(decl.name, init != null ? init : VarType.INT);
		}

		for (int round = 0; round < 3; round++) {
			boolean changed = false;
			for (Node.VarDecl decl : varDecls) {
				VarType current = ctx.preInferredTypes.get(decl.name);
				VarType assigned = findAssignedType(decl.name, root, ctx);
				VarType merged = mergeTypes(current, assigned);
				if (merged != null && merged != current) {
					ctx.preInferredTypes.put(decl.name, merged);
					changed = true;
				}
			}
			if (!changed) break;
		}
	}

	private static void collectVarDecls(Node node, List<Node.VarDecl> out) {
		if (node == null) return;
		if (node instanceof Node.VarDecl decl) {
			out.add(decl);
			if (decl.init != null) collectVarDecls(decl.init, out);
		} else if (node instanceof Node.Program prog) {
			for (Node s : prog.body) collectVarDecls(s, out);
		} else if (node instanceof Node.BlockStmt block) {
			for (Node s : block.statements) collectVarDecls(s, out);
		} else if (node instanceof Node.IfStmt ifStmt) {
			collectVarDecls(ifStmt.thenBranch, out);
			collectVarDecls(ifStmt.elseBranch, out);
		} else if (node instanceof Node.WhileStmt whileStmt) {
			collectVarDecls(whileStmt.body, out);
		} else if (node instanceof Node.ForStmt forStmt) {
			collectVarDecls(forStmt.init, out);
			collectVarDecls(forStmt.body, out);
		} else if (node instanceof Node.ForOfStmt forOf) {
			if (forOf.isDeclaration) out.add(new Node.VarDecl(forOf.varName, new Node.LiteralExpr(null, forOf.line, forOf.column), forOf.line, forOf.column));
			collectVarDecls(forOf.body, out);
		} else if (node instanceof Node.ForInStmt forIn) {
			if (forIn.isDeclaration) out.add(new Node.VarDecl(forIn.varName, new Node.LiteralExpr("", forIn.line, forIn.column), forIn.line, forIn.column));
			collectVarDecls(forIn.body, out);
		} else if (node instanceof Node.DoWhileStmt doWhile) {
			collectVarDecls(doWhile.body, out);
		} else if (node instanceof Node.TryStmt tryStmt) {
			collectVarDecls(tryStmt.tryBlock, out);
			if (tryStmt.catchParam != null) out.add(new Node.VarDecl(tryStmt.catchParam, new Node.LiteralExpr(null, tryStmt.line, tryStmt.column), tryStmt.line, tryStmt.column));
			if (tryStmt.catchBlock != null) collectVarDecls(tryStmt.catchBlock, out);
			if (tryStmt.finallyBlock != null) collectVarDecls(tryStmt.finallyBlock, out);
		} else if (node instanceof Node.SwitchStmt switchStmt) {
			for (Node.CaseClause c : switchStmt.cases) {
				for (Node s : c.consequent) collectVarDecls(s, out);
			}
		}
	}

	private static void collectFunctionDecls(Node node, List<Node.FunctionDecl> out) {
		if (node == null) return;
		if (node instanceof Node.FunctionDecl decl) {
			out.add(decl);
		} else if (node instanceof Node.Program prog) {
			for (Node s : prog.body) collectFunctionDecls(s, out);
		} else if (node instanceof Node.BlockStmt block) {
			for (Node s : block.statements) collectFunctionDecls(s, out);
		} else if (node instanceof Node.IfStmt ifStmt) {
			collectFunctionDecls(ifStmt.thenBranch, out);
			collectFunctionDecls(ifStmt.elseBranch, out);
		} else if (node instanceof Node.WhileStmt whileStmt) {
			collectFunctionDecls(whileStmt.body, out);
		} else if (node instanceof Node.ForStmt forStmt) {
			collectFunctionDecls(forStmt.body, out);
		} else if (node instanceof Node.ForOfStmt forOf) {
			collectFunctionDecls(forOf.body, out);
		} else if (node instanceof Node.ForInStmt forIn) {
			collectFunctionDecls(forIn.body, out);
		} else if (node instanceof Node.DoWhileStmt doWhile) {
			collectFunctionDecls(doWhile.body, out);
		} else if (node instanceof Node.TryStmt tryStmt) {
			collectFunctionDecls(tryStmt.tryBlock, out);
			if (tryStmt.catchBlock != null) collectFunctionDecls(tryStmt.catchBlock, out);
			if (tryStmt.finallyBlock != null) collectFunctionDecls(tryStmt.finallyBlock, out);
		} else if (node instanceof Node.SwitchStmt switchStmt) {
			for (Node.CaseClause c : switchStmt.cases) {
				for (Node s : c.consequent) collectFunctionDecls(s, out);
			}
		}
	}

	private static void registerTryCatchBlocks(Node node, MethodVisitor mv, Map<Node.TryStmt, TryCatchLabels> tryCatchMap) {
		if (node == null) return;
		if (node instanceof Node.TryStmt tryStmt) {
			Label tryStart = new Label();
			Label tryEnd = new Label();
			Label catchHandler = new Label();
			Label afterTryCatch = new Label();
			tryCatchMap.put(tryStmt, new TryCatchLabels(tryStart, tryEnd, catchHandler, afterTryCatch));
			if (tryStmt.catchBlock != null) {
				mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Throwable");
			}
			registerTryCatchBlocks(tryStmt.tryBlock, mv, tryCatchMap);
			if (tryStmt.catchBlock != null) registerTryCatchBlocks(tryStmt.catchBlock, mv, tryCatchMap);
			if (tryStmt.finallyBlock != null) registerTryCatchBlocks(tryStmt.finallyBlock, mv, tryCatchMap);
		} else if (node instanceof Node.Program prog) {
			for (Node s : prog.body) registerTryCatchBlocks(s, mv, tryCatchMap);
		} else if (node instanceof Node.BlockStmt block) {
			for (Node s : block.statements) registerTryCatchBlocks(s, mv, tryCatchMap);
		} else if (node instanceof Node.IfStmt ifStmt) {
			registerTryCatchBlocks(ifStmt.thenBranch, mv, tryCatchMap);
			registerTryCatchBlocks(ifStmt.elseBranch, mv, tryCatchMap);
		} else if (node instanceof Node.WhileStmt whileStmt) {
			registerTryCatchBlocks(whileStmt.body, mv, tryCatchMap);
		} else if (node instanceof Node.ForStmt forStmt) {
			registerTryCatchBlocks(forStmt.body, mv, tryCatchMap);
		} else if (node instanceof Node.ForOfStmt forOf) {
			registerTryCatchBlocks(forOf.body, mv, tryCatchMap);
		} else if (node instanceof Node.ForInStmt forIn) {
			registerTryCatchBlocks(forIn.body, mv, tryCatchMap);
		} else if (node instanceof Node.DoWhileStmt doWhile) {
			registerTryCatchBlocks(doWhile.body, mv, tryCatchMap);
		} else if (node instanceof Node.SwitchStmt switchStmt) {
			for (Node.CaseClause c : switchStmt.cases) {
				for (Node s : c.consequent) registerTryCatchBlocks(s, mv, tryCatchMap);
			}
		}
	}

	private static VarType preInferVarType(Node.VarDecl varDecl, CompileContext ctx) {
		if (ctx != null && ctx.preInferredTypes.containsKey(varDecl.name)) {
			return ctx.preInferredTypes.get(varDecl.name);
		}
		VarType initType = varDecl.init != null ? inferVarType(varDecl.init, ctx) : null;
		if (ctx != null && ctx.rootNode != null) {
			VarType assigned = findAssignedType(varDecl.name, ctx.rootNode, ctx);
			VarType merged = mergeTypes(initType, assigned);
			if (merged != null) return merged;
		}
		if (initType != null) return initType;
		return VarType.INT;
	}

	private static VarType findAssignedType(String name, Node node, CompileContext ctx) {
		if (node == null) return null;
		if (node instanceof Node.Program prog) {
			VarType res = null;
			for (Node s : prog.body) {
				res = mergeTypes(res, findAssignedType(name, s, ctx));
			}
			return res;
		} else if (node instanceof Node.BlockStmt block) {
			VarType res = null;
			for (Node s : block.statements) {
				res = mergeTypes(res, findAssignedType(name, s, ctx));
			}
			return res;
		} else if (node instanceof Node.IfStmt ifStmt) {
			VarType t1 = findAssignedType(name, ifStmt.thenBranch, ctx);
			VarType t2 = ifStmt.elseBranch != null ? findAssignedType(name, ifStmt.elseBranch, ctx) : null;
			return mergeTypes(t1, t2);
		} else if (node instanceof Node.WhileStmt whileStmt) {
			return findAssignedType(name, whileStmt.body, ctx);
		} else if (node instanceof Node.ForStmt forStmt) {
			VarType tInit = forStmt.init != null ? findAssignedType(name, forStmt.init, ctx) : null;
			VarType tUpdate = forStmt.update != null ? findAssignedType(name, forStmt.update, ctx) : null;
			VarType tBody = findAssignedType(name, forStmt.body, ctx);
			return mergeTypes(tInit, mergeTypes(tUpdate, tBody));
		} else if (node instanceof Node.ForOfStmt forOf) {
			if (forOf.varName.equals(name)) return VarType.OBJECT;
			return findAssignedType(name, forOf.body, ctx);
		} else if (node instanceof Node.ForInStmt forIn) {
			if (forIn.varName.equals(name)) return VarType.OBJECT;
			return findAssignedType(name, forIn.body, ctx);
		} else if (node instanceof Node.DoWhileStmt doWhile) {
			return findAssignedType(name, doWhile.body, ctx);
		} else if (node instanceof Node.TryStmt tryStmt) {
			VarType t1 = findAssignedType(name, tryStmt.tryBlock, ctx);
			VarType t2 = tryStmt.catchBlock != null ? findAssignedType(name, tryStmt.catchBlock, ctx) : null;
			VarType t3 = tryStmt.finallyBlock != null ? findAssignedType(name, tryStmt.finallyBlock, ctx) : null;
			return mergeTypes(t1, mergeTypes(t2, t3));
		} else if (node instanceof Node.SwitchStmt switchStmt) {
			VarType res = null;
			for (Node.CaseClause c : switchStmt.cases) {
				for (Node s : c.consequent) {
					res = mergeTypes(res, findAssignedType(name, s, ctx));
				}
			}
			return res;
		} else if (node instanceof Node.ExprStmt exprStmt) {
			return findAssignedType(name, exprStmt.expr, ctx);
		} else if (node instanceof Node.AssignExpr assign) {
			if (assign.target instanceof Node.IdentifierExpr ident && ident.name.equals(name)) {
				if (assign.op == TokenType.SLASH_ASSIGN) return VarType.DOUBLE;
				if (assign.op == TokenType.PLUS_ASSIGN || assign.op == TokenType.MINUS_ASSIGN || assign.op == TokenType.STAR_ASSIGN) {
					VarType valType = inferVarType(assign.value, ctx);
					if (isNumeric(valType)) {
						return valType == VarType.DOUBLE ? VarType.DOUBLE : (valType == VarType.LONG ? VarType.LONG : VarType.INT);
					}
					if (assign.value instanceof Node.MemberAccessExpr || assign.value instanceof Node.IndexAccessExpr || isNumericExpr(assign.value)) {
						return VarType.DOUBLE;
					}
					return valType;
				}
				VarType valType = inferVarType(assign.value, ctx);
				if (valType == VarType.OBJECT && (assign.value instanceof Node.MemberAccessExpr || isNumericExpr(assign.value))) {
					if (ctx != null) {
						VarType pre = ctx.preInferredTypes.get(name);
						if (pre != null && isNumeric(pre)) return VarType.DOUBLE;
					}
				}
				return valType;
			}
		} else if (node instanceof Node.TernaryExpr ternary) {
			VarType t1 = findAssignedType(name, ternary.thenExpr, ctx);
			VarType t2 = findAssignedType(name, ternary.elseExpr, ctx);
			return mergeTypes(t1, t2);
		}
		return null;
	}

	private static boolean isZeroLiteral(Node node) {
		if (node instanceof Node.LiteralExpr lit) {
			if (lit.value instanceof Number num) {
				return num.doubleValue() == 0.0;
			}
		}
		return false;
	}

	private static boolean isLiteralNull(Node node) {
		return node instanceof Node.LiteralExpr lit && lit.value == null;
	}

	private static boolean isLiteralUndefined(Node node) {
		return node instanceof Node.LiteralExpr lit && lit.value == JSUndefined.INSTANCE;
	}

	private static boolean isLiteralBoolean(Node node) {
		return node instanceof Node.LiteralExpr lit && lit.value instanceof Boolean;
	}

	private static boolean isLiteralNumber(Node node) {
		return node instanceof Node.LiteralExpr lit && lit.value instanceof Number;
	}

	private static boolean isLiteralString(Node node) {
		return node instanceof Node.LiteralExpr lit && lit.value instanceof String;
	}

	private static void pushInt(MethodVisitor mv, int iVal) {
		if (iVal == -1) {
			mv.visitInsn(Opcodes.ICONST_M1);
		} else if (iVal >= 0 && iVal <= 5) {
			mv.visitInsn(Opcodes.ICONST_0 + iVal);
		} else if (iVal >= Byte.MIN_VALUE && iVal <= Byte.MAX_VALUE) {
			mv.visitIntInsn(Opcodes.BIPUSH, iVal);
		} else if (iVal >= Short.MIN_VALUE && iVal <= Short.MAX_VALUE) {
			mv.visitIntInsn(Opcodes.SIPUSH, iVal);
		} else {
			mv.visitLdcInsn(iVal);
		}
	}

	private static void compileNode(Node node, CompileContext ctx, boolean needResult) {
		MethodVisitor mv = ctx.mv;

		if (node instanceof Node.VarDecl varDecl) {
			VarType  type = preInferVarType(varDecl, ctx);
			LocalVar var  = ctx.declareLocal(varDecl.name, type);
			if (var.isInt()) {
				if (varDecl.init != null) {
					compileNodeAsInt(varDecl.init, ctx);
				} else {
					mv.visitInsn(Opcodes.ICONST_0);
				}
				mv.visitVarInsn(Opcodes.ISTORE, var.slot);
			} else if (var.isLong()) {
				if (varDecl.init != null) {
					compileNodeAsLong(varDecl.init, ctx);
				} else {
					mv.visitInsn(Opcodes.LCONST_0);
				}
				mv.visitVarInsn(Opcodes.LSTORE, var.slot);
			} else if (var.isDouble()) {
				if (varDecl.init != null) {
					compileNodeAsDouble(varDecl.init, ctx);
				} else {
					mv.visitInsn(Opcodes.DCONST_0);
				}
				mv.visitVarInsn(Opcodes.DSTORE, var.slot);
			} else {
				if (varDecl.init != null) {
					compileNode(varDecl.init, ctx, true);
				} else {
					mv.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(JSUndefined.class), "INSTANCE", "L" + Type.getInternalName(JSUndefined.class) + ";");
				}
				mv.visitVarInsn(Opcodes.ASTORE, var.slot);
			}
			if (needResult) {
				mv.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(JSUndefined.class), "INSTANCE", "L" + Type.getInternalName(JSUndefined.class) + ";");
			}
			return;
		}

		if (node instanceof Node.ExprStmt exprStmt) {
			compileNode(exprStmt.expr, ctx, false);
			return;
		}

		if (node instanceof Node.BlockStmt blockStmt) {
			for (Node s : blockStmt.statements) {
				compileNode(s, ctx, false);
			}
			return;
		}

		if (node instanceof Node.IfStmt ifStmt) {
			Label elseLabel = new Label();
			Label endLabel  = new Label();

			compileConditionJumpTo(ifStmt.condition, ctx, elseLabel, false);

			compileNode(ifStmt.thenBranch, ctx, false);
			if (ifStmt.elseBranch != null) {
				mv.visitJumpInsn(Opcodes.GOTO, endLabel);
				mv.visitLabel(elseLabel);
				compileNode(ifStmt.elseBranch, ctx, false);
				mv.visitLabel(endLabel);
			} else {
				mv.visitLabel(elseLabel);
			}
			return;
		}

		if (node instanceof Node.WhileStmt whileStmt) {
			Label loopCond = new Label();
			Label loopBody = new Label();
			Label loopEnd  = new Label();

			ctx.breakTargets.push(loopEnd);
			ctx.continueTargets.push(loopCond);

			mv.visitJumpInsn(Opcodes.GOTO, loopCond);

			mv.visitLabel(loopBody);
			compileNode(whileStmt.body, ctx, false);

			mv.visitLabel(loopCond);
			compileConditionJumpTo(whileStmt.condition, ctx, loopBody, true);

			mv.visitLabel(loopEnd);
			ctx.breakTargets.pop();
			ctx.continueTargets.pop();
			return;
		}

		if (node instanceof Node.ForStmt forStmt) {
			if (forStmt.init != null) {
				compileNode(forStmt.init, ctx, false);
			}

			Label loopCond   = new Label();
			Label loopBody   = new Label();
			Label loopUpdate = new Label();
			Label loopEnd    = new Label();

			ctx.breakTargets.push(loopEnd);
			ctx.continueTargets.push(loopUpdate);

			if (forStmt.condition != null) {
				mv.visitJumpInsn(Opcodes.GOTO, loopCond);
			}

			mv.visitLabel(loopBody);
			compileNode(forStmt.body, ctx, false);

			mv.visitLabel(loopUpdate);
			if (forStmt.update != null) {
				compileNode(forStmt.update, ctx, false);
			}

			if (forStmt.condition != null) {
				mv.visitLabel(loopCond);
				compileConditionJumpTo(forStmt.condition, ctx, loopBody, true);
			} else {
				mv.visitJumpInsn(Opcodes.GOTO, loopBody);
			}

			mv.visitLabel(loopEnd);
			ctx.breakTargets.pop();
			ctx.continueTargets.pop();
			return;
		}

		if (node instanceof Node.ForOfStmt forOf) {
			// 1. 编译可迭代对象表达式压入栈顶
			compileNode(forOf.iterable, ctx, true);
			// 2. 调用 JSOps.toIterator(target) 转为统一 Iterator<?>
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toIterator", "(Ljava/lang/Object;)Ljava/util/Iterator;", false);

			// 3. 分配局部变量槽位存放 Iterator
			LocalVar iterVar = ctx.declareLocal("$iter_" + (++ctx.tempVarCounter), VarType.OBJECT);
			mv.visitVarInsn(Opcodes.ASTORE, iterVar.slot);

			// 4. 获取或声明循环变量
			LocalVar loopVar = ctx.getLocal(forOf.varName);
			if (loopVar == null) {
				loopVar = ctx.declareLocal(forOf.varName, VarType.OBJECT);
			}

			Label startLabel    = new Label();
			Label continueLabel = new Label();
			Label breakLabel    = new Label();

			ctx.breakTargets.push(breakLabel);
			ctx.continueTargets.push(continueLabel);

			mv.visitLabel(startLabel);

			// 判断 iter.hasNext()
			mv.visitVarInsn(Opcodes.ALOAD, iterVar.slot);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
			mv.visitJumpInsn(Opcodes.IFEQ, breakLabel);

			// 提取元素 var = iter.next()
			mv.visitVarInsn(Opcodes.ALOAD, iterVar.slot);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
			if (loopVar.isInt()) {
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toInt", "(Ljava/lang/Object;)I", false);
				mv.visitVarInsn(Opcodes.ISTORE, loopVar.slot);
			} else if (loopVar.isDouble()) {
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toDouble", "(Ljava/lang/Object;)D", false);
				mv.visitVarInsn(Opcodes.DSTORE, loopVar.slot);
			} else if (loopVar.isLong()) {
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toLong", "(Ljava/lang/Object;)J", false);
				mv.visitVarInsn(Opcodes.LSTORE, loopVar.slot);
			} else {
				mv.visitVarInsn(Opcodes.ASTORE, loopVar.slot);
			}

			// 编译循环体
			compileNode(forOf.body, ctx, false);

			mv.visitLabel(continueLabel);
			mv.visitJumpInsn(Opcodes.GOTO, startLabel);

			mv.visitLabel(breakLabel);

			ctx.breakTargets.pop();
			ctx.continueTargets.pop();
			return;
		}

		if (node instanceof Node.ForInStmt forIn) {
			compileNode(forIn.object, ctx, true);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toKeyIterator", "(Ljava/lang/Object;)Ljava/util/Iterator;", false);

			LocalVar iterVar = ctx.declareLocal("$iter_" + (++ctx.tempVarCounter), VarType.OBJECT);
			mv.visitVarInsn(Opcodes.ASTORE, iterVar.slot);

			LocalVar loopVar = ctx.getLocal(forIn.varName);
			if (loopVar == null) {
				loopVar = ctx.declareLocal(forIn.varName, VarType.OBJECT);
			}

			Label startLabel    = new Label();
			Label continueLabel = new Label();
			Label breakLabel    = new Label();

			ctx.breakTargets.push(breakLabel);
			ctx.continueTargets.push(continueLabel);

			mv.visitLabel(startLabel);

			mv.visitVarInsn(Opcodes.ALOAD, iterVar.slot);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
			mv.visitJumpInsn(Opcodes.IFEQ, breakLabel);

			mv.visitVarInsn(Opcodes.ALOAD, iterVar.slot);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
			mv.visitVarInsn(Opcodes.ASTORE, loopVar.slot);

			compileNode(forIn.body, ctx, false);

			mv.visitLabel(continueLabel);
			mv.visitJumpInsn(Opcodes.GOTO, startLabel);

			mv.visitLabel(breakLabel);

			ctx.breakTargets.pop();
			ctx.continueTargets.pop();
			return;
		}

		if (node instanceof Node.DoWhileStmt doWhile) {
			Label startLabel    = new Label();
			Label continueLabel = new Label();
			Label endLabel      = new Label();

			ctx.breakTargets.push(endLabel);
			ctx.continueTargets.push(continueLabel);

			mv.visitLabel(startLabel);
			compileNode(doWhile.body, ctx, false);

			mv.visitLabel(continueLabel);
			compileConditionJumpTo(doWhile.condition, ctx, startLabel, true);

			mv.visitLabel(endLabel);

			ctx.breakTargets.pop();
			ctx.continueTargets.pop();
			return;
		}

		if (node instanceof Node.ThrowStmt throwStmt) {
			compileNode(throwStmt.expr, ctx, true);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "throwValue", "(Ljava/lang/Object;)Ljava/lang/RuntimeException;", false);
			mv.visitInsn(Opcodes.ATHROW);
			return;
		}

		if (node instanceof Node.TryStmt tryStmt) {
			TryCatchLabels labels = ctx.tryCatchMap.get(tryStmt);
			Label tryStart = labels != null ? labels.tryStart : new Label();
			Label tryEnd = labels != null ? labels.tryEnd : new Label();
			Label catchHandler = labels != null ? labels.catchHandler : new Label();
			Label afterTryCatch = labels != null ? labels.afterTryCatch : new Label();

			boolean hasCatch = tryStmt.catchBlock != null;
			boolean hasFinally = tryStmt.finallyBlock != null;

			mv.visitLabel(tryStart);
			compileNode(tryStmt.tryBlock, ctx, false);
			mv.visitLabel(tryEnd);

			if (hasFinally) {
				compileNode(tryStmt.finallyBlock, ctx, false);
			}
			mv.visitJumpInsn(Opcodes.GOTO, afterTryCatch);

			if (hasCatch) {
				mv.visitLabel(catchHandler);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "unwrapException", "(Ljava/lang/Throwable;)Ljava/lang/Object;", false);
				LocalVar catchVar = null;
				if (tryStmt.catchParam != null) {
					catchVar = ctx.getLocal(tryStmt.catchParam);
					if (catchVar == null) {
						catchVar = ctx.declareLocal(tryStmt.catchParam, VarType.OBJECT);
					}
				}
				if (catchVar != null) {
					mv.visitVarInsn(Opcodes.ASTORE, catchVar.slot);
				} else {
					mv.visitInsn(Opcodes.POP);
				}
				compileNode(tryStmt.catchBlock, ctx, false);
				if (hasFinally) {
					compileNode(tryStmt.finallyBlock, ctx, false);
				}
			}

			mv.visitLabel(afterTryCatch);
			return;
		}

		if (node instanceof Node.SwitchStmt switchStmt) {
			compileNode(switchStmt.discriminant, ctx, true);
			int discSlot = ctx.allocTempSlot();
			mv.visitVarInsn(Opcodes.ASTORE, discSlot);

			Label switchEnd = new Label();
			ctx.breakTargets.push(switchEnd);

			int numCases = switchStmt.cases.size();
			Label[] caseLabels = new Label[numCases];
			for (int i = 0; i < numCases; i++) caseLabels[i] = new Label();

			int defaultIndex = -1;

			for (int i = 0; i < numCases; i++) {
				Node.CaseClause clause = switchStmt.cases.get(i);
				if (clause.test == null) {
					defaultIndex = i;
				} else {
					mv.visitVarInsn(Opcodes.ALOAD, discSlot);
					compileNode(clause.test, ctx, true);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isStrictEq", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
					mv.visitJumpInsn(Opcodes.IFNE, caseLabels[i]);
				}
			}

			if (defaultIndex >= 0) {
				mv.visitJumpInsn(Opcodes.GOTO, caseLabels[defaultIndex]);
			} else {
				mv.visitJumpInsn(Opcodes.GOTO, switchEnd);
			}

			for (int i = 0; i < numCases; i++) {
				Node.CaseClause clause = switchStmt.cases.get(i);
				mv.visitLabel(caseLabels[i]);
				for (Node stmt : clause.consequent) {
					compileNode(stmt, ctx, false);
				}
			}

			mv.visitLabel(switchEnd);
			ctx.breakTargets.pop();
			return;
		}

		if (node instanceof Node.BreakStmt) {
			Label target = ctx.breakTargets.peek();
			if (target != null) {
				mv.visitJumpInsn(Opcodes.GOTO, target);
			}
			return;
		}

		if (node instanceof Node.ContinueStmt) {
			Label target = ctx.continueTargets.peek();
			if (target != null) {
				mv.visitJumpInsn(Opcodes.GOTO, target);
			}
			return;
		}

		if (node instanceof Node.ReturnStmt returnStmt) {
			if (returnStmt.value != null) {
				compileNode(returnStmt.value, ctx, true);
			} else {
				mv.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(JSUndefined.class), "INSTANCE", "L" + Type.getInternalName(JSUndefined.class) + ";");
			}
			mv.visitInsn(Opcodes.ARETURN);
			return;
		}

		if (node instanceof Node.LiteralExpr lit) {
			if (!needResult) return;
			Object val = lit.value;
			if (val == null) {
				mv.visitInsn(Opcodes.ACONST_NULL);
			} else if (val == JSUndefined.INSTANCE) {
				mv.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(JSUndefined.class), "INSTANCE", "L" + Type.getInternalName(JSUndefined.class) + ";");
			} else if (val instanceof Number num) {
				mv.visitLdcInsn(num.doubleValue());
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
			} else if (val instanceof String str) {
				mv.visitLdcInsn(str);
			} else if (val instanceof Boolean b) {
				if (b) {
					mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
				} else {
					mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");
				}
			}
			return;
		}

		if (node instanceof Node.IdentifierExpr ident) {
			if (!needResult) return;
			String   name = ident.name;
			LocalVar var  = ctx.getLocal(name);
			if (var != null) {
				if (var.isInt()) {
					mv.visitVarInsn(Opcodes.ILOAD, var.slot);
					mv.visitInsn(Opcodes.I2D);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
				} else if (var.isLong()) {
					mv.visitVarInsn(Opcodes.LLOAD, var.slot);
					mv.visitInsn(Opcodes.L2D);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
				} else if (var.isDouble()) {
					mv.visitVarInsn(Opcodes.DLOAD, var.slot);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
				} else {
					mv.visitVarInsn(Opcodes.ALOAD, var.slot);
				}
			} else {
				// 全局变量查找槽位化：通过全局槽位索引直读 (O(1) 数组寻址)
				int slot = JSContext.getGlobalSlot(name);
				mv.visitVarInsn(Opcodes.ALOAD, 1); // cx
				pushInt(mv, slot);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSContext.class), "getSlot", "(I)Ljava/lang/Object;", false);
			}
			return;
		}

		if (node instanceof Node.AssignExpr assign) {
			if (assign.target instanceof Node.IdentifierExpr ident) {
				String   name = ident.name;
				LocalVar var  = ctx.getLocal(name);
				String opStr = assign.op == TokenType.PLUS_ASSIGN ? "+" :
				 (assign.op == TokenType.MINUS_ASSIGN ? "-" :
					(assign.op == TokenType.STAR_ASSIGN ? "*" : "/"));
				if (var != null) {
					boolean isCompound = assign.op == TokenType.PLUS_ASSIGN || assign.op == TokenType.MINUS_ASSIGN
					                      || assign.op == TokenType.STAR_ASSIGN || assign.op == TokenType.SLASH_ASSIGN;

					if (var.isInt()) {
						if (assign.op == TokenType.ASSIGN) {
							compileNodeAsInt(assign.value, ctx);
							if (needResult) {
								mv.visitInsn(Opcodes.DUP);
								mv.visitVarInsn(Opcodes.ISTORE, var.slot);
								mv.visitInsn(Opcodes.I2D);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
							} else {
								mv.visitVarInsn(Opcodes.ISTORE, var.slot);
							}
						} else if (isCompound) {
							mv.visitVarInsn(Opcodes.ILOAD, var.slot);
							compileNodeAsInt(assign.value, ctx);
							switch (assign.op) {
								case PLUS_ASSIGN -> mv.visitInsn(Opcodes.IADD);
								case MINUS_ASSIGN -> mv.visitInsn(Opcodes.ISUB);
								case STAR_ASSIGN -> mv.visitInsn(Opcodes.IMUL);
								case SLASH_ASSIGN -> mv.visitInsn(Opcodes.IDIV);
							}
							if (needResult) {
								mv.visitInsn(Opcodes.DUP);
								mv.visitVarInsn(Opcodes.ISTORE, var.slot);
								mv.visitInsn(Opcodes.I2D);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
							} else {
								mv.visitVarInsn(Opcodes.ISTORE, var.slot);
							}
						}
						return;
					}

					if (var.isLong()) {
						if (assign.op == TokenType.ASSIGN) {
							compileNodeAsLong(assign.value, ctx);
							if (needResult) {
								mv.visitInsn(Opcodes.DUP2);
								mv.visitVarInsn(Opcodes.LSTORE, var.slot);
								mv.visitInsn(Opcodes.L2D);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
							} else {
								mv.visitVarInsn(Opcodes.LSTORE, var.slot);
							}
						} else if (isCompound) {
							mv.visitVarInsn(Opcodes.LLOAD, var.slot);
							compileNodeAsLong(assign.value, ctx);
							switch (assign.op) {
								case PLUS_ASSIGN -> mv.visitInsn(Opcodes.LADD);
								case MINUS_ASSIGN -> mv.visitInsn(Opcodes.LSUB);
								case STAR_ASSIGN -> mv.visitInsn(Opcodes.LMUL);
								case SLASH_ASSIGN -> mv.visitInsn(Opcodes.LDIV);
							}
							if (needResult) {
								mv.visitInsn(Opcodes.DUP2);
								mv.visitVarInsn(Opcodes.LSTORE, var.slot);
								mv.visitInsn(Opcodes.L2D);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
							} else {
								mv.visitVarInsn(Opcodes.LSTORE, var.slot);
							}
						}
						return;
					}

					if (var.isDouble()) {
						if (assign.op == TokenType.ASSIGN) {
							compileNodeAsDouble(assign.value, ctx);
							if (needResult) {
								mv.visitInsn(Opcodes.DUP2);
								mv.visitVarInsn(Opcodes.DSTORE, var.slot);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
							} else {
								mv.visitVarInsn(Opcodes.DSTORE, var.slot);
							}
						} else if (isCompound) {
							mv.visitVarInsn(Opcodes.DLOAD, var.slot);
							compileNodeAsDouble(assign.value, ctx);
							switch (assign.op) {
								case PLUS_ASSIGN -> mv.visitInsn(Opcodes.DADD);
								case MINUS_ASSIGN -> mv.visitInsn(Opcodes.DSUB);
								case STAR_ASSIGN -> mv.visitInsn(Opcodes.DMUL);
								case SLASH_ASSIGN -> mv.visitInsn(Opcodes.DDIV);
							}
							if (needResult) {
								mv.visitInsn(Opcodes.DUP2);
								mv.visitVarInsn(Opcodes.DSTORE, var.slot);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
							} else {
								mv.visitVarInsn(Opcodes.DSTORE, var.slot);
							}
						}
						return;
					}

					if (assign.op == TokenType.ASSIGN) {
						compileNode(assign.value, ctx, true);
					} else if (isCompound) {
						mv.visitVarInsn(Opcodes.ALOAD, var.slot);
						compileNode(assign.value, ctx, true);
						mv.visitInvokeDynamicInsn("op", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", BSM_BINARY_OP, opStr);
					}
					if (needResult) mv.visitInsn(Opcodes.DUP);
					mv.visitVarInsn(Opcodes.ASTORE, var.slot);
				} else {
					int slot = JSContext.getGlobalSlot(name);
					mv.visitVarInsn(Opcodes.ALOAD, 1); // cx
					pushInt(mv, slot);
					if (assign.op == TokenType.ASSIGN) {
						compileNode(assign.value, ctx, true);
					} else {
						mv.visitVarInsn(Opcodes.ALOAD, 1);
						pushInt(mv, slot);
						mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSContext.class), "getSlot", "(I)Ljava/lang/Object;", false);
						compileNode(assign.value, ctx, true);
						mv.visitInvokeDynamicInsn("op", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", BSM_BINARY_OP, opStr);
					}
					if (needResult) {
						mv.visitInsn(Opcodes.DUP_X2);
						mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSContext.class), "setSlot", "(ILjava/lang/Object;)V", false);
					} else {
						mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSContext.class), "setSlot", "(ILjava/lang/Object;)V", false);
					}
				}
				return;
			}

			if (assign.target instanceof Node.MemberAccessExpr member) {
				compileNode(member.target, ctx, true);
				compileNode(assign.value, ctx, true);
				if (needResult) {
					mv.visitInsn(Opcodes.DUP_X1);
				}
				mv.visitInvokeDynamicInsn("setProp", "(Ljava/lang/Object;Ljava/lang/Object;)V", BSM_SET_PROP, member.property);
				return;
			}

			if (assign.target instanceof Node.IndexAccessExpr idxAccess) {
				if (idxAccess.index instanceof Node.LiteralExpr lit && lit.value instanceof String s) {
					compileNode(idxAccess.target, ctx, true);
					compileNode(assign.value, ctx, true);
					if (needResult) {
						mv.visitInsn(Opcodes.DUP_X1);
					}
					mv.visitInvokeDynamicInsn("setProp", "(Ljava/lang/Object;Ljava/lang/Object;)V", BSM_SET_PROP, s);
					return;
				}

				VarType idxType = inferVarType(idxAccess.index, ctx);
				if (idxType == VarType.INT) {
					int targetSlot = ctx.allocTempSlot();
					int idxSlot = ctx.allocTempSlot();
					int valSlot = ctx.allocTempSlot();

					compileNode(idxAccess.target, ctx, true);
					mv.visitVarInsn(Opcodes.ASTORE, targetSlot);

					compileNodeAsInt(idxAccess.index, ctx);
					mv.visitVarInsn(Opcodes.ISTORE, idxSlot);

					compileNode(assign.value, ctx, true);
					if (needResult) {
						mv.visitInsn(Opcodes.DUP);
					}
					mv.visitVarInsn(Opcodes.ASTORE, valSlot);

					Label slowPath = new Label();
					Label endLabel = new Label();

					// 1. JSArray fast-path
					mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
					mv.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(JSArray.class));
					mv.visitJumpInsn(Opcodes.IFEQ, slowPath);

					mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
					mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(JSArray.class));
					mv.visitVarInsn(Opcodes.ILOAD, idxSlot);
					mv.visitVarInsn(Opcodes.ALOAD, valSlot);
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSArray.class), "setElement", "(ILjava/lang/Object;)V", false);
					mv.visitJumpInsn(Opcodes.GOTO, endLabel);

					// 2. slowPath: fallback to JSLinker.setIndex(target, idx, val)
					mv.visitLabel(slowPath);
					mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
					mv.visitVarInsn(Opcodes.ILOAD, idxSlot);
					mv.visitVarInsn(Opcodes.ALOAD, valSlot);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSLinker.class), "setIndex", "(Ljava/lang/Object;ILjava/lang/Object;)V", false);

					mv.visitLabel(endLabel);
					return;
				}

				compileNode(idxAccess.target, ctx, true);
				compileNode(idxAccess.index, ctx, true);
				compileNode(assign.value, ctx, true);
				if (needResult) {
					mv.visitInsn(Opcodes.DUP_X2);
				}
				mv.visitInvokeDynamicInsn("setIndex", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", BSM_SET_INDEX);
				return;
			}
		}

		if (node instanceof Node.MemberAccessExpr member) {
			compileMemberAccess(member, ctx, needResult);
			return;
		}

		if (node instanceof Node.IndexAccessExpr idxAccess) {
			compileIndexAccess(idxAccess, ctx, needResult);
			return;
		}

		if (node instanceof Node.TernaryExpr ternary) {
			Label elseLabel = new Label();
			Label endLabel = new Label();
			compileConditionJumpTo(ternary.condition, ctx, elseLabel, false);
			compileNode(ternary.thenExpr, ctx, needResult);
			mv.visitJumpInsn(Opcodes.GOTO, endLabel);
			mv.visitLabel(elseLabel);
			compileNode(ternary.elseExpr, ctx, needResult);
			mv.visitLabel(endLabel);
			return;
		}

		if (node instanceof Node.BinaryExpr bin) {
			if (bin.op == TokenType.AND) {
				Label endLabel = new Label();
				if (needResult) {
					compileNode(bin.left, ctx, true);
					mv.visitInsn(Opcodes.DUP);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isTruthy", "(Ljava/lang/Object;)Z", false);
					mv.visitJumpInsn(Opcodes.IFEQ, endLabel);
					mv.visitInsn(Opcodes.POP);
					compileNode(bin.right, ctx, true);
					mv.visitLabel(endLabel);
				} else {
					compileNode(bin.left, ctx, true);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isTruthy", "(Ljava/lang/Object;)Z", false);
					mv.visitJumpInsn(Opcodes.IFEQ, endLabel);
					compileNode(bin.right, ctx, false);
					mv.visitLabel(endLabel);
				}
				return;
			}
			if (bin.op == TokenType.OR) {
				Label endLabel = new Label();
				if (needResult) {
					compileNode(bin.left, ctx, true);
					mv.visitInsn(Opcodes.DUP);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isTruthy", "(Ljava/lang/Object;)Z", false);
					mv.visitJumpInsn(Opcodes.IFNE, endLabel);
					mv.visitInsn(Opcodes.POP);
					compileNode(bin.right, ctx, true);
					mv.visitLabel(endLabel);
				} else {
					compileNode(bin.left, ctx, true);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isTruthy", "(Ljava/lang/Object;)Z", false);
					mv.visitJumpInsn(Opcodes.IFNE, endLabel);
					compileNode(bin.right, ctx, false);
					mv.visitLabel(endLabel);
				}
				return;
			}

			VarType leftType  = inferVarType(bin.left, ctx);
			VarType rightType = inferVarType(bin.right, ctx);


			boolean isNumericMath = (bin.op == TokenType.STAR || bin.op == TokenType.SLASH || bin.op == TokenType.PERCENT || bin.op == TokenType.MINUS);
			boolean isNumericPlus = (bin.op == TokenType.PLUS && !isStringExpr(bin.left) && !isStringExpr(bin.right)
				&& ((isNumeric(leftType) && isNumeric(rightType)) || (isNumericExpr(bin.left) && isNumericExpr(bin.right))));

			if (isNumericMath || isNumericPlus) {
				if (!needResult) {
					compileNode(bin.left, ctx, false);
					compileNode(bin.right, ctx, false);
					return;
				}
				compileNodeAsDouble(bin, ctx);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
				return;
			}

			if (bin.op == TokenType.EQ || bin.op == TokenType.EQ_EQ || bin.op == TokenType.NOT_EQ || bin.op == TokenType.NOT_EQ_EQ
			    || bin.op == TokenType.LT || bin.op == TokenType.LTE || bin.op == TokenType.GT || bin.op == TokenType.GTE) {
				if (!needResult) {
					compileNode(bin.left, ctx, false);
					compileNode(bin.right, ctx, false);
					return;
				}
				Label trueLabel = new Label();
				Label endLabel = new Label();
				compileConditionJumpTo(bin, ctx, trueLabel, true);
				mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");
				mv.visitJumpInsn(Opcodes.GOTO, endLabel);
				mv.visitLabel(trueLabel);
				mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
				mv.visitLabel(endLabel);
				return;
			}

			compileNode(bin.left, ctx, true);
			compileNode(bin.right, ctx, true);

			String opStr = switch (bin.op) {
				case PLUS -> "+";
				case MINUS -> "-";
				case STAR -> "*";
				case SLASH -> "/";
				case PERCENT -> "%";
				default -> throw new IllegalArgumentException("Unsupported binary op: " + bin.op);
			};

			mv.visitInvokeDynamicInsn("op", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", BSM_BINARY_OP, opStr);
			if (!needResult) mv.visitInsn(Opcodes.POP);
			return;
		}

		if (node instanceof Node.UnaryExpr un) {
			if (un.op == TokenType.NOT) {
				if (!needResult) {
					compileNode(un.expr, ctx, false);
					return;
				}
				compileNode(un.expr, ctx, true);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "not", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
				return;
			} else if (un.op == TokenType.MINUS) {
				if (!needResult) {
					compileNode(un.expr, ctx, false);
					return;
				}
				compileNode(un.expr, ctx, true);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toDouble", "(Ljava/lang/Object;)D", false);
				mv.visitInsn(Opcodes.DNEG);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
				return;
			} else if (un.op == TokenType.PLUS_PLUS || un.op == TokenType.MINUS_MINUS) {
				if (un.expr instanceof Node.IdentifierExpr ident) {
					String   name = ident.name;
					LocalVar var  = ctx.getLocal(name);
					if (var != null) {
						if (var.isInt()) {
							int delta = (un.op == TokenType.PLUS_PLUS ? 1 : -1);
							if (!needResult) {
								mv.visitIincInsn(var.slot, delta);
								return;
							}
							if (un.isPrefix) {
								mv.visitIincInsn(var.slot, delta);
								mv.visitVarInsn(Opcodes.ILOAD, var.slot);
								mv.visitInsn(Opcodes.I2D);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
							} else {
								mv.visitVarInsn(Opcodes.ILOAD, var.slot);
								mv.visitInsn(Opcodes.I2D);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
								mv.visitIincInsn(var.slot, delta);
							}
							return;
						}

						if (var.isLong()) {
							if (!un.isPrefix && needResult) {
								mv.visitVarInsn(Opcodes.LLOAD, var.slot);
								mv.visitInsn(Opcodes.L2D);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
							}
							mv.visitVarInsn(Opcodes.LLOAD, var.slot);
							mv.visitInsn(Opcodes.LCONST_1);
							mv.visitInsn(un.op == TokenType.PLUS_PLUS ? Opcodes.LADD : Opcodes.LSUB);
							if (un.isPrefix && needResult) {
								mv.visitInsn(Opcodes.DUP2);
								mv.visitInsn(Opcodes.L2D);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
							}
							mv.visitVarInsn(Opcodes.LSTORE, var.slot);
							return;
						}

						if (var.isDouble()) {
							if (!un.isPrefix && needResult) {
								mv.visitVarInsn(Opcodes.DLOAD, var.slot);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
							}
							mv.visitVarInsn(Opcodes.DLOAD, var.slot);
							mv.visitInsn(Opcodes.DCONST_1);
							mv.visitInsn(un.op == TokenType.PLUS_PLUS ? Opcodes.DADD : Opcodes.DSUB);
							if (un.isPrefix && needResult) {
								mv.visitInsn(Opcodes.DUP2);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
							}
							mv.visitVarInsn(Opcodes.DSTORE, var.slot);
							return;
						}

						mv.visitVarInsn(Opcodes.ALOAD, var.slot);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toDouble", "(Ljava/lang/Object;)D", false);
						if (!un.isPrefix && needResult) {
							// 后置运算且需要结果：保留旧值
							mv.visitInsn(Opcodes.DUP2);
							mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
						}
						mv.visitInsn(Opcodes.DCONST_1);
						if (un.op == TokenType.PLUS_PLUS) { mv.visitInsn(Opcodes.DADD); } else mv.visitInsn(Opcodes.DSUB);

						if (un.isPrefix && needResult) {
							// 前置运算且需要结果：保留新值
							mv.visitInsn(Opcodes.DUP2);
							mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
						}
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
						mv.visitVarInsn(Opcodes.ASTORE, var.slot);
					}
				}
				return;
			} else if (un.op == TokenType.DELETE) {
				if (un.expr instanceof Node.MemberAccessExpr member) {
					compileNode(member.target, ctx, true);
					mv.visitLdcInsn(member.property);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "delete", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
				} else if (un.expr instanceof Node.IndexAccessExpr idx) {
					compileNode(idx.target, ctx, true);
					compileNode(idx.index, ctx, true);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "delete", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
				} else {
					compileNode(un.expr, ctx, false);
					mv.visitInsn(Opcodes.ICONST_1);
				}
				if (needResult) {
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
				} else {
					mv.visitInsn(Opcodes.POP);
				}
				return;
			}
		}

		if (node instanceof Node.TypeOfExpr typeOf) {
			if (typeOf.expr instanceof Node.IdentifierExpr ident) {
				LocalVar var = ctx.getLocal(ident.name);
				if (var != null) {
					compileNode(ident, ctx, true);
				} else {
					int slot = JSContext.getGlobalSlot(ident.name);
					mv.visitVarInsn(Opcodes.ALOAD, 1);
					pushInt(mv, slot);
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSContext.class), "getSlot", "(I)Ljava/lang/Object;", false);
				}
			} else {
				compileNode(typeOf.expr, ctx, true);
			}
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "typeOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
			if (!needResult) mv.visitInsn(Opcodes.POP);
			return;
		}

		if (node instanceof Node.VoidExpr voidExpr) {
			compileNode(voidExpr.expr, ctx, false);
			if (needResult) {
				mv.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(JSUndefined.class), "INSTANCE", "L" + Type.getInternalName(JSUndefined.class) + ";");
			}
			return;
		}

		if (node instanceof Node.CallExpr call) {
			if (call.callee instanceof Node.IdentifierExpr ident && ctx.isFunction && ctx.functionName != null && ctx.functionName.equals(ident.name)) {
				// 自递归单态直连调用 (Direct self-recursive monomorphic invocation on 'this')
				int arity = call.arguments.size();
				mv.visitVarInsn(Opcodes.ALOAD, 0); // this
				mv.visitVarInsn(Opcodes.ALOAD, 1); // cx
				mv.visitInsn(Opcodes.ACONST_NULL); // thisObj
				if (arity == 0) {
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ctx.className, "call0", "(L" + Type.getInternalName(JSContext.class) + ";Ljava/lang/Object;)Ljava/lang/Object;", false);
				} else if (arity == 1) {
					compileNode(call.arguments.get(0), ctx, true);
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ctx.className, "call1", "(L" + Type.getInternalName(JSContext.class) + ";Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
				} else if (arity == 2) {
					compileNode(call.arguments.get(0), ctx, true);
					compileNode(call.arguments.get(1), ctx, true);
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ctx.className, "call2", "(L" + Type.getInternalName(JSContext.class) + ";Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
				} else if (arity == 3) {
					compileNode(call.arguments.get(0), ctx, true);
					compileNode(call.arguments.get(1), ctx, true);
					compileNode(call.arguments.get(2), ctx, true);
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ctx.className, "call3", "(L" + Type.getInternalName(JSContext.class) + ";Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
				} else {
					pushInt(mv, arity);
					mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
					for (int i = 0; i < arity; i++) {
						mv.visitInsn(Opcodes.DUP);
						pushInt(mv, i);
						compileNode(call.arguments.get(i), ctx, true);
						mv.visitInsn(Opcodes.AASTORE);
					}
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ctx.className, "call", "(L" + Type.getInternalName(JSContext.class) + ";Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
				}
				if (!needResult) mv.visitInsn(Opcodes.POP);
				return;
			}

			if (call.callee instanceof Node.MemberAccessExpr member) {
				compileNode(member.target, ctx, true); // target
				StringBuilder desc = new StringBuilder("(Ljava/lang/Object;");
				for (Node arg : call.arguments) {
					compileNode(arg, ctx, true);
					desc.append("Ljava/lang/Object;");
				}
				desc.append(")Ljava/lang/Object;");
				mv.visitInvokeDynamicInsn("invoke", desc.toString(), BSM_INVOKE, member.property);
			} else {
				compileNode(call.callee, ctx, true); // callee function
				StringBuilder desc = new StringBuilder("(Ljava/lang/Object;");
				for (Node arg : call.arguments) {
					compileNode(arg, ctx, true);
					desc.append("Ljava/lang/Object;");
				}
				desc.append(")Ljava/lang/Object;");
				mv.visitInvokeDynamicInsn("invoke", desc.toString(), BSM_INVOKE, "call");
			}
			if (!needResult) mv.visitInsn(Opcodes.POP);
			return;
		}

		if (node instanceof Node.NewExpr newExpr) {
			compileNode(newExpr.constructor, ctx, true);
			StringBuilder desc = new StringBuilder("(Ljava/lang/Object;");
			for (Node arg : newExpr.arguments) {
				compileNode(arg, ctx, true);
				desc.append("Ljava/lang/Object;");
			}
			desc.append(")Ljava/lang/Object;");
			mv.visitInvokeDynamicInsn("new", desc.toString(), BSM_NEW);
			if (!needResult) mv.visitInsn(Opcodes.POP);
			return;
		}

		if (node instanceof Node.ObjectLiteralExpr objLit) {
			mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(JSObject.class));
			mv.visitInsn(Opcodes.DUP);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(JSObject.class), "<init>", "()V", false);

			for (Node.ObjectLiteralExpr.Entry entry : objLit.entries) {
				int propId = SymbolTable.id(entry.key());
				VarType valType = inferVarType(entry.value(), ctx);
				if (valType == VarType.DOUBLE || (entry.value() instanceof Node.LiteralExpr lit && lit.value instanceof Number num && num.doubleValue() != num.intValue())) {
					mv.visitInsn(Opcodes.DUP);
					pushInt(mv, propId);
					compileNodeAsDouble(entry.value(), ctx);
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSObject.class), "putDouble", "(ID)V", false);
				} else {
					mv.visitInsn(Opcodes.DUP);
					pushInt(mv, propId);
					compileNode(entry.value(), ctx, true);
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSObject.class), "put", "(ILjava/lang/Object;)V", false);
				}
			}
			if (!needResult) mv.visitInsn(Opcodes.POP);
			return;
		}

		if (node instanceof Node.ArrayLiteralExpr arrLit) {
			mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(JSArray.class));
			mv.visitInsn(Opcodes.DUP);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(JSArray.class), "<init>", "()V", false);

			for (Node elem : arrLit.elements) {
				mv.visitInsn(Opcodes.DUP);
				compileNode(elem, ctx, true);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSArray.class), "push", "(Ljava/lang/Object;)V", false);
			}
			if (!needResult) mv.visitInsn(Opcodes.POP);
			return;
		}

		if (node instanceof Node.RegExpLiteral regLit) {
			mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(JSRegExp.class));
			mv.visitInsn(Opcodes.DUP);
			mv.visitLdcInsn(regLit.pattern);
			mv.visitLdcInsn(regLit.flags);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(JSRegExp.class), "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
			if (!needResult) mv.visitInsn(Opcodes.POP);
			return;
		}

		if (node instanceof Node.FunctionExpr funcExpr) {
			String funcClass = generateFunctionClass(funcExpr.name, funcExpr.params, funcExpr.body);
			if (needResult) {
				mv.visitTypeInsn(Opcodes.NEW, funcClass);
				mv.visitInsn(Opcodes.DUP);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitMethodInsn(Opcodes.INVOKESPECIAL, funcClass, "<init>", "(L" + Type.getInternalName(JSContext.class) + ";)V", false);
			}
			return;
		}

		if (node instanceof Node.FunctionDecl funcDecl) {
			LocalVar var = ctx.getLocal(funcDecl.name);
			if (var == null) {
				String funcClass = generateFunctionClass(funcDecl.name, funcDecl.params, funcDecl.body);
				int slot = JSContext.getGlobalSlot(funcDecl.name);
				mv.visitVarInsn(Opcodes.ALOAD, 1); // cx
				pushInt(mv, slot);
				mv.visitTypeInsn(Opcodes.NEW, funcClass);
				mv.visitInsn(Opcodes.DUP);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitMethodInsn(Opcodes.INVOKESPECIAL, funcClass, "<init>", "(L" + Type.getInternalName(JSContext.class) + ";)V", false);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSContext.class), "setSlot", "(ILjava/lang/Object;)V", false);
			}
			if (needResult) {
				mv.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(JSUndefined.class), "INSTANCE", "L" + Type.getInternalName(JSUndefined.class) + ";");
			}
			return;
		}
	}

	public static String generateFunctionClass(List<String> params, Node.BlockStmt body) {
		return generateFunctionClass(null, params, body);
	}

	public static String generateFunctionClass(String functionName, List<String> params, Node.BlockStmt body) {
		String funcClassName = "hope/magic/gen/MagicJSFunction_" + SCRIPT_ID.incrementAndGet();
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, funcClassName, null, "java/lang/Object", new String[]{ Type.getInternalName(JSFunction.class) });

		// public JSContext cx;
		cw.visitField(Opcodes.ACC_PUBLIC, "cx", "L" + Type.getInternalName(JSContext.class) + ";", null, null).visitEnd();

		// <init>(JSContext cx)
		MethodVisitor initCxMv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + Type.getInternalName(JSContext.class) + ";)V", null, null);
		initCxMv.visitCode();
		initCxMv.visitVarInsn(Opcodes.ALOAD, 0);
		initCxMv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		initCxMv.visitVarInsn(Opcodes.ALOAD, 0);
		initCxMv.visitVarInsn(Opcodes.ALOAD, 1);
		initCxMv.visitFieldInsn(Opcodes.PUTFIELD, funcClassName, "cx", "L" + Type.getInternalName(JSContext.class) + ";");
		initCxMv.visitInsn(Opcodes.RETURN);
		initCxMv.visitMaxs(2, 2);
		initCxMv.visitEnd();

		// <init>()
		MethodVisitor initMv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		initMv.visitCode();
		initMv.visitVarInsn(Opcodes.ALOAD, 0);
		initMv.visitInsn(Opcodes.ACONST_NULL);
		initMv.visitMethodInsn(Opcodes.INVOKESPECIAL, funcClassName, "<init>", "(L" + Type.getInternalName(JSContext.class) + ";)V", false);
		initMv.visitInsn(Opcodes.RETURN);
		initMv.visitMaxs(2, 1);
		initMv.visitEnd();

		int paramCount = params.size();
		String targetMethodName = "call";
		String targetMethodDesc = "(L" + Type.getInternalName(JSContext.class) + ";Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";
		if (paramCount == 0) {
			targetMethodName = "call0";
			targetMethodDesc = "(L" + Type.getInternalName(JSContext.class) + ";Ljava/lang/Object;)Ljava/lang/Object;";
		} else if (paramCount == 1) {
			targetMethodName = "call1";
			targetMethodDesc = "(L" + Type.getInternalName(JSContext.class) + ";Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
		} else if (paramCount == 2) {
			targetMethodName = "call2";
			targetMethodDesc = "(L" + Type.getInternalName(JSContext.class) + ";Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
		} else if (paramCount == 3) {
			targetMethodName = "call3";
			targetMethodDesc = "(L" + Type.getInternalName(JSContext.class) + ";Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
		}

		// 主执行方法 (当 paramCount <= 3 时编译为特化 call0..call3，零 Object[] 堆分配)
		MethodVisitor callMv = cw.visitMethod(
			Opcodes.ACC_PUBLIC,
			targetMethodName,
			targetMethodDesc,
			null,
			new String[]{ "java/lang/Throwable" }
		);
		callMv.visitCode();

		Label cxReady = new Label();
		callMv.visitVarInsn(Opcodes.ALOAD, 1);
		callMv.visitJumpInsn(Opcodes.IFNONNULL, cxReady);
		callMv.visitVarInsn(Opcodes.ALOAD, 0);
		callMv.visitFieldInsn(Opcodes.GETFIELD, funcClassName, "cx", "L" + Type.getInternalName(JSContext.class) + ";");
		callMv.visitVarInsn(Opcodes.ASTORE, 1);
		callMv.visitLabel(cxReady);

		Node.Program fakeProg = new Node.Program(body.statements, body.line, body.column);
		CompileContext ctx = new CompileContext(callMv, funcClassName, fakeProg);
		ctx.isFunction = true;
		ctx.functionName = functionName;
		registerTryCatchBlocks(fakeProg, callMv, ctx.tryCatchMap);
		preScanVariables(fakeProg, ctx);

		if (paramCount <= 3) {
			// 参数直接绑定到 JVM 局部变量槽位 (slot 0=this, 1=cx, 2=thisObj, 3=a0, 4=a1, 5=a2)
			ctx.nextLocalSlot = 3;
			for (int i = 0; i < paramCount; i++) {
				ctx.declareLocal(params.get(i), VarType.OBJECT);
			}
		} else {
			ctx.nextLocalSlot = 4; // slot 0=this, 1=cx, 2=thisObj, 3=args
			// Bind parameters
			for (int i = 0; i < params.size(); i++) {
				String paramName = params.get(i);
				LocalVar var = ctx.declareLocal(paramName, VarType.OBJECT);
				Label defaultUndefined = new Label();
				Label storeEnd = new Label();

				callMv.visitVarInsn(Opcodes.ALOAD, 3); // args
				callMv.visitJumpInsn(Opcodes.IFNULL, defaultUndefined);

				callMv.visitVarInsn(Opcodes.ALOAD, 3); // args
				callMv.visitInsn(Opcodes.ARRAYLENGTH);
				pushInt(callMv, i);
				callMv.visitJumpInsn(Opcodes.IF_ICMPLE, defaultUndefined);

				callMv.visitVarInsn(Opcodes.ALOAD, 3); // args
				pushInt(callMv, i);
				callMv.visitInsn(Opcodes.AALOAD);
				callMv.visitJumpInsn(Opcodes.GOTO, storeEnd);

				callMv.visitLabel(defaultUndefined);
				callMv.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(JSUndefined.class), "INSTANCE", "L" + Type.getInternalName(JSUndefined.class) + ";");

				callMv.visitLabel(storeEnd);
				callMv.visitVarInsn(Opcodes.ASTORE, var.slot);
			}
		}

		// 嵌套函数局部作用域与提升 (Nested Function Hoisting)
		List<Node.FunctionDecl> nestedFuncs = new ArrayList<>();
		collectFunctionDecls(fakeProg, nestedFuncs);
		for (Node.FunctionDecl fd : nestedFuncs) {
			if (ctx.getLocal(fd.name) == null) {
				ctx.declareLocal(fd.name, VarType.OBJECT);
			}
		}
		for (Node.FunctionDecl fd : nestedFuncs) {
			LocalVar var = ctx.getLocal(fd.name);
			if (var != null) {
				String childFuncClass = generateFunctionClass(fd.name, fd.params, fd.body);
				callMv.visitTypeInsn(Opcodes.NEW, childFuncClass);
				callMv.visitInsn(Opcodes.DUP);
				callMv.visitVarInsn(Opcodes.ALOAD, 1); // cx
				callMv.visitMethodInsn(Opcodes.INVOKESPECIAL, childFuncClass, "<init>", "(L" + Type.getInternalName(JSContext.class) + ";)V", false);
				callMv.visitVarInsn(Opcodes.ASTORE, var.slot);
			}
		}

		// Compile statements
		for (int i = 0; i < body.statements.size(); i++) {
			Node stmt = body.statements.get(i);
			compileNode(stmt, ctx, false);
		}

		callMv.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(JSUndefined.class), "INSTANCE", "L" + Type.getInternalName(JSUndefined.class) + ";");
		callMv.visitInsn(Opcodes.ARETURN);
		callMv.visitMaxs(0, 0);
		callMv.visitEnd();

		// 当 paramCount <= 3 时，补充通用的 call(cx, thisObj, args[]) 桥接转发器
		if (paramCount <= 3) {
			MethodVisitor bridgeMv = cw.visitMethod(
				Opcodes.ACC_PUBLIC,
				"call",
				"(L" + Type.getInternalName(JSContext.class) + ";Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
				null,
				new String[]{ "java/lang/Throwable" }
			);
			bridgeMv.visitCode();
			bridgeMv.visitVarInsn(Opcodes.ALOAD, 0); // this
			bridgeMv.visitVarInsn(Opcodes.ALOAD, 1); // cx
			bridgeMv.visitVarInsn(Opcodes.ALOAD, 2); // thisObj
			for (int i = 0; i < paramCount; i++) {
				Label lUndef = new Label();
				Label lEnd = new Label();
				bridgeMv.visitVarInsn(Opcodes.ALOAD, 3); // args
				bridgeMv.visitJumpInsn(Opcodes.IFNULL, lUndef);
				bridgeMv.visitVarInsn(Opcodes.ALOAD, 3);
				bridgeMv.visitInsn(Opcodes.ARRAYLENGTH);
				pushInt(bridgeMv, i);
				bridgeMv.visitJumpInsn(Opcodes.IF_ICMPLE, lUndef);
				bridgeMv.visitVarInsn(Opcodes.ALOAD, 3);
				pushInt(bridgeMv, i);
				bridgeMv.visitInsn(Opcodes.AALOAD);
				bridgeMv.visitJumpInsn(Opcodes.GOTO, lEnd);
				bridgeMv.visitLabel(lUndef);
				bridgeMv.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(JSUndefined.class), "INSTANCE", "L" + Type.getInternalName(JSUndefined.class) + ";");
				bridgeMv.visitLabel(lEnd);
			}
			bridgeMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, funcClassName, targetMethodName, targetMethodDesc, false);
			bridgeMv.visitInsn(Opcodes.ARETURN);
			bridgeMv.visitMaxs(0, 0);
			bridgeMv.visitEnd();
		}

		for (int i = 0; i < ctx.nextSiteId; i++) {
			for (int s = 0; s < 3; s++) {
				cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "$shape_" + i + "_" + s, "L" + Type.getInternalName(JSShape.class) + ";", null, null).visitEnd();
				cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "$offset_" + i + "_" + s, "I", null, null).visitEnd();
			}
		}

		cw.visitEnd();
		byte[] bytes = cw.toByteArray();
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		if (loader == null) loader = JSCompiler.class.getClassLoader();
		Magic.defineClass(loader, bytes);
		return funcClassName;
	}

	private static void compileNodeAsInt(Node node, CompileContext ctx) {
		MethodVisitor mv = ctx.mv;

		if (node instanceof Node.LiteralExpr lit) {
			if (lit.value instanceof Number num) {
				pushInt(mv, num.intValue());
				return;
			}
			if (lit.value instanceof Boolean b) {
				mv.visitInsn(b ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
				return;
			}
		}

		if (node instanceof Node.IdentifierExpr ident) {
			LocalVar var = ctx.getLocal(ident.name);
			if (var != null) {
				if (var.isInt()) {
					mv.visitVarInsn(Opcodes.ILOAD, var.slot);
				} else if (var.isLong()) {
					mv.visitVarInsn(Opcodes.LLOAD, var.slot);
					mv.visitInsn(Opcodes.L2I);
				} else if (var.isDouble()) {
					mv.visitVarInsn(Opcodes.DLOAD, var.slot);
					mv.visitInsn(Opcodes.D2I);
				} else {
					mv.visitVarInsn(Opcodes.ALOAD, var.slot);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toInt", "(Ljava/lang/Object;)I", false);
				}
				return;
			} else {
				int slot = JSContext.getGlobalSlot(ident.name);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				pushInt(mv, slot);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSContext.class), "getSlot", "(I)Ljava/lang/Object;", false);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toInt", "(Ljava/lang/Object;)I", false);
				return;
			}
		}

		if (node instanceof Node.BinaryExpr bin) {
			if (bin.op == TokenType.STAR) {
				compileNodeAsInt(bin.left, ctx);
				compileNodeAsInt(bin.right, ctx);
				mv.visitInsn(Opcodes.IMUL);
				return;
			} else if (bin.op == TokenType.PERCENT) {
				compileNodeAsInt(bin.left, ctx);
				compileNodeAsInt(bin.right, ctx);
				mv.visitInsn(Opcodes.IREM);
				return;
			} else if (bin.op == TokenType.MINUS) {
				compileNodeAsInt(bin.left, ctx);
				compileNodeAsInt(bin.right, ctx);
				mv.visitInsn(Opcodes.ISUB);
				return;
			} else if (bin.op == TokenType.PLUS) {
				compileNodeAsInt(bin.left, ctx);
				compileNodeAsInt(bin.right, ctx);
				mv.visitInsn(Opcodes.IADD);
				return;
			} else if (bin.op == TokenType.SLASH) {
				compileNodeAsInt(bin.left, ctx);
				compileNodeAsInt(bin.right, ctx);
				mv.visitInsn(Opcodes.IDIV);
				return;
			}
		}

		if (node instanceof Node.UnaryExpr un) {
			if (un.op == TokenType.MINUS) {
				compileNodeAsInt(un.expr, ctx);
				mv.visitInsn(Opcodes.INEG);
				return;
			}
			if (un.op == TokenType.NOT) {
				Label setOne = new Label();
				Label end    = new Label();
				compileConditionJumpTo(un.expr, ctx, setOne, false);
				mv.visitInsn(Opcodes.ICONST_0);
				mv.visitJumpInsn(Opcodes.GOTO, end);
				mv.visitLabel(setOne);
				mv.visitInsn(Opcodes.ICONST_1);
				mv.visitLabel(end);
				return;
			}
		}

		if (node instanceof Node.MemberAccessExpr member) {
			compileMemberAccessAsDouble(member, ctx);
			mv.visitInsn(Opcodes.D2I);
			return;
		}

		// 通用降级
		compileNodeAsDouble(node, ctx);
		mv.visitInsn(Opcodes.D2I);
	}

	private static void compileNodeAsLong(Node node, CompileContext ctx) {
		MethodVisitor mv = ctx.mv;

		if (node instanceof Node.LiteralExpr lit) {
			if (lit.value instanceof Number num) {
				long lVal = num.longValue();
				if (lVal >= 0 && lVal <= 1) {
					mv.visitInsn(lVal == 0 ? Opcodes.LCONST_0 : Opcodes.LCONST_1);
				} else {
					mv.visitLdcInsn(lVal);
				}
				return;
			}
		}

		if (node instanceof Node.IdentifierExpr ident) {
			LocalVar var = ctx.getLocal(ident.name);
			if (var != null) {
				if (var.isLong()) {
					mv.visitVarInsn(Opcodes.LLOAD, var.slot);
				} else if (var.isInt()) {
					mv.visitVarInsn(Opcodes.ILOAD, var.slot);
					mv.visitInsn(Opcodes.I2L);
				} else if (var.isDouble()) {
					mv.visitVarInsn(Opcodes.DLOAD, var.slot);
					mv.visitInsn(Opcodes.D2L);
				} else {
					mv.visitVarInsn(Opcodes.ALOAD, var.slot);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toLong", "(Ljava/lang/Object;)J", false);
				}
				return;
			} else {
				int slot = JSContext.getGlobalSlot(ident.name);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				pushInt(mv, slot);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSContext.class), "getSlot", "(I)Ljava/lang/Object;", false);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toLong", "(Ljava/lang/Object;)J", false);
				return;
			}
		}

		if (node instanceof Node.MemberAccessExpr member) {
			compileMemberAccessAsDouble(member, ctx);
			mv.visitInsn(Opcodes.D2L);
			return;
		}

		if (node instanceof Node.BinaryExpr bin) {
			if (bin.op == TokenType.STAR) {
				compileNodeAsLong(bin.left, ctx);
				compileNodeAsLong(bin.right, ctx);
				mv.visitInsn(Opcodes.LMUL);
				return;
			} else if (bin.op == TokenType.PERCENT) {
				compileNodeAsLong(bin.left, ctx);
				compileNodeAsLong(bin.right, ctx);
				mv.visitInsn(Opcodes.LREM);
				return;
			} else if (bin.op == TokenType.MINUS) {
				compileNodeAsLong(bin.left, ctx);
				compileNodeAsLong(bin.right, ctx);
				mv.visitInsn(Opcodes.LSUB);
				return;
			} else if (bin.op == TokenType.PLUS) {
				compileNodeAsLong(bin.left, ctx);
				compileNodeAsLong(bin.right, ctx);
				mv.visitInsn(Opcodes.LADD);
				return;
			} else if (bin.op == TokenType.SLASH) {
				compileNodeAsLong(bin.left, ctx);
				compileNodeAsLong(bin.right, ctx);
				mv.visitInsn(Opcodes.LDIV);
				return;
			}
		}

		if (node instanceof Node.UnaryExpr un) {
			if (un.op == TokenType.MINUS) {
				compileNodeAsLong(un.expr, ctx);
				mv.visitInsn(Opcodes.LNEG);
				return;
			}
		}

		// 通用降级
		compileNodeAsDouble(node, ctx);
		mv.visitInsn(Opcodes.D2L);
	}

	private static void compileNodeAsDouble(Node node, CompileContext ctx) {
		MethodVisitor mv = ctx.mv;

		if (node instanceof Node.LiteralExpr lit) {
			if (lit.value instanceof Number num) {
				mv.visitLdcInsn(num.doubleValue());
				return;
			}
		}

		if (node instanceof Node.IdentifierExpr ident) {
			LocalVar var = ctx.getLocal(ident.name);
			if (var != null) {
				if (var.isDouble()) {
					mv.visitVarInsn(Opcodes.DLOAD, var.slot);
				} else if (var.isInt()) {
					mv.visitVarInsn(Opcodes.ILOAD, var.slot);
					mv.visitInsn(Opcodes.I2D);
				} else if (var.isLong()) {
					mv.visitVarInsn(Opcodes.LLOAD, var.slot);
					mv.visitInsn(Opcodes.L2D);
				} else {
					mv.visitVarInsn(Opcodes.ALOAD, var.slot);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toDouble", "(Ljava/lang/Object;)D", false);
				}
				return;
			} else {
				int slot = JSContext.getGlobalSlot(ident.name);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				pushInt(mv, slot);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSContext.class), "getSlot", "(I)Ljava/lang/Object;", false);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toDouble", "(Ljava/lang/Object;)D", false);
				return;
			}
		}

		if (node instanceof Node.MemberAccessExpr member) {
			compileMemberAccessAsDouble(member, ctx);
			return;
		}

		if (node instanceof Node.BinaryExpr bin) {
			if (bin.op == TokenType.STAR) {
				compileNodeAsDouble(bin.left, ctx);
				compileNodeAsDouble(bin.right, ctx);
				mv.visitInsn(Opcodes.DMUL);
				return;
			} else if (bin.op == TokenType.SLASH) {
				compileNodeAsDouble(bin.left, ctx);
				compileNodeAsDouble(bin.right, ctx);
				mv.visitInsn(Opcodes.DDIV);
				return;
			} else if (bin.op == TokenType.PERCENT) {
				compileNodeAsDouble(bin.left, ctx);
				compileNodeAsDouble(bin.right, ctx);
				mv.visitInsn(Opcodes.DREM);
				return;
			} else if (bin.op == TokenType.MINUS) {
				compileNodeAsDouble(bin.left, ctx);
				compileNodeAsDouble(bin.right, ctx);
				mv.visitInsn(Opcodes.DSUB);
				return;
			} else if (bin.op == TokenType.PLUS) {
				compileNodeAsDouble(bin.left, ctx);
				compileNodeAsDouble(bin.right, ctx);
				mv.visitInsn(Opcodes.DADD);
				return;
			}
		}

		if (node instanceof Node.UnaryExpr un) {
			if (un.op == TokenType.MINUS) {
				compileNodeAsDouble(un.expr, ctx);
				mv.visitInsn(Opcodes.DNEG);
				return;
			}
		}

		if (node instanceof Node.TernaryExpr ternary) {
			Label elseLabel = new Label();
			Label endLabel = new Label();
			compileConditionJumpTo(ternary.condition, ctx, elseLabel, false);
			compileNodeAsDouble(ternary.thenExpr, ctx);
			mv.visitJumpInsn(Opcodes.GOTO, endLabel);
			mv.visitLabel(elseLabel);
			compileNodeAsDouble(ternary.elseExpr, ctx);
			mv.visitLabel(endLabel);
			return;
		}

		if (node instanceof Node.CallExpr call) {
			if (call.callee instanceof Node.MemberAccessExpr member && member.target instanceof Node.IdentifierExpr targetIdent && targetIdent.name.equals("Math")) {
				String mathMethod = member.property;
				if (mathMethod.equals("abs") && call.arguments.size() == 1) {
					compileNodeAsDouble(call.arguments.get(0), ctx);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
					return;
				} else if (mathMethod.equals("sqrt") && call.arguments.size() == 1) {
					compileNodeAsDouble(call.arguments.get(0), ctx);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "sqrt", "(D)D", false);
					return;
				} else if (mathMethod.equals("floor") && call.arguments.size() == 1) {
					compileNodeAsDouble(call.arguments.get(0), ctx);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "floor", "(D)D", false);
					return;
				} else if (mathMethod.equals("ceil") && call.arguments.size() == 1) {
					compileNodeAsDouble(call.arguments.get(0), ctx);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "ceil", "(D)D", false);
					return;
				} else if (mathMethod.equals("round") && call.arguments.size() == 1) {
					compileNodeAsDouble(call.arguments.get(0), ctx);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "round", "(D)J", false);
					mv.visitInsn(Opcodes.L2D);
					return;
				} else if (mathMethod.equals("sin") && call.arguments.size() == 1) {
					compileNodeAsDouble(call.arguments.get(0), ctx);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "sin", "(D)D", false);
					return;
				} else if (mathMethod.equals("cos") && call.arguments.size() == 1) {
					compileNodeAsDouble(call.arguments.get(0), ctx);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "cos", "(D)D", false);
					return;
				} else if (mathMethod.equals("min") && call.arguments.size() == 2) {
					compileNodeAsDouble(call.arguments.get(0), ctx);
					compileNodeAsDouble(call.arguments.get(1), ctx);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
					return;
				} else if (mathMethod.equals("max") && call.arguments.size() == 2) {
					compileNodeAsDouble(call.arguments.get(0), ctx);
					compileNodeAsDouble(call.arguments.get(1), ctx);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
					return;
				} else if (mathMethod.equals("pow") && call.arguments.size() == 2) {
					compileNodeAsDouble(call.arguments.get(0), ctx);
					compileNodeAsDouble(call.arguments.get(1), ctx);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "pow", "(DD)D", false);
					return;
				}
			}
		}

		if (node instanceof Node.IndexAccessExpr idxAccess) {
			compileIndexAccess(idxAccess, ctx, true);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toDouble", "(Ljava/lang/Object;)D", false);
			return;
		}

		// 通用降级：先计算出 Object，再转换为 double
		compileNode(node, ctx, true);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toDouble", "(Ljava/lang/Object;)D", false);
	}

	private static void compileConditionJumpTo(Node condition, CompileContext ctx, Label targetLabel, boolean jumpOnTrue) {
		MethodVisitor mv = ctx.mv;

		if (condition instanceof Node.BinaryExpr bin) {
			TokenType op = bin.op;

			if (op == TokenType.LT || op == TokenType.LTE || op == TokenType.GT || op == TokenType.GTE
			    || op == TokenType.EQ || op == TokenType.EQ_EQ || op == TokenType.NOT_EQ || op == TokenType.NOT_EQ_EQ) {

				VarType leftType  = inferVarType(bin.left, ctx);
				VarType rightType = inferVarType(bin.right, ctx);

				// 特化 1: INT 比较与 0 比较单操作数跳转
				if (leftType == VarType.INT && rightType == VarType.INT) {
					if (isZeroLiteral(bin.right)) {
						compileNodeAsInt(bin.left, ctx);
						if (jumpOnTrue) {
							switch (op) {
								case EQ, EQ_EQ -> mv.visitJumpInsn(Opcodes.IFEQ, targetLabel);
								case NOT_EQ, NOT_EQ_EQ -> mv.visitJumpInsn(Opcodes.IFNE, targetLabel);
								case LT -> mv.visitJumpInsn(Opcodes.IFLT, targetLabel);
								case LTE -> mv.visitJumpInsn(Opcodes.IFLE, targetLabel);
								case GT -> mv.visitJumpInsn(Opcodes.IFGT, targetLabel);
								case GTE -> mv.visitJumpInsn(Opcodes.IFGE, targetLabel);
							}
						} else {
							switch (op) {
								case EQ, EQ_EQ -> mv.visitJumpInsn(Opcodes.IFNE, targetLabel);
								case NOT_EQ, NOT_EQ_EQ -> mv.visitJumpInsn(Opcodes.IFEQ, targetLabel);
								case LT -> mv.visitJumpInsn(Opcodes.IFGE, targetLabel);
								case LTE -> mv.visitJumpInsn(Opcodes.IFGT, targetLabel);
								case GT -> mv.visitJumpInsn(Opcodes.IFLE, targetLabel);
								case GTE -> mv.visitJumpInsn(Opcodes.IFLT, targetLabel);
							}
						}
						return;
					}

					if (isZeroLiteral(bin.left)) {
						compileNodeAsInt(bin.right, ctx);
						if (jumpOnTrue) {
							switch (op) {
								case EQ, EQ_EQ -> mv.visitJumpInsn(Opcodes.IFEQ, targetLabel);
								case NOT_EQ, NOT_EQ_EQ -> mv.visitJumpInsn(Opcodes.IFNE, targetLabel);
								case LT -> mv.visitJumpInsn(Opcodes.IFGT, targetLabel);
								case LTE -> mv.visitJumpInsn(Opcodes.IFGE, targetLabel);
								case GT -> mv.visitJumpInsn(Opcodes.IFLT, targetLabel);
								case GTE -> mv.visitJumpInsn(Opcodes.IFLE, targetLabel);
							}
						} else {
							switch (op) {
								case EQ, EQ_EQ -> mv.visitJumpInsn(Opcodes.IFNE, targetLabel);
								case NOT_EQ, NOT_EQ_EQ -> mv.visitJumpInsn(Opcodes.IFEQ, targetLabel);
								case LT -> mv.visitJumpInsn(Opcodes.IFLE, targetLabel);
								case LTE -> mv.visitJumpInsn(Opcodes.IFLT, targetLabel);
								case GT -> mv.visitJumpInsn(Opcodes.IFGE, targetLabel);
								case GTE -> mv.visitJumpInsn(Opcodes.IFGT, targetLabel);
							}
						}
						return;
					}

					// 两个非 0 的 int 比较 (IF_ICMPxx)
					compileNodeAsInt(bin.left, ctx);
					compileNodeAsInt(bin.right, ctx);
					if (jumpOnTrue) {
						switch (op) {
							case LT -> mv.visitJumpInsn(Opcodes.IF_ICMPLT, targetLabel);
							case LTE -> mv.visitJumpInsn(Opcodes.IF_ICMPLE, targetLabel);
							case GT -> mv.visitJumpInsn(Opcodes.IF_ICMPGT, targetLabel);
							case GTE -> mv.visitJumpInsn(Opcodes.IF_ICMPGE, targetLabel);
							case EQ, EQ_EQ -> mv.visitJumpInsn(Opcodes.IF_ICMPEQ, targetLabel);
							case NOT_EQ, NOT_EQ_EQ -> mv.visitJumpInsn(Opcodes.IF_ICMPNE, targetLabel);
						}
					} else {
						switch (op) {
							case LT -> mv.visitJumpInsn(Opcodes.IF_ICMPGE, targetLabel);
							case LTE -> mv.visitJumpInsn(Opcodes.IF_ICMPGT, targetLabel);
							case GT -> mv.visitJumpInsn(Opcodes.IF_ICMPLE, targetLabel);
							case GTE -> mv.visitJumpInsn(Opcodes.IF_ICMPLT, targetLabel);
							case EQ, EQ_EQ -> mv.visitJumpInsn(Opcodes.IF_ICMPNE, targetLabel);
							case NOT_EQ, NOT_EQ_EQ -> mv.visitJumpInsn(Opcodes.IF_ICMPEQ, targetLabel);
						}
					}
					return;
				}

				// 特化 2: LONG 比较
				if (leftType == VarType.LONG && rightType == VarType.LONG) {
					compileNodeAsLong(bin.left, ctx);
					compileNodeAsLong(bin.right, ctx);
					mv.visitInsn(Opcodes.LCMP);
					if (jumpOnTrue) {
						switch (op) {
							case LT -> mv.visitJumpInsn(Opcodes.IFLT, targetLabel);
							case LTE -> mv.visitJumpInsn(Opcodes.IFLE, targetLabel);
							case GT -> mv.visitJumpInsn(Opcodes.IFGT, targetLabel);
							case GTE -> mv.visitJumpInsn(Opcodes.IFGE, targetLabel);
							case EQ, EQ_EQ -> mv.visitJumpInsn(Opcodes.IFEQ, targetLabel);
							case NOT_EQ, NOT_EQ_EQ -> mv.visitJumpInsn(Opcodes.IFNE, targetLabel);
						}
					} else {
						switch (op) {
							case LT -> mv.visitJumpInsn(Opcodes.IFGE, targetLabel);
							case LTE -> mv.visitJumpInsn(Opcodes.IFGT, targetLabel);
							case GT -> mv.visitJumpInsn(Opcodes.IFLE, targetLabel);
							case GTE -> mv.visitJumpInsn(Opcodes.IFLT, targetLabel);
							case EQ, EQ_EQ -> mv.visitJumpInsn(Opcodes.IFNE, targetLabel);
							case NOT_EQ, NOT_EQ_EQ -> mv.visitJumpInsn(Opcodes.IFEQ, targetLabel);
						}
					}
					return;
				}

				// 特化 3: DOUBLE / 数值 / 通用关系比较 (LT, LTE, GT, GTE)
				if (op == TokenType.LT || op == TokenType.LTE || op == TokenType.GT || op == TokenType.GTE) {
					compileNodeAsDouble(bin.left, ctx);
					compileNodeAsDouble(bin.right, ctx);
					mv.visitInsn(Opcodes.DCMPG);
					if (jumpOnTrue) {
						switch (op) {
							case LT -> mv.visitJumpInsn(Opcodes.IFLT, targetLabel);
							case LTE -> mv.visitJumpInsn(Opcodes.IFLE, targetLabel);
							case GT -> mv.visitJumpInsn(Opcodes.IFGT, targetLabel);
							case GTE -> mv.visitJumpInsn(Opcodes.IFGE, targetLabel);
						}
					} else {
						switch (op) {
							case LT -> mv.visitJumpInsn(Opcodes.IFGE, targetLabel);
							case LTE -> mv.visitJumpInsn(Opcodes.IFGT, targetLabel);
							case GT -> mv.visitJumpInsn(Opcodes.IFLE, targetLabel);
							case GTE -> mv.visitJumpInsn(Opcodes.IFLT, targetLabel);
						}
					}
					return;
				}

				// 特化 4: x === null / x !== null / x == null / x != null
				if (isLiteralNull(bin.left) || isLiteralNull(bin.right)) {
					Node target = isLiteralNull(bin.left) ? bin.right : bin.left;
					if (op == TokenType.EQ_EQ) {
						compileNode(target, ctx, true);
						mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNULL : Opcodes.IFNONNULL, targetLabel);
						return;
					} else if (op == TokenType.NOT_EQ_EQ) {
						compileNode(target, ctx, true);
						mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNONNULL : Opcodes.IFNULL, targetLabel);
						return;
					} else if (op == TokenType.EQ) {
						compileNode(target, ctx, true);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isEqNull", "(Ljava/lang/Object;)Z", false);
						mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
						return;
					} else /* if (op == TokenType.NOT_EQ) */ {
						compileNode(target, ctx, true);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isEqNull", "(Ljava/lang/Object;)Z", false);
						mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFEQ : Opcodes.IFNE, targetLabel);
						return;
					}
				}

				// 特化 5: x === undefined / x !== undefined / x == undefined / x != undefined
				if (isLiteralUndefined(bin.left) || isLiteralUndefined(bin.right)) {
					Node target = isLiteralUndefined(bin.left) ? bin.right : bin.left;
					if (op == TokenType.EQ_EQ) {
						compileNode(target, ctx, true);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isStrictEqUndefined", "(Ljava/lang/Object;)Z", false);
						mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
						return;
					} else if (op == TokenType.NOT_EQ_EQ) {
						compileNode(target, ctx, true);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isStrictEqUndefined", "(Ljava/lang/Object;)Z", false);
						mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFEQ : Opcodes.IFNE, targetLabel);
						return;
					} else if (op == TokenType.EQ) {
						compileNode(target, ctx, true);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isEqNull", "(Ljava/lang/Object;)Z", false);
						mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
						return;
					} else /* if (op == TokenType.NOT_EQ) */ {
						compileNode(target, ctx, true);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isEqNull", "(Ljava/lang/Object;)Z", false);
						mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFEQ : Opcodes.IFNE, targetLabel);
						return;
					}
				}

				// 特化 6: x === true / x === false / x == true / x == false
				if (isLiteralBoolean(bin.left) || isLiteralBoolean(bin.right)) {
					boolean bVal = isLiteralBoolean(bin.left)
						? (Boolean) ((Node.LiteralExpr) bin.left).value
						: (Boolean) ((Node.LiteralExpr) bin.right).value;
					Node target = isLiteralBoolean(bin.left) ? bin.right : bin.left;

					if (op == TokenType.EQ_EQ) {
						compileNode(target, ctx, true);
						pushInt(mv, bVal ? 1 : 0);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isStrictEqBool", "(Ljava/lang/Object;Z)Z", false);
						mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
						return;
					} else if (op == TokenType.NOT_EQ_EQ) {
						compileNode(target, ctx, true);
						pushInt(mv, bVal ? 1 : 0);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isStrictEqBool", "(Ljava/lang/Object;Z)Z", false);
						mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFEQ : Opcodes.IFNE, targetLabel);
						return;
					} else /* if (op == TokenType.EQ || op == TokenType.NOT_EQ) */ {
						VarType targetType = inferVarType(target, ctx);
						if (targetType == VarType.INT) {
							compileNodeAsInt(target, ctx);
							int exp = bVal ? 1 : 0;
							pushInt(mv, exp);
							if (op == TokenType.EQ) {
								mv.visitJumpInsn(jumpOnTrue ? Opcodes.IF_ICMPEQ : Opcodes.IF_ICMPNE, targetLabel);
							} else {
								mv.visitJumpInsn(jumpOnTrue ? Opcodes.IF_ICMPNE : Opcodes.IF_ICMPEQ, targetLabel);
							}
							return;
						}
						compileNode(target, ctx, true);
						pushInt(mv, bVal ? 1 : 0);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isEqBool", "(Ljava/lang/Object;Z)Z", false);
						if (op == TokenType.EQ) {
							mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
						} else {
							mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFEQ : Opcodes.IFNE, targetLabel);
						}
						return;
					}
				}

				// 特化 7: x === <number literal> / x == <number literal>
				if (isLiteralNumber(bin.left) || isLiteralNumber(bin.right)) {
					Number numVal = isLiteralNumber(bin.left)
						? (Number) ((Node.LiteralExpr) bin.left).value
						: (Number) ((Node.LiteralExpr) bin.right).value;
					Node target = isLiteralNumber(bin.left) ? bin.right : bin.left;

					if (op == TokenType.EQ_EQ || op == TokenType.NOT_EQ_EQ) {
						compileNode(target, ctx, true);
						mv.visitLdcInsn(numVal.doubleValue());
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isStrictEqDouble", "(Ljava/lang/Object;D)Z", false);
						if (op == TokenType.EQ_EQ) {
							mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
						} else {
							mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFEQ : Opcodes.IFNE, targetLabel);
						}
						return;
					} else /* if (op == TokenType.EQ || op == TokenType.NOT_EQ) */ {
						compileNode(target, ctx, true);
						mv.visitLdcInsn(numVal.doubleValue());
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isEqDouble", "(Ljava/lang/Object;D)Z", false);
						if (op == TokenType.EQ) {
							mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
						} else {
							mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFEQ : Opcodes.IFNE, targetLabel);
						}
						return;
					}
				}

				// 特化 8: x === <string literal> / x == <string literal>
				if (isLiteralString(bin.left) || isLiteralString(bin.right)) {
					String strVal = isLiteralString(bin.left)
						? (String) ((Node.LiteralExpr) bin.left).value
						: (String) ((Node.LiteralExpr) bin.right).value;
					Node target = isLiteralString(bin.left) ? bin.right : bin.left;

					if (op == TokenType.EQ_EQ || op == TokenType.NOT_EQ_EQ) {
						compileNode(target, ctx, true);
						mv.visitLdcInsn(strVal);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isStrictEqString", "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
						if (op == TokenType.EQ_EQ) {
							mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
						} else {
							mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFEQ : Opcodes.IFNE, targetLabel);
						}
						return;
					} else /* if (op == TokenType.EQ || op == TokenType.NOT_EQ) */ {
						compileNode(target, ctx, true);
						mv.visitLdcInsn(strVal);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isEqString", "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
						if (op == TokenType.EQ) {
							mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
						} else {
							mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFEQ : Opcodes.IFNE, targetLabel);
						}
						return;
					}
				}

				// 特化 9: 通用 strictEq / eq 快速跳转
				if (op == TokenType.EQ_EQ || op == TokenType.NOT_EQ_EQ) {
					compileNode(bin.left, ctx, true);
					compileNode(bin.right, ctx, true);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isStrictEq", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
					if (op == TokenType.EQ_EQ) {
						mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
					} else {
						mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFEQ : Opcodes.IFNE, targetLabel);
					}
					return;
				} else /* if (op == TokenType.EQ || op == TokenType.NOT_EQ) */ {
					compileNode(bin.left, ctx, true);
					compileNode(bin.right, ctx, true);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isEq", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
					if (op == TokenType.EQ) {
						mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
					} else {
						mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFEQ : Opcodes.IFNE, targetLabel);
					}
					return;
				}
			}
		}

		compileNode(condition, ctx, true);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isTruthy", "(Ljava/lang/Object;)Z", false);
		mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
	}

	private static void compileMemberAccess(Node.MemberAccessExpr member, CompileContext ctx, boolean needResult) {
		MethodVisitor mv = ctx.mv;
		String propName = SymbolTable.symbol(member.property);
		int propId = SymbolTable.id(member.property);

		if (!needResult) {
			compileNode(member.target, ctx, true);
			mv.visitInvokeDynamicInsn("getProp", "(Ljava/lang/Object;)Ljava/lang/Object;", BSM_GET_PROP, propName);
			mv.visitInsn(Opcodes.POP);
			return;
		}

		int siteId = ctx.nextSiteId++;
		int targetSlot = ctx.allocTempSlot();
		int resSlot = ctx.allocTempSlot();
		int shapeSlot = ctx.allocTempSlot();

		compileNode(member.target, ctx, true);
		mv.visitVarInsn(Opcodes.ASTORE, targetSlot);

		Label trySlot1 = new Label();
		Label trySlot2 = new Label();
		Label slowPath = new Label();
		Label endLabel = new Label();

		// 1. 检查 target 是否为 JSObject
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(JSObject.class));
		mv.visitJumpInsn(Opcodes.IFEQ, slowPath);

		// 提取 target.shape
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(JSObject.class));
		mv.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(JSObject.class), "shape", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitVarInsn(Opcodes.ASTORE, shapeSlot);

		// Slot 0 检查
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_0", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.IF_ACMPNE, trySlot1);

		// Slot 0 命中快路径
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(JSObject.class));
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$offset_" + siteId + "_0", "I");
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSObject.class), "getSlot", "(I)Ljava/lang/Object;", false);
		mv.visitJumpInsn(Opcodes.GOTO, endLabel);

		// Slot 1 检查
		mv.visitLabel(trySlot1);
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_1", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.IF_ACMPNE, trySlot2);

		// Slot 1 命中快路径
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(JSObject.class));
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$offset_" + siteId + "_1", "I");
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSObject.class), "getSlot", "(I)Ljava/lang/Object;", false);
		mv.visitJumpInsn(Opcodes.GOTO, endLabel);

		// Slot 2 检查
		mv.visitLabel(trySlot2);
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_2", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.IF_ACMPNE, slowPath);

		// Slot 2 命中快路径
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(JSObject.class));
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$offset_" + siteId + "_2", "I");
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSObject.class), "getSlot", "(I)Ljava/lang/Object;", false);
		mv.visitJumpInsn(Opcodes.GOTO, endLabel);

		// 慢路径：调用 invokedynamic getProp 并回填 3 槽内联缓存
		mv.visitLabel(slowPath);
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitInvokeDynamicInsn("getProp", "(Ljava/lang/Object;)Ljava/lang/Object;", BSM_GET_PROP, propName);
		mv.visitVarInsn(Opcodes.ASTORE, resSlot);

		Label skipUpdate = new Label();
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(JSObject.class));
		mv.visitJumpInsn(Opcodes.IFEQ, skipUpdate);

		int offSlot = ctx.allocTempSlot();
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(JSObject.class));
		mv.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(JSObject.class), "shape", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitVarInsn(Opcodes.ASTORE, shapeSlot);

		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		pushInt(mv, propId);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSShape.class), "getOffset", "(I)I", false);
		mv.visitVarInsn(Opcodes.ISTORE, offSlot);
		mv.visitVarInsn(Opcodes.ILOAD, offSlot);
		mv.visitJumpInsn(Opcodes.IFLT, skipUpdate);

		Label fillSlot1 = new Label();
		Label fillSlot2 = new Label();

		// 检查 Slot 0
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_0", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.IFNONNULL, fillSlot1);
		mv.visitVarInsn(Opcodes.ILOAD, offSlot);
		mv.visitFieldInsn(Opcodes.PUTSTATIC, ctx.className, "$offset_" + siteId + "_0", "I");
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitFieldInsn(Opcodes.PUTSTATIC, ctx.className, "$shape_" + siteId + "_0", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.GOTO, skipUpdate);

		// 检查 Slot 1
		mv.visitLabel(fillSlot1);
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_0", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitJumpInsn(Opcodes.IF_ACMPEQ, skipUpdate);

		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_1", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.IFNONNULL, fillSlot2);
		mv.visitVarInsn(Opcodes.ILOAD, offSlot);
		mv.visitFieldInsn(Opcodes.PUTSTATIC, ctx.className, "$offset_" + siteId + "_1", "I");
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitFieldInsn(Opcodes.PUTSTATIC, ctx.className, "$shape_" + siteId + "_1", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.GOTO, skipUpdate);

		// 检查 Slot 2
		mv.visitLabel(fillSlot2);
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_1", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitJumpInsn(Opcodes.IF_ACMPEQ, skipUpdate);

		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_2", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.IFNONNULL, skipUpdate);
		mv.visitVarInsn(Opcodes.ILOAD, offSlot);
		mv.visitFieldInsn(Opcodes.PUTSTATIC, ctx.className, "$offset_" + siteId + "_2", "I");
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitFieldInsn(Opcodes.PUTSTATIC, ctx.className, "$shape_" + siteId + "_2", "L" + Type.getInternalName(JSShape.class) + ";");

		mv.visitLabel(skipUpdate);
		mv.visitVarInsn(Opcodes.ALOAD, resSlot);
		mv.visitLabel(endLabel);
	}

	private static void compileMemberAccessAsDouble(Node.MemberAccessExpr member, CompileContext ctx) {
		MethodVisitor mv = ctx.mv;
		int siteId = ctx.nextSiteId++;
		String propName = SymbolTable.symbol(member.property);
		int propId = SymbolTable.id(member.property);
		int targetSlot = ctx.allocTempSlot();
		int resSlot = ctx.allocDoubleTempSlot();
		int shapeSlot = ctx.allocTempSlot();

		compileNode(member.target, ctx, true);
		mv.visitVarInsn(Opcodes.ASTORE, targetSlot);

		Label trySlot1 = new Label();
		Label trySlot2 = new Label();
		Label slowPath = new Label();
		Label endLabel = new Label();

		// 1. 检查 target 是否为 JSObject
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(JSObject.class));
		mv.visitJumpInsn(Opcodes.IFEQ, slowPath);

		// 提取 target.shape
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(JSObject.class));
		mv.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(JSObject.class), "shape", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitVarInsn(Opcodes.ASTORE, shapeSlot);

		// Slot 0 检查
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_0", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.IF_ACMPNE, trySlot1);

		// Slot 0 命中快路径
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(JSObject.class));
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$offset_" + siteId + "_0", "I");
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSObject.class), "getDoubleSlot", "(I)D", false);
		mv.visitJumpInsn(Opcodes.GOTO, endLabel);

		// Slot 1 检查
		mv.visitLabel(trySlot1);
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_1", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.IF_ACMPNE, trySlot2);

		// Slot 1 命中快路径
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(JSObject.class));
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$offset_" + siteId + "_1", "I");
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSObject.class), "getDoubleSlot", "(I)D", false);
		mv.visitJumpInsn(Opcodes.GOTO, endLabel);

		// Slot 2 检查
		mv.visitLabel(trySlot2);
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_2", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.IF_ACMPNE, slowPath);

		// Slot 2 命中快路径
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(JSObject.class));
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$offset_" + siteId + "_2", "I");
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSObject.class), "getDoubleSlot", "(I)D", false);
		mv.visitJumpInsn(Opcodes.GOTO, endLabel);

		// 慢路径：调用 invokedynamic getPropDouble 并回填 3 槽内联缓存
		mv.visitLabel(slowPath);
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitInvokeDynamicInsn("getPropDouble", "(Ljava/lang/Object;)D", BSM_GET_PROP_DOUBLE, propName);
		mv.visitVarInsn(Opcodes.DSTORE, resSlot);

		Label skipUpdate = new Label();
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(JSObject.class));
		mv.visitJumpInsn(Opcodes.IFEQ, skipUpdate);

		int offSlot = ctx.allocTempSlot();
		mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
		mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(JSObject.class));
		mv.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(JSObject.class), "shape", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitVarInsn(Opcodes.ASTORE, shapeSlot);

		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		pushInt(mv, propId);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSShape.class), "getOffset", "(I)I", false);
		mv.visitVarInsn(Opcodes.ISTORE, offSlot);
		mv.visitVarInsn(Opcodes.ILOAD, offSlot);
		mv.visitJumpInsn(Opcodes.IFLT, skipUpdate);

		Label fillSlot1 = new Label();
		Label fillSlot2 = new Label();

		// 检查 Slot 0
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_0", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.IFNONNULL, fillSlot1);
		mv.visitVarInsn(Opcodes.ILOAD, offSlot);
		mv.visitFieldInsn(Opcodes.PUTSTATIC, ctx.className, "$offset_" + siteId + "_0", "I");
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitFieldInsn(Opcodes.PUTSTATIC, ctx.className, "$shape_" + siteId + "_0", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.GOTO, skipUpdate);

		// 检查 Slot 1
		mv.visitLabel(fillSlot1);
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_0", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitJumpInsn(Opcodes.IF_ACMPEQ, skipUpdate);

		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_1", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.IFNONNULL, fillSlot2);
		mv.visitVarInsn(Opcodes.ILOAD, offSlot);
		mv.visitFieldInsn(Opcodes.PUTSTATIC, ctx.className, "$offset_" + siteId + "_1", "I");
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitFieldInsn(Opcodes.PUTSTATIC, ctx.className, "$shape_" + siteId + "_1", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.GOTO, skipUpdate);

		// 检查 Slot 2
		mv.visitLabel(fillSlot2);
		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_1", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitJumpInsn(Opcodes.IF_ACMPEQ, skipUpdate);

		mv.visitFieldInsn(Opcodes.GETSTATIC, ctx.className, "$shape_" + siteId + "_2", "L" + Type.getInternalName(JSShape.class) + ";");
		mv.visitJumpInsn(Opcodes.IFNONNULL, skipUpdate);
		mv.visitVarInsn(Opcodes.ILOAD, offSlot);
		mv.visitFieldInsn(Opcodes.PUTSTATIC, ctx.className, "$offset_" + siteId + "_2", "I");
		mv.visitVarInsn(Opcodes.ALOAD, shapeSlot);
		mv.visitFieldInsn(Opcodes.PUTSTATIC, ctx.className, "$shape_" + siteId + "_2", "L" + Type.getInternalName(JSShape.class) + ";");

		mv.visitLabel(skipUpdate);
		mv.visitVarInsn(Opcodes.DLOAD, resSlot);
		mv.visitLabel(endLabel);
	}

	private static void compileIndexAccess(Node.IndexAccessExpr idxAccess, CompileContext ctx, boolean needResult) {
		MethodVisitor mv = ctx.mv;
		if (idxAccess.index instanceof Node.LiteralExpr lit && lit.value instanceof String s) {
			compileMemberAccess(new Node.MemberAccessExpr(idxAccess.target, s, idxAccess.line, idxAccess.column), ctx, needResult);
			return;
		}

		VarType idxType = inferVarType(idxAccess.index, ctx);
		if (idxType == VarType.INT) {
			int targetSlot = ctx.allocTempSlot();
			int idxSlot = ctx.allocTempSlot();

			compileNode(idxAccess.target, ctx, true);
			mv.visitVarInsn(Opcodes.ASTORE, targetSlot);

			compileNodeAsInt(idxAccess.index, ctx);
			mv.visitVarInsn(Opcodes.ISTORE, idxSlot);

			Label slowPath = new Label();
			Label endLabel = new Label();

			// 1. target instanceof JSArray -> jsArr.getElement(idx)
			mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
			mv.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(JSArray.class));
			mv.visitJumpInsn(Opcodes.IFEQ, slowPath);

			mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
			mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(JSArray.class));
			mv.visitVarInsn(Opcodes.ILOAD, idxSlot);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSArray.class), "getElement", "(I)Ljava/lang/Object;", false);
			if (!needResult) mv.visitInsn(Opcodes.POP);
			mv.visitJumpInsn(Opcodes.GOTO, endLabel);

			// 2. slowPath: fallback to JSLinker.getIndex(target, idx)
			mv.visitLabel(slowPath);
			mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
			mv.visitVarInsn(Opcodes.ILOAD, idxSlot);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSLinker.class), "getIndex", "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
			if (!needResult) mv.visitInsn(Opcodes.POP);

			mv.visitLabel(endLabel);
			return;
		}

		compileNode(idxAccess.target, ctx, true);
		compileNode(idxAccess.index, ctx, true);
		mv.visitInvokeDynamicInsn("getIndex", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", BSM_GET_INDEX);
		if (!needResult) mv.visitInsn(Opcodes.POP);
	}
}
