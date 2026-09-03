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
	private static final AtomicInteger SCRIPT_ID   = new AtomicInteger(0);
	// 语义化控制常量
	public static final  long          MASK_UINT32 = JSOps.UINT32_MASK; // 0xFFFFFFFFL (用于无符号位移 >>> 0 等)

	public static final String
	 IN_JSLinker    = Type.getInternalName(JSLinker.class),
	 IN_JSContext   = Type.getInternalName(JSContext.class),
	 IN_JSOps       = Type.getInternalName(JSOps.class),
	 IN_JSScript    = Type.getInternalName(JSScript.class),
	 IN_JSUndefined = Type.getInternalName(JSUndefined.class),
	 IN_JSObject    = Type.getInternalName(JSObject.class),
	 IN_JSArray     = Type.getInternalName(JSArray.class),
	 IN_JSShape     = Type.getInternalName(JSShape.class);

	private static final MethodType
	 BSM_TYPE_PROP = MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class, String.class),
	 BSM_TYPE_BASE = MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class);

	private static final Handle
	 BSM_GET_PROP        = createBSM("bootstrapGetProp"),
	 BSM_GET_PROP_INT    = createBSM("bootstrapGetPropInt"),
	 BSM_GET_PROP_DOUBLE = createBSM("bootstrapGetPropDouble"),
	 BSM_GET_PROP_LONG   = createBSM("bootstrapGetPropLong"),
	// --
	BSM_SET_PROP         = createBSM("bootstrapSetProp"),
	 BSM_SET_PROP_DOUBLE = createBSM("bootstrapSetPropDouble"),
	 BSM_INVOKE          = createBSM("bootstrapInvoke"),
	 BSM_NEW             = createBSM("bootstrapNew", BSM_TYPE_BASE),
	 BSM_BINARY_OP       = createBSM("bootstrapBinaryOp"),
	 BSM_GET_INDEX       = createBSM("bootstrapGetIndex", BSM_TYPE_BASE),
	 BSM_SET_INDEX       = createBSM("bootstrapSetIndex", BSM_TYPE_BASE);

	public static JSScript compile(String code) throws Exception {
		JSLexer      lexer   = new JSLexer(code);
		JSParser     parser  = new JSParser(lexer.tokenize());
		Node.Program program = parser.parse();
		return JSCompiler.compile(program);
	}

	public static JSScript compile(Node.Program program) throws Exception {
		Node.Program foldedProgram = ConstantFolder.fold(program);
		String       className     = "hope/magic/gen/MagicJSScript_" + SCRIPT_ID.incrementAndGet();
		byte[]       classBytes    = generateScriptBytecode(className, foldedProgram);

		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		if (loader == null) loader = JSCompiler.class.getClassLoader();
		Class<?> loadedClass = Magic.defineClass(loader, classBytes);
		return (JSScript) loadedClass.getDeclaredConstructor().newInstance();
	}

	private static class FastClassWriter extends ClassWriter {
		public FastClassWriter(int flags) {
			super(flags);
		}

		@Override
		protected String getCommonSuperClass(String type1, String type2) {
			if (type1.equals(type2)) return type1;
			// JS 运行时代币绝大多数直接汇聚于 java/lang/Object
			return "java/lang/Object";
		}
	}

	private static byte[] generateScriptBytecode(String className, Node.Program program) {
		ClassWriter cw = new FastClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, className, null, IN_JSScript, null);

		// 默认构造函数 <init>()
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, IN_JSScript, "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();

		// 顶层函数提升收集
		List<Node.FunctionDecl> topFuncDecls = new ArrayList<>();
		collectFunctionDecls(program, topFuncDecls);

		// public void __initGlobals__(JSContext cx)
		MethodVisitor initGmv = cw.visitMethod(
		 Opcodes.ACC_PRIVATE,
		 "__initGlobals__",
		 "(L" + IN_JSContext + ";)V",
		 null, null
		);
		initGmv.visitCode();
		for (Node.FunctionDecl fd : topFuncDecls) {
			String funcClass = generateFunctionClass(fd.name, fd.params, fd.body);
			int    slot      = JSContext.getGlobalSlot(fd.name);
			initGmv.visitVarInsn(Opcodes.ALOAD, 1); // cx
			pushInt(initGmv, slot);
			instantiateFunction(initGmv, funcClass);
			initGmv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IN_JSContext, "setSlot", "(ILjava/lang/Object;)V", false);
		}
		initGmv.visitInsn(Opcodes.RETURN);
		initGmv.visitMaxs(0, 0);
		initGmv.visitEnd();

		// public Object run(JSContext cx)
		generateRunMethod(cw, className, program, "run", "(L" + IN_JSContext + ";)Ljava/lang/Object;", VarType.OBJECT);
		// public double runDouble(JSContext cx)
		generateRunMethod(cw, className, program, "runDouble", "(L" + IN_JSContext + ";)D", VarType.DOUBLE);
		// public int runInt(JSContext cx)
		generateRunMethod(cw, className, program, "runInt", "(L" + IN_JSContext + ";)I", VarType.INT);
		// public long runLong(JSContext cx)
		generateRunMethod(cw, className, program, "runLong", "(L" + IN_JSContext + ";)J", VarType.LONG);

		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void generateRunMethod(ClassWriter cw, String className, Node.Program program,
	                                      String methodName, String desc, VarType returnType) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, desc, null, new String[]{"java/lang/Throwable"});
		mv.visitCode();

		CompileContext ctx = createCompileContext(mv, className, program, null, returnType == VarType.DOUBLE);

		// this.__initGlobals__(cx)
		mv.visitVarInsn(Opcodes.ALOAD, 0); // this
		mv.visitVarInsn(Opcodes.ALOAD, 1); // cx
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, className, "__initGlobals__", "(L" + IN_JSContext + ";)V", false);

		// 遍历顶层语句
		boolean hasReturned = false;
		for (int i = 0; i < program.body.size(); i++) {
			Node    stmt   = program.body.get(i);
			boolean isLast = (i == program.body.size() - 1);
			if (isLast && (stmt instanceof Node.ExprStmt exprStmt)) {
				switch (returnType) {
					case DOUBLE -> {
						compileNodeAsDouble(exprStmt.expr, ctx);
						mv.visitInsn(Opcodes.DRETURN);
					}
					case INT -> {
						compileNodeAsInt(exprStmt.expr, ctx);
						mv.visitInsn(Opcodes.IRETURN);
					}
					case LONG -> {
						compileNodeAsLong(exprStmt.expr, ctx);
						mv.visitInsn(Opcodes.LRETURN);
					}
					default -> {
						compileNode(exprStmt.expr, ctx, true);
						mv.visitInsn(Opcodes.ARETURN);
					}
				}
				hasReturned = true;
			} else {
				compileNode(stmt, ctx, false);
			}
		}

		if (!hasReturned) {
			switch (returnType) {
				case DOUBLE -> {
					mv.visitLdcInsn(0.0);
					mv.visitInsn(Opcodes.DRETURN);
				}
				case INT -> {
					mv.visitInsn(Opcodes.ICONST_0);
					mv.visitInsn(Opcodes.IRETURN);
				}
				case LONG -> {
					mv.visitInsn(Opcodes.LCONST_0);
					mv.visitInsn(Opcodes.LRETURN);
				}
				default -> {
					visitUndefined(mv);
					mv.visitInsn(Opcodes.ARETURN);
				}
			}
		}

		mv.visitMaxs(0, 0);
		mv.visitEnd();
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

	private record TryCatchLabels(Label tryStart, Label tryEnd, Label catchHandler, Label afterTryCatch) { }

	private static class CompileContext {
		final MethodVisitor                     mv;
		final String                            className;
		final Node                              rootNode;
		final Map<String, LocalVar>             locals           = new LinkedHashMap<>();
		final Map<String, VarType>              preInferredTypes = new LinkedHashMap<>();
		final Map<Node.TryStmt, TryCatchLabels> tryCatchMap      = new IdentityHashMap<>();
		int     nextLocalSlot       = 2; // Slot 0 is 'this', Slot 1 is 'cx' (JSContext)
		int     nextSiteId          = 0;
		int     tempVarCounter      = 0;
		boolean isFunction          = false;
		String  functionName        = null;
		boolean isDoubleSpecialized = false;

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

		int markTempSlots() {
			return nextLocalSlot;
		}

		void resetTempSlots(int mark) {
			this.nextLocalSlot = mark;
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

	private static CompileContext createCompileContext(MethodVisitor mv, String className, Node.Program program,
	                                                   String functionName, boolean isDoubleSpecialized) {
		CompileContext ctx = new CompileContext(mv, className, program);
		ctx.isDoubleSpecialized = isDoubleSpecialized;
		if (functionName != null) {
			ctx.isFunction = true;
			ctx.functionName = functionName;
		}
		registerTryCatchBlocks(program, mv, ctx.tryCatchMap);
		preScanVariables(program, ctx);
		return ctx;
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
			TokenType op    = bin.op;
			VarType   left  = inferVarType(bin.left, ctx);
			VarType   right = inferVarType(bin.right, ctx);
			if (left == VarType.INT && right == VarType.INT) {
				if (op == TokenType.SLASH) return VarType.DOUBLE;
				return VarType.INT;
			}
			if (left == VarType.LONG && right == VarType.LONG && op != TokenType.SLASH) {
				return VarType.LONG;
			}
			// JS 规范：位运算产出 32 位整型 (>>> 除外可能超过 31 位正数)
			if (op == TokenType.BIT_OR || op == TokenType.BIT_AND || op == TokenType.BIT_XOR
			    || op == TokenType.SHL || op == TokenType.SHR) {
				return VarType.INT;
			}
			if (op == TokenType.USHR) {
				return VarType.DOUBLE;
			}

			// JS 规范：* / - % 是纯数值运算，只要不是纯整型，统一推断为 DOUBLE
			if (op == TokenType.STAR || op == TokenType.SLASH || op == TokenType.MINUS || op == TokenType.PERCENT) {
				return VarType.DOUBLE;
			}
			// 如果 + 且两侧非字符串，只要一侧是数值类型或数值表达式，在数学计算中推断为 DOUBLE
			if (op == TokenType.PLUS && !isStringExpr(bin.left) && !isStringExpr(bin.right)) {
				if (left.isPrimitive() || right.isPrimitive() || isNumeric(left) || isNumeric(right) || isNumericExpr(bin.left) || isNumericExpr(bin.right)) {
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
			if (op == TokenType.NOT || op == TokenType.BIT_NOT) {
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

	private static boolean isNumericBinaryOp(TokenType op) {
		return op == TokenType.STAR || op == TokenType.SLASH || op == TokenType.MINUS || op == TokenType.PERCENT
		       || op == TokenType.BIT_AND || op == TokenType.BIT_OR || op == TokenType.BIT_XOR
		       || op == TokenType.SHL || op == TokenType.SHR || op == TokenType.USHR;
	}

	private static boolean isNumericUnaryOp(TokenType op) {
		return op == TokenType.MINUS || op == TokenType.PLUS_PLUS || op == TokenType.MINUS_MINUS || op == TokenType.BIT_NOT;
	}

	private static boolean isNumericExpr(Node node) {
		if (node == null) return false;
		if (node instanceof Node.LiteralExpr lit && lit.value instanceof Number) return true;
		if (node instanceof Node.BinaryExpr bin) {
			if (bin.op == TokenType.PLUS && !isStringExpr(bin.left) && !isStringExpr(bin.right)) {
				return isNumericExpr(bin.left) || isNumericExpr(bin.right);
			}
			return isNumericBinaryOp(bin.op);
		}
		if (node instanceof Node.UnaryExpr un) {
			return isNumericUnaryOp(un.op);
		}
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

	private static boolean isVarUsedAsNumeric(String name, Node node) {
		if (node == null) return false;
		if (node instanceof Node.BinaryExpr bin) {
			TokenType op      = bin.op;
			boolean   isLeft  = bin.left instanceof Node.IdentifierExpr id && id.name.equals(name);
			boolean   isRight = bin.right instanceof Node.IdentifierExpr id && id.name.equals(name);
			if (isLeft || isRight) {
				if (isNumericBinaryOp(op) || op == TokenType.LT || op == TokenType.LTE || op == TokenType.GT || op == TokenType.GTE) {
					return true;
				}
				if (op == TokenType.EQ || op == TokenType.EQ_EQ || op == TokenType.NOT_EQ || op == TokenType.NOT_EQ_EQ) {
					Node other = isLeft ? bin.right : bin.left;
					if (isLiteralNumber(other)) return true;
				}
			}
			return isVarUsedAsNumeric(name, bin.left) || isVarUsedAsNumeric(name, bin.right);
		}
		if (node instanceof Node.UnaryExpr un) {
			if (un.expr instanceof Node.IdentifierExpr id && id.name.equals(name)) {
				if (isNumericUnaryOp(un.op)) return true;
			}
			return isVarUsedAsNumeric(name, un.expr);
		}
		if (node instanceof Node.Program prog) {
			for (Node s : prog.body) if (isVarUsedAsNumeric(name, s)) return true;
		} else if (node instanceof Node.BlockStmt b) {
			for (Node s : b.statements) if (isVarUsedAsNumeric(name, s)) return true;
		} else if (node instanceof Node.IfStmt ifStmt) {
			if (isVarUsedAsNumeric(name, ifStmt.condition)) return true;
			if (isVarUsedAsNumeric(name, ifStmt.thenBranch)) return true;
			if (ifStmt.elseBranch != null && isVarUsedAsNumeric(name, ifStmt.elseBranch)) return true;
		} else if (node instanceof Node.WhileStmt w) {
			if (isVarUsedAsNumeric(name, w.condition)) return true;
			if (isVarUsedAsNumeric(name, w.body)) return true;
		} else if (node instanceof Node.DoWhileStmt dw) {
			if (isVarUsedAsNumeric(name, dw.condition)) return true;
			if (isVarUsedAsNumeric(name, dw.body)) return true;
		} else if (node instanceof Node.ForStmt f) {
			if (isVarUsedAsNumeric(name, f.init)) return true;
			if (isVarUsedAsNumeric(name, f.condition)) return true;
			if (isVarUsedAsNumeric(name, f.update)) return true;
			if (isVarUsedAsNumeric(name, f.body)) return true;
		} else if (node instanceof Node.AssignExpr assign) {
			return isVarUsedAsNumeric(name, assign.value);
		} else if (node instanceof Node.ExprStmt exprStmt) {
			return isVarUsedAsNumeric(name, exprStmt.expr);
		} else if (node instanceof Node.ReturnStmt ret) {
			return isVarUsedAsNumeric(name, ret.value);
		}
		return false;
	}

	private static void preScanVariables(Node root, CompileContext ctx) {
		if (root == null || ctx == null) return;
		List<Node.VarDecl> varDecls = new ArrayList<>();
		collectVarDecls(root, varDecls);

		for (Node.VarDecl decl : varDecls) {
			VarType init = decl.init != null ? inferVarType(decl.init, ctx) : null;
			if ((decl.init instanceof Node.MemberAccessExpr || decl.init instanceof Node.IndexAccessExpr)
			    && isVarUsedAsNumeric(decl.name, root)) {
				init = VarType.DOUBLE;
			}
			ctx.preInferredTypes.put(decl.name, init != null ? init : VarType.INT);
		}

		for (int round = 0; round < 3; round++) {
			boolean changed = false;
			for (Node.VarDecl decl : varDecls) {
				VarType current  = ctx.preInferredTypes.get(decl.name);
				VarType assigned = findAssignedType(decl.name, root, ctx);
				VarType merged   = mergeTypes(current, assigned);
				if (merged != null && merged != current) {
					ctx.preInferredTypes.put(decl.name, merged);
					changed = true;
				}
			}
			if (!changed) break;
		}
	}

	private static void forEachChildStmt(Node node, java.util.function.Consumer<Node> action) {
		if (node == null) return;
		if (node instanceof Node.Program prog) {
			for (Node s : prog.body) action.accept(s);
		} else if (node instanceof Node.BlockStmt block) {
			for (Node s : block.statements) action.accept(s);
		} else if (node instanceof Node.IfStmt ifStmt) {
			action.accept(ifStmt.thenBranch);
			if (ifStmt.elseBranch != null) action.accept(ifStmt.elseBranch);
		} else if (node instanceof Node.WhileStmt whileStmt) {
			action.accept(whileStmt.body);
		} else if (node instanceof Node.ForStmt forStmt) {
			if (forStmt.init != null) action.accept(forStmt.init);
			action.accept(forStmt.body);
		} else if (node instanceof Node.ForOfStmt forOf) {
			action.accept(forOf.body);
		} else if (node instanceof Node.ForInStmt forIn) {
			action.accept(forIn.body);
		} else if (node instanceof Node.DoWhileStmt doWhile) {
			action.accept(doWhile.body);
		} else if (node instanceof Node.TryStmt tryStmt) {
			action.accept(tryStmt.tryBlock);
			if (tryStmt.catchBlock != null) action.accept(tryStmt.catchBlock);
			if (tryStmt.finallyBlock != null) action.accept(tryStmt.finallyBlock);
		} else if (node instanceof Node.SwitchStmt switchStmt) {
			for (Node.CaseClause c : switchStmt.cases) {
				for (Node s : c.consequent) action.accept(s);
			}
		}
	}

	private static void collectVarDecls(Node node, List<Node.VarDecl> out) {
		if (node == null) return;
		if (node instanceof Node.VarDecl decl) {
			out.add(decl);
			if (decl.init != null) collectVarDecls(decl.init, out);
		} else if (node instanceof Node.ForOfStmt forOf) {
			if (forOf.isDeclaration) {
				out.add(new Node.VarDecl(forOf.varName, new Node.LiteralExpr(null, forOf.line, forOf.column), forOf.line, forOf.column));
			}
			collectVarDecls(forOf.body, out);
		} else if (node instanceof Node.ForInStmt forIn) {
			if (forIn.isDeclaration) {
				out.add(new Node.VarDecl(forIn.varName, new Node.LiteralExpr("", forIn.line, forIn.column), forIn.line, forIn.column));
			}
			collectVarDecls(forIn.body, out);
		} else if (node instanceof Node.TryStmt tryStmt) {
			collectVarDecls(tryStmt.tryBlock, out);
			if (tryStmt.catchParam != null) {
				out.add(new Node.VarDecl(tryStmt.catchParam, new Node.LiteralExpr(null, tryStmt.line, tryStmt.column), tryStmt.line, tryStmt.column));
			}
			if (tryStmt.catchBlock != null) collectVarDecls(tryStmt.catchBlock, out);
			if (tryStmt.finallyBlock != null) collectVarDecls(tryStmt.finallyBlock, out);
		} else {
			forEachChildStmt(node, s -> collectVarDecls(s, out));
		}
	}

	private static void collectFunctionDecls(Node node, List<Node.FunctionDecl> out) {
		if (node == null) return;
		if (node instanceof Node.FunctionDecl decl) {
			out.add(decl);
		} else {
			forEachChildStmt(node, s -> collectFunctionDecls(s, out));
		}
	}

	private static void registerTryCatchBlocks(Node node, MethodVisitor mv,
	                                           Map<Node.TryStmt, TryCatchLabels> tryCatchMap) {
		if (node == null) return;
		if (node instanceof Node.TryStmt tryStmt) {
			Label tryStart      = new Label();
			Label tryEnd        = new Label();
			Label catchHandler  = new Label();
			Label afterTryCatch = new Label();
			tryCatchMap.put(tryStmt, new TryCatchLabels(tryStart, tryEnd, catchHandler, afterTryCatch));
			if (tryStmt.catchBlock != null) {
				mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Throwable");
			}
			registerTryCatchBlocks(tryStmt.tryBlock, mv, tryCatchMap);
			if (tryStmt.catchBlock != null) registerTryCatchBlocks(tryStmt.catchBlock, mv, tryCatchMap);
			if (tryStmt.finallyBlock != null) registerTryCatchBlocks(tryStmt.finallyBlock, mv, tryCatchMap);
		} else {
			forEachChildStmt(node, s -> registerTryCatchBlocks(s, mv, tryCatchMap));
		}
	}

	private static VarType preInferVarType(Node.VarDecl varDecl, CompileContext ctx) {
		if (ctx != null && ctx.preInferredTypes.containsKey(varDecl.name)) {
			return ctx.preInferredTypes.get(varDecl.name);
		}
		VarType initType = varDecl.init != null ? inferVarType(varDecl.init, ctx) : null;
		if (ctx != null && ctx.rootNode != null) {
			VarType assigned = findAssignedType(varDecl.name, ctx.rootNode, ctx);
			VarType merged   = mergeTypes(initType, assigned);
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
			VarType tInit   = forStmt.init != null ? findAssignedType(name, forStmt.init, ctx) : null;
			VarType tUpdate = forStmt.update != null ? findAssignedType(name, forStmt.update, ctx) : null;
			VarType tBody   = findAssignedType(name, forStmt.body, ctx);
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
				if (assign.op == TokenType.SLASH_ASSIGN || assign.op == TokenType.USHR_ASSIGN) return VarType.DOUBLE;
				if (assign.op == TokenType.BIT_AND_ASSIGN || assign.op == TokenType.BIT_OR_ASSIGN
				    || assign.op == TokenType.BIT_XOR_ASSIGN || assign.op == TokenType.SHL_ASSIGN
				    || assign.op == TokenType.SHR_ASSIGN) { return VarType.INT; }
				if (assign.op == TokenType.PLUS_ASSIGN || assign.op == TokenType.MINUS_ASSIGN
				    || assign.op == TokenType.STAR_ASSIGN || assign.op == TokenType.PERCENT_ASSIGN) {
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

	private static void pushLong(MethodVisitor mv, long lVal) {
		if (lVal == 0L) {
			mv.visitInsn(Opcodes.LCONST_0);
		} else if (lVal == 1L) {
			mv.visitInsn(Opcodes.LCONST_1);
		} else {
			mv.visitLdcInsn(lVal);
		}
	}

	private static void pushBoolean(MethodVisitor mv, boolean b) {
		mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", b ? "TRUE" : "FALSE", "Ljava/lang/Boolean;");
	}

	private static void boxDouble(MethodVisitor mv) {
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
	}

	private static void boxBoolean(MethodVisitor mv) {
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
	}

	private static void loadGlobal(MethodVisitor mv, String name) {
		int slot = JSContext.getGlobalSlot(name);
		mv.visitVarInsn(Opcodes.ALOAD, 1); // cx
		pushInt(mv, slot);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IN_JSContext, "getSlot", "(I)Ljava/lang/Object;", false);
	}

	private static void instantiateFunction(MethodVisitor mv, String funcClass) {
		mv.visitTypeInsn(Opcodes.NEW, funcClass);
		mv.visitInsn(Opcodes.DUP);
		mv.visitVarInsn(Opcodes.ALOAD, 1); // cx
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, funcClass, "<init>", "(L" + IN_JSContext + ";)V", false);
	}

	private static void compileNode(Node node, CompileContext ctx, boolean needResult) {
		MethodVisitor mv = ctx.mv;

		if (node instanceof Node.VarDecl varDecl) {
			compileVarDecl(varDecl, ctx, needResult);
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
			compileIf(ifStmt, ctx);
			return;
		}
		if (node instanceof Node.WhileStmt whileStmt) {
			compileWhile(whileStmt, ctx);
			return;
		}
		if (node instanceof Node.ForStmt forStmt) {
			compileFor(forStmt, ctx);
			return;
		}
		if (node instanceof Node.ForOfStmt forOf) {
			compileIteratorLoop(forOf.iterable, "toIterator", forOf.varName, forOf.body, ctx);
			return;
		}
		if (node instanceof Node.ForInStmt forIn) {
			compileIteratorLoop(forIn.object, "toKeyIterator", forIn.varName, forIn.body, ctx);
			return;
		}
		if (node instanceof Node.DoWhileStmt doWhile) {
			compileDoWhile(doWhile, ctx);
			return;
		}
		if (node instanceof Node.ThrowStmt throwStmt) {
			compileNode(throwStmt.expr, ctx, true);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "throwValue", "(Ljava/lang/Object;)Ljava/lang/RuntimeException;", false);
			mv.visitInsn(Opcodes.ATHROW);
			return;
		}
		if (node instanceof Node.TryStmt tryStmt) {
			compileTry(tryStmt, ctx);
			return;
		}
		if (node instanceof Node.SwitchStmt switchStmt) {
			compileSwitch(switchStmt, ctx);
			return;
		}
		if (node instanceof Node.BreakStmt) {
			Label target = ctx.breakTargets.peek();
			if (target != null) mv.visitJumpInsn(Opcodes.GOTO, target);
			return;
		}
		if (node instanceof Node.ContinueStmt) {
			Label target = ctx.continueTargets.peek();
			if (target != null) mv.visitJumpInsn(Opcodes.GOTO, target);
			return;
		}
		if (node instanceof Node.ReturnStmt returnStmt) {
			compileReturn(returnStmt, ctx);
			return;
		}
		if (node instanceof Node.LiteralExpr lit) {
			if (needResult) compileLiteral(lit, mv);
			return;
		}
		if (node instanceof Node.IdentifierExpr ident) {
			if (needResult) compileIdentifier(ident, ctx);
			return;
		}
		if (node instanceof Node.AssignExpr assign) {
			compileAssign(assign, ctx, needResult);
			return;
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
			compileTernary(ternary, ctx, needResult);
			return;
		}
		if (node instanceof Node.BinaryExpr bin) {
			compileBinary(bin, ctx, needResult);
			return;
		}
		if (node instanceof Node.UnaryExpr un) {
			compileUnary(un, ctx, needResult);
			return;
		}
		if (node instanceof Node.TypeOfExpr typeOf) {
			compileTypeOf(typeOf, ctx, needResult);
			return;
		}
		if (node instanceof Node.VoidExpr voidExpr) {
			compileNode(voidExpr.expr, ctx, false);
			if (needResult) visitUndefined(mv);
			return;
		}
		if (node instanceof Node.CallExpr call) {
			compileCall(call, ctx, needResult);
			return;
		}
		if (node instanceof Node.NewExpr newExpr) {
			compileNew(newExpr, ctx, needResult);
			return;
		}
		if (node instanceof Node.ObjectLiteralExpr objLit) {
			compileObjectLiteral(objLit, ctx, needResult);
			return;
		}
		if (node instanceof Node.ArrayLiteralExpr arrLit) {
			compileArrayLiteral(arrLit, ctx, needResult);
			return;
		}
		if (node instanceof Node.RegExpLiteral regLit) {
			compileRegExp(regLit, ctx, needResult);
			return;
		}
		if (node instanceof Node.FunctionExpr funcExpr) {
			compileFunctionExpr(funcExpr, ctx, needResult);
			return;
		}
		if (node instanceof Node.FunctionDecl funcDecl) {
			compileFunctionDecl(funcDecl, ctx, needResult);
			//noinspection UnnecessaryReturnStatement
			return;
		}
	}

	private static void compileVarDecl(Node.VarDecl varDecl, CompileContext ctx, boolean needResult) {
		MethodVisitor mv   = ctx.mv;
		VarType       type = preInferVarType(varDecl, ctx);
		LocalVar      var  = ctx.declareLocal(varDecl.name, type);
		if (var.isInt()) {
			if (varDecl.init != null) { compileNodeAsInt(varDecl.init, ctx); } else mv.visitInsn(Opcodes.ICONST_0);
			mv.visitVarInsn(Opcodes.ISTORE, var.slot);
		} else if (var.isLong()) {
			if (varDecl.init != null) { compileNodeAsLong(varDecl.init, ctx); } else mv.visitInsn(Opcodes.LCONST_0);
			mv.visitVarInsn(Opcodes.LSTORE, var.slot);
		} else if (var.isDouble()) {
			if (varDecl.init != null) { compileNodeAsDouble(varDecl.init, ctx); } else mv.visitInsn(Opcodes.DCONST_0);
			mv.visitVarInsn(Opcodes.DSTORE, var.slot);
		} else {
			if (varDecl.init != null) { compileNode(varDecl.init, ctx, true); } else visitUndefined(mv);
			mv.visitVarInsn(Opcodes.ASTORE, var.slot);
		}
		if (needResult) visitUndefined(mv);
	}

	private static void compileIf(Node.IfStmt ifStmt, CompileContext ctx) {
		MethodVisitor mv        = ctx.mv;
		Label         elseLabel = new Label();
		Label         endLabel  = new Label();

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
	}

	private static void compileWhile(Node.WhileStmt whileStmt, CompileContext ctx) {
		MethodVisitor mv       = ctx.mv;
		Label         loopCond = new Label();
		Label         loopBody = new Label();
		Label         loopEnd  = new Label();

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
	}

	private static void compileFor(Node.ForStmt forStmt, CompileContext ctx) {
		MethodVisitor mv = ctx.mv;
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
	}

	private static void compileIteratorLoop(Node iterable, String iterMethod, String varName, Node body,
	                                        CompileContext ctx) {
		MethodVisitor mv = ctx.mv;
		// 1. 编译可迭代对象表达式压入栈顶
		compileNode(iterable, ctx, true);
		// 2. 调用 JSOps.toIterator(target) 转为统一 Iterator<?>
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, iterMethod, "(Ljava/lang/Object;)Ljava/util/Iterator;", false);

		// 3. 分配局部变量槽位存放 Iterator
		LocalVar iterVar = ctx.declareLocal("$iter_" + (++ctx.tempVarCounter), VarType.OBJECT);
		mv.visitVarInsn(Opcodes.ASTORE, iterVar.slot);

		// 4. 获取或声明循环变量
		LocalVar loopVar = ctx.getLocal(varName);
		if (loopVar == null) {
			loopVar = ctx.declareLocal(varName, VarType.OBJECT);
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
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toInt", "(Ljava/lang/Object;)I", false);
			mv.visitVarInsn(Opcodes.ISTORE, loopVar.slot);
		} else if (loopVar.isDouble()) {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toDouble", "(Ljava/lang/Object;)D", false);
			mv.visitVarInsn(Opcodes.DSTORE, loopVar.slot);
		} else if (loopVar.isLong()) {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toLong", "(Ljava/lang/Object;)J", false);
			mv.visitVarInsn(Opcodes.LSTORE, loopVar.slot);
		} else {
			mv.visitVarInsn(Opcodes.ASTORE, loopVar.slot);
		}

		// 编译循环体
		compileNode(body, ctx, false);

		mv.visitLabel(continueLabel);
		mv.visitJumpInsn(Opcodes.GOTO, startLabel);

		mv.visitLabel(breakLabel);

		ctx.breakTargets.pop();
		ctx.continueTargets.pop();
	}

	private static void compileDoWhile(Node.DoWhileStmt doWhile, CompileContext ctx) {
		MethodVisitor mv            = ctx.mv;
		Label         startLabel    = new Label();
		Label         continueLabel = new Label();
		Label         endLabel      = new Label();

		ctx.breakTargets.push(endLabel);
		ctx.continueTargets.push(continueLabel);

		mv.visitLabel(startLabel);
		compileNode(doWhile.body, ctx, false);

		mv.visitLabel(continueLabel);
		compileConditionJumpTo(doWhile.condition, ctx, startLabel, true);

		mv.visitLabel(endLabel);

		ctx.breakTargets.pop();
		ctx.continueTargets.pop();
	}

	private static void compileTry(Node.TryStmt tryStmt, CompileContext ctx) {
		MethodVisitor  mv            = ctx.mv;
		TryCatchLabels labels        = ctx.tryCatchMap.get(tryStmt);
		Label          tryStart      = labels != null ? labels.tryStart() : new Label();
		Label          tryEnd        = labels != null ? labels.tryEnd() : new Label();
		Label          catchHandler  = labels != null ? labels.catchHandler() : new Label();
		Label          afterTryCatch = labels != null ? labels.afterTryCatch() : new Label();

		boolean hasCatch   = tryStmt.catchBlock != null;
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
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "unwrapException", "(Ljava/lang/Throwable;)Ljava/lang/Object;", false);
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
	}

	private static void compileSwitch(Node.SwitchStmt switchStmt, CompileContext ctx) {
		MethodVisitor mv = ctx.mv;
		compileNode(switchStmt.discriminant, ctx, true);
		int discSlot = ctx.allocTempSlot();
		mv.visitVarInsn(Opcodes.ASTORE, discSlot);

		Label switchEnd = new Label();
		ctx.breakTargets.push(switchEnd);

		int     numCases   = switchStmt.cases.size();
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
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "isStrictEq", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
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
	}

	private static void compileReturn(Node.ReturnStmt returnStmt, CompileContext ctx) {
		MethodVisitor mv = ctx.mv;
		if (ctx.isDoubleSpecialized) {
			if (returnStmt.value != null) {
				compileNodeAsDouble(returnStmt.value, ctx);
			} else {
				mv.visitLdcInsn(0.0);
			}
			mv.visitInsn(Opcodes.DRETURN);
			return;
		}
		if (returnStmt.value != null) {
			compileNode(returnStmt.value, ctx, true);
		} else {
			visitUndefined(mv);
		}
		mv.visitInsn(Opcodes.ARETURN);
	}

	private static void compileLiteral(Node.LiteralExpr lit, MethodVisitor mv) {
		Object val = lit.value;
		if (val == null) {
			mv.visitInsn(Opcodes.ACONST_NULL);
		} else if (val == JSUndefined.INSTANCE) {
			visitUndefined(mv);
		} else if (val instanceof Number num) {
			mv.visitLdcInsn(num.doubleValue());
			boxDouble(mv);
		} else if (val instanceof String str) {
			mv.visitLdcInsn(str);
		} else if (val instanceof Boolean b) {
			pushBoolean(mv, b);
		}
	}

	private static void compileIdentifier(Node.IdentifierExpr ident, CompileContext ctx) {
		MethodVisitor mv   = ctx.mv;
		String        name = ident.name;
		LocalVar      var  = ctx.getLocal(name);
		if (var != null) {
			if (var.isInt()) {
				mv.visitVarInsn(Opcodes.ILOAD, var.slot);
				mv.visitInsn(Opcodes.I2D);
				boxDouble(mv);
			} else if (var.isLong()) {
				mv.visitVarInsn(Opcodes.LLOAD, var.slot);
				mv.visitInsn(Opcodes.L2D);
				boxDouble(mv);
			} else if (var.isDouble()) {
				mv.visitVarInsn(Opcodes.DLOAD, var.slot);
				boxDouble(mv);
			} else {
				mv.visitVarInsn(Opcodes.ALOAD, var.slot);
			}
		} else {
			// 全局变量查找槽位化：通过全局槽位索引直读 (O(1) 数组寻址)
			loadGlobal(mv, name);
		}
	}

	private static void compileIdentifierAs(Node.IdentifierExpr ident, CompileContext ctx, VarType targetType) {
		MethodVisitor mv  = ctx.mv;
		LocalVar      var = ctx.getLocal(ident.name);
		if (var != null) {
			if (targetType == VarType.INT) {
				if (var.isInt()) { mv.visitVarInsn(Opcodes.ILOAD, var.slot); } else if (var.isLong()) {
					mv.visitVarInsn(Opcodes.LLOAD, var.slot);
					mv.visitInsn(Opcodes.L2I);
				} else if (var.isDouble()) {
					mv.visitVarInsn(Opcodes.DLOAD, var.slot);
					mv.visitInsn(Opcodes.D2I);
				} else {
					mv.visitVarInsn(Opcodes.ALOAD, var.slot);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toInt", "(Ljava/lang/Object;)I", false);
				}
			} else if (targetType == VarType.LONG) {
				if (var.isLong()) { mv.visitVarInsn(Opcodes.LLOAD, var.slot); } else if (var.isInt()) {
					mv.visitVarInsn(Opcodes.ILOAD, var.slot);
					mv.visitInsn(Opcodes.I2L);
				} else if (var.isDouble()) {
					mv.visitVarInsn(Opcodes.DLOAD, var.slot);
					mv.visitInsn(Opcodes.D2L);
				} else {
					mv.visitVarInsn(Opcodes.ALOAD, var.slot);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toLong", "(Ljava/lang/Object;)J", false);
				}
			} else {
				if (var.isDouble()) { mv.visitVarInsn(Opcodes.DLOAD, var.slot); } else if (var.isInt()) {
					mv.visitVarInsn(Opcodes.ILOAD, var.slot);
					mv.visitInsn(Opcodes.I2D);
				} else if (var.isLong()) {
					mv.visitVarInsn(Opcodes.LLOAD, var.slot);
					mv.visitInsn(Opcodes.L2D);
				} else {
					mv.visitVarInsn(Opcodes.ALOAD, var.slot);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toDouble", "(Ljava/lang/Object;)D", false);
				}
			}
		} else {
			loadGlobal(mv, ident.name);
			if (targetType == VarType.INT) {
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toInt", "(Ljava/lang/Object;)I", false);
			} else if (targetType == VarType.LONG) {
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toLong", "(Ljava/lang/Object;)J", false);
			} else {
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toDouble", "(Ljava/lang/Object;)D", false);
			}
		}
	}

	private static void storeAndResult(MethodVisitor mv, LocalVar var, boolean needResult) {
		if (var.isInt()) {
			if (needResult) {
				mv.visitInsn(Opcodes.DUP);
				mv.visitVarInsn(Opcodes.ISTORE, var.slot);
				mv.visitInsn(Opcodes.I2D);
				boxDouble(mv);
			} else {
				mv.visitVarInsn(Opcodes.ISTORE, var.slot);
			}
		} else if (var.isLong()) {
			if (needResult) {
				mv.visitInsn(Opcodes.DUP2);
				mv.visitVarInsn(Opcodes.LSTORE, var.slot);
				mv.visitInsn(Opcodes.L2D);
				boxDouble(mv);
			} else {
				mv.visitVarInsn(Opcodes.LSTORE, var.slot);
			}
		} else if (var.isDouble()) {
			if (needResult) {
				mv.visitInsn(Opcodes.DUP2);
				mv.visitVarInsn(Opcodes.DSTORE, var.slot);
				boxDouble(mv);
			} else {
				mv.visitVarInsn(Opcodes.DSTORE, var.slot);
			}
		} else {
			if (needResult) mv.visitInsn(Opcodes.DUP);
			mv.visitVarInsn(Opcodes.ASTORE, var.slot);
		}
	}

	private static int getIntCompoundOpcode(TokenType op) {
		return switch (op) {
			case PLUS_ASSIGN -> Opcodes.IADD;
			case MINUS_ASSIGN -> Opcodes.ISUB;
			case STAR_ASSIGN -> Opcodes.IMUL;
			case PERCENT_ASSIGN -> Opcodes.IREM;
			case BIT_AND_ASSIGN -> Opcodes.IAND;
			case BIT_OR_ASSIGN -> Opcodes.IOR;
			case BIT_XOR_ASSIGN -> Opcodes.IXOR;
			case SHL_ASSIGN -> Opcodes.ISHL;
			case SHR_ASSIGN -> Opcodes.ISHR;
			case USHR_ASSIGN -> Opcodes.IUSHR;
			default -> 0;
		};
	}

	private static int getLongCompoundOpcode(TokenType op) {
		return switch (op) {
			case PLUS_ASSIGN -> Opcodes.LADD;
			case MINUS_ASSIGN -> Opcodes.LSUB;
			case STAR_ASSIGN -> Opcodes.LMUL;
			case SLASH_ASSIGN -> Opcodes.LDIV;
			case PERCENT_ASSIGN -> Opcodes.LREM;
			case BIT_AND_ASSIGN -> Opcodes.LAND;
			case BIT_OR_ASSIGN -> Opcodes.LOR;
			case BIT_XOR_ASSIGN -> Opcodes.LXOR;
			default -> 0;
		};
	}

	private static void compileDoubleCompound(MethodVisitor mv, LocalVar var, Node.AssignExpr assign,
	                                          CompileContext ctx) {
		mv.visitVarInsn(Opcodes.DLOAD, var.slot);
		int dOpcode = switch (assign.op) {
			case PLUS_ASSIGN -> Opcodes.DADD;
			case MINUS_ASSIGN -> Opcodes.DSUB;
			case STAR_ASSIGN -> Opcodes.DMUL;
			case SLASH_ASSIGN -> Opcodes.DDIV;
			case PERCENT_ASSIGN -> Opcodes.DREM;
			default -> 0;
		};
		if (dOpcode != 0) {
			compileNodeAsDouble(assign.value, ctx);
			mv.visitInsn(dOpcode);
			return;
		}
		mv.visitInsn(Opcodes.D2I);
		compileNodeAsInt(assign.value, ctx);
		if (assign.op == TokenType.USHR_ASSIGN) {
			mv.visitInsn(Opcodes.IUSHR);
			mv.visitInsn(Opcodes.I2L);
			pushLong(mv, MASK_UINT32);
			mv.visitInsn(Opcodes.LAND);
			mv.visitInsn(Opcodes.L2D);
		} else {
			mv.visitInsn(getIntCompoundOpcode(assign.op));
			mv.visitInsn(Opcodes.I2D);
		}
	}

	private static void compileAssign(Node.AssignExpr assign, CompileContext ctx, boolean needResult) {
		MethodVisitor mv = ctx.mv;

		if (assign.target instanceof Node.IdentifierExpr ident) {
			String   name  = ident.name;
			LocalVar var   = ctx.getLocal(name);
			String   opStr = getBinaryOpStr(assign.op);

			if (var != null) {
				boolean isIntCompound = assign.op == TokenType.PLUS_ASSIGN || assign.op == TokenType.MINUS_ASSIGN
				                        || assign.op == TokenType.STAR_ASSIGN || assign.op == TokenType.PERCENT_ASSIGN
				                        || assign.op == TokenType.BIT_AND_ASSIGN || assign.op == TokenType.BIT_OR_ASSIGN
				                        || assign.op == TokenType.BIT_XOR_ASSIGN || assign.op == TokenType.SHL_ASSIGN
				                        || assign.op == TokenType.SHR_ASSIGN || assign.op == TokenType.USHR_ASSIGN;
				boolean isCompound = isIntCompound || assign.op == TokenType.SLASH_ASSIGN;

				if (var.isInt()) {
					if (assign.op == TokenType.ASSIGN) {
						compileNodeAsInt(assign.value, ctx);
					} else if (isIntCompound) {
						mv.visitVarInsn(Opcodes.ILOAD, var.slot);
						compileNodeAsInt(assign.value, ctx);
						mv.visitInsn(getIntCompoundOpcode(assign.op));
					}
					storeAndResult(mv, var, needResult);
					return;
				}

				if (var.isLong()) {
					if (assign.op == TokenType.ASSIGN) {
						compileNodeAsLong(assign.value, ctx);
					} else if (isCompound) {
						mv.visitVarInsn(Opcodes.LLOAD, var.slot);
						compileNodeAsLong(assign.value, ctx);
						mv.visitInsn(getLongCompoundOpcode(assign.op));
					}
					storeAndResult(mv, var, needResult);
					return;
				}

				if (var.isDouble()) {
					if (assign.op == TokenType.ASSIGN) {
						compileNodeAsDouble(assign.value, ctx);
					} else if (isCompound) {
						compileDoubleCompound(mv, var, assign, ctx);
					}
					storeAndResult(mv, var, needResult);
					return;
				}

				if (assign.op == TokenType.ASSIGN) {
					compileNode(assign.value, ctx, true);
				} else if (isCompound) {
					mv.visitVarInsn(Opcodes.ALOAD, var.slot);
					compileNode(assign.value, ctx, true);
					mv.visitInvokeDynamicInsn("op", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", BSM_BINARY_OP, opStr);
				}
				storeAndResult(mv, var, needResult);
			} else {
				int slot = JSContext.getGlobalSlot(name);
				mv.visitVarInsn(Opcodes.ALOAD, 1); // cx
				pushInt(mv, slot);
				if (assign.op == TokenType.ASSIGN) {
					compileNode(assign.value, ctx, true);
				} else {
					loadGlobal(mv, name);
					compileNode(assign.value, ctx, true);
					mv.visitInvokeDynamicInsn("op", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", BSM_BINARY_OP, opStr);
				}
				if (needResult) {
					mv.visitInsn(Opcodes.DUP_X2);
				}
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IN_JSContext, "setSlot", "(ILjava/lang/Object;)V", false);
			}
			return;
		}

		if (assign.target instanceof Node.MemberAccessExpr member) {
			compileSetProp(member.target, member.property, assign.value, ctx, needResult);
			return;
		}

		if (assign.target instanceof Node.IndexAccessExpr idxAccess) {
			compileAssignIndex(idxAccess, assign.value, ctx, needResult);
		}
	}

	private static void compileAssignIndex(Node.IndexAccessExpr idxAccess, Node value, CompileContext ctx,
	                                       boolean needResult) {
		MethodVisitor mv = ctx.mv;
		if (idxAccess.index instanceof Node.LiteralExpr lit && lit.value instanceof String s) {
			compileSetProp(idxAccess.target, s, value, ctx, needResult);
			return;
		}

		if (inferVarType(idxAccess.index, ctx) == VarType.INT) {
			int mark = ctx.markTempSlots();
			try {
				int targetSlot = ctx.allocTempSlot();
				int idxSlot    = ctx.allocTempSlot();
				int valSlot    = ctx.allocTempSlot();

				compileNode(idxAccess.target, ctx, true);
				mv.visitVarInsn(Opcodes.ASTORE, targetSlot);

				compileNodeAsInt(idxAccess.index, ctx);
				mv.visitVarInsn(Opcodes.ISTORE, idxSlot);

				compileNode(value, ctx, true);
				if (needResult) {
					mv.visitInsn(Opcodes.DUP);
				}
				mv.visitVarInsn(Opcodes.ASTORE, valSlot);

				Label slowPath = new Label();
				Label endLabel = new Label();

				// 1. JSArray fast-path
				mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
				mv.visitTypeInsn(Opcodes.INSTANCEOF, IN_JSArray);
				mv.visitJumpInsn(Opcodes.IFEQ, slowPath);

				mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
				mv.visitTypeInsn(Opcodes.CHECKCAST, IN_JSArray);
				mv.visitVarInsn(Opcodes.ILOAD, idxSlot);
				mv.visitVarInsn(Opcodes.ALOAD, valSlot);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IN_JSArray, "setElement", "(ILjava/lang/Object;)V", false);
				mv.visitJumpInsn(Opcodes.GOTO, endLabel);

				// 2. slowPath: fallback to JSLinker.setIndex(target, idx, val)
				mv.visitLabel(slowPath);
				mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
				mv.visitVarInsn(Opcodes.ILOAD, idxSlot);
				mv.visitVarInsn(Opcodes.ALOAD, valSlot);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSLinker, "setIndex", "(Ljava/lang/Object;ILjava/lang/Object;)V", false);

				mv.visitLabel(endLabel);
			} finally {
				ctx.resetTempSlots(mark);
			}
			return;
		}

		compileNode(idxAccess.target, ctx, true);
		compileNode(idxAccess.index, ctx, true);
		compileNode(value, ctx, true);
		if (needResult) {
			mv.visitInsn(Opcodes.DUP_X2);
		}
		mv.visitInvokeDynamicInsn("setIndex", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", BSM_SET_INDEX);
	}

	private static void compileSetProp(Node target, String prop, Node value, CompileContext ctx, boolean needResult) {
		MethodVisitor mv      = ctx.mv;
		VarType       valType = inferVarType(value, ctx);
		boolean       isNum   = isNumeric(valType) || isNumericExpr(value);
		if (isNum) {
			compileNode(target, ctx, true);
			compileNodeAsDouble(value, ctx);
			if (needResult) {
				mv.visitInsn(Opcodes.DUP2_X1);
				mv.visitInvokeDynamicInsn("setPropDouble", "(Ljava/lang/Object;D)V", BSM_SET_PROP_DOUBLE, prop);
				boxDouble(mv);
			} else {
				mv.visitInvokeDynamicInsn("setPropDouble", "(Ljava/lang/Object;D)V", BSM_SET_PROP_DOUBLE, prop);
			}
			return;
		}

		compileNode(target, ctx, true);
		compileNode(value, ctx, true);
		if (needResult) {
			mv.visitInsn(Opcodes.DUP_X1);
		}
		mv.visitInvokeDynamicInsn("setProp", "(Ljava/lang/Object;Ljava/lang/Object;)V", BSM_SET_PROP, prop);
	}

	private static void compileTernary(Node.TernaryExpr ternary, CompileContext ctx, boolean needResult) {
		MethodVisitor mv        = ctx.mv;
		Label         elseLabel = new Label();
		Label         endLabel  = new Label();
		compileConditionJumpTo(ternary.condition, ctx, elseLabel, false);
		compileNode(ternary.thenExpr, ctx, needResult);
		mv.visitJumpInsn(Opcodes.GOTO, endLabel);
		mv.visitLabel(elseLabel);
		compileNode(ternary.elseExpr, ctx, needResult);
		mv.visitLabel(endLabel);
	}

	private static void compileBinary(Node.BinaryExpr bin, CompileContext ctx, boolean needResult) {
		MethodVisitor mv = ctx.mv;
		if (bin.op == TokenType.AND || bin.op == TokenType.OR) {
			boolean isAnd    = (bin.op == TokenType.AND);
			Label   endLabel = new Label();
			compileNode(bin.left, ctx, true);
			if (needResult) {
				mv.visitInsn(Opcodes.DUP);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "isTruthy", "(Ljava/lang/Object;)Z", false);
				mv.visitJumpInsn(isAnd ? Opcodes.IFEQ : Opcodes.IFNE, endLabel);
				mv.visitInsn(Opcodes.POP);
				compileNode(bin.right, ctx, true);
			} else {
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "isTruthy", "(Ljava/lang/Object;)Z", false);
				mv.visitJumpInsn(isAnd ? Opcodes.IFEQ : Opcodes.IFNE, endLabel);
				compileNode(bin.right, ctx, false);
			}
			mv.visitLabel(endLabel);
			return;
		}

		VarType leftType  = inferVarType(bin.left, ctx);
		VarType rightType = inferVarType(bin.right, ctx);

		boolean isNumericMath = (bin.op == TokenType.STAR || bin.op == TokenType.SLASH || bin.op == TokenType.PERCENT || bin.op == TokenType.MINUS);
		boolean isNumericPlus = (bin.op == TokenType.PLUS && !isStringExpr(bin.left) && !isStringExpr(bin.right)
		                         && ((isNumeric(leftType) && isNumeric(rightType)) || (isNumericExpr(bin.left) && isNumericExpr(bin.right))));
		boolean isBitwise = (bin.op == TokenType.BIT_OR || bin.op == TokenType.BIT_AND || bin.op == TokenType.BIT_XOR || bin.op == TokenType.SHL || bin.op == TokenType.SHR);
		boolean isCompare = (bin.op == TokenType.EQ || bin.op == TokenType.EQ_EQ || bin.op == TokenType.NOT_EQ || bin.op == TokenType.NOT_EQ_EQ
		                     || bin.op == TokenType.LT || bin.op == TokenType.LTE || bin.op == TokenType.GT || bin.op == TokenType.GTE);

		if (!needResult && (isNumericMath || isNumericPlus || isBitwise || bin.op == TokenType.USHR || isCompare)) {
			compileNode(bin.left, ctx, false);
			compileNode(bin.right, ctx, false);
			return;
		}

		if (isNumericMath || isNumericPlus) {
			compileNodeAsDouble(bin, ctx);
			boxDouble(mv);
			return;
		}

		if (isBitwise) {
			compileNodeAsInt(bin, ctx);
			mv.visitInsn(Opcodes.I2D);
			boxDouble(mv);
			return;
		}
		if (bin.op == TokenType.USHR) {
			compileNodeAsInt(bin, ctx);
			mv.visitInsn(Opcodes.I2L);
			pushLong(mv, MASK_UINT32);
			mv.visitInsn(Opcodes.LAND);
			mv.visitInsn(Opcodes.L2D);
			boxDouble(mv);
			return;
		}

		if (isCompare) {
			Label trueLabel = new Label();
			Label endLabel  = new Label();
			compileConditionJumpTo(bin, ctx, trueLabel, true);
			pushBoolean(mv, false);
			mv.visitJumpInsn(Opcodes.GOTO, endLabel);
			mv.visitLabel(trueLabel);
			pushBoolean(mv, true);
			mv.visitLabel(endLabel);
			return;
		}

		compileNode(bin.left, ctx, true);
		compileNode(bin.right, ctx, true);

		String opStr = getBinaryOpStr(bin.op);
		if (opStr == null) throw new IllegalArgumentException("Unsupported binary op: " + bin.op);

		mv.visitInvokeDynamicInsn("op", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", BSM_BINARY_OP, opStr);
		if (!needResult) mv.visitInsn(Opcodes.POP);
	}

	private static void compileIncDec(Node.UnaryExpr un, LocalVar var, CompileContext ctx, boolean needResult) {
		MethodVisitor mv    = ctx.mv;
		boolean       isInc = (un.op == TokenType.PLUS_PLUS);
		if (var.isInt()) {
			int delta = isInc ? 1 : -1;
			if (!needResult) {
				mv.visitIincInsn(var.slot, delta);
				return;
			}
			if (un.isPrefix) {
				mv.visitIincInsn(var.slot, delta);
				mv.visitVarInsn(Opcodes.ILOAD, var.slot);
			} else {
				mv.visitVarInsn(Opcodes.ILOAD, var.slot);
				mv.visitIincInsn(var.slot, delta);
			}
			mv.visitInsn(Opcodes.I2D);
			boxDouble(mv);
			return;
		}

		if (var.isLong()) {
			if (!un.isPrefix && needResult) {
				mv.visitVarInsn(Opcodes.LLOAD, var.slot);
				mv.visitInsn(Opcodes.L2D);
				boxDouble(mv);
			}
			mv.visitVarInsn(Opcodes.LLOAD, var.slot);
			mv.visitInsn(Opcodes.LCONST_1);
			mv.visitInsn(isInc ? Opcodes.LADD : Opcodes.LSUB);
			if (un.isPrefix && needResult) {
				mv.visitInsn(Opcodes.DUP2);
				mv.visitInsn(Opcodes.L2D);
				boxDouble(mv);
			}
			mv.visitVarInsn(Opcodes.LSTORE, var.slot);
			return;
		}

		if (var.isDouble()) {
			if (!un.isPrefix && needResult) {
				mv.visitVarInsn(Opcodes.DLOAD, var.slot);
				boxDouble(mv);
			}
			mv.visitVarInsn(Opcodes.DLOAD, var.slot);
			mv.visitInsn(Opcodes.DCONST_1);
			mv.visitInsn(isInc ? Opcodes.DADD : Opcodes.DSUB);
			if (un.isPrefix && needResult) {
				mv.visitInsn(Opcodes.DUP2);
				boxDouble(mv);
			}
			mv.visitVarInsn(Opcodes.DSTORE, var.slot);
			return;
		}

		mv.visitVarInsn(Opcodes.ALOAD, var.slot);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toDouble", "(Ljava/lang/Object;)D", false);
		if (!un.isPrefix && needResult) {
			// 后置运算且需要结果：保留旧值
			mv.visitInsn(Opcodes.DUP2);
			boxDouble(mv);
		}
		mv.visitInsn(Opcodes.DCONST_1);
		mv.visitInsn(isInc ? Opcodes.DADD : Opcodes.DSUB);
		if (un.isPrefix && needResult) {
			// 前置运算且需要结果：保留新值
			mv.visitInsn(Opcodes.DUP2);
			boxDouble(mv);
		}
		boxDouble(mv);
		mv.visitVarInsn(Opcodes.ASTORE, var.slot);
	}

	private static void compileUnary(Node.UnaryExpr un, CompileContext ctx, boolean needResult) {
		MethodVisitor mv = ctx.mv;
		if (un.op == TokenType.BIT_NOT) {
			if (!needResult) {
				compileNode(un.expr, ctx, false);
				return;
			}
			compileNodeAsInt(un, ctx);
			mv.visitInsn(Opcodes.I2D);
			boxDouble(mv);
		} else if (un.op == TokenType.NOT) {
			if (!needResult) {
				compileNode(un.expr, ctx, false);
				return;
			}
			compileNode(un.expr, ctx, true);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "not", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
		} else if (un.op == TokenType.MINUS) {
			if (!needResult) {
				compileNode(un.expr, ctx, false);
				return;
			}
			compileNodeAsDouble(un.expr, ctx);
			mv.visitInsn(Opcodes.DNEG);
			boxDouble(mv);
		} else if (un.op == TokenType.PLUS_PLUS || un.op == TokenType.MINUS_MINUS) {
			if (un.expr instanceof Node.IdentifierExpr ident) {
				LocalVar var = ctx.getLocal(ident.name);
				if (var != null) {
					compileIncDec(un, var, ctx, needResult);
				}
			}
		} else if (un.op == TokenType.DELETE) {
			if (un.expr instanceof Node.MemberAccessExpr member) {
				compileNode(member.target, ctx, true);
				mv.visitLdcInsn(member.property);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "delete", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
			} else if (un.expr instanceof Node.IndexAccessExpr idx) {
				compileNode(idx.target, ctx, true);
				compileNode(idx.index, ctx, true);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "delete", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
			} else {
				compileNode(un.expr, ctx, false);
				mv.visitInsn(Opcodes.ICONST_1);
			}
			if (needResult) {
				boxBoolean(mv);
			} else {
				mv.visitInsn(Opcodes.POP);
			}
		}
	}

	private static void compileTypeOf(Node.TypeOfExpr typeOf, CompileContext ctx, boolean needResult) {
		MethodVisitor mv = ctx.mv;
		if (typeOf.expr instanceof Node.IdentifierExpr ident) {
			LocalVar var = ctx.getLocal(ident.name);
			if (var != null) {
				compileNode(ident, ctx, true);
			} else {
				loadGlobal(mv, ident.name);
			}
		} else {
			compileNode(typeOf.expr, ctx, true);
		}
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "typeOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
		if (!needResult) mv.visitInsn(Opcodes.POP);
	}

	private static String compileArgsAndGetDesc(List<Node> args, CompileContext ctx) {
		StringBuilder desc = new StringBuilder("(Ljava/lang/Object;");
		for (Node arg : args) {
			compileNode(arg, ctx, true);
			desc.append("Ljava/lang/Object;");
		}
		desc.append(")Ljava/lang/Object;");
		return desc.toString();
	}

	private static void compileCall(Node.CallExpr call, CompileContext ctx, boolean needResult) {
		MethodVisitor mv = ctx.mv;
		if (call.callee instanceof Node.IdentifierExpr ident && ctx.isFunction && ctx.functionName != null && ctx.functionName.equals(ident.name)) {
			// 自递归单态直连调用 (Direct self-recursive monomorphic invocation on 'this')
			int arity = call.arguments.size();
			if (ctx.isDoubleSpecialized && arity <= 3) {
				mv.visitVarInsn(Opcodes.ALOAD, 0); // this
				mv.visitVarInsn(Opcodes.ALOAD, 1); // cx
				for (int i = 0; i < arity; i++) {
					compileNodeAsDouble(call.arguments.get(i), ctx);
				}
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ctx.className, "call" + arity + "Double", getPrimDesc(arity), false);
				if (needResult) {
					boxDouble(mv);
				} else {
					mv.visitInsn(Opcodes.POP2);
				}
				return;
			}
			mv.visitVarInsn(Opcodes.ALOAD, 0); // this
			mv.visitVarInsn(Opcodes.ALOAD, 1); // cx
			mv.visitInsn(Opcodes.ACONST_NULL); // thisObj
			if (arity <= 3) {
				for (int i = 0; i < arity; i++) {
					compileNode(call.arguments.get(i), ctx, true);
				}
				String desc = "(L" + IN_JSContext + ";Ljava/lang/Object;" + "Ljava/lang/Object;".repeat(arity) + ")Ljava/lang/Object;";
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ctx.className, "call" + arity, desc, false);
			} else {
				pushInt(mv, arity);
				mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
				for (int i = 0; i < arity; i++) {
					mv.visitInsn(Opcodes.DUP);
					pushInt(mv, i);
					compileNode(call.arguments.get(i), ctx, true);
					mv.visitInsn(Opcodes.AASTORE);
				}
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ctx.className, "call", "(L" + IN_JSContext + ";Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
			}
			if (!needResult) mv.visitInsn(Opcodes.POP);
			return;
		}

		boolean isMember = (call.callee instanceof Node.MemberAccessExpr);
		Node    target   = isMember ? ((Node.MemberAccessExpr) call.callee).target : call.callee;
		String  name     = isMember ? ((Node.MemberAccessExpr) call.callee).property : "call";

		compileNode(target, ctx, true);
		String desc = compileArgsAndGetDesc(call.arguments, ctx);
		mv.visitInvokeDynamicInsn("invoke", desc, BSM_INVOKE, name);
		if (!needResult) mv.visitInsn(Opcodes.POP);
	}

	private static void compileNew(Node.NewExpr newExpr, CompileContext ctx, boolean needResult) {
		MethodVisitor mv = ctx.mv;
		compileNode(newExpr.constructor, ctx, true);
		String desc = compileArgsAndGetDesc(newExpr.arguments, ctx);
		mv.visitInvokeDynamicInsn("new", desc, BSM_NEW);
		if (!needResult) mv.visitInsn(Opcodes.POP);
	}

	private static void compileObjectLiteral(Node.ObjectLiteralExpr objLit, CompileContext ctx, boolean needResult) {
		MethodVisitor mv = ctx.mv;
		// 1. 预先在编译期推断完整 Shape，避免对象字面量构造过程中反复触发 3~5 次动态迁移与 putDoubleSlow
		JSShape   finalShape    = JSShape.ROOT;
		boolean[] isDoubleField = new boolean[objLit.entries.size()];
		for (int i = 0; i < objLit.entries.size(); i++) {
			Node.ObjectLiteralExpr.Entry entry   = objLit.entries.get(i);
			int                          propId  = SymbolTable.id(entry.key());
			VarType                      valType = inferVarType(entry.value(), ctx);
			boolean isNum = (valType == VarType.DOUBLE || valType == VarType.INT || valType == VarType.LONG
			                 || (entry.value() instanceof Node.LiteralExpr lit && lit.value instanceof Number));
			isDoubleField[i] = isNum;
			byte fieldType = isNum ? JSShape.TYPE_DOUBLE : JSShape.TYPE_OBJECT;
			finalShape = finalShape.addProperty(propId, fieldType);
		}

		int shapeId = JSShape.registerPrecomputedShape(finalShape);

		// 2. 实例化 JSObject 并直传预构建 Shape (0 动态迁移)
		mv.visitTypeInsn(Opcodes.NEW, IN_JSObject);
		mv.visitInsn(Opcodes.DUP);
		mv.visitFieldInsn(Opcodes.GETSTATIC, IN_JSShape, "PRECOMPUTED_SHAPES", "[L" + IN_JSShape + ";");
		pushInt(mv, shapeId);
		mv.visitInsn(Opcodes.AALOAD);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, IN_JSObject, "<init>", "(L" + IN_JSShape + ";)V", false);

		// 3. 槽位直接注入 (offset 已在编译期固定为 0, 1, 2...，直接发射 setDoubleSlot / setSlot，0 动态查表)
		for (int i = 0; i < objLit.entries.size(); i++) {
			Node.ObjectLiteralExpr.Entry entry = objLit.entries.get(i);
			mv.visitInsn(Opcodes.DUP);
			pushInt(mv, i);
			if (isDoubleField[i]) {
				compileNodeAsDouble(entry.value(), ctx);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IN_JSObject, "setDoubleSlot", "(ID)V", false);
			} else {
				compileNode(entry.value(), ctx, true);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IN_JSObject, "setSlot", "(ILjava/lang/Object;)V", false);
			}
		}
		if (!needResult) mv.visitInsn(Opcodes.POP);
	}

	private static void compileArrayLiteral(Node.ArrayLiteralExpr arrLit, CompileContext ctx, boolean needResult) {
		MethodVisitor mv = ctx.mv;
		mv.visitTypeInsn(Opcodes.NEW, IN_JSArray);
		mv.visitInsn(Opcodes.DUP);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, IN_JSArray, "<init>", "()V", false);

		for (Node elem : arrLit.elements) {
			mv.visitInsn(Opcodes.DUP);
			compileNode(elem, ctx, true);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IN_JSArray, "push", "(Ljava/lang/Object;)V", false);
		}
		if (!needResult) mv.visitInsn(Opcodes.POP);
	}

	private static void compileRegExp(Node.RegExpLiteral regLit, CompileContext ctx, boolean needResult) {
		MethodVisitor mv = ctx.mv;
		mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(JSRegExp.class));
		mv.visitInsn(Opcodes.DUP);
		mv.visitLdcInsn(regLit.pattern);
		mv.visitLdcInsn(regLit.flags);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(JSRegExp.class), "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
		if (!needResult) mv.visitInsn(Opcodes.POP);
	}

	private static void compileFunctionExpr(Node.FunctionExpr funcExpr, CompileContext ctx, boolean needResult) {
		String funcClass = generateFunctionClass(funcExpr.name, funcExpr.params, funcExpr.body);
		if (needResult) {
			instantiateFunction(ctx.mv, funcClass);
		}
	}

	private static void compileFunctionDecl(Node.FunctionDecl funcDecl, CompileContext ctx, boolean needResult) {
		MethodVisitor mv  = ctx.mv;
		LocalVar      var = ctx.getLocal(funcDecl.name);
		if (var == null) {
			String funcClass = generateFunctionClass(funcDecl.name, funcDecl.params, funcDecl.body);
			int    slot      = JSContext.getGlobalSlot(funcDecl.name);
			mv.visitVarInsn(Opcodes.ALOAD, 1); // cx
			pushInt(mv, slot);
			instantiateFunction(mv, funcClass);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IN_JSContext, "setSlot", "(ILjava/lang/Object;)V", false);
		}
		if (needResult) {
			visitUndefined(mv);
		}
	}

	private static String getPrimDesc(int arity) {
		return switch (arity) {
			case 0 -> "(L" + IN_JSContext + ";)D";
			case 1 -> "(L" + IN_JSContext + ";D)D";
			case 2 -> "(L" + IN_JSContext + ";DD)D";
			case 3 -> "(L" + IN_JSContext + ";DDD)D";
			default -> throw new IllegalArgumentException("Unsupported arity: " + arity);
		};
	}

	private static boolean isNumericFunction(Node.BlockStmt body, List<String> params, String functionName) {
		if (body == null || body.statements.isEmpty()) return false;
		List<Node.ReturnStmt> returns = new ArrayList<>();
		collectReturnStmts(body, returns);
		if (returns.isEmpty()) return false;
		Set<String> paramSet = new HashSet<>(params);
		for (Node.ReturnStmt ret : returns) {
			if (ret.value == null) return false;
			if (!isNumericReturnExpr(ret.value, paramSet, functionName)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isNumericReturnExpr(Node node, Set<String> params, String functionName) {
		if (node == null) return false;
		if (node instanceof Node.LiteralExpr lit) {
			return lit.value instanceof Number;
		}
		if (node instanceof Node.IdentifierExpr ident) {
			return params.contains(ident.name);
		}
		if (node instanceof Node.MemberAccessExpr mem) {
			return mem.target instanceof Node.IdentifierExpr t && t.name.equals("Math");
		}
		if (node instanceof Node.BinaryExpr bin) {
			if (bin.op == TokenType.PLUS) {
				return isNumericReturnExpr(bin.left, params, functionName)
				       && isNumericReturnExpr(bin.right, params, functionName);
			}
			return isNumericBinaryOp(bin.op);
		}
		if (node instanceof Node.UnaryExpr un) {
			return isNumericUnaryOp(un.op);
		}
		if (node instanceof Node.CallExpr call) {
			if (call.callee instanceof Node.IdentifierExpr ident && ident.name.equals(functionName)) {
				return true;
			}
			return call.callee instanceof Node.MemberAccessExpr mem && mem.target instanceof Node.IdentifierExpr t && t.name.equals("Math");
		}
		if (node instanceof Node.TernaryExpr ternary) {
			return isNumericReturnExpr(ternary.thenExpr, params, functionName)
			       && isNumericReturnExpr(ternary.elseExpr, params, functionName);
		}
		return false;
	}

	private static void collectReturnStmts(Node node, List<Node.ReturnStmt> out) {
		if (node == null) return;
		if (node instanceof Node.ReturnStmt ret) {
			out.add(ret);
		} else {
			forEachChildStmt(node, s -> collectReturnStmts(s, out));
		}
	}

	public static String generateFunctionClass(List<String> params, Node.BlockStmt body) {
		return generateFunctionClass(null, params, body);
	}

	public static String generateFunctionClass(String functionName, List<String> params, Node.BlockStmt body) {
		String      funcClassName = "hope/magic/gen/MagicJSFunction_" + SCRIPT_ID.incrementAndGet();
		ClassWriter cw            = new FastClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, funcClassName, null, "java/lang/Object", new String[]{Type.getInternalName(JSFunction.class)});

		// public JSContext cx;
		cw.visitField(Opcodes.ACC_PUBLIC, "cx", "L" + IN_JSContext + ";", null, null).visitEnd();

		// <init>(JSContext cx)
		MethodVisitor initCxMv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + IN_JSContext + ";)V", null, null);
		initCxMv.visitCode();
		initCxMv.visitVarInsn(Opcodes.ALOAD, 0);
		initCxMv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		initCxMv.visitVarInsn(Opcodes.ALOAD, 0);
		initCxMv.visitVarInsn(Opcodes.ALOAD, 1);
		initCxMv.visitFieldInsn(Opcodes.PUTFIELD, funcClassName, "cx", "L" + IN_JSContext + ";");
		initCxMv.visitInsn(Opcodes.RETURN);
		initCxMv.visitMaxs(2, 2);
		initCxMv.visitEnd();

		// <init>()
		MethodVisitor initMv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		initMv.visitCode();
		initMv.visitVarInsn(Opcodes.ALOAD, 0);
		initMv.visitInsn(Opcodes.ACONST_NULL);
		initMv.visitMethodInsn(Opcodes.INVOKESPECIAL, funcClassName, "<init>", "(L" + IN_JSContext + ";)V", false);
		initMv.visitInsn(Opcodes.RETURN);
		initMv.visitMaxs(2, 1);
		initMv.visitEnd();

		int    paramCount       = params.size();
		String targetMethodName = paramCount <= 3 ? "call" + paramCount : "call";
		String targetMethodDesc = paramCount <= 3
		 ? "(L" + IN_JSContext + ";Ljava/lang/Object;" + "Ljava/lang/Object;".repeat(paramCount) + ")Ljava/lang/Object;"
		 : "(L" + IN_JSContext + ";Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";

		boolean isNumFunc = isNumericFunction(body, params, functionName) && paramCount <= 3;

		if (isNumFunc) {
			String primMethodName = "call" + paramCount + "Double";
			String primMethodDesc = getPrimDesc(paramCount);
			MethodVisitor primMv = cw.visitMethod(
			 Opcodes.ACC_PUBLIC,
			 primMethodName,
			 primMethodDesc,
			 null,
			 new String[]{"java/lang/Throwable"}
			);
			primMv.visitCode();

			ensureContext(primMv, funcClassName);

			Node.Program   fakeProgPrim = new Node.Program(body.statements, body.line, body.column);
			CompileContext primCtx      = createCompileContext(primMv, funcClassName, fakeProgPrim, functionName, true);

			primCtx.nextLocalSlot = 2;
			for (int i = 0; i < paramCount; i++) {
				primCtx.declareLocal(params.get(i), VarType.DOUBLE);
			}

			// 嵌套函数局部作用域与提升
			hoistNestedFunctions(fakeProgPrim, primCtx);

			for (int i = 0; i < body.statements.size(); i++) {
				compileNode(body.statements.get(i), primCtx, false);
			}

			primMv.visitLdcInsn(0.0);
			primMv.visitInsn(Opcodes.DRETURN);
			primMv.visitMaxs(0, 0);
			primMv.visitEnd();

			// 生成转发到 callXDouble 的快速包装方法 targetMethodName (call0..call3)
			MethodVisitor callMv = cw.visitMethod(
			 Opcodes.ACC_PUBLIC,
			 targetMethodName,
			 targetMethodDesc,
			 null,
			 new String[]{"java/lang/Throwable"}
			);
			callMv.visitCode();
			callMv.visitVarInsn(Opcodes.ALOAD, 0); // this
			callMv.visitVarInsn(Opcodes.ALOAD, 1); // cx
			for (int i = 0; i < paramCount; i++) {
				callMv.visitVarInsn(Opcodes.ALOAD, 3 + i);
				callMv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toDouble", "(Ljava/lang/Object;)D", false);
			}
			callMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, funcClassName, primMethodName, primMethodDesc, false);
			boxDouble(callMv);
			callMv.visitInsn(Opcodes.ARETURN);
			callMv.visitMaxs(0, 0);
			callMv.visitEnd();
		} else {
			// 主执行方法 (当 paramCount <= 3 时编译为特化 call0..call3，零 Object[] 堆分配)
			MethodVisitor callMv = cw.visitMethod(
			 Opcodes.ACC_PUBLIC,
			 targetMethodName,
			 targetMethodDesc,
			 null,
			 new String[]{"java/lang/Throwable"}
			);
			callMv.visitCode();

			ensureContext(callMv, funcClassName);

			Node.Program   fakeProg = new Node.Program(body.statements, body.line, body.column);
			CompileContext ctx      = createCompileContext(callMv, funcClassName, fakeProg, functionName, false);

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
					LocalVar var = ctx.declareLocal(params.get(i), VarType.OBJECT);
					loadArgSafe(callMv, 3, i);
					callMv.visitVarInsn(Opcodes.ASTORE, var.slot);
				}
			}

			// 嵌套函数局部作用域与提升 (Nested Function Hoisting)
			hoistNestedFunctions(fakeProg, ctx);

			// Compile statements
			for (int i = 0; i < body.statements.size(); i++) {
				Node stmt = body.statements.get(i);
				compileNode(stmt, ctx, false);
			}

			visitUndefined(callMv);
			callMv.visitInsn(Opcodes.ARETURN);
			callMv.visitMaxs(0, 0);
			callMv.visitEnd();
		}

		// 当 paramCount <= 3 时，补充通用的 call(cx, thisObj, args[]) 桥接转发器
		if (paramCount <= 3) {
			MethodVisitor bridgeMv = cw.visitMethod(
			 Opcodes.ACC_PUBLIC,
			 "call",
			 "(L" + IN_JSContext + ";Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
			 null,
			 new String[]{"java/lang/Throwable"}
			);
			bridgeMv.visitCode();
			bridgeMv.visitVarInsn(Opcodes.ALOAD, 0); // this
			bridgeMv.visitVarInsn(Opcodes.ALOAD, 1); // cx
			bridgeMv.visitVarInsn(Opcodes.ALOAD, 2); // thisObj
			for (int i = 0; i < paramCount; i++) {
				loadArgSafe(bridgeMv, 3, i);
			}
			bridgeMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, funcClassName, targetMethodName, targetMethodDesc, false);
			bridgeMv.visitInsn(Opcodes.ARETURN);
			bridgeMv.visitMaxs(0, 0);
			bridgeMv.visitEnd();
		}

		cw.visitEnd();
		byte[]      bytes  = cw.toByteArray();
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		if (loader == null) loader = JSCompiler.class.getClassLoader();
		Magic.defineClass(loader, bytes);
		return funcClassName;
	}

	private static void ensureContext(MethodVisitor mv, String funcClassName) {
		Label cxReady = new Label();
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitJumpInsn(Opcodes.IFNONNULL, cxReady);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, funcClassName, "cx", "L" + IN_JSContext + ";");
		mv.visitVarInsn(Opcodes.ASTORE, 1);
		mv.visitLabel(cxReady);
	}

	private static void loadArgSafe(MethodVisitor mv, int argSlot, int index) {
		Label lUndef = new Label();
		Label lEnd   = new Label();
		mv.visitVarInsn(Opcodes.ALOAD, argSlot);
		mv.visitJumpInsn(Opcodes.IFNULL, lUndef);
		mv.visitVarInsn(Opcodes.ALOAD, argSlot);
		mv.visitInsn(Opcodes.ARRAYLENGTH);
		pushInt(mv, index);
		mv.visitJumpInsn(Opcodes.IF_ICMPLE, lUndef);
		mv.visitVarInsn(Opcodes.ALOAD, argSlot);
		pushInt(mv, index);
		mv.visitInsn(Opcodes.AALOAD);
		mv.visitJumpInsn(Opcodes.GOTO, lEnd);
		mv.visitLabel(lUndef);
		visitUndefined(mv);
		mv.visitLabel(lEnd);
	}

	private static void hoistNestedFunctions(Node.Program prog, CompileContext ctx) {
		List<Node.FunctionDecl> nestedFuncs = new ArrayList<>();
		collectFunctionDecls(prog, nestedFuncs);
		for (Node.FunctionDecl fd : nestedFuncs) {
			if (ctx.getLocal(fd.name) == null) {
				ctx.declareLocal(fd.name, VarType.OBJECT);
			}
		}
		for (Node.FunctionDecl fd : nestedFuncs) {
			LocalVar var = ctx.getLocal(fd.name);
			if (var != null) {
				String childFuncClass = generateFunctionClass(fd.name, fd.params, fd.body);
				instantiateFunction(ctx.mv, childFuncClass);
				ctx.mv.visitVarInsn(Opcodes.ASTORE, var.slot);
			}
		}
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
			compileIdentifierAs(ident, ctx, VarType.INT);
			return;
		}

		if (node instanceof Node.BinaryExpr bin) {
			int opcode = switch (bin.op) {
				case STAR -> Opcodes.IMUL;
				case PERCENT -> Opcodes.IREM;
				case MINUS -> Opcodes.ISUB;
				case PLUS -> Opcodes.IADD;
				case SLASH -> Opcodes.IDIV;
				case BIT_OR -> Opcodes.IOR;
				case BIT_AND -> Opcodes.IAND;
				case BIT_XOR -> Opcodes.IXOR;
				case SHL -> Opcodes.ISHL;
				case SHR -> Opcodes.ISHR;
				case USHR -> Opcodes.IUSHR;
				default -> 0;
			};
			if (opcode != 0) {
				compileNodeAsInt(bin.left, ctx);
				compileNodeAsInt(bin.right, ctx);
				mv.visitInsn(opcode);
				return;
			}
		}

		if (node instanceof Node.UnaryExpr un) {
			if (un.op == TokenType.BIT_NOT) {
				compileNodeAsInt(un.expr, ctx);
				mv.visitInsn(Opcodes.ICONST_M1);
				mv.visitInsn(Opcodes.IXOR);
				return;
			}
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
			compileMemberAccessAsInt(member, ctx);
			return;
		}

		if (node instanceof Node.IndexAccessExpr idxAccess) {
			compileIndexAccessAsInt(idxAccess, ctx);
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
				pushLong(mv, num.longValue());
				return;
			}
		}

		if (node instanceof Node.IdentifierExpr ident) {
			compileIdentifierAs(ident, ctx, VarType.LONG);
			return;
		}

		if (node instanceof Node.MemberAccessExpr member) {
			compileMemberAccessAsLong(member, ctx);
			return;
		}

		if (node instanceof Node.IndexAccessExpr idxAccess) {
			compileIndexAccessAsLong(idxAccess, ctx);
			return;
		}

		if (node instanceof Node.BinaryExpr bin) {
			int opcode = switch (bin.op) {
				case STAR -> Opcodes.LMUL;
				case PERCENT -> Opcodes.LREM;
				case MINUS -> Opcodes.LSUB;
				case PLUS -> Opcodes.LADD;
				case SLASH -> Opcodes.LDIV;
				default -> 0;
			};
			if (opcode != 0) {
				compileNodeAsLong(bin.left, ctx);
				compileNodeAsLong(bin.right, ctx);
				mv.visitInsn(opcode);
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
			compileIdentifierAs(ident, ctx, VarType.DOUBLE);
			return;
		}

		if (node instanceof Node.MemberAccessExpr member) {
			compileMemberAccessAsDouble(member, ctx);
			return;
		}

		if (node instanceof Node.BinaryExpr bin) {
			int opcode = switch (bin.op) {
				case STAR -> Opcodes.DMUL;
				case SLASH -> Opcodes.DDIV;
				case PERCENT -> Opcodes.DREM;
				case MINUS -> Opcodes.DSUB;
				case PLUS -> Opcodes.DADD;
				default -> 0;
			};
			if (opcode != 0) {
				compileNodeAsDouble(bin.left, ctx);
				compileNodeAsDouble(bin.right, ctx);
				mv.visitInsn(opcode);
				return;
			}
			if (bin.op == TokenType.BIT_OR || bin.op == TokenType.BIT_AND || bin.op == TokenType.BIT_XOR
			    || bin.op == TokenType.SHL || bin.op == TokenType.SHR) {
				compileNodeAsInt(bin, ctx);
				mv.visitInsn(Opcodes.I2D);
				return;
			}
			if (bin.op == TokenType.USHR) {
				compileNodeAsInt(bin, ctx);
				mv.visitInsn(Opcodes.I2L);
				pushLong(mv, MASK_UINT32);
				mv.visitInsn(Opcodes.LAND);
				mv.visitInsn(Opcodes.L2D);
				return;
			}
		}

		if (node instanceof Node.UnaryExpr un) {
			if (un.op == TokenType.BIT_NOT) {
				compileNodeAsInt(un, ctx);
				mv.visitInsn(Opcodes.I2D);
				return;
			}
			if (un.op == TokenType.MINUS) {
				compileNodeAsDouble(un.expr, ctx);
				mv.visitInsn(Opcodes.DNEG);
				return;
			}
		}

		if (node instanceof Node.TernaryExpr ternary) {
			Label elseLabel = new Label();
			Label endLabel  = new Label();
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
				int    argc       = call.arguments.size();
				if (argc == 1) {
					switch (mathMethod) {
						case "abs", "sqrt", "floor", "ceil", "sin", "cos" -> {
							compileNodeAsDouble(call.arguments.get(0), ctx);
							mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", mathMethod, "(D)D", false);
							return;
						}
						case "round" -> {
							compileNodeAsDouble(call.arguments.get(0), ctx);
							mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "round", "(D)J", false);
							mv.visitInsn(Opcodes.L2D);
							return;
						}
					}
				} else if (argc == 2) {
					switch (mathMethod) {
						case "min", "max", "pow" -> {
							compileNodeAsDouble(call.arguments.get(0), ctx);
							compileNodeAsDouble(call.arguments.get(1), ctx);
							mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", mathMethod, "(DD)D", false);
							return;
						}
					}
				}
			}

			// 针对 a.b(...) 的成员方法调用，必须走 BSM_INVOKE，不能作为 JSFunction 强转！
			if (call.callee instanceof Node.MemberAccessExpr member) {
				compileNode(member.target, ctx, true); // target
				String desc = compileArgsAndGetDesc(call.arguments, ctx);
				mv.visitInvokeDynamicInsn("invoke", desc, BSM_INVOKE, member.property);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toDouble", "(Ljava/lang/Object;)D", false);
				return;
			}

			int arity = call.arguments.size();
			if (arity <= 3) {
				// 自递归单态调用
				if (call.callee instanceof Node.IdentifierExpr ident && ctx.isFunction && ctx.functionName != null && ctx.functionName.equals(ident.name)) {
					// 自递归单态直连调用 (Direct self-recursive monomorphic invocation on 'this')
					mv.visitVarInsn(Opcodes.ALOAD, 0); // this
					mv.visitVarInsn(Opcodes.ALOAD, 1); // cx
					for (int i = 0; i < arity; i++) {
						compileNodeAsDouble(call.arguments.get(i), ctx);
					}
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ctx.className, "call" + arity + "Double", getPrimDesc(arity), false);
					return;
				}

				// 纯函数变量调用 (如 foo(1, 2))
				compileNode(call.callee, ctx, true);
				mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(JSFunction.class));
				mv.visitVarInsn(Opcodes.ALOAD, 1); // cx
				for (int i = 0; i < arity; i++) {
					compileNodeAsDouble(call.arguments.get(i), ctx);
				}
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(JSFunction.class), "call" + arity + "Double", getPrimDesc(arity), true);
				return;
			}
		}

		if (node instanceof Node.IndexAccessExpr idxAccess) {
			compileIndexAccessAsDouble(idxAccess, ctx);
			return;
		}

		// 通用降级：先计算出 Object，再转换为 double
		compileNode(node, ctx, true);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toDouble", "(Ljava/lang/Object;)D", false);
	}

	private static int getZeroCompareOpcode(TokenType op, boolean jumpOnTrue) {
		return switch (op) {
			case EQ, EQ_EQ -> jumpOnTrue ? Opcodes.IFEQ : Opcodes.IFNE;
			case NOT_EQ, NOT_EQ_EQ -> jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ;
			case LT -> jumpOnTrue ? Opcodes.IFLT : Opcodes.IFGE;
			case LTE -> jumpOnTrue ? Opcodes.IFLE : Opcodes.IFGT;
			case GT -> jumpOnTrue ? Opcodes.IFGT : Opcodes.IFLE;
			case GTE -> jumpOnTrue ? Opcodes.IFGE : Opcodes.IFLT;
			default -> throw new IllegalArgumentException("Unsupported compare op: " + op);
		};
	}

	private static int getIntCompareOpcode(TokenType op, boolean jumpOnTrue) {
		return switch (op) {
			case EQ, EQ_EQ -> jumpOnTrue ? Opcodes.IF_ICMPEQ : Opcodes.IF_ICMPNE;
			case NOT_EQ, NOT_EQ_EQ -> jumpOnTrue ? Opcodes.IF_ICMPNE : Opcodes.IF_ICMPEQ;
			case LT -> jumpOnTrue ? Opcodes.IF_ICMPLT : Opcodes.IF_ICMPGE;
			case LTE -> jumpOnTrue ? Opcodes.IF_ICMPLE : Opcodes.IF_ICMPGT;
			case GT -> jumpOnTrue ? Opcodes.IF_ICMPGT : Opcodes.IF_ICMPLE;
			case GTE -> jumpOnTrue ? Opcodes.IF_ICMPGE : Opcodes.IF_ICMPLT;
			default -> throw new IllegalArgumentException("Unsupported icmp op: " + op);
		};
	}

	private static void jumpOnEqualityResult(MethodVisitor mv, TokenType op, boolean jumpOnTrue, Label targetLabel) {
		boolean isEq            = (op == TokenType.EQ || op == TokenType.EQ_EQ);
		boolean shouldJumpOnOne = (isEq == jumpOnTrue);
		mv.visitJumpInsn(shouldJumpOnOne ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
	}

	private static void compileConditionJumpTo(Node condition, CompileContext ctx, Label targetLabel,
	                                           boolean jumpOnTrue) {
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
						mv.visitJumpInsn(getZeroCompareOpcode(op, jumpOnTrue), targetLabel);
						return;
					}
					if (isZeroLiteral(bin.left)) {
						compileNodeAsInt(bin.right, ctx);
						mv.visitJumpInsn(getZeroCompareOpcode(op, jumpOnTrue), targetLabel);
						return;
					}

					// 两个非 0 的 int 比较 (IF_ICMPxx)
					compileNodeAsInt(bin.left, ctx);
					compileNodeAsInt(bin.right, ctx);
					mv.visitJumpInsn(getIntCompareOpcode(op, jumpOnTrue), targetLabel);
					return;
				}

				// 特化 2: LONG 比较
				if (leftType == VarType.LONG && rightType == VarType.LONG) {
					compileNodeAsLong(bin.left, ctx);
					compileNodeAsLong(bin.right, ctx);
					mv.visitInsn(Opcodes.LCMP);
					mv.visitJumpInsn(getZeroCompareOpcode(op, jumpOnTrue), targetLabel);
					return;
				}

				// 特化 3: DOUBLE / 数值 / 通用关系比较 (LT, LTE, GT, GTE)
				if (op == TokenType.LT || op == TokenType.LTE || op == TokenType.GT || op == TokenType.GTE) {
					compileNodeAsDouble(bin.left, ctx);
					compileNodeAsDouble(bin.right, ctx);
					// IEEE 754 规范: 与 NaN 比较恒为 false
					// < 和 <= 使用 DCMPG (遇到 NaN 产出 1, IFLT/IFLE 判定失败)
					// > 和 >= 必须使用 DCMPL (遇到 NaN 产出 -1, IFGT/IFGE 判定失败)
					mv.visitInsn((op == TokenType.LT || op == TokenType.LTE) ? Opcodes.DCMPG : Opcodes.DCMPL);
					mv.visitJumpInsn(getZeroCompareOpcode(op, jumpOnTrue), targetLabel);
					return;
				}

				// 特化 4: x === null / x !== null / x == null / x != null
				if (isLiteralNull(bin.left) || isLiteralNull(bin.right)) {
					Node target = isLiteralNull(bin.left) ? bin.right : bin.left;
					if (op == TokenType.EQ_EQ || op == TokenType.NOT_EQ_EQ) {
						compileNode(target, ctx, true);
						boolean jumpOnNull = (op == TokenType.EQ_EQ) == jumpOnTrue;
						mv.visitJumpInsn(jumpOnNull ? Opcodes.IFNULL : Opcodes.IFNONNULL, targetLabel);
						return;
					}
					compileNode(target, ctx, true);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "isEqNull", "(Ljava/lang/Object;)Z", false);
					jumpOnEqualityResult(mv, op, jumpOnTrue, targetLabel);
					return;
				}

				// 特化 5: x === undefined / x !== undefined / x == undefined / x != undefined
				if (isLiteralUndefined(bin.left) || isLiteralUndefined(bin.right)) {
					Node target = isLiteralUndefined(bin.left) ? bin.right : bin.left;
					compileNode(target, ctx, true);
					if (op == TokenType.EQ_EQ || op == TokenType.NOT_EQ_EQ) {
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "isStrictEqUndefined", "(Ljava/lang/Object;)Z", false);
					} else {
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "isEqNull", "(Ljava/lang/Object;)Z", false);
					}
					jumpOnEqualityResult(mv, op, jumpOnTrue, targetLabel);
					return;
				}

				// 特化 6: x === true / x === false / x == true / x == false
				if (isLiteralBoolean(bin.left) || isLiteralBoolean(bin.right)) {
					boolean bVal = (Boolean) (isLiteralBoolean(bin.left)
					 ? ((Node.LiteralExpr) bin.left).value
					 : ((Node.LiteralExpr) bin.right).value);
					Node target = isLiteralBoolean(bin.left) ? bin.right : bin.left;

					if (op == TokenType.EQ_EQ || op == TokenType.NOT_EQ_EQ) {
						compileNode(target, ctx, true);
						pushInt(mv, bVal ? 1 : 0);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "isStrictEqBool", "(Ljava/lang/Object;Z)Z", false);
						jumpOnEqualityResult(mv, op, jumpOnTrue, targetLabel);
						return;
					}

					VarType targetType = inferVarType(target, ctx);
					if (targetType == VarType.INT) {
						compileNodeAsInt(target, ctx);
						pushInt(mv, bVal ? 1 : 0);
						mv.visitJumpInsn(getIntCompareOpcode(op, jumpOnTrue), targetLabel);
						return;
					}
					compileNode(target, ctx, true);
					pushInt(mv, bVal ? 1 : 0);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "isEqBool", "(Ljava/lang/Object;Z)Z", false);
					jumpOnEqualityResult(mv, op, jumpOnTrue, targetLabel);
					return;
				}

				// 特化 7: x === <number literal> / x == <number literal>
				if (isLiteralNumber(bin.left) || isLiteralNumber(bin.right)) {
					Number numVal = (Number) (isLiteralNumber(bin.left)
					 ? ((Node.LiteralExpr) bin.left).value
					 : ((Node.LiteralExpr) bin.right).value);
					Node target = isLiteralNumber(bin.left) ? bin.right : bin.left;

					VarType targetType      = inferVarType(target, ctx);
					boolean isTargetNumeric = isNumeric(targetType) || isNumericExpr(target);
					if (isTargetNumeric) {
						if (targetType == VarType.INT && numVal.doubleValue() == numVal.intValue()) {
							compileNodeAsInt(target, ctx);
							pushInt(mv, numVal.intValue());
							mv.visitJumpInsn(getIntCompareOpcode(op, jumpOnTrue), targetLabel);
							return;
						}
						compileNodeAsDouble(target, ctx);
						mv.visitLdcInsn(numVal.doubleValue());
						mv.visitInsn(Opcodes.DCMPL);
						mv.visitJumpInsn(getZeroCompareOpcode(op, jumpOnTrue), targetLabel);
						return;
					}

					compileNode(target, ctx, true);
					mv.visitLdcInsn(numVal.doubleValue());
					boolean strict = (op == TokenType.EQ_EQ || op == TokenType.NOT_EQ_EQ);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, strict ? "isStrictEqDouble" : "isEqDouble", "(Ljava/lang/Object;D)Z", false);
					jumpOnEqualityResult(mv, op, jumpOnTrue, targetLabel);
					return;
				}

				// 特化 8: x === <string literal> / x == <string literal>
				if (isLiteralString(bin.left) || isLiteralString(bin.right)) {
					String strVal = (String) (isLiteralString(bin.left)
					 ? ((Node.LiteralExpr) bin.left).value
					 : ((Node.LiteralExpr) bin.right).value);
					Node target = isLiteralString(bin.left) ? bin.right : bin.left;

					compileNode(target, ctx, true);
					mv.visitLdcInsn(strVal);
					boolean strict = (op == TokenType.EQ_EQ || op == TokenType.NOT_EQ_EQ);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, strict ? "isStrictEqString" : "isEqString", "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
					jumpOnEqualityResult(mv, op, jumpOnTrue, targetLabel);
					return;
				}

				// 特化 9: 通用 strictEq / eq 快速跳转
				boolean strict = (op == TokenType.EQ_EQ || op == TokenType.NOT_EQ_EQ);
				compileNode(bin.left, ctx, true);
				compileNode(bin.right, ctx, true);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, strict ? "isStrictEq" : "isEq", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
				jumpOnEqualityResult(mv, op, jumpOnTrue, targetLabel);
				return;
			}
		}

		compileNode(condition, ctx, true);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "isTruthy", "(Ljava/lang/Object;)Z", false);
		mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
	}

	private static void compileMemberAccess(Node.MemberAccessExpr member, CompileContext ctx, boolean needResult) {
		MethodVisitor mv       = ctx.mv;
		String        propName = SymbolTable.symbol(member.property);

		compileNode(member.target, ctx, true);
		mv.visitInvokeDynamicInsn("getProp", "(Ljava/lang/Object;)Ljava/lang/Object;", BSM_GET_PROP, propName);
		if (!needResult) {
			mv.visitInsn(Opcodes.POP);
		}
	}

	private static void compileMemberAccessAsDouble(Node.MemberAccessExpr member, CompileContext ctx) {
		MethodVisitor mv       = ctx.mv;
		String        propName = SymbolTable.symbol(member.property);

		compileNode(member.target, ctx, true);
		mv.visitInvokeDynamicInsn("getPropDouble", "(Ljava/lang/Object;)D", BSM_GET_PROP_DOUBLE, propName);
	}

	private static void compileMemberAccessAsInt(Node.MemberAccessExpr member, CompileContext ctx) {
		MethodVisitor mv       = ctx.mv;
		String        propName = SymbolTable.symbol(member.property);

		compileNode(member.target, ctx, true);
		mv.visitInvokeDynamicInsn("getPropInt", "(Ljava/lang/Object;)I", BSM_GET_PROP_INT, propName);
	}

	private static void compileMemberAccessAsLong(Node.MemberAccessExpr member, CompileContext ctx) {
		MethodVisitor mv       = ctx.mv;
		String        propName = SymbolTable.symbol(member.property);

		compileNode(member.target, ctx, true);
		mv.visitInvokeDynamicInsn("getPropLong", "(Ljava/lang/Object;)J", BSM_GET_PROP_LONG, propName);
	}

	private static void compileIntIndexedAccess(Node target, Node index, CompileContext ctx, boolean asDouble,
	                                            boolean needResult) {
		MethodVisitor mv   = ctx.mv;
		int           mark = ctx.markTempSlots();
		try {
			int targetSlot = ctx.allocTempSlot();
			int idxSlot    = ctx.allocTempSlot();

			compileNode(target, ctx, true);
			mv.visitVarInsn(Opcodes.ASTORE, targetSlot);

			compileNodeAsInt(index, ctx);
			mv.visitVarInsn(Opcodes.ISTORE, idxSlot);

			Label slowPath = new Label();
			Label endLabel = new Label();

			// 1. target instanceof JSArray -> jsArr.getElement(idx) / jsArr.getElementDouble(idx) (无装箱直读原生 double)
			mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
			mv.visitTypeInsn(Opcodes.INSTANCEOF, IN_JSArray);
			mv.visitJumpInsn(Opcodes.IFEQ, slowPath);

			mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
			mv.visitTypeInsn(Opcodes.CHECKCAST, IN_JSArray);
			mv.visitVarInsn(Opcodes.ILOAD, idxSlot);
			if (asDouble) {
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IN_JSArray, "getElementDouble", "(I)D", false);
			} else {
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IN_JSArray, "getElement", "(I)Ljava/lang/Object;", false);
				if (!needResult) mv.visitInsn(Opcodes.POP);
			}
			mv.visitJumpInsn(Opcodes.GOTO, endLabel);

			// 2. slowPath: fallback to JSLinker.getIndex(target, idx) [-> toDouble]
			mv.visitLabel(slowPath);
			mv.visitVarInsn(Opcodes.ALOAD, targetSlot);
			mv.visitVarInsn(Opcodes.ILOAD, idxSlot);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSLinker, "getIndex", "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
			if (asDouble) {
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toDouble", "(Ljava/lang/Object;)D", false);
			} else if (!needResult) {
				mv.visitInsn(Opcodes.POP);
			}

			mv.visitLabel(endLabel);
		} finally {
			ctx.resetTempSlots(mark);
		}
	}

	private static void compileIndexAccessAsDouble(Node.IndexAccessExpr idxAccess, CompileContext ctx) {
		MethodVisitor mv = ctx.mv;
		if (idxAccess.index instanceof Node.LiteralExpr lit && lit.value instanceof String s) {
			compileMemberAccessAsDouble(new Node.MemberAccessExpr(idxAccess.target, s, idxAccess.line, idxAccess.column), ctx);
			return;
		}

		if (inferVarType(idxAccess.index, ctx) == VarType.INT) {
			compileIntIndexedAccess(idxAccess.target, idxAccess.index, ctx, true, true);
			return;
		}

		compileNode(idxAccess.target, ctx, true);
		compileNode(idxAccess.index, ctx, true);
		mv.visitInvokeDynamicInsn("getIndex", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", BSM_GET_INDEX);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, IN_JSOps, "toDouble", "(Ljava/lang/Object;)D", false);
	}

	private static void compileIndexAccessAsInt(Node.IndexAccessExpr idxAccess, CompileContext ctx) {
		MethodVisitor mv = ctx.mv;
		if (idxAccess.index instanceof Node.LiteralExpr lit && lit.value instanceof String s) {
			compileMemberAccessAsInt(new Node.MemberAccessExpr(idxAccess.target, s, idxAccess.line, idxAccess.column), ctx);
			return;
		}
		compileIndexAccessAsDouble(idxAccess, ctx);
		mv.visitInsn(Opcodes.D2I);
	}

	private static void compileIndexAccessAsLong(Node.IndexAccessExpr idxAccess, CompileContext ctx) {
		MethodVisitor mv = ctx.mv;
		if (idxAccess.index instanceof Node.LiteralExpr lit && lit.value instanceof String s) {
			compileMemberAccessAsLong(new Node.MemberAccessExpr(idxAccess.target, s, idxAccess.line, idxAccess.column), ctx);
			return;
		}
		compileIndexAccessAsDouble(idxAccess, ctx);
		mv.visitInsn(Opcodes.D2L);
	}

	private static void compileIndexAccess(Node.IndexAccessExpr idxAccess, CompileContext ctx, boolean needResult) {
		MethodVisitor mv = ctx.mv;
		if (idxAccess.index instanceof Node.LiteralExpr lit && lit.value instanceof String s) {
			compileMemberAccess(new Node.MemberAccessExpr(idxAccess.target, s, idxAccess.line, idxAccess.column), ctx, needResult);
			return;
		}

		if (inferVarType(idxAccess.index, ctx) == VarType.INT) {
			compileIntIndexedAccess(idxAccess.target, idxAccess.index, ctx, false, needResult);
			return;
		}

		compileNode(idxAccess.target, ctx, true);
		compileNode(idxAccess.index, ctx, true);
		mv.visitInvokeDynamicInsn("getIndex", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", BSM_GET_INDEX);
		if (!needResult) mv.visitInsn(Opcodes.POP);
	}

	private static String getBinaryOpStr(TokenType op) {
		return switch (op) {
			case PLUS, PLUS_ASSIGN -> "+";
			case MINUS, MINUS_ASSIGN -> "-";
			case STAR, STAR_ASSIGN -> "*";
			case SLASH, SLASH_ASSIGN -> "/";
			case PERCENT, PERCENT_ASSIGN -> "%";
			case BIT_AND, BIT_AND_ASSIGN -> "&";
			case BIT_OR, BIT_OR_ASSIGN -> "|";
			case BIT_XOR, BIT_XOR_ASSIGN -> "^";
			case SHL, SHL_ASSIGN -> "<<";
			case SHR, SHR_ASSIGN -> ">>";
			case USHR, USHR_ASSIGN -> ">>>";
			default -> null;
		};
	}

	//region 辅助方法

	private static Handle createBSM(String name) {
		return new Handle(Opcodes.H_INVOKESTATIC, IN_JSLinker, name, BSM_TYPE_PROP.toMethodDescriptorString(), false);
	}

	private static Handle createBSM(String name, MethodType methodType) {
		return new Handle(Opcodes.H_INVOKESTATIC, IN_JSLinker, name, methodType.toMethodDescriptorString(), false);
	}

	private static void visitUndefined(MethodVisitor mv) {
		mv.visitFieldInsn(Opcodes.GETSTATIC, IN_JSUndefined, "INSTANCE", "L" + IN_JSUndefined + ";");
	}
	//endregion
}
