package hope.magic.js.compiler;

import hope.magic.js.ast.*;
import hope.magic.js.runtime.JSOps;

import java.util.*;

public class ConstantFolder {

	public static Node.Program fold(Node.Program program) {
		if (program == null) return null;
		List<Node> newBody = new ArrayList<>();
		for (Node stmt : program.body) {
			flattenNode(foldNode(stmt), newBody);
		}
		return new Node.Program(newBody, program.line, program.column);
	}

	private static void flattenNode(Node node, List<Node> list) {
		if (node == null) return;
		if (node instanceof Node.BlockStmt block) {
			for (Node s : block.statements) {
				flattenNode(s, list);
			}
		} else {
			list.add(node);
		}
	}

	private static Node unwrapBlock(Node node) {
		if (node instanceof Node.BlockStmt block) {
			if (block.statements.size() == 1) {
				return unwrapBlock(block.statements.get(0));
			}
		}
		return node;
	}

	public static Node foldNode(Node node) {
		if (node == null) return null;

		if (node instanceof Node.ExprStmt exprStmt) {
			Node foldedExpr = foldNode(exprStmt.expr);
			return new Node.ExprStmt(foldedExpr, exprStmt.line, exprStmt.column);
		}

		if (node instanceof Node.VarDecl varDecl) {
			Node foldedInit = varDecl.init != null ? foldNode(varDecl.init) : null;
			return new Node.VarDecl(varDecl.name, foldedInit, varDecl.line, varDecl.column);
		}

		if (node instanceof Node.BlockStmt blockStmt) {
			List<Node> newStmts = new ArrayList<>();
			for (Node s : blockStmt.statements) {
				Node folded = foldNode(s);
				if (folded != null) {
					if (folded instanceof Node.BlockStmt b && b.statements.isEmpty()) {
						continue;
					}
					newStmts.add(folded);
				}
			}
			return new Node.BlockStmt(newStmts, blockStmt.line, blockStmt.column);
		}

		if (node instanceof Node.IfStmt ifStmt) {
			Node foldedCond = foldNode(ifStmt.condition);
			Node foldedThen = foldNode(ifStmt.thenBranch);
			Node foldedElse = ifStmt.elseBranch != null ? foldNode(ifStmt.elseBranch) : null;

			if (foldedCond instanceof Node.LiteralExpr lit) {
				boolean truthy = JSOps.isTruthy(lit.value);
				if (truthy) {
					return unwrapBlock(foldedThen);
				} else {
					return unwrapBlock(foldedElse != null ? foldedElse : new Node.BlockStmt(Collections.emptyList(), ifStmt.line, ifStmt.column));
				}
			}
			return new Node.IfStmt(foldedCond, foldedThen, foldedElse, ifStmt.line, ifStmt.column);
		}

		if (node instanceof Node.WhileStmt whileStmt) {
			Node foldedCond = foldNode(whileStmt.condition);
			if (foldedCond instanceof Node.LiteralExpr lit && !JSOps.isTruthy(lit.value)) {
				return new Node.BlockStmt(Collections.emptyList(), whileStmt.line, whileStmt.column);
			}
			Node foldedBody = foldNode(whileStmt.body);
			return new Node.WhileStmt(foldedCond, foldedBody, whileStmt.line, whileStmt.column);
		}

		if (node instanceof Node.ForStmt forStmt) {
			Node foldedInit = forStmt.init != null ? foldNode(forStmt.init) : null;
			Node foldedCond = forStmt.condition != null ? foldNode(forStmt.condition) : null;
			Node foldedUpdate = forStmt.update != null ? foldNode(forStmt.update) : null;
			Node foldedBody = foldNode(forStmt.body);

			if (foldedCond instanceof Node.LiteralExpr lit && !JSOps.isTruthy(lit.value)) {
				return foldedInit != null ? new Node.ExprStmt(foldedInit, forStmt.line, forStmt.column)
					: new Node.BlockStmt(Collections.emptyList(), forStmt.line, forStmt.column);
			}
			return new Node.ForStmt(foldedInit, foldedCond, foldedUpdate, foldedBody, forStmt.line, forStmt.column);
		}

		if (node instanceof Node.ForOfStmt forOf) {
			Node foldedIterable = foldNode(forOf.iterable);
			Node foldedBody = foldNode(forOf.body);
			return new Node.ForOfStmt(forOf.varName, forOf.isDeclaration, foldedIterable, foldedBody, forOf.line, forOf.column);
		}

		if (node instanceof Node.ForInStmt forIn) {
			Node foldedObject = foldNode(forIn.object);
			Node foldedBody = foldNode(forIn.body);
			return new Node.ForInStmt(forIn.varName, forIn.isDeclaration, foldedObject, foldedBody, forIn.line, forIn.column);
		}

		if (node instanceof Node.DoWhileStmt doWhile) {
			Node foldedBody = foldNode(doWhile.body);
			Node foldedCond = foldNode(doWhile.condition);
			return new Node.DoWhileStmt(foldedBody, foldedCond, doWhile.line, doWhile.column);
		}

		if (node instanceof Node.ThrowStmt throwStmt) {
			return new Node.ThrowStmt(foldNode(throwStmt.expr), throwStmt.line, throwStmt.column);
		}

		if (node instanceof Node.TryStmt tryStmt) {
			Node.BlockStmt tryB = (Node.BlockStmt) foldNode(tryStmt.tryBlock);
			Node.BlockStmt catchB = tryStmt.catchBlock != null ? (Node.BlockStmt) foldNode(tryStmt.catchBlock) : null;
			Node.BlockStmt finB = tryStmt.finallyBlock != null ? (Node.BlockStmt) foldNode(tryStmt.finallyBlock) : null;
			return new Node.TryStmt(tryB, tryStmt.catchParam, catchB, finB, tryStmt.line, tryStmt.column);
		}

		if (node instanceof Node.SwitchStmt switchStmt) {
			Node foldedDisc = foldNode(switchStmt.discriminant);
			List<Node.CaseClause> newCases = new ArrayList<>();
			for (Node.CaseClause c : switchStmt.cases) {
				Node newTest = c.test != null ? foldNode(c.test) : null;
				List<Node> newStmts = new ArrayList<>();
				for (Node s : c.consequent) {
					Node fs = foldNode(s);
					if (fs != null) newStmts.add(fs);
				}
				newCases.add(new Node.CaseClause(newTest, newStmts, c.line, c.column));
			}
			return new Node.SwitchStmt(foldedDisc, newCases, switchStmt.line, switchStmt.column);
		}

		if (node instanceof Node.TypeOfExpr typeOf) {
			Node foldedInner = foldNode(typeOf.expr);
			if (foldedInner instanceof Node.LiteralExpr lit) {
				return new Node.LiteralExpr(JSOps.typeOf(lit.value), typeOf.line, typeOf.column);
			}
			return new Node.TypeOfExpr(foldedInner, typeOf.line, typeOf.column);
		}

		if (node instanceof Node.VoidExpr voidExpr) {
			return new Node.VoidExpr(foldNode(voidExpr.expr), voidExpr.line, voidExpr.column);
		}

		if (node instanceof Node.AssignExpr assign) {
			Node foldedVal = foldNode(assign.value);
			return new Node.AssignExpr(assign.target, assign.op, foldedVal, assign.line, assign.column);
		}

		if (node instanceof Node.UnaryExpr un) {
			Node inner = foldNode(un.expr);
			if (inner instanceof Node.LiteralExpr lit) {
				Object val = lit.value;
				if (un.op == TokenType.MINUS) {
					if (val instanceof Integer i) {
						return new Node.LiteralExpr(-i, un.line, un.column);
					}
					if (val instanceof Long l) {
						return new Node.LiteralExpr(-l, un.line, un.column);
					}
					if (val instanceof Number num) {
						return new Node.LiteralExpr(-num.doubleValue(), un.line, un.column);
					}
				} else if (un.op == TokenType.NOT) {
					boolean b = !JSOps.isTruthy(val);
					return new Node.LiteralExpr(b, un.line, un.column);
				}
			}
			return new Node.UnaryExpr(un.op, inner, un.isPrefix, un.line, un.column);
		}

		if (node instanceof Node.BinaryExpr bin) {
			Node left = foldNode(bin.left);
			Node right = foldNode(bin.right);

			// 左右均为常量：直接计算并返回 LiteralExpr
			if (left instanceof Node.LiteralExpr lLit && right instanceof Node.LiteralExpr rLit) {
				Node folded = foldBinaryLiterals(lLit.value, bin.op, rLit.value, bin.line, bin.column);
				if (folded != null) return folded;
			}

			// 代数恒等式与逻辑短路简化 (Algebraic & Logical Short-circuit Simplification)
			if (bin.op == TokenType.AND) {
				if (left instanceof Node.LiteralExpr lLit) {
					if (!JSOps.isTruthy(lLit.value)) return left;
					return right;
				}
			} else if (bin.op == TokenType.OR) {
				if (left instanceof Node.LiteralExpr lLit) {
					if (JSOps.isTruthy(lLit.value)) return left;
					return right;
				}
			} else if (bin.op == TokenType.PLUS) {
				if (isLiteralZero(right) && isGuaranteedNumeric(left)) return left;
				if (isLiteralZero(left) && isGuaranteedNumeric(right)) return right;
			} else if (bin.op == TokenType.MINUS) {
				if (isLiteralZero(right) && isGuaranteedNumeric(left)) return left;
			} else if (bin.op == TokenType.STAR) {
				if (isLiteralOne(right) && isGuaranteedNumeric(left)) return left;
				if (isLiteralOne(left) && isGuaranteedNumeric(right)) return right;
			} else if (bin.op == TokenType.SLASH) {
				if (isLiteralOne(right) && isGuaranteedNumeric(left)) return left;
			}

			return new Node.BinaryExpr(left, bin.op, right, bin.line, bin.column);
		}

		if (node instanceof Node.TernaryExpr ternary) {
			Node foldedCond = foldNode(ternary.condition);
			Node foldedThen = foldNode(ternary.thenExpr);
			Node foldedElse = foldNode(ternary.elseExpr);
			if (foldedCond instanceof Node.LiteralExpr lit) {
				if (JSOps.isTruthy(lit.value)) {
					return foldedThen;
				} else {
					return foldedElse;
				}
			}
			return new Node.TernaryExpr(foldedCond, foldedThen, foldedElse, ternary.line, ternary.column);
		}

		if (node instanceof Node.ReturnStmt ret) {
			Node val = ret.value != null ? foldNode(ret.value) : null;
			return new Node.ReturnStmt(val, ret.line, ret.column);
		}

		if (node instanceof Node.CallExpr call) {
			Node callee = foldNode(call.callee);
			List<Node> args = new ArrayList<>();
			for (Node a : call.arguments) {
				args.add(foldNode(a));
			}
			return new Node.CallExpr(callee, args, call.line, call.column);
		}

		if (node instanceof Node.NewExpr newExpr) {
			Node ctor = foldNode(newExpr.constructor);
			List<Node> args = new ArrayList<>();
			for (Node a : newExpr.arguments) {
				args.add(foldNode(a));
			}
			return new Node.NewExpr(ctor, args, newExpr.line, newExpr.column);
		}

		if (node instanceof Node.MemberAccessExpr member) {
			Node target = foldNode(member.target);
			return new Node.MemberAccessExpr(target, member.property, member.line, member.column);
		}

		if (node instanceof Node.IndexAccessExpr idx) {
			Node target = foldNode(idx.target);
			Node index = foldNode(idx.index);
			return new Node.IndexAccessExpr(target, index, idx.line, idx.column);
		}

		if (node instanceof Node.ObjectLiteralExpr objLit) {
			List<Node.ObjectLiteralExpr.Entry> newEntries = new ArrayList<>();
			for (Node.ObjectLiteralExpr.Entry e : objLit.entries) {
				newEntries.add(new Node.ObjectLiteralExpr.Entry(e.key(), foldNode(e.value())));
			}
			return new Node.ObjectLiteralExpr(newEntries, objLit.line, objLit.column);
		}

		if (node instanceof Node.ArrayLiteralExpr arrLit) {
			List<Node> newElems = new ArrayList<>();
			for (Node e : arrLit.elements) {
				newElems.add(foldNode(e));
			}
			return new Node.ArrayLiteralExpr(newElems, arrLit.line, arrLit.column);
		}

		return node;
	}

