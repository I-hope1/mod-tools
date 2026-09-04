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
		KEYWORDS.put("of", TokenType.OF);
		KEYWORDS.put("in", TokenType.IN);
		KEYWORDS.put("break", TokenType.BREAK);
		KEYWORDS.put("continue", TokenType.CONTINUE);
		KEYWORDS.put("typeof", TokenType.TYPEOF);
		KEYWORDS.put("void", TokenType.VOID);
		KEYWORDS.put("delete", TokenType.DELETE);
		KEYWORDS.put("throw", TokenType.THROW);
		KEYWORDS.put("try", TokenType.TRY);
		KEYWORDS.put("catch", TokenType.CATCH);
		KEYWORDS.put("finally", TokenType.FINALLY);
		KEYWORDS.put("do", TokenType.DO);
		KEYWORDS.put("switch", TokenType.SWITCH);
		KEYWORDS.put("case", TokenType.CASE);
		KEYWORDS.put("default", TokenType.DEFAULT);
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
				case '.':
					if (cursor < length && source.charAt(cursor) == '.' && cursor + 1 < length && source.charAt(cursor + 1) == '.') {
						advance();
						advance();
						tokens.add(new Token(TokenType.ELLIPSIS, "...", null, startLine, startCol));
					} else if (cursor < length && isDigit(source.charAt(cursor))) {
						cursor--;
						column--;
						tokens.add(scanNumber(startLine, startCol));
					} else {
						tokens.add(new Token(TokenType.DOT, ".", null, startLine, startCol));
					}
					break;
				case ';': tokens.add(new Token(TokenType.SEMICOLON, ";", null, startLine, startCol)); break;
				case ':': tokens.add(new Token(TokenType.COLON, ":", null, startLine, startCol)); break;
				case '?': tokens.add(new Token(TokenType.QUESTION, "?", null, startLine, startCol)); break;
				case '%':
					if (match('=')) tokens.add(new Token(TokenType.PERCENT_ASSIGN, "%=", null, startLine, startCol));
					else tokens.add(new Token(TokenType.PERCENT, "%", null, startLine, startCol));
					break;

				case '^':
					if (match('=')) tokens.add(new Token(TokenType.BIT_XOR_ASSIGN, "^=", null, startLine, startCol));
					else tokens.add(new Token(TokenType.BIT_XOR, "^", null, startLine, startCol));
					break;

				case '~':
					tokens.add(new Token(TokenType.BIT_NOT, "~", null, startLine, startCol));
					break;

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
					if (isRegExpContext(tokens)) {
						tokens.add(scanRegExp(startLine, startCol));
					} else if (match('=')) {
						tokens.add(new Token(TokenType.SLASH_ASSIGN, "/=", null, startLine, startCol));
					} else {
						tokens.add(new Token(TokenType.SLASH, "/", null, startLine, startCol));
					}
					break;

				case '=':
					if (match('=')) {
						if (match('=')) tokens.add(new Token(TokenType.EQ_EQ, "===", null, startLine, startCol));
						else tokens.add(new Token(TokenType.EQ, "==", null, startLine, startCol));
					} else if (match('>')) {
						tokens.add(new Token(TokenType.ARROW, "=>", null, startLine, startCol));
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
					else if (match('<')) {
						if (match('=')) tokens.add(new Token(TokenType.SHL_ASSIGN, "<<=", null, startLine, startCol));
						else tokens.add(new Token(TokenType.SHL, "<<", null, startLine, startCol));
					}
					else tokens.add(new Token(TokenType.LT, "<", null, startLine, startCol));
					break;

				case '>':
					if (match('=')) tokens.add(new Token(TokenType.GTE, ">=", null, startLine, startCol));
					else if (match('>')) {
						if (match('>')) {
							if (match('=')) tokens.add(new Token(TokenType.USHR_ASSIGN, ">>>=", null, startLine, startCol));
							else tokens.add(new Token(TokenType.USHR, ">>>", null, startLine, startCol));
						} else if (match('=')) {
							tokens.add(new Token(TokenType.SHR_ASSIGN, ">>=", null, startLine, startCol));
						} else {
							tokens.add(new Token(TokenType.SHR, ">>", null, startLine, startCol));
						}
					}
					else tokens.add(new Token(TokenType.GT, ">", null, startLine, startCol));
					break;

				case '&':
					if (match('&')) tokens.add(new Token(TokenType.AND, "&&", null, startLine, startCol));
					else if (match('=')) tokens.add(new Token(TokenType.BIT_AND_ASSIGN, "&=", null, startLine, startCol));
					else tokens.add(new Token(TokenType.BIT_AND, "&", null, startLine, startCol));
					break;

				case '|':
					if (match('|')) tokens.add(new Token(TokenType.OR, "||", null, startLine, startCol));
					else if (match('=')) tokens.add(new Token(TokenType.BIT_OR_ASSIGN, "|=", null, startLine, startCol));
					else tokens.add(new Token(TokenType.BIT_OR, "|", null, startLine, startCol));
					break;

				case '"':
				case '\'':
					tokens.add(scanString(c, startLine, startCol));
					break;

				case '`':
					tokens.add(scanTemplateString(startLine, startCol));
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
					case '0': sb.append('\0'); break;
					case '\\': sb.append('\\'); break;
					case '\'': sb.append('\''); break;
					case '"': sb.append('"'); break;
					case '`': sb.append('`'); break;
					case 'u': {
						if (cursor + 4 <= length) {
							String hex = source.substring(cursor, cursor + 4);
							try {
								int code = Integer.parseInt(hex, 16);
								sb.append((char) code);
								cursor += 4;
								column += 4;
							} catch (NumberFormatException e) {
								sb.append('u');
							}
						} else {
							sb.append('u');
						}
						break;
					}
					case 'x': {
						if (cursor + 2 <= length) {
							String hex = source.substring(cursor, cursor + 2);
							try {
								int code = Integer.parseInt(hex, 16);
								sb.append((char) code);
								cursor += 2;
								column += 2;
							} catch (NumberFormatException e) {
								sb.append('x');
							}
						} else {
							sb.append('x');
						}
						break;
					}
					default: sb.append(esc); break;
				}
			} else {
				sb.append(c);
			}
		}
		throw new RuntimeException("Unterminated string starting at line " + startLine + ":" + startCol);
	}

	private Token scanTemplateString(int startLine, int startCol) {
		StringBuilder sb = new StringBuilder();
		while (!isAtEnd()) {
			char c = advance();
			if (c == '`') {
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
					case '0': sb.append('\0'); break;
					case '\\': sb.append('\\'); break;
					case '`': sb.append('`'); break;
					case '$': sb.append('$'); break;
					case 'u': {
						if (cursor + 4 <= length) {
							String hex = source.substring(cursor, cursor + 4);
							try {
								int code = Integer.parseInt(hex, 16);
								sb.append((char) code);
								cursor += 4;
								column += 4;
							} catch (NumberFormatException e) {
								sb.append('u');
							}
						} else {
							sb.append('u');
						}
						break;
					}
					case 'x': {
						if (cursor + 2 <= length) {
							String hex = source.substring(cursor, cursor + 2);
							try {
								int code = Integer.parseInt(hex, 16);
								sb.append((char) code);
								cursor += 2;
								column += 2;
							} catch (NumberFormatException e) {
								sb.append('x');
							}
						} else {
							sb.append('x');
						}
						break;
					}
					default: sb.append(esc); break;
				}
			} else {
				sb.append(c);
			}
		}
		throw new RuntimeException("Unterminated template string starting at line " + startLine + ":" + startCol);
	}

	private Token scanNumber(int startLine, int startCol) {
		int start = cursor;
		if (source.charAt(cursor) == '0' && cursor + 1 < length) {
			char next = source.charAt(cursor + 1);
			if (next == 'x' || next == 'X') {
				advance(); advance(); // consume 0x
				int hexStart = cursor;
				while (!isAtEnd() && isHexDigit(peek())) advance();
				String hex = source.substring(hexStart, cursor);
				double val = 0.0;
				try {
					val = (double) Long.parseUnsignedLong(hex, 16);
				} catch (Exception ignored) {}
				return new Token(TokenType.NUMBER, source.substring(start, cursor), val, startLine, startCol);
			} else if (next == 'b' || next == 'B') {
				advance(); advance(); // consume 0b
				int binStart = cursor;
				while (!isAtEnd() && (peek() == '0' || peek() == '1')) advance();
				String bin = source.substring(binStart, cursor);
				double val = 0.0;
				try {
					val = (double) Long.parseUnsignedLong(bin, 2);
				} catch (Exception ignored) {}
				return new Token(TokenType.NUMBER, source.substring(start, cursor), val, startLine, startCol);
			} else if (next == 'o' || next == 'O') {
				advance(); advance(); // consume 0o
				int octStart = cursor;
				while (!isAtEnd() && peek() >= '0' && peek() <= '7') advance();
				String oct = source.substring(octStart, cursor);
				double val = 0.0;
				try {
					val = (double) Long.parseUnsignedLong(oct, 8);
				} catch (Exception ignored) {}
				return new Token(TokenType.NUMBER, source.substring(start, cursor), val, startLine, startCol);
			}
		}

		while (!isAtEnd() && isDigit(peek())) advance();

		if (!isAtEnd() && peek() == '.' && cursor + 1 < length && isDigit(source.charAt(cursor + 1))) {
			advance(); // consume '.'
			while (!isAtEnd() && isDigit(peek())) advance();
		}

		if (!isAtEnd() && (peek() == 'e' || peek() == 'E')) {
			advance(); // consume 'e'
			if (!isAtEnd() && (peek() == '+' || peek() == '-')) {
				advance();
			}
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
		String sym = hope.magic.js.runtime.SymbolTable.symbol(text);
		return new Token(TokenType.IDENTIFIER, sym, sym, startLine, startCol);
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

	private boolean isRegExpContext(List<Token> tokens) {
		if (tokens.isEmpty()) return true;
		Token last = tokens.get(tokens.size() - 1);
		if (last.type == TokenType.RPAREN) {
			int depth = 0;
			for (int i = tokens.size() - 1; i >= 0; i--) {
				TokenType t = tokens.get(i).type;
				if (t == TokenType.RPAREN) depth++;
				else if (t == TokenType.LPAREN) {
					depth--;
					if (depth == 0) {
						if (i > 0) {
							TokenType before = tokens.get(i - 1).type;
							if (before == TokenType.IF || before == TokenType.WHILE || before == TokenType.FOR) {
								return true;
							}
						}
						break;
					}
				}
			}
			return false;
		}
		if (last.type == TokenType.RBRACE) {
			return true;
		}
		return switch (last.type) {
			case IDENTIFIER, NUMBER, STRING, REGEXP,
			     TRUE, FALSE, NULL, UNDEFINED, THIS,
			     RBRACKET,
			     PLUS_PLUS, MINUS_MINUS -> false;
			default -> true;
		};
	}

	private Token scanRegExp(int startLine, int startCol) {
		StringBuilder pattern = new StringBuilder();
		boolean inClass = false;
		while (!isAtEnd()) {
			char c = advance();
			if (c == '\\') {
				if (isAtEnd()) break;
				pattern.append('\\');
				pattern.append(advance());
			} else if (c == '[') {
				inClass = true;
				pattern.append(c);
			} else if (c == ']') {
				inClass = false;
				pattern.append(c);
			} else if (c == '/' && !inClass) {
				StringBuilder flags = new StringBuilder();
				while (!isAtEnd() && isAlpha(peek())) {
					flags.append(advance());
				}
				String raw = "/" + pattern + "/" + flags;
				return new Token(TokenType.REGEXP, raw, new String[]{pattern.toString(), flags.toString()}, startLine, startCol);
			} else if (c == '\n') {
				break;
			} else {
				pattern.append(c);
			}
		}
		throw new RuntimeException("Unterminated regular expression literal at line " + startLine + ":" + startCol);
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

	private boolean isHexDigit(char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}

	private boolean isAlpha(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	private boolean isAlphaNumeric(char c) {
		return isAlpha(c) || isDigit(c);
	}
}
