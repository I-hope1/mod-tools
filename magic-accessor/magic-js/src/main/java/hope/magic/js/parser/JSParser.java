package hope.magic.js.parser;

import hope.magic.js.ast.Node;
import hope.magic.js.ast.Token;
import hope.magic.js.ast.TokenType;

import java.util.ArrayList;
import java.util.List;

public class JSParser {
	private final List<Token> tokens;
	private int cursor = 0;

	public JSParser(List<Token> tokens) {
		this.tokens = tokens;
	}

	public Node.Program parse() {
		List<Node> body = new ArrayList<>();
		int line = peek().line;
		int col = peek().column;

		while (!isAtEnd()) {
			Node stmt = parseStatement();
			if (stmt != null) {
				body.add(stmt);
			}
		}

		return new Node.Program(body, line, col);
	}

	// ==================== 语句解析 ====================

	private Node parseStatement() {
		Token t = peek();
		if (t.type == TokenType.VAR || t.type == TokenType.LET || t.type == TokenType.CONST) {
			return parseVarDecl();
		}
		if (t.type == TokenType.FUNCTION) {
			return parseFunctionDecl();
		}
		if (t.type == TokenType.IF) {
			return parseIfStatement();
		}
		if (t.type == TokenType.WHILE) {
			return parseWhileStatement();
		}
		if (t.type == TokenType.FOR) {
			return parseForStatement();
		}
		if (t.type == TokenType.RETURN) {
			return parseReturnStatement();
		}
		if (t.type == TokenType.BREAK) {
			advance();
			match(TokenType.SEMICOLON);
			return new Node.BreakStmt(t.line, t.column);
		}
		if (t.type == TokenType.CONTINUE) {
			advance();
			match(TokenType.SEMICOLON);
			return new Node.ContinueStmt(t.line, t.column);
		}
		if (t.type == TokenType.LBRACE) {
			return parseBlockStatement();
		}
		if (t.type == TokenType.SEMICOLON) {
			advance();
			return null;
		}

		// 表达式语句
		Node expr = parseExpression();
		match(TokenType.SEMICOLON);
		return new Node.ExprStmt(expr, t.line, t.column);
	}

	private Node parseVarDecl() {
		Token kw = advance();
		Token id = consume(TokenType.IDENTIFIER, "Expected identifier after " + kw.text);
		Node init = null;
		if (match(TokenType.ASSIGN)) {
			init = parseExpression();
		}
		match(TokenType.SEMICOLON);
		return new Node.VarDecl(id.text, init, kw.line, kw.column);
	}

	private Node parseFunctionDecl() {
		Token kw = advance();
		Token id = consume(TokenType.IDENTIFIER, "Expected function name");
		consume(TokenType.LPAREN, "Expected '(' after function name");

		List<String> params = new ArrayList<>();
		if (!check(TokenType.RPAREN)) {
			do {
				Token p = consume(TokenType.IDENTIFIER, "Expected parameter name");
				params.add(p.text);
			} while (match(TokenType.COMMA));
		}
		consume(TokenType.RPAREN, "Expected ')' after parameters");

		Node.BlockStmt body = parseBlockStatement();
		return new Node.FunctionDecl(id.text, params, body, kw.line, kw.column);
	}

	private Node parseIfStatement() {
		Token kw = advance();
		consume(TokenType.LPAREN, "Expected '(' after 'if'");
		Node condition = parseExpression();
		consume(TokenType.RPAREN, "Expected ')' after if condition");

		Node thenBranch = parseStatement();
		Node elseBranch = null;
		if (match(TokenType.ELSE)) {
			elseBranch = parseStatement();
		}
		return new Node.IfStmt(condition, thenBranch, elseBranch, kw.line, kw.column);
	}

	private Node parseWhileStatement() {
		Token kw = advance();
		consume(TokenType.LPAREN, "Expected '(' after 'while'");
		Node condition = parseExpression();
		consume(TokenType.RPAREN, "Expected ')' after while condition");

		Node body = parseStatement();
		return new Node.WhileStmt(condition, body, kw.line, kw.column);
	}

	private Node parseForStatement() {
		Token kw = advance();
		consume(TokenType.LPAREN, "Expected '(' after 'for'");

		Node init = null;
		if (!check(TokenType.SEMICOLON)) {
			if (check(TokenType.VAR) || check(TokenType.LET) || check(TokenType.CONST)) {
				init = parseVarDecl(); // will consume semicolon
			} else {
				init = parseExpression();
				consume(TokenType.SEMICOLON, "Expected ';' after for init");
			}
		} else {
			consume(TokenType.SEMICOLON, "Expected ';'");
		}

		Node condition = null;
		if (!check(TokenType.SEMICOLON)) {
			condition = parseExpression();
		}
		consume(TokenType.SEMICOLON, "Expected ';' after for condition");

		Node update = null;
		if (!check(TokenType.RPAREN)) {
			update = parseExpression();
		}
		consume(TokenType.RPAREN, "Expected ')' after for clauses");

		Node body = parseStatement();
		return new Node.ForStmt(init, condition, update, body, kw.line, kw.column);
	}