	private static Node foldBinaryLiterals(Object lVal, TokenType op, Object rVal, int line, int column) {
		// 1. 字符串拼接
		if (op == TokenType.PLUS && (lVal instanceof String || rVal instanceof String)) {
			return new Node.LiteralExpr(JSOps.toStr(lVal) + JSOps.toStr(rVal), line, column);
		}

		// 2. 数值运算
		if (lVal instanceof Number lNum && rVal instanceof Number rNum) {
			boolean isIntOp = (lVal instanceof Integer || lVal instanceof Short || lVal instanceof Byte)
				&& (rVal instanceof Integer || rVal instanceof Short || rVal instanceof Byte);

			if (isIntOp && op != TokenType.SLASH) {
				int l = lNum.intValue();
				int r = rNum.intValue();
				return switch (op) {
					case PLUS -> {
						long res = (long) l + (long) r;
						if (res >= Integer.MIN_VALUE && res <= Integer.MAX_VALUE) yield new Node.LiteralExpr((int) res, line, column);
						yield createNumberLiteral((double) res, line, column);
					}
					case MINUS -> {
						long res = (long) l - (long) r;
						if (res >= Integer.MIN_VALUE && res <= Integer.MAX_VALUE) yield new Node.LiteralExpr((int) res, line, column);
						yield createNumberLiteral((double) res, line, column);
					}
					case STAR -> {
						long res = (long) l * (long) r;
						if (res >= Integer.MIN_VALUE && res <= Integer.MAX_VALUE) yield new Node.LiteralExpr((int) res, line, column);
						yield createNumberLiteral((double) res, line, column);
					}
					case PERCENT -> r != 0 ? new Node.LiteralExpr(l % r, line, column) : null;
					case LT -> new Node.LiteralExpr(l < r, line, column);
					case LTE -> new Node.LiteralExpr(l <= r, line, column);
					case GT -> new Node.LiteralExpr(l > r, line, column);
					case GTE -> new Node.LiteralExpr(l >= r, line, column);
					case EQ, EQ_EQ -> new Node.LiteralExpr(l == r, line, column);
					case NOT_EQ, NOT_EQ_EQ -> new Node.LiteralExpr(l != r, line, column);
					default -> null;
				};
			}

			double ld = lNum.doubleValue();
			double rd = rNum.doubleValue();
			return switch (op) {
				case PLUS -> createNumberLiteral(ld + rd, line, column);
				case MINUS -> createNumberLiteral(ld - rd, line, column);
				case STAR -> createNumberLiteral(ld * rd, line, column);
				case SLASH -> rd != 0.0 ? createNumberLiteral(ld / rd, line, column) : null;
				case PERCENT -> rd != 0.0 ? createNumberLiteral(ld % rd, line, column) : null;
				case LT -> new Node.LiteralExpr(ld < rd, line, column);
				case LTE -> new Node.LiteralExpr(ld <= rd, line, column);
				case GT -> new Node.LiteralExpr(ld > rd, line, column);
				case GTE -> new Node.LiteralExpr(ld >= rd, line, column);
				case EQ, EQ_EQ -> new Node.LiteralExpr(ld == rd, line, column);
				case NOT_EQ, NOT_EQ_EQ -> new Node.LiteralExpr(ld != rd, line, column);
				default -> null;
			};
		}

		// 3. 逻辑运算符与布尔比较
		if (op == TokenType.AND) {
			return new Node.LiteralExpr(JSOps.isTruthy(lVal) ? rVal : lVal, line, column);
		}
		if (op == TokenType.OR) {
			return new Node.LiteralExpr(JSOps.isTruthy(lVal) ? lVal : rVal, line, column);
		}

		if (lVal instanceof Boolean lB && rVal instanceof Boolean rB) {
			if (op == TokenType.EQ || op == TokenType.EQ_EQ) {
				return new Node.LiteralExpr(lB.equals(rB), line, column);
			}
			if (op == TokenType.NOT_EQ || op == TokenType.NOT_EQ_EQ) {
				return new Node.LiteralExpr(!lB.equals(rB), line, column);
			}
		}

		if (op == TokenType.EQ_EQ || op == TokenType.NOT_EQ_EQ || op == TokenType.EQ || op == TokenType.NOT_EQ) {
			if (lVal == null || lVal instanceof hope.magic.js.runtime.JSUndefined || rVal == null || rVal instanceof hope.magic.js.runtime.JSUndefined) {
				Object eqRes = (op == TokenType.EQ_EQ || op == TokenType.NOT_EQ_EQ) ? JSOps.strictEq(lVal, rVal) : JSOps.eq(lVal, rVal);
				boolean res = (Boolean) eqRes;
				if (op == TokenType.NOT_EQ || op == TokenType.NOT_EQ_EQ) res = !res;
				return new Node.LiteralExpr(res, line, column);
			}
		}

		return null;
	}

