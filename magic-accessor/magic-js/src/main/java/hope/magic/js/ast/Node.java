package hope.magic.js.ast;

import java.util.List;

public abstract class Node {
	public final int line;
	public final int column;

	protected Node(int line, int column) {
		this.line = line;
		this.column = column;
	}

	public abstract <R, C> R accept(ASTVisitor<R, C> visitor, C context);

	public interface ASTVisitor<R, C> {
		R visitProgram(Program node, C context);
		R visitVarDecl(VarDecl node, C context);
		R visitBlockStmt(BlockStmt node, C context);
		R visitIfStmt(IfStmt node, C context);
		R visitWhileStmt(WhileStmt node, C context);
		R visitForStmt(ForStmt node, C context);
		R visitForOfStmt(ForOfStmt node, C context);
		R visitForInStmt(ForInStmt node, C context);
		R visitDoWhileStmt(DoWhileStmt node, C context);
		R visitThrowStmt(ThrowStmt node, C context);
		R visitTryStmt(TryStmt node, C context);
		R visitSwitchStmt(SwitchStmt node, C context);
		R visitReturnStmt(ReturnStmt node, C context);
		R visitBreakStmt(BreakStmt node, C context);
		R visitContinueStmt(ContinueStmt node, C context);
		R visitExprStmt(ExprStmt node, C context);
		R visitFunctionDecl(FunctionDecl node, C context);

		R visitAssignExpr(AssignExpr node, C context);
		R visitBinaryExpr(BinaryExpr node, C context);
		R visitUnaryExpr(UnaryExpr node, C context);
		R visitTypeOfExpr(TypeOfExpr node, C context);
		R visitVoidExpr(VoidExpr node, C context);
		R visitLiteralExpr(LiteralExpr node, C context);
		R visitIdentifierExpr(IdentifierExpr node, C context);
		R visitMemberAccessExpr(MemberAccessExpr node, C context);
		R visitIndexAccessExpr(IndexAccessExpr node, C context);
		R visitCallExpr(CallExpr node, C context);
		R visitNewExpr(NewExpr node, C context);
		R visitObjectLiteralExpr(ObjectLiteralExpr node, C context);
		R visitArrayLiteralExpr(ArrayLiteralExpr node, C context);
		R visitRegExpLiteral(RegExpLiteral node, C context);
		R visitFunctionExpr(FunctionExpr node, C context);
		R visitTernaryExpr(TernaryExpr node, C context);
		R visitClassDecl(ClassDecl node, C context);
		R visitSuperExpr(SuperExpr node, C context);
	}

	//region 语句 Statements

	public static class Program extends Node {
		public final List<Node> body;

		public Program(List<Node> body, int line, int column) {
			super(line, column);
			this.body = body;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitProgram(this, context);
		}
	}

	public static class VarDecl extends Node {
		public final String name;
		public final Node init; // can be null

		public VarDecl(String name, Node init, int line, int column) {
			super(line, column);
			this.name = name;
			this.init = init;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitVarDecl(this, context);
		}
	}

	public static class BlockStmt extends Node {
		public final List<Node> statements;

		public BlockStmt(List<Node> statements, int line, int column) {
			super(line, column);
			this.statements = statements;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitBlockStmt(this, context);
		}
	}

	public static class IfStmt extends Node {
		public final Node condition;
		public final Node thenBranch;
		public final Node elseBranch; // can be null

		public IfStmt(Node condition, Node thenBranch, Node elseBranch, int line, int column) {
			super(line, column);
			this.condition = condition;
			this.thenBranch = thenBranch;
			this.elseBranch = elseBranch;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitIfStmt(this, context);
		}
	}

	public static class WhileStmt extends Node {
		public final Node condition;
		public final Node body;

		public WhileStmt(Node condition, Node body, int line, int column) {
			super(line, column);
			this.condition = condition;
			this.body = body;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitWhileStmt(this, context);
		}
	}

	public static class ForStmt extends Node {
		public final Node init;      // VarDeclStmt, Expression, or null
		public final Node condition; // Expression or null
		public final Node update;    // Expression or null
		public final Node body;

		public ForStmt(Node init, Node condition, Node update, Node body, int line, int column) {
			super(line, column);
			this.init = init;
			this.condition = condition;
			this.update = update;
			this.body = body;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitForStmt(this, context);
		}
	}

	public static class ForOfStmt extends Node {
		public final String varName;
		public final boolean isDeclaration;
		public final Node iterable;
		public final Node body;

