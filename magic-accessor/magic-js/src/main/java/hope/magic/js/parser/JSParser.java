package hope.magic.js.parser;

import hope.magic.js.ast.Node;
import hope.magic.js.ast.Token;
import hope.magic.js.ast.TokenType;

import java.util.ArrayList;
import java.util.Collections;
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

	//region 语句解析

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
		if (t.type == TokenType.DO) {
			return parseDoWhileStatement();
		}
		if (t.type == TokenType.THROW) {
			return parseThrowStatement();
		}
		if (t.type == TokenType.TRY) {
			return parseTryStatement();
		}
		if (t.type == TokenType.SWITCH) {
			return parseSwitchStatement();
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

		if (t.type == TokenType.LBRACKET) {
			int saveCursor = cursor;
			try {
				ArrayPattern pattern = parseArrayPattern();
				if (match(TokenType.ASSIGN)) {
					Node right = parseExpression();
					match(TokenType.SEMICOLON);
					List<Node> stmts = new ArrayList<>();
					pattern.desugar(right, false, stmts, t.line, t.column);
					return new Node.BlockStmt(stmts, t.line, t.column);
				}
			} catch (Exception ignored) {
			}
			cursor = saveCursor;
		}

		if (t.type == TokenType.LPAREN && peekNext().type == TokenType.LBRACE) {
			int saveCursor = cursor;
			try {
				advance(); // consume '('
				ObjectPattern pattern = parseObjectPattern();
				if (match(TokenType.ASSIGN)) {
					Node right = parseExpression();
					consume(TokenType.RPAREN, "Expected ')'");
					match(TokenType.SEMICOLON);
					List<Node> stmts = new ArrayList<>();
					pattern.desugar(right, false, stmts, t.line, t.column);
					return new Node.BlockStmt(stmts, t.line, t.column);
				}
			} catch (Exception ignored) {
			}
			cursor = saveCursor;
		}

		// 表达式语句
		Node expr = parseExpression();
		match(TokenType.SEMICOLON);
		return new Node.ExprStmt(expr, t.line, t.column);
	}

	private Node parseVarDecl() {
		Token kw = advance();
		List<Node> stmts = new ArrayList<>();

		do {
			if (check(TokenType.LBRACE)) {
				ObjectPattern pattern = parseObjectPattern();
				consume(TokenType.ASSIGN, "Expected '=' in destructuring declaration");
				Node init = parseExpression();
				pattern.desugar(init, true, stmts, kw.line, kw.column);
			} else if (check(TokenType.LBRACKET)) {
				ArrayPattern pattern = parseArrayPattern();
				consume(TokenType.ASSIGN, "Expected '=' in destructuring declaration");
				Node init = parseExpression();
				pattern.desugar(init, true, stmts, kw.line, kw.column);
			} else {
				Token id = consume(TokenType.IDENTIFIER, "Expected identifier after " + kw.text);
				Node init = null;
				if (match(TokenType.ASSIGN)) {
					init = parseExpression();
				}
				stmts.add(new Node.VarDecl(id.text, init, kw.line, kw.column));
			}
		} while (match(TokenType.COMMA));

		match(TokenType.SEMICOLON);
		if (stmts.size() == 1) {
			return stmts.get(0);
		}
		return new Node.BlockStmt(stmts, kw.line, kw.column);
	}

	private Node parseFunctionDecl() {
		Token kw = advance();
		Token id = consume(TokenType.IDENTIFIER, "Expected function name");
		consume(TokenType.LPAREN, "Expected '(' after function name");

		ParamParseResult paramRes = parseFunctionParams(kw);
		consume(TokenType.RPAREN, "Expected ')' after parameters");

		Node.BlockStmt rawBody = parseBlockStatement();
		List<Node> allStmts = new ArrayList<>(paramRes.unpackStmts);
		allStmts.addAll(rawBody.statements);
		return new Node.FunctionDecl(id.text, paramRes.params, new Node.BlockStmt(allStmts, rawBody.line, rawBody.column), kw.line, kw.column);
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

		// Check for "for (var/let/const x of/in iterable/object)" or "for (x of/in iterable/object)"
		int saveCursor = cursor;
		boolean isDecl = match(TokenType.VAR) || match(TokenType.LET) || match(TokenType.CONST);
		if (check(TokenType.IDENTIFIER)) {
			Token varId = advance();
			if (match(TokenType.OF)) {
				Node iterable = parseExpression();
				consume(TokenType.RPAREN, "Expected ')' after for-of expression");
				Node body = parseStatement();
				return new Node.ForOfStmt(varId.text, isDecl, iterable, body, kw.line, kw.column);
			} else if (match(TokenType.IN)) {
				Node object = parseExpression();
				consume(TokenType.RPAREN, "Expected ')' after for-in expression");
				Node body = parseStatement();
				return new Node.ForInStmt(varId.text, isDecl, object, body, kw.line, kw.column);
			}
		}
		cursor = saveCursor;

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

	private Node parseDoWhileStatement() {
		Token kw = advance(); // consume 'do'
		Node body = parseStatement();
		consume(TokenType.WHILE, "Expected 'while' after 'do' body");
		consume(TokenType.LPAREN, "Expected '(' after 'while'");
		Node condition = parseExpression();
		consume(TokenType.RPAREN, "Expected ')' after while condition");
		match(TokenType.SEMICOLON);
		return new Node.DoWhileStmt(body, condition, kw.line, kw.column);
	}

	private Node parseThrowStatement() {
		Token kw = advance(); // consume 'throw'
		Node expr = parseExpression();
		match(TokenType.SEMICOLON);
		return new Node.ThrowStmt(expr, kw.line, kw.column);
	}

	private Node parseTryStatement() {
		Token kw = advance(); // consume 'try'
		Node.BlockStmt tryBlock = parseBlockStatement();
		String catchParam = null;
		Node.BlockStmt catchBlock = null;
		if (match(TokenType.CATCH)) {
			if (match(TokenType.LPAREN)) {
				Token param = consume(TokenType.IDENTIFIER, "Expected identifier in catch clause");
				catchParam = param.text;
				consume(TokenType.RPAREN, "Expected ')' after catch parameter");
			}
			catchBlock = parseBlockStatement();
		}
		Node.BlockStmt finallyBlock = null;
		if (match(TokenType.FINALLY)) {
			finallyBlock = parseBlockStatement();
		}
		return new Node.TryStmt(tryBlock, catchParam, catchBlock, finallyBlock, kw.line, kw.column);
	}

	private Node parseSwitchStatement() {
		Token kw = advance(); // consume 'switch'
		consume(TokenType.LPAREN, "Expected '(' after 'switch'");
		Node discriminant = parseExpression();
		consume(TokenType.RPAREN, "Expected ')' after switch discriminant");
		consume(TokenType.LBRACE, "Expected '{' before switch cases");

		List<Node.CaseClause> cases = new ArrayList<>();
		while (!check(TokenType.RBRACE) && !isAtEnd()) {
			if (match(TokenType.CASE)) {
				Token caseToken = previous();
				Node test = parseExpression();
				consume(TokenType.COLON, "Expected ':' after case expression");
				List<Node> stmts = new ArrayList<>();
				while (!check(TokenType.CASE) && !check(TokenType.DEFAULT) && !check(TokenType.RBRACE) && !isAtEnd()) {
					Node stmt = parseStatement();
					if (stmt != null) stmts.add(stmt);
				}
				cases.add(new Node.CaseClause(test, stmts, caseToken.line, caseToken.column));
			} else if (match(TokenType.DEFAULT)) {
				Token defaultToken = previous();
				consume(TokenType.COLON, "Expected ':' after 'default'");
				List<Node> stmts = new ArrayList<>();
				while (!check(TokenType.CASE) && !check(TokenType.DEFAULT) && !check(TokenType.RBRACE) && !isAtEnd()) {
					Node stmt = parseStatement();
					if (stmt != null) stmts.add(stmt);
				}
				cases.add(new Node.CaseClause(null, stmts, defaultToken.line, defaultToken.column));
			} else {
				throw new RuntimeException("Expected 'case' or 'default' in switch at line " + peek().line + ":" + peek().column);
			}
		}
		consume(TokenType.RBRACE, "Expected '}' after switch cases");
		return new Node.SwitchStmt(discriminant, cases, kw.line, kw.column);
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
	//endregion

	//region 表达式解析 (Pratt / Precedence)

	public Node parseExpression() {
		return parseAssignment();
	}

	private Node parseAssignment() {
		Node left = parseConditional();

		if (check(TokenType.ASSIGN) || check(TokenType.PLUS_ASSIGN) || check(TokenType.MINUS_ASSIGN)
			|| check(TokenType.STAR_ASSIGN) || check(TokenType.SLASH_ASSIGN)) {
			Token op = advance();
			Node right = parseAssignment();
			if (op.type == TokenType.ASSIGN) {
				if (left instanceof Node.ArrayLiteralExpr arrLit) {
					return desugarArrayDestructuringAssignment(arrLit, right, op.line, op.column);
				}
				if (left instanceof Node.ObjectLiteralExpr objLit) {
					return desugarObjectDestructuringAssignment(objLit, right, op.line, op.column);
				}
			}
			return new Node.AssignExpr(left, op.type, right, op.line, op.column);
		}

		return left;
	}

	private Node parseConditional() {
		Node expr = parseLogicalOr();
		if (match(TokenType.QUESTION)) {
			Token op = previous();
			Node thenExpr = parseAssignment();
			consume(TokenType.COLON, "Expected ':' in conditional expression");
			Node elseExpr = parseAssignment();
			return new Node.TernaryExpr(expr, thenExpr, elseExpr, op.line, op.column);
		}
		return expr;
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
		if (match(TokenType.TYPEOF)) {
			Token op = previous();
			Node right = parseUnary();
			return new Node.TypeOfExpr(right, op.line, op.column);
		}
		if (match(TokenType.VOID)) {
			Token op = previous();
			Node right = parseUnary();
			return new Node.VoidExpr(right, op.line, op.column);
		}
		if (match(TokenType.DELETE)) {
			Token op = previous();
			Node right = parseUnary();
			return new Node.UnaryExpr(TokenType.DELETE, right, true, op.line, op.column);
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
		if (match(TokenType.REGEXP)) {
			Token token = previous();
			String[] parts = (String[]) token.value;
			return new Node.RegExpLiteral(parts[0], parts[1], token.line, token.column);
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
			return new Node.LiteralExpr(hope.magic.js.runtime.JSUndefined.INSTANCE, previous().line, previous().column);
		}
		if (match(TokenType.THIS)) {
			return new Node.IdentifierExpr("this", previous().line, previous().column);
		}
		// 单参数箭头函数: x => x * 2 或 x => { ... }
		if (check(TokenType.IDENTIFIER) && peekNext().type == TokenType.ARROW) {
			Token param = advance();
			consume(TokenType.ARROW, "Expected '=>'");
			Node.BlockStmt body;
			if (check(TokenType.LBRACE)) {
				body = parseBlockStatement();
			} else {
				Node expr = parseExpression();
				body = new Node.BlockStmt(Collections.singletonList(new Node.ReturnStmt(expr, expr.line, expr.column)), expr.line, expr.column);
			}
			return new Node.FunctionExpr(null, Collections.singletonList(param.text), body, param.line, param.column);
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
			ParamParseResult paramRes = parseFunctionParams(kw);
			consume(TokenType.RPAREN, "Expected ')' after parameters");
			Node.BlockStmt rawBody = parseBlockStatement();
			List<Node> allStmts = new ArrayList<>(paramRes.unpackStmts);
			allStmts.addAll(rawBody.statements);
			return new Node.FunctionExpr(name, paramRes.params, new Node.BlockStmt(allStmts, rawBody.line, rawBody.column), kw.line, kw.column);
		}

		// 对象字面量 { a: 1, b: 2 } 或属性简写 { a, b }
		if (match(TokenType.LBRACE)) {
			Token lbrace = previous();
			List<Node.ObjectLiteralExpr.Entry> entries = new ArrayList<>();
			if (!check(TokenType.RBRACE)) {
				do {
					Token keyToken = advance();
					String key = keyToken.text;
					Node val;
					if (match(TokenType.COLON)) {
						val = parseExpression();
					} else {
						val = new Node.IdentifierExpr(key, keyToken.line, keyToken.column);
					}
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

		// 括号表达式 或 箭头函数 ( a, b ) => expr
		if (match(TokenType.LPAREN)) {
			Token lparen = previous();
			if (isArrowParamList()) {
				ParamParseResult paramRes = parseFunctionParams(lparen);
				consume(TokenType.RPAREN, "Expected ')' after parameters");
				consume(TokenType.ARROW, "Expected '=>'");
				Node.BlockStmt body;
				if (check(TokenType.LBRACE)) {
					Node.BlockStmt rawBody = parseBlockStatement();
					List<Node> allStmts = new ArrayList<>(paramRes.unpackStmts);
					allStmts.addAll(rawBody.statements);
					body = new Node.BlockStmt(allStmts, rawBody.line, rawBody.column);
				} else {
					Node expr = parseExpression();
					List<Node> allStmts = new ArrayList<>(paramRes.unpackStmts);
					allStmts.add(new Node.ReturnStmt(expr, expr.line, expr.column));
					body = new Node.BlockStmt(allStmts, expr.line, expr.column);
				}
				return new Node.FunctionExpr(null, paramRes.params, body, lparen.line, lparen.column);
			}
			Node expr = parseExpression();
			consume(TokenType.RPAREN, "Expected ')' after expression");
			return expr;
		}

		throw new RuntimeException("Unexpected token " + t + " at line " + t.line + ":" + t.column);
	}
	//endregion

	//region 辅助方法与 ES6 解构脱糖

	private static final java.util.concurrent.atomic.AtomicInteger TEMP_VAR_GEN = new java.util.concurrent.atomic.AtomicInteger(0);

	private static class ParamParseResult {
		final List<String> params = new ArrayList<>();
		final List<Node> unpackStmts = new ArrayList<>();
	}

	private ParamParseResult parseFunctionParams(Token contextToken) {
		ParamParseResult result = new ParamParseResult();
		if (!check(TokenType.RPAREN)) {
			int paramIdx = 0;
			do {
				if (check(TokenType.LBRACE)) {
					ObjectPattern pattern = parseObjectPattern();
					String paramName = "$p_" + (paramIdx++);
					result.params.add(paramName);
					Node defaultValue = null;
					if (match(TokenType.ASSIGN)) {
						defaultValue = parseAssignment();
					}
					Node paramRef = new Node.IdentifierExpr(paramName, contextToken.line, contextToken.column);
					Node srcExpr = defaultValue != null
						? new Node.TernaryExpr(new Node.BinaryExpr(paramRef, TokenType.NOT_EQ_EQ, new Node.IdentifierExpr("undefined", contextToken.line, contextToken.column), contextToken.line, contextToken.column), paramRef, defaultValue, contextToken.line, contextToken.column)
						: paramRef;
					pattern.desugar(srcExpr, true, result.unpackStmts, contextToken.line, contextToken.column);
				} else if (check(TokenType.LBRACKET)) {
					ArrayPattern pattern = parseArrayPattern();
					String paramName = "$p_" + (paramIdx++);
					result.params.add(paramName);
					Node defaultValue = null;
					if (match(TokenType.ASSIGN)) {
						defaultValue = parseAssignment();
					}
					Node paramRef = new Node.IdentifierExpr(paramName, contextToken.line, contextToken.column);
					Node srcExpr = defaultValue != null
						? new Node.TernaryExpr(new Node.BinaryExpr(paramRef, TokenType.NOT_EQ_EQ, new Node.IdentifierExpr("undefined", contextToken.line, contextToken.column), contextToken.line, contextToken.column), paramRef, defaultValue, contextToken.line, contextToken.column)
						: paramRef;
					pattern.desugar(srcExpr, true, result.unpackStmts, contextToken.line, contextToken.column);
				} else {
					Token p = consume(TokenType.IDENTIFIER, "Expected parameter name");
					result.params.add(p.text);
					if (match(TokenType.ASSIGN)) {
						Node defaultVal = parseAssignment();
						Node paramRef = new Node.IdentifierExpr(p.text, contextToken.line, contextToken.column);
						Node cond = new Node.BinaryExpr(paramRef, TokenType.EQ_EQ, new Node.LiteralExpr(hope.magic.js.runtime.JSUndefined.INSTANCE, contextToken.line, contextToken.column), contextToken.line, contextToken.column);
						result.unpackStmts.add(new Node.IfStmt(cond, new Node.ExprStmt(new Node.AssignExpr(paramRef, TokenType.ASSIGN, defaultVal, contextToken.line, contextToken.column), contextToken.line, contextToken.column), null, contextToken.line, contextToken.column));
					}
				}
			} while (match(TokenType.COMMA));
		}
		return result;
	}

	private ObjectPattern parseObjectPattern() {
		consume(TokenType.LBRACE, "Expected '{'");
		ObjectPattern pattern = new ObjectPattern();
		if (!check(TokenType.RBRACE)) {
			do {
				if (match(TokenType.ELLIPSIS)) {
					Token restId = consume(TokenType.IDENTIFIER, "Expected identifier after '...'");
					pattern.entries.add(new ObjectPattern.Entry(restId.text, restId.text, null, null, true));
					break;
				}
				Token keyToken = consume(TokenType.IDENTIFIER, "Expected property name");
				String key = keyToken.text;
				String targetName = key;
				DestructuringPattern nested = null;
				if (match(TokenType.COLON)) {
					if (check(TokenType.LBRACE)) {
						nested = parseObjectPattern();
						targetName = null;
					} else if (check(TokenType.LBRACKET)) {
						nested = parseArrayPattern();
						targetName = null;
					} else {
						Token alias = consume(TokenType.IDENTIFIER, "Expected alias identifier");
						targetName = alias.text;
					}
				}
				Node defaultValue = null;
				if (match(TokenType.ASSIGN)) {
					defaultValue = parseAssignment();
				}
				pattern.entries.add(new ObjectPattern.Entry(key, targetName, nested, defaultValue, false));
			} while (match(TokenType.COMMA) && !check(TokenType.RBRACE));
		}
		consume(TokenType.RBRACE, "Expected '}'");
		return pattern;
	}

	private ArrayPattern parseArrayPattern() {
		consume(TokenType.LBRACKET, "Expected '['");
		ArrayPattern pattern = new ArrayPattern();
		while (!check(TokenType.RBRACKET) && !isAtEnd()) {
			if (match(TokenType.COMMA)) {
				pattern.elements.add(new ArrayPattern.Element(null, null, null, false, true));
				continue;
			}
			if (match(TokenType.ELLIPSIS)) {
				Token restId = consume(TokenType.IDENTIFIER, "Expected identifier after '...'");
				pattern.elements.add(new ArrayPattern.Element(restId.text, null, null, true, false));
				match(TokenType.COMMA);
				break;
			}
			if (check(TokenType.LBRACE)) {
				DestructuringPattern nested = parseObjectPattern();
				Node defaultValue = null;
				if (match(TokenType.ASSIGN)) {
					defaultValue = parseAssignment();
				}
				pattern.elements.add(new ArrayPattern.Element(null, nested, defaultValue, false, false));
			} else if (check(TokenType.LBRACKET)) {
				DestructuringPattern nested = parseArrayPattern();
				Node defaultValue = null;
				if (match(TokenType.ASSIGN)) {
					defaultValue = parseAssignment();
				}
				pattern.elements.add(new ArrayPattern.Element(null, nested, defaultValue, false, false));
			} else {
				Token id = consume(TokenType.IDENTIFIER, "Expected identifier in array pattern");
				Node defaultValue = null;
				if (match(TokenType.ASSIGN)) {
					defaultValue = parseAssignment();
				}
				pattern.elements.add(new ArrayPattern.Element(id.text, null, defaultValue, false, false));
			}
			match(TokenType.COMMA);
		}
		consume(TokenType.RBRACKET, "Expected ']'");
		return pattern;
	}

	public interface DestructuringPattern {
		void desugar(Node sourceExpr, boolean isDecl, List<Node> outStmts, int line, int column);
	}

	public static class ObjectPattern implements DestructuringPattern {
		public static class Entry {
			public final String key;
			public final String targetName;
			public final DestructuringPattern nestedPattern;
			public final Node defaultValue;
			public final boolean isRest;

			public Entry(String key, String targetName, DestructuringPattern nestedPattern, Node defaultValue, boolean isRest) {
				this.key = key;
				this.targetName = targetName;
				this.nestedPattern = nestedPattern;
				this.defaultValue = defaultValue;
				this.isRest = isRest;
			}
		}

		public final List<Entry> entries = new ArrayList<>();

		@Override
		public void desugar(Node sourceExpr, boolean isDecl, List<Node> outStmts, int line, int column) {
			String tmpVar = "$d_tmp_" + TEMP_VAR_GEN.getAndIncrement();
			outStmts.add(new Node.VarDecl(tmpVar, sourceExpr, line, column));

			List<String> normalKeys = new ArrayList<>();
			for (Entry entry : entries) {
				if (!entry.isRest) {
					normalKeys.add(entry.key);
				}
			}

			for (Entry entry : entries) {
				if (entry.isRest) {
					String csv = String.join(",", normalKeys);
					List<Node> args = List.of(
						new Node.IdentifierExpr(tmpVar, line, column),
						new Node.LiteralExpr(csv, line, column)
					);
					Node restCall = new Node.CallExpr(
						new Node.MemberAccessExpr(
							new Node.IdentifierExpr("JSOps", line, column),
							"restObject",
							line, column
						),
						args,
						line, column
					);
					if (entry.targetName != null) {
						if (isDecl) {
							outStmts.add(new Node.VarDecl(entry.targetName, restCall, line, column));
						} else {
							outStmts.add(new Node.ExprStmt(new Node.AssignExpr(new Node.IdentifierExpr(entry.targetName, line, column), TokenType.ASSIGN, restCall, line, column), line, column));
						}
					}
					continue;
				}

				Node propAccess = new Node.MemberAccessExpr(new Node.IdentifierExpr(tmpVar, line, column), entry.key, line, column);
				Node valExpr;
				if (entry.defaultValue != null) {
					Node undef = new Node.LiteralExpr(hope.magic.js.runtime.JSUndefined.INSTANCE, line, column);
					Node cond = new Node.BinaryExpr(propAccess, TokenType.NOT_EQ_EQ, undef, line, column);
					valExpr = new Node.TernaryExpr(cond, propAccess, entry.defaultValue, line, column);
				} else {
					valExpr = propAccess;
				}

				if (entry.nestedPattern != null) {
					entry.nestedPattern.desugar(valExpr, isDecl, outStmts, line, column);
				} else if (entry.targetName != null) {
					if (isDecl) {
						outStmts.add(new Node.VarDecl(entry.targetName, valExpr, line, column));
					} else {
						outStmts.add(new Node.ExprStmt(new Node.AssignExpr(new Node.IdentifierExpr(entry.targetName, line, column), TokenType.ASSIGN, valExpr, line, column), line, column));
					}
				}
			}
		}
	}

	public static class ArrayPattern implements DestructuringPattern {
		public static class Element {
			public final String targetName;
			public final DestructuringPattern nestedPattern;
			public final Node defaultValue;
			public final boolean isRest;
			public final boolean isOmitted;

			public Element(String targetName, DestructuringPattern nestedPattern, Node defaultValue, boolean isRest, boolean isOmitted) {
				this.targetName = targetName;
				this.nestedPattern = nestedPattern;
				this.defaultValue = defaultValue;
				this.isRest = isRest;
				this.isOmitted = isOmitted;
			}
		}

		public final List<Element> elements = new ArrayList<>();

		@Override
		public void desugar(Node sourceExpr, boolean isDecl, List<Node> outStmts, int line, int column) {
			String tmpVar = "$d_tmp_" + TEMP_VAR_GEN.getAndIncrement();
			outStmts.add(new Node.VarDecl(tmpVar, sourceExpr, line, column));

			for (int i = 0; i < elements.size(); i++) {
				Element elem = elements.get(i);
				if (elem.isOmitted) continue;

				if (elem.isRest) {
					List<Node> args = List.of(
						new Node.IdentifierExpr(tmpVar, line, column),
						new Node.LiteralExpr(i, line, column)
					);
					Node sliceCall = new Node.CallExpr(
						new Node.MemberAccessExpr(
							new Node.IdentifierExpr("JSOps", line, column),
							"slice",
							line, column
						),
						args,
						line, column
					);
					if (elem.targetName != null) {
						if (isDecl) {
							outStmts.add(new Node.VarDecl(elem.targetName, sliceCall, line, column));
						} else {
							outStmts.add(new Node.ExprStmt(new Node.AssignExpr(new Node.IdentifierExpr(elem.targetName, line, column), TokenType.ASSIGN, sliceCall, line, column), line, column));
						}
					}
					continue;
				}

				Node idxAccess = new Node.IndexAccessExpr(new Node.IdentifierExpr(tmpVar, line, column), new Node.LiteralExpr(i, line, column), line, column);
				Node valExpr;
				if (elem.defaultValue != null) {
					Node undef = new Node.LiteralExpr(hope.magic.js.runtime.JSUndefined.INSTANCE, line, column);
					Node cond = new Node.BinaryExpr(idxAccess, TokenType.NOT_EQ_EQ, undef, line, column);
					valExpr = new Node.TernaryExpr(cond, idxAccess, elem.defaultValue, line, column);
				} else {
					valExpr = idxAccess;
				}

				if (elem.nestedPattern != null) {
					elem.nestedPattern.desugar(valExpr, isDecl, outStmts, line, column);
				} else if (elem.targetName != null) {
					if (isDecl) {
						outStmts.add(new Node.VarDecl(elem.targetName, valExpr, line, column));
					} else {
						outStmts.add(new Node.ExprStmt(new Node.AssignExpr(new Node.IdentifierExpr(elem.targetName, line, column), TokenType.ASSIGN, valExpr, line, column), line, column));
					}
				}
			}
		}
	}

	private Node desugarArrayDestructuringAssignment(Node.ArrayLiteralExpr arrLit, Node right, int line, int column) {
		ArrayPattern pattern = convertArrayLiteralToPattern(arrLit);
		List<Node> stmts = new ArrayList<>();
		String resVar = "$d_res_" + TEMP_VAR_GEN.getAndIncrement();
		stmts.add(new Node.VarDecl(resVar, right, line, column));
		pattern.desugar(new Node.IdentifierExpr(resVar, line, column), false, stmts, line, column);
		stmts.add(new Node.ReturnStmt(new Node.IdentifierExpr(resVar, line, column), line, column));
		Node.FunctionExpr fn = new Node.FunctionExpr(null, Collections.emptyList(), new Node.BlockStmt(stmts, line, column), line, column);
		return new Node.CallExpr(fn, Collections.emptyList(), line, column);
	}

	private Node desugarObjectDestructuringAssignment(Node.ObjectLiteralExpr objLit, Node right, int line, int column) {
		ObjectPattern pattern = convertObjectLiteralToPattern(objLit);
		List<Node> stmts = new ArrayList<>();
		String resVar = "$d_res_" + TEMP_VAR_GEN.getAndIncrement();
		stmts.add(new Node.VarDecl(resVar, right, line, column));
		pattern.desugar(new Node.IdentifierExpr(resVar, line, column), false, stmts, line, column);
		stmts.add(new Node.ReturnStmt(new Node.IdentifierExpr(resVar, line, column), line, column));
		Node.FunctionExpr fn = new Node.FunctionExpr(null, Collections.emptyList(), new Node.BlockStmt(stmts, line, column), line, column);
		return new Node.CallExpr(fn, Collections.emptyList(), line, column);
	}

	private ArrayPattern convertArrayLiteralToPattern(Node.ArrayLiteralExpr arrLit) {
		ArrayPattern pattern = new ArrayPattern();
		for (Node elem : arrLit.elements) {
			if (elem == null) {
				pattern.elements.add(new ArrayPattern.Element(null, null, null, false, true));
			} else if (elem instanceof Node.IdentifierExpr id) {
				pattern.elements.add(new ArrayPattern.Element(id.name, null, null, false, false));
			} else if (elem instanceof Node.AssignExpr assign && assign.target instanceof Node.IdentifierExpr id) {
				pattern.elements.add(new ArrayPattern.Element(id.name, null, assign.value, false, false));
			} else if (elem instanceof Node.ArrayLiteralExpr nestedArr) {
				pattern.elements.add(new ArrayPattern.Element(null, convertArrayLiteralToPattern(nestedArr), null, false, false));
			} else if (elem instanceof Node.ObjectLiteralExpr nestedObj) {
				pattern.elements.add(new ArrayPattern.Element(null, convertObjectLiteralToPattern(nestedObj), null, false, false));
			}
		}
		return pattern;
	}

	private ObjectPattern convertObjectLiteralToPattern(Node.ObjectLiteralExpr objLit) {
		ObjectPattern pattern = new ObjectPattern();
		for (Node.ObjectLiteralExpr.Entry prop : objLit.entries) {
			String key = prop.key();
			Node val = prop.value();
			if (val instanceof Node.IdentifierExpr id) {
				pattern.entries.add(new ObjectPattern.Entry(key, id.name, null, null, false));
			} else if (val instanceof Node.AssignExpr assign && assign.target instanceof Node.IdentifierExpr id) {
				pattern.entries.add(new ObjectPattern.Entry(key, id.name, null, assign.value, false));
			} else if (val instanceof Node.ObjectLiteralExpr nestedObj) {
				pattern.entries.add(new ObjectPattern.Entry(key, null, convertObjectLiteralToPattern(nestedObj), null, false));
			} else if (val instanceof Node.ArrayLiteralExpr nestedArr) {
				pattern.entries.add(new ObjectPattern.Entry(key, null, convertArrayLiteralToPattern(nestedArr), null, false));
			}
		}
		return pattern;
	}

	private boolean isArrowParamList() {
		int i = cursor;
		int depth = 1;
		while (i < tokens.size()) {
			TokenType type = tokens.get(i).type;
			if (type == TokenType.LPAREN || type == TokenType.LBRACE || type == TokenType.LBRACKET) {
				depth++;
			} else if (type == TokenType.RPAREN || type == TokenType.RBRACE || type == TokenType.RBRACKET) {
				depth--;
				if (depth == 0) {
					return i + 1 < tokens.size() && tokens.get(i + 1).type == TokenType.ARROW;
				}
			}
			i++;
		}
		return false;
	}

	private boolean check(TokenType type) {
		if (isAtEnd()) return false;
		return peek().type == type;
	}

	private Token peekNext() {
		if (cursor + 1 < tokens.size()) return tokens.get(cursor + 1);
		return tokens.get(tokens.size() - 1);
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
	//endregion
}