	private static Node.LiteralExpr createNumberLiteral(double val, int line, int column) {
		if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE && val == Math.floor(val) && !Double.isInfinite(val)) {
			return new Node.LiteralExpr((int) val, line, column);
		}
		if (val == Math.floor(val) && !Double.isInfinite(val)) {
			return new Node.LiteralExpr((long) val, line, column);
		}
		return new Node.LiteralExpr(val, line, column);
	}

	private static boolean isLiteralZero(Node node) {
		if (node instanceof Node.LiteralExpr lit) {
			if (lit.value instanceof Number num) {
				return num.doubleValue() == 0.0;
			}
		}
		return false;
	}

	private static boolean isLiteralOne(Node node) {
		if (node instanceof Node.LiteralExpr lit) {
			if (lit.value instanceof Number num) {
				return num.doubleValue() == 1.0;
			}
		}
		return false;
	}

	private static boolean isGuaranteedNumeric(Node node) {
		if (node instanceof Node.LiteralExpr lit && lit.value instanceof Number) return true;
		if (node instanceof Node.BinaryExpr bin) {
			if (bin.op == TokenType.MINUS || bin.op == TokenType.STAR || bin.op == TokenType.SLASH || bin.op == TokenType.PERCENT) {
				return true;
			}
			if (bin.op == TokenType.PLUS) {
				return isGuaranteedNumeric(bin.left) && isGuaranteedNumeric(bin.right);
			}
		}
		if (node instanceof Node.UnaryExpr un && (un.op == TokenType.MINUS || un.op == TokenType.PLUS_PLUS || un.op == TokenType.MINUS_MINUS)) {
			return true;
		}
		return false;
	}
}