		public ForOfStmt(String varName, boolean isDeclaration, Node iterable, Node body, int line, int column) {
			super(line, column);
			this.varName = varName;
			this.isDeclaration = isDeclaration;
			this.iterable = iterable;
			this.body = body;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitForOfStmt(this, context);
		}
	}

	public static class ForInStmt extends Node {
		public final String varName;
		public final boolean isDeclaration;
		public final Node object;
		public final Node body;

		public ForInStmt(String varName, boolean isDeclaration, Node object, Node body, int line, int column) {
			super(line, column);
			this.varName = varName;
			this.isDeclaration = isDeclaration;
			this.object = object;
			this.body = body;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitForInStmt(this, context);
		}
	}

	public static class DoWhileStmt extends Node {
		public final Node body;
		public final Node condition;

		public DoWhileStmt(Node body, Node condition, int line, int column) {
			super(line, column);
			this.body = body;
			this.condition = condition;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitDoWhileStmt(this, context);
		}
	}

	public static class ThrowStmt extends Node {
		public final Node expr;

		public ThrowStmt(Node expr, int line, int column) {
			super(line, column);
			this.expr = expr;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitThrowStmt(this, context);
		}
	}

	public static class TryStmt extends Node {
		public final BlockStmt tryBlock;
		public final String catchParam; // can be null
		public final BlockStmt catchBlock; // can be null
		public final BlockStmt finallyBlock; // can be null

		public TryStmt(BlockStmt tryBlock, String catchParam, BlockStmt catchBlock, BlockStmt finallyBlock, int line, int column) {
			super(line, column);
			this.tryBlock = tryBlock;
			this.catchParam = catchParam;
			this.catchBlock = catchBlock;
			this.finallyBlock = finallyBlock;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitTryStmt(this, context);
		}
	}

	public static class CaseClause {
		public final Node test; // null for default clause
		public final List<Node> consequent;
		public final int line;
		public final int column;

		public CaseClause(Node test, List<Node> consequent, int line, int column) {
			this.test = test;
			this.consequent = consequent;
			this.line = line;
			this.column = column;
		}
	}

	public static class SwitchStmt extends Node {
		public final Node discriminant;
		public final List<CaseClause> cases;

		public SwitchStmt(Node discriminant, List<CaseClause> cases, int line, int column) {
			super(line, column);
			this.discriminant = discriminant;
			this.cases = cases;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitSwitchStmt(this, context);
		}
	}

	public static class ReturnStmt extends Node {
		public final Node value; // can be null

		public ReturnStmt(Node value, int line, int column) {
			super(line, column);
			this.value = value;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitReturnStmt(this, context);
		}
	}

	public static class BreakStmt extends Node {
		public BreakStmt(int line, int column) {
			super(line, column);
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitBreakStmt(this, context);
		}
	}

	public static class ContinueStmt extends Node {
		public ContinueStmt(int line, int column) {
			super(line, column);
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitContinueStmt(this, context);
		}
	}

	public static class ExprStmt extends Node {
		public final Node expr;

		public ExprStmt(Node expr, int line, int column) {
			super(line, column);
			this.expr = expr;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitExprStmt(this, context);
		}
	}

	public static class FunctionDecl extends Node {
		public final String name;
		public final List<String> params;
		public final BlockStmt body;

		public FunctionDecl(String name, List<String> params, BlockStmt body, int line, int column) {
			super(line, column);
			this.name = name;
			this.params = params;
			this.body = body;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitFunctionDecl(this, context);
		}
	}
	//endregion

	//region 表达式 Expressions

	public static class AssignExpr extends Node {
		public final Node target;
		public final TokenType op; // ASSIGN, PLUS_ASSIGN, ...
		public final Node value;

		public AssignExpr(Node target, TokenType op, Node value, int line, int column) {
			super(line, column);
			this.target = target;
			this.op = op;
			this.value = value;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitAssignExpr(this, context);
		}
	}

	public static class BinaryExpr extends Node {
		public final Node left;
		public final TokenType op;
		public final Node right;

		public BinaryExpr(Node left, TokenType op, Node right, int line, int column) {
			super(line, column);
			this.left = left;
			this.op = op;
			this.right = right;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitBinaryExpr(this, context);
		}
	}

	public static class UnaryExpr extends Node {
		public final TokenType op;
		public final Node expr;
		public final boolean isPrefix;

		public UnaryExpr(TokenType op, Node expr, boolean isPrefix, int line, int column) {
			super(line, column);
			this.op = op;
			this.expr = expr;
			this.isPrefix = isPrefix;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitUnaryExpr(this, context);
		}
	}

	public static class TypeOfExpr extends Node {
		public final Node expr;

		public TypeOfExpr(Node expr, int line, int column) {
			super(line, column);
			this.expr = expr;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitTypeOfExpr(this, context);
		}
	}

