package hope.magic.js.parser;

import hope.magic.js.ast.Token;
import hope.magic.js.ast.TokenType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JSLexer {
	private static final Map<String, TokenType> KEYWORDS = new HashMap<>();

	static {
		KEYWORDS.put("var", TokenType.VAR);
		KEYWORDS.put("let", TokenType.LET);
		KEYWORDS.put("const", TokenType.CONST);
		KEYWORDS.put("function", TokenType.FUNCTION);
		KEYWORDS.put("return", TokenType.RETURN);
		KEYWORDS.put("if", TokenType.IF);
		KEYWORDS.put("else", TokenType.ELSE);
		KEYWORDS.put("while", TokenType.WHILE);
		KEYWORDS.put("for", TokenType.FOR);
		KEYWORDS.put("break", TokenType.BREAK);
		KEYWORDS.put("continue", TokenType.CONTINUE);
		KEYWORDS.put("new", TokenType.NEW);
		KEYWORDS.put("this", TokenType.THIS);
		KEYWORDS.put("true", TokenType.TRUE);
		KEYWORDS.put("false", TokenType.FALSE);
		KEYWORDS.put("null", TokenType.NULL);
		KEYWORDS.put("undefined", TokenType.UNDEFINED);
	}

	private final String source;
	private final int length;
	private int cursor = 0;
	private int line = 1;
	private int column = 1;

	public JSLexer(String source) {
		this.source = source == null ? "" : source;
		this.length = this.source.length();
	}

	public List<Token> tokenize() {
		List<Token> tokens = new ArrayList<>();
		while (!isAtEnd()) {
			skipWhitespaceAndComments();
			if (isAtEnd()) break;

			int startLine = line;
			int startCol = column;
			char c = advance();

			switch (c) {
				case '(': tokens.add(new Token(TokenType.LPAREN, "(", null, startLine, startCol)); break;
				case ')': tokens.add(new Token(TokenType.RPAREN, ")", null, startLine, startCol)); break;
				case '{': tokens.add(new Token(TokenType.LBRACE, "{", null, startLine, startCol)); break;
				case '}': tokens.add(new Token(TokenType.RBRACE, "}", null, startLine, startCol)); break;
				case '[': tokens.add(new Token(TokenType.LBRACKET, "[", null, startLine, startCol)); break;
				case ']': tokens.add(new Token(TokenType.RBRACKET, "]", null, startLine, startCol)); break;
				case ',': tokens.add(new Token(TokenType.COMMA, ",", null, startLine, startCol)); break;
				case '.': tokens.add(new Token(TokenType.DOT, ".", null, startLine, startCol)); break;
				case ';': tokens.add(new Token(TokenType.SEMICOLON, ";", null, startLine, startCol)); break;
				case ':': tokens.add(new Token(TokenType.COLON, ":", null, startLine, startCol)); break;
				case '?': tokens.add(new Token(TokenType.QUESTION, "?", null, startLine, startCol)); break;
				case '%': tokens.add(new Token(TokenType.PERCENT, "%", null, startLine, startCol)); break;

				case '+':
					if (match('+')) tokens.add(new Token(TokenType.PLUS_PLUS, "++", null, startLine, startCol));
					else if (match('=')) tokens.add(new Token(TokenType.PLUS_ASSIGN, "+=", null, startLine, startCol));
					else tokens.add(new Token(TokenType.PLUS, "+", null, startLine, startCol));
					break;

				case '-':
					if (match('-')) tokens.add(new Token(TokenType.MINUS_MINUS, "--", null, startLine, startCol));
					else if (match('=')) tokens.add(new Token(TokenType.MINUS_ASSIGN, "-=", null, startLine, startCol));
					else tokens.add(new Token(TokenType.MINUS, "-", null, startLine, startCol));
					break;

				case '*':
					if (match('=')) tokens.add(new Token(TokenType.STAR_ASSIGN, "*=", null, startLine, startCol));
					else tokens.add(new Token(TokenType.STAR, "*", null, startLine, startCol));
					break;

				case '/':
					if (match('=')) tokens.add(new Token(TokenType.SLASH_ASSIGN, "/=", null, startLine, startCol));
					else tokens.add(new Token(TokenType.SLASH, "/", null, startLine, startCol));
					break;

				case '=':
					if (match('=')) {
						if (match('=')) tokens.add(new Token(TokenType.EQ_EQ, "===", null, startLine, startCol));
						else tokens.add(new Token(TokenType.EQ, "==", null, startLine, startCol));
					} else {
						tokens.add(new Token(TokenType.ASSIGN, "=", null, startLine, startCol));
					}
					break;

				case '!':
					if (match('=')) {
						if (match('=')) tokens.add(new Token(TokenType.NOT_EQ_EQ, "!==", null, startLine, startCol));
						else tokens.add(new Token(TokenType.NOT_EQ, "!=", null, startLine, startCol));
					} else {
						tokens.add(new Token(TokenType.NOT, "!", null, startLine, startCol));
					}
					break;

				case '<':
					if (match('=')) tokens.add(new Token(TokenType.LTE, "<=", null, startLine, startCol));
					else tokens.add(new Token(TokenType.LT, "<", null, startLine, startCol));
					break;

				case '>':
					if (match('=')) tokens.add(new Token(TokenType.GTE, ">=", null, startLine, startCol));
					else tokens.add(new Token(TokenType.GT, ">", null, startLine, startCol));
					break;

				case '&':
					if (match('&')) tokens.add(new Token(TokenType.AND, "&&", null, startLine, startCol));
					else throw new RuntimeException("Unexpected character '&' at line " + startLine + ":" + startCol);
					break;

				case '|':
					if (match('|')) tokens.add(new Token(TokenType.OR, "||", null, startLine, startCol));
					else throw new RuntimeException("Unexpected character '|' at line " + startLine + ":" + startCol);
					break;

				case '"':
				case '\'':
					tokens.add(scanString(c, startLine, startCol));
					break;

				default:
					if (isDigit(c)) {
						cursor--; // back up
						column--;
						tokens.add(scanNumber(startLine, startCol));
					} else if (isAlpha(c) || c == '_' || c == '$') {
						cursor--; // back up
						column--;
						tokens.add(scanIdentifierOrKeyword(startLine, startCol));
					} else {
						throw new RuntimeException("Unexpected character '" + c + "' at line " + startLine + ":" + startCol);
					}
					break;
			}
		}

		tokens.add(new Token(TokenType.EOF, "", null, line, column));
		return tokens;
	}

	private Token scanString(char quote, int startLine, int startCol) {
		StringBuilder sb = new StringBuilder();
		while (!isAtEnd()) {
			char c = advance();
			if (c == quote) {
				return new Token(TokenType.STRING, sb.toString(), sb.toString(), startLine, startCol);
			}
			if (c == '\\') {
				if (isAtEnd()) break;
				char esc = advance();
				switch (esc) {
					case 'n': sb.append('\n'); break;
					case 't': sb.append('\t'); break;
					case 'r': sb.append('\r'); break;
					case 'b': sb.append('\b'); break;
					case 'f': sb.append('\f'); break;
					case '\\': sb.append('\\'); break;
					case '\'': sb.append('\''); break;
					case '"': sb.append('"'); break;
					default: sb.append(esc); break;
				}
			} else {
				sb.append(c);
			}
		}
		throw new RuntimeException("Unterminated string starting at line " + startLine + ":" + startCol);
	}

	private Token scanNumber(int startLine, int startCol) {
		int start = cursor;
		while (!isAtEnd() && isDigit(peek())) advance();

		if (!isAtEnd() && peek() == '.' && cursor + 1 < length && isDigit(source.charAt(cursor + 1))) {
			advance(); // consume '.'
			while (!isAtEnd() && isDigit(peek())) advance();
		}

		String text = source.substring(start, cursor);
		double val = Double.parseDouble(text);
		return new Token(TokenType.NUMBER, text, val, startLine, startCol);
	}

	private Token scanIdentifierOrKeyword(int startLine, int startCol) {
		int start = cursor;
		while (!isAtEnd() && (isAlphaNumeric(peek()) || peek() == '_' || peek() == '$')) {
			advance();
		}
		String text = source.substring(start, cursor);
		TokenType type = KEYWORDS.get(text);
		if (type != null) {
			Object val = null;
			if (type == TokenType.TRUE) val = Boolean.TRUE;
			else if (type == TokenType.FALSE) val = Boolean.FALSE;
			return new Token(type, text, val, startLine, startCol);
		}
		return new Token(TokenType.IDENTIFIER, text, text, startLine, startCol);
	}

	private void skipWhitespaceAndComments() {
		while (!isAtEnd()) {
			char c = peek();
			if (c == ' ' || c == '\t' || c == '\r') {
				advance();
			} else if (c == '\n') {
				advance();
				line++;
				column = 1;
			} else if (c == '/' && cursor + 1 < length && source.charAt(cursor + 1) == '/') {
				// 单行注释 //
				while (!isAtEnd() && peek() != '\n') advance();
			} else if (c == '/' && cursor + 1 < length && source.charAt(cursor + 1) == '*') {
				// 多行注释 /* */
				advance(); advance();
				while (!isAtEnd()) {
					if (peek() == '\n') { line++; column = 1; }
					if (peek() == '*' && cursor + 1 < length && source.charAt(cursor + 1) == '/') {
						advance(); advance();
						break;
					}
					advance();
				}
			} else {
				break;
			}
		}
	}

	private boolean isAtEnd() {
		return cursor >= length;
	}

	private char peek() {
		return source.charAt(cursor);
	}

	private char advance() {
		char c = source.charAt(cursor++);
		column++;
		return c;
	}

	private boolean match(char expected) {
		if (isAtEnd() || source.charAt(cursor) != expected) return false;
		cursor++;
		column++;
		return true;
	}

	private boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private boolean isAlpha(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	private boolean isAlphaNumeric(char c) {
		return isAlpha(c) || isDigit(c);
	}
}
