package hope.magic.js.compiler;

import hope.magic.js.ast.*;
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

		// 遍历顶层语句
		for (int i = 0; i < program.body.size(); i++) {
			Node    stmt   = program.body.get(i);
			boolean isLast = (i == program.body.size() - 1);
			if (isLast && (stmt instanceof Node.ExprStmt exprStmt)) {
				// 最后一条表达式语句：保留计算结果并直接 ARETURN
				compileNode(exprStmt.expr, ctx, true);
				runMv.visitInsn(Opcodes.ARETURN);
				runMv.visitMaxs(0, 0);
				runMv.visitEnd();
				cw.visitEnd();
				return cw.toByteArray();
			} else {
				compileNode(stmt, ctx, false);
			}
		}

		// 如果最后没有显式 return，默认返回 undefined
		runMv.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(JSUndefined.class), "INSTANCE", "L" + Type.getInternalName(JSUndefined.class) + ";");
		runMv.visitInsn(Opcodes.ARETURN);

		runMv.visitMaxs(0, 0);
		runMv.visitEnd();

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

	private static class CompileContext {
		final MethodVisitor         mv;
		final String                className;
		final Node                  rootNode;
		final Map<String, LocalVar> locals = new LinkedHashMap<>();
		int nextLocalSlot = 2; // Slot 0 is 'this', Slot 1 is 'cx' (JSContext)

		final Deque<Label> breakTargets    = new ArrayDeque<>();
		final Deque<Label> continueTargets = new ArrayDeque<>();

		CompileContext(MethodVisitor mv, String className, Node rootNode) {
			this.mv = mv;
			this.className = className;
			this.rootNode = rootNode;
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
				return VarType.INT;
			}
		}
		if (node instanceof Node.IdentifierExpr ident) {
			LocalVar var = ctx != null ? ctx.getLocal(ident.name) : null;
			if (var != null) return var.type;
		}
		if (node instanceof Node.BinaryExpr bin) {
			TokenType op = bin.op;
			VarType left = inferVarType(bin.left, ctx);
			VarType right = inferVarType(bin.right, ctx);
			if (left == VarType.INT && right == VarType.INT) {
				if (op == TokenType.SLASH) return VarType.DOUBLE;
				return VarType.INT;
			}
			if (op == TokenType.PERCENT) {
				if (left == VarType.INT && right == VarType.INT) return VarType.INT;
				return VarType.LONG;
			}
			if (left == VarType.LONG && right == VarType.LONG && op != TokenType.SLASH) {
				return VarType.LONG;
			}
			if ((left.isPrimitive() || right.isPrimitive())
				&& (op == TokenType.STAR || op == TokenType.SLASH || op == TokenType.MINUS || op == TokenType.PLUS)) {
				return VarType.DOUBLE;
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
		return VarType.OBJECT;
	}

	private static VarType preInferVarType(Node.VarDecl varDecl, CompileContext ctx) {
		if (varDecl.init != null) {
			return inferVarType(varDecl.init, ctx);
		}
		if (ctx != null && ctx.rootNode != null) {
			VarType assigned = findAssignedType(varDecl.name, ctx.rootNode, ctx);
			if (assigned != null && assigned != VarType.OBJECT) {
				return assigned;
			}
		}
		return VarType.INT;
	}

	private static VarType findAssignedType(String name, Node node, CompileContext ctx) {
		if (node == null) return null;
		if (node instanceof Node.Program prog) {
			for (Node s : prog.body) {
				VarType t = findAssignedType(name, s, ctx);
				if (t != null) return t;
			}
		} else if (node instanceof Node.BlockStmt block) {
			for (Node s : block.statements) {
				VarType t = findAssignedType(name, s, ctx);
				if (t != null) return t;
			}
		} else if (node instanceof Node.IfStmt ifStmt) {
			VarType t = findAssignedType(name, ifStmt.thenBranch, ctx);
			if (t != null) return t;
			if (ifStmt.elseBranch != null) return findAssignedType(name, ifStmt.elseBranch, ctx);
		} else if (node instanceof Node.WhileStmt whileStmt) {
			return findAssignedType(name, whileStmt.body, ctx);
		} else if (node instanceof Node.ForStmt forStmt) {
			if (forStmt.init != null) {
				VarType t = findAssignedType(name, forStmt.init, ctx);
				if (t != null) return t;
			}
			return findAssignedType(name, forStmt.body, ctx);
		} else if (node instanceof Node.ExprStmt exprStmt) {
			return findAssignedType(name, exprStmt.expr, ctx);
		} else if (node instanceof Node.AssignExpr assign) {
			if (assign.target instanceof Node.IdentifierExpr ident && ident.name.equals(name)) {
				return inferVarType(assign.value, ctx);
			}
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
			VarType  type = varDecl.init != null ? inferVarType(varDecl.init, ctx) : preInferVarType(varDecl, ctx);
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
				if (var != null) {
					boolean isCompound = assign.op == TokenType.PLUS_ASSIGN || assign.op == TokenType.MINUS_ASSIGN
					                      || assign.op == TokenType.STAR_ASSIGN || assign.op == TokenType.SLASH_ASSIGN;

					if (var.isInt()) {
						if (assign.op == TokenType.ASSIGN) {
							compileNodeAsInt(assign.value, ctx);
							if (needResult) {
								mv.visitInsn(Opcodes.DUP);
								mv.visitInsn(Opcodes.I2D);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
								mv.visitVarInsn(Opcodes.ISTORE, var.slot);
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
								mv.visitInsn(Opcodes.I2D);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
								mv.visitVarInsn(Opcodes.ISTORE, var.slot);
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
								mv.visitInsn(Opcodes.L2D);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
								mv.visitVarInsn(Opcodes.LSTORE, var.slot);
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
								mv.visitInsn(Opcodes.L2D);
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
								mv.visitVarInsn(Opcodes.LSTORE, var.slot);
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
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
								mv.visitVarInsn(Opcodes.DSTORE, var.slot);
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
								mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
								mv.visitVarInsn(Opcodes.DSTORE, var.slot);
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
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "toDouble", "(Ljava/lang/Object;)D", false);
						compileNodeAsDouble(assign.value, ctx);
						switch (assign.op) {
							case PLUS_ASSIGN -> mv.visitInsn(Opcodes.DADD);
							case MINUS_ASSIGN -> mv.visitInsn(Opcodes.DSUB);
							case STAR_ASSIGN -> mv.visitInsn(Opcodes.DMUL);
							case SLASH_ASSIGN -> mv.visitInsn(Opcodes.DDIV);
						}
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
					} else {
						mv.visitVarInsn(Opcodes.ALOAD, var.slot);
						compileNode(assign.value, ctx, true);
						mv.visitInvokeDynamicInsn("op", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", BSM_BINARY_OP, "+");
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
						String opStr = assign.op == TokenType.PLUS_ASSIGN ? "+" :
						 (assign.op == TokenType.MINUS_ASSIGN ? "-" :
							(assign.op == TokenType.STAR_ASSIGN ? "*" : "/"));
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
				mv.visitInvokeDynamicInsn("setProp", "(Ljava/lang/Object;Ljava/lang/Object;)V", BSM_SET_PROP, member.property);
				if (needResult) {
					mv.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(JSUndefined.class), "INSTANCE", "L" + Type.getInternalName(JSUndefined.class) + ";");
				}
				return;
			}

			if (assign.target instanceof Node.IndexAccessExpr idxAccess) {
				compileNode(idxAccess.target, ctx, true);
				compileNode(idxAccess.index, ctx, true);
				compileNode(assign.value, ctx, true);
				mv.visitInvokeDynamicInsn("setIndex", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", BSM_SET_INDEX);
				if (needResult) {
					mv.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(JSUndefined.class), "INSTANCE", "L" + Type.getInternalName(JSUndefined.class) + ";");
				}
				return;
			}
		}

		if (node instanceof Node.MemberAccessExpr member) {
			compileNode(member.target, ctx, true);
			mv.visitInvokeDynamicInsn("getProp", "(Ljava/lang/Object;)Ljava/lang/Object;", BSM_GET_PROP, member.property);
			if (!needResult) mv.visitInsn(Opcodes.POP);
			return;
		}

		if (node instanceof Node.IndexAccessExpr idxAccess) {
			compileNode(idxAccess.target, ctx, true);
			compileNode(idxAccess.index, ctx, true);
			mv.visitInvokeDynamicInsn("getIndex", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", BSM_GET_INDEX);
			if (!needResult) mv.visitInsn(Opcodes.POP);
			return;
		}

		if (node instanceof Node.BinaryExpr bin) {
			VarType leftType  = inferVarType(bin.left, ctx);
			VarType rightType = inferVarType(bin.right, ctx);

			if (leftType.isPrimitive() && rightType.isPrimitive()
			    && (bin.op == TokenType.STAR || bin.op == TokenType.SLASH || bin.op == TokenType.PERCENT
			        || bin.op == TokenType.MINUS || bin.op == TokenType.PLUS)) {
				if (!needResult) {
					compileNode(bin.left, ctx, false);
					compileNode(bin.right, ctx, false);
					return;
				}
				if (leftType == VarType.INT && rightType == VarType.INT && bin.op != TokenType.SLASH) {
					compileNodeAsInt(bin, ctx);
					mv.visitInsn(Opcodes.I2D);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
					return;
				}
				if (leftType == VarType.LONG && rightType == VarType.LONG && bin.op != TokenType.SLASH) {
					compileNodeAsLong(bin, ctx);
					mv.visitInsn(Opcodes.L2D);
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
					return;
				}
				compileNodeAsDouble(bin, ctx);
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
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
				case EQ -> "==";
				case EQ_EQ -> "===";
				case NOT_EQ -> "!=";
				case NOT_EQ_EQ -> "!==";
				case LT -> "<";
				case LTE -> "<=";
				case GT -> ">";
				case GTE -> ">=";
				case AND -> "&&";
				case OR -> "||";
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
			}
		}

		if (node instanceof Node.CallExpr call) {
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
				mv.visitInsn(Opcodes.DUP);
				mv.visitLdcInsn(entry.key());
				compileNode(entry.value(), ctx, true);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(JSObject.class), "put", "(Ljava/lang/String;Ljava/lang/Object;)V", false);
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
			compileNode(member.target, ctx, true);
			mv.visitInvokeDynamicInsn("getPropInt", "(Ljava/lang/Object;)I", BSM_GET_PROP_INT, member.property);
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
			compileNode(member.target, ctx, true);
			mv.visitInvokeDynamicInsn("getPropLong", "(Ljava/lang/Object;)J", BSM_GET_PROP_LONG, member.property);
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
			compileNode(member.target, ctx, true);
			mv.visitInvokeDynamicInsn("getPropDouble", "(Ljava/lang/Object;)D", BSM_GET_PROP_DOUBLE, member.property);
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
				compileNodeAsLong(bin.left, ctx);
				compileNodeAsLong(bin.right, ctx);
				mv.visitInsn(Opcodes.LREM);
				mv.visitInsn(Opcodes.L2D);
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

				// 特化 3: DOUBLE 比较
				compileNodeAsDouble(bin.left, ctx);
				compileNodeAsDouble(bin.right, ctx);
				mv.visitInsn(Opcodes.DCMPG);
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
		}

		compileNode(condition, ctx, true);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JSOps.class), "isTruthy", "(Ljava/lang/Object;)Z", false);
		mv.visitJumpInsn(jumpOnTrue ? Opcodes.IFNE : Opcodes.IFEQ, targetLabel);
	}
}