	public static class VoidExpr extends Node {
		public final Node expr;

		public VoidExpr(Node expr, int line, int column) {
			super(line, column);
			this.expr = expr;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitVoidExpr(this, context);
		}
	}

	public static class LiteralExpr extends Node {
		public final Object value; // Double, String, Boolean, null, etc.

		public LiteralExpr(Object value, int line, int column) {
			super(line, column);
			this.value = value;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitLiteralExpr(this, context);
		}
	}

	public static class IdentifierExpr extends Node {
		public final String name;

		public IdentifierExpr(String name, int line, int column) {
			super(line, column);
			this.name = name;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitIdentifierExpr(this, context);
		}
	}

	public static class MemberAccessExpr extends Node {
		public final Node target;
		public final String property;

		public MemberAccessExpr(Node target, String property, int line, int column) {
			super(line, column);
			this.target = target;
			this.property = property;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitMemberAccessExpr(this, context);
		}
	}

	public static class IndexAccessExpr extends Node {
		public final Node target;
		public final Node index;

		public IndexAccessExpr(Node target, Node index, int line, int column) {
			super(line, column);
			this.target = target;
			this.index = index;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitIndexAccessExpr(this, context);
		}
	}

	public static class CallExpr extends Node {
		public final Node callee;
		public final List<Node> arguments;

		public CallExpr(Node callee, List<Node> arguments, int line, int column) {
			super(line, column);
			this.callee = callee;
			this.arguments = arguments;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitCallExpr(this, context);
		}
	}

	public static class NewExpr extends Node {
		public final Node constructor;
		public final List<Node> arguments;

		public NewExpr(Node constructor, List<Node> arguments, int line, int column) {
			super(line, column);
			this.constructor = constructor;
			this.arguments = arguments;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitNewExpr(this, context);
		}
	}

	public static class ObjectLiteralExpr extends Node {
		public final List<Entry> entries;

		public record Entry(String key, Node value) { }

		public ObjectLiteralExpr(List<Entry> entries, int line, int column) {
			super(line, column);
			this.entries = entries;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitObjectLiteralExpr(this, context);
		}
	}

	public static class ArrayLiteralExpr extends Node {
		public final List<Node> elements;

		public ArrayLiteralExpr(List<Node> elements, int line, int column) {
			super(line, column);
			this.elements = elements;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitArrayLiteralExpr(this, context);
		}
	}

	public static class RegExpLiteral extends Node {
		public final String pattern;
		public final String flags;

		public RegExpLiteral(String pattern, String flags, int line, int column) {
			super(line, column);
			this.pattern = pattern;
			this.flags = flags;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitRegExpLiteral(this, context);
		}
	}

	public static class FunctionExpr extends Node {
		public final String name; // can be null
		public final List<String> params;
		public final BlockStmt body;

		public FunctionExpr(String name, List<String> params, BlockStmt body, int line, int column) {
			super(line, column);
			this.name = name;
			this.params = params;
			this.body = body;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitFunctionExpr(this, context);
		}
	}

	public static class TernaryExpr extends Node {
		public final Node condition;
		public final Node thenExpr;
		public final Node elseExpr;

		public TernaryExpr(Node condition, Node thenExpr, Node elseExpr, int line, int column) {
			super(line, column);
			this.condition = condition;
			this.thenExpr = thenExpr;
			this.elseExpr = elseExpr;
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitTernaryExpr(this, context);
		}
	}

	public static class ClassDecl extends Node {
		public final String name; // 类名（可为 null，如作为匿名表达式）
		public final Node superClass; // extends 的父类表达式（可为 null）
		public final FunctionDecl constructor; // 构造函数（可为 null，默认无参）
		public final List<FunctionDecl> methods; // 实例方法列表
		public final List<FunctionDecl> staticMethods; // 静态方法列表

		public ClassDecl(String name, Node superClass, FunctionDecl constructor,
						 List<FunctionDecl> methods, List<FunctionDecl> staticMethods,
						 int line, int column) {
			super(line, column);
			this.name = name;
			this.superClass = superClass;
			this.constructor = constructor;
			this.methods = methods != null ? methods : List.of();
			this.staticMethods = staticMethods != null ? staticMethods : List.of();
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitClassDecl(this, context);
		}
	}

	public static class SuperExpr extends Node {
		public SuperExpr(int line, int column) {
			super(line, column);
		}

		@Override
		public <R, C> R accept(ASTVisitor<R, C> visitor, C context) {
			return visitor.visitSuperExpr(this, context);
		}
	}
	//endregion
}
