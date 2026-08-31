package hope.magic.js.ast;

public enum TokenType {
	// 关键字
	VAR, LET, CONST, FUNCTION, RETURN, IF, ELSE, WHILE, FOR, NEW, THIS,
	BREAK, CONTINUE,
	TRUE, FALSE, NULL, UNDEFINED,

	// 字面量与标识符
	NUMBER, STRING, IDENTIFIER,

	// 算术与位运算符
	PLUS,           // +
	MINUS,          // -
	STAR,           // *
	SLASH,          // /
	PERCENT,        // %
	PLUS_PLUS,      // ++
	MINUS_MINUS,    // --

	// 比较与逻辑运算符
	EQ,             // ==
	EQ_EQ,          // ===
	NOT_EQ,         // !=
	NOT_EQ_EQ,      // !==
	LT,             // <
	LTE,            // <=
	GT,             // >
	GTE,            // >=
	AND,            // &&
	OR,             // ||
	NOT,            // !

	// 赋值运算符
	ASSIGN,         // =
	PLUS_ASSIGN,    // +=
	MINUS_ASSIGN,   // -=
	STAR_ASSIGN,    // *=
	SLASH_ASSIGN,   // /=

	// 分隔符与标点
	DOT,            // .
	COMMA,          // ,
	COLON,          // :
	SEMICOLON,      // ;
	QUESTION,       // ?
	LPAREN,         // (
	RPAREN,         // )
	LBRACE,         // {
	RBRACE,         // }
	LBRACKET,       // [
	RBRACKET,       // ]

	EOF
}
