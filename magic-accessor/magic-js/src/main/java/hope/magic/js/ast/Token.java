package hope.magic.js.ast;

public class Token {
	public final TokenType type;
	public final String text;
	public final Object value;
	public final int line;
	public final int column;

	public Token(TokenType type, String text, Object value, int line, int column) {
		this.type = type;
		this.text = text;
		this.value = value;
		this.line = line;
		this.column = column;
	}

	@Override
	public String toString() {
		return "Token(" + type + ", '" + text + "', line=" + line + ":" + column + ")";
	}
}