	private Node parseReturnStatement() {
		Token kw = advance();
		Node value = null;
		if (!check(TokenType.SEMICOLON) && !check(TokenType.RBRACE) && !isAtEnd()) {
			value = parseExpression();
		}
		match(TokenType.SEMICOLON);
		return new Node.ReturnStmt(value, kw.line, kw.column);
	}

	private Node.BlockStmt parseBlockStatement() {
		Token lbrace = consume(TokenType.LBRACE, "Expected '{'");
		List<Node> stmts = new ArrayList<>();
		while (!check(TokenType.RBRACE) && !isAtEnd()) {
			Node s = parseStatement();
			if (s != null) stmts.add(s);
		}
		consume(TokenType.RBRACE, "Expected '}' after block");
		return new Node.BlockStmt(stmts, lbrace.line, lbrace.column);
	}

	// ==================== 表达式解析 (Pratt / Precedence) ====================

	public Node parseExpression() {
		return parseAssignment();
	}

	private Node parseAssignment() {
		Node left = parseLogicalOr();

		if (check(TokenType.ASSIGN) || check(TokenType.PLUS_ASSIGN) || check(TokenType.MINUS_ASSIGN)
			|| check(TokenType.STAR_ASSIGN) || check(TokenType.SLASH_ASSIGN)) {
			Token op = advance();
			Node right = parseAssignment();
			return new Node.AssignExpr(left, op.type, right, op.line, op.column);
		}

		return left;
	}

	private Node parseLogicalOr() {
		Node expr = parseLogicalAnd();
		while (match(TokenType.OR)) {
			Token op = previous();
			Node right = parseLogicalAnd();
			expr = new Node.BinaryExpr(expr, op.type, right, op.line, op.column);
		}
		return expr;
	}

	private Node parseLogicalAnd() {
		Node expr = parseEquality();
		while (match(TokenType.AND)) {
			Token op = previous();
			Node right = parseEquality();
			expr = new Node.BinaryExpr(expr, op.type, right, op.line, op.column);
		}
		return expr;
	}

	private Node parseEquality() {
		Node expr = parseRelational();
		while (match(TokenType.EQ, TokenType.EQ_EQ, TokenType.NOT_EQ, TokenType.NOT_EQ_EQ)) {
			Token op = previous();
			Node right = parseRelational();
			expr = new Node.BinaryExpr(expr, op.type, right, op.line, op.column);
		}
		return expr;
	}

	private Node parseRelational() {
		Node expr = parseAdditive();
		while (match(TokenType.LT, TokenType.LTE, TokenType.GT, TokenType.GTE)) {
			Token op = previous();
			Node right = parseAdditive();
			expr = new Node.BinaryExpr(expr, op.type, right, op.line, op.column);
		}
		return expr;
	}

	private Node parseAdditive() {
		Node expr = parseMultiplicative();
		while (match(TokenType.PLUS, TokenType.MINUS)) {
			Token op = previous();
			Node right = parseMultiplicative();
			expr = new Node.BinaryExpr(expr, op.type, right, op.line, op.column);
		}
		return expr;
	}

	private Node parseMultiplicative() {
		Node expr = parseUnary();
		while (match(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
			Token op = previous();
			Node right = parseUnary();
			expr = new Node.BinaryExpr(expr, op.type, right, op.line, op.column);
		}
		return expr;
	}

	private Node parseUnary() {
		if (match(TokenType.NOT, TokenType.MINUS, TokenType.PLUS, TokenType.PLUS_PLUS, TokenType.MINUS_MINUS)) {
			Token op = previous();
			Node right = parseUnary();
			return new Node.UnaryExpr(op.type, right, true, op.line, op.column);
		}
		return parsePostfix();
	}

	private Node parsePostfix() {
		Node expr = parsePrimary();

		while (true) {
			if (match(TokenType.DOT)) {
				Token prop = consume(TokenType.IDENTIFIER, "Expected property name after '.'");
				expr = new Node.MemberAccessExpr(expr, prop.text, prop.line, prop.column);
			} else if (match(TokenType.LBRACKET)) {
				Node index = parseExpression();
				consume(TokenType.RBRACKET, "Expected ']' after index");
				expr = new Node.IndexAccessExpr(expr, index, peek().line, peek().column);
			} else if (match(TokenType.LPAREN)) {
				List<Node> args = new ArrayList<>();
				if (!check(TokenType.RPAREN)) {
					do {
						args.add(parseExpression());
					} while (match(TokenType.COMMA));
				}
				Token rparen = consume(TokenType.RPAREN, "Expected ')' after arguments");
				expr = new Node.CallExpr(expr, args, rparen.line, rparen.column);
			} else if (match(TokenType.PLUS_PLUS, TokenType.MINUS_MINUS)) {
				Token op = previous();
				expr = new Node.UnaryExpr(op.type, expr, false, op.line, op.column);
			} else {
				break;
			}
		}

		return expr;
	}

	private Node parsePrimary() {
		Token t = peek();

		if (match(TokenType.NUMBER, TokenType.STRING)) {
			return new Node.LiteralExpr(previous().value, previous().line, previous().column);
		}
		if (match(TokenType.TRUE)) {
			return new Node.LiteralExpr(Boolean.TRUE, previous().line, previous().column);
		}
		if (match(TokenType.FALSE)) {
			return new Node.LiteralExpr(Boolean.FALSE, previous().line, previous().column);
		}
		if (match(TokenType.NULL)) {
			return new Node.LiteralExpr(null, previous().line, previous().column);
		}
		if (match(TokenType.UNDEFINED)) {
			return new Node.LiteralExpr(null, previous().line, previous().column);
		}
		if (match(TokenType.THIS)) {
			return new Node.IdentifierExpr("this", previous().line, previous().column);
		}
		if (match(TokenType.IDENTIFIER)) {
			return new Node.IdentifierExpr(previous().text, previous().line, previous().column);
		}

		if (match(TokenType.NEW)) {
			Token kw = previous();
			Node ctor = parsePrimary();
			List<Node> args = new ArrayList<>();
			if (match(TokenType.LPAREN)) {
				if (!check(TokenType.RPAREN)) {
					do {
						args.add(parseExpression());
					} while (match(TokenType.COMMA));
				}
				consume(TokenType.RPAREN, "Expected ')' after arguments");
			}
			return new Node.NewExpr(ctor, args, kw.line, kw.column);
		}

		if (match(TokenType.FUNCTION)) {
			Token kw = previous();
			String name = null;
			if (check(TokenType.IDENTIFIER)) {
				name = advance().text;
			}
			consume(TokenType.LPAREN, "Expected '(' in function expression");
			List<String> params = new ArrayList<>();
			if (!check(TokenType.RPAREN)) {
				do {
					params.add(consume(TokenType.IDENTIFIER, "Expected parameter name").text);
				} while (match(TokenType.COMMA));
			}
			consume(TokenType.RPAREN, "Expected ')' after parameters");
			Node.BlockStmt body = parseBlockStatement();
			return new Node.FunctionExpr(name, params, body, kw.line, kw.column);
		}

		// 对象字面量 { a: 1, b: 2 }
		if (match(TokenType.LBRACE)) {
			Token lbrace = previous();
			List<Node.ObjectLiteralExpr.Entry> entries = new ArrayList<>();
			if (!check(TokenType.RBRACE)) {
				do {
					Token keyToken = advance();
					String key = keyToken.text;
					consume(TokenType.COLON, "Expected ':' after property key");
					Node val = parseExpression();
					entries.add(new Node.ObjectLiteralExpr.Entry(key, val));
				} while (match(TokenType.COMMA));
			}
			consume(TokenType.RBRACE, "Expected '}' after object literal");
			return new Node.ObjectLiteralExpr(entries, lbrace.line, lbrace.column);
		}

		// 数组字面量 [ 1, 2, 3 ]
		if (match(TokenType.LBRACKET)) {
			Token lbracket = previous();
			List<Node> elements = new ArrayList<>();
			if (!check(TokenType.RBRACKET)) {
				do {
					elements.add(parseExpression());
				} while (match(TokenType.COMMA));
			}
			consume(TokenType.RBRACKET, "Expected ']' after array literal");
			return new Node.ArrayLiteralExpr(elements, lbracket.line, lbracket.column);
		}

		// 括号表达式 ( expr )
		if (match(TokenType.LPAREN)) {
			Node expr = parseExpression();
			consume(TokenType.RPAREN, "Expected ')' after expression");
			return expr;
		}

		throw new RuntimeException("Unexpected token " + t + " at line " + t.line + ":" + t.column);
	}

	// ==================== 辅助方法 ====================

	private boolean check(TokenType type) {
		if (isAtEnd()) return false;
		return peek().type == type;
	}

	private boolean match(TokenType... types) {
		for (TokenType type : types) {
			if (check(type)) {
				advance();
				return true;
			}
		}
		return false;
	}

	private Token consume(TokenType type, String message) {
		if (check(type)) return advance();
		Token t = peek();
		throw new RuntimeException(message + " (found '" + t.text + "' at line " + t.line + ":" + t.column + ")");
	}

	private Token peek() {
		return tokens.get(cursor);
	}

	private Token previous() {
		return tokens.get(cursor - 1);
	}

	private Token advance() {
		if (!isAtEnd()) cursor++;
		return previous();
	}

	private boolean isAtEnd() {
		return peek().type == TokenType.EOF;
	}
}
