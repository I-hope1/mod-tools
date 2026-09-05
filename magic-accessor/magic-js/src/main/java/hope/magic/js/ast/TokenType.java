package hope.magic.js.ast;

public enum TokenType {
	// 关键字
	VAR, LET, CONST, FUNCTION, RETURN, IF, ELSE, WHILE, FOR, OF, IN, NEW, THIS,
	BREAK, CONTINUE,
	TYPEOF, VOID, DELETE, THROW, TRY, CATCH, FINALLY, DO, SWITCH, CASE, DEFAULT,
	CLASS, EXTENDS, SUPER,
	TRUE, FALSE, NULL, UNDEFINED,

	// 字面量与标识符
	NUMBER, STRING, REGEXP, IDENTIFIER,

	// 算术与位运算符
	PLUS,           // +
	MINUS,          // -
	STAR,           // *
	SLASH,          // /
	PERCENT,        // %
	PLUS_PLUS,      // ++
	MINUS_MINUS,    // --
	BIT_AND,        // &
	BIT_OR,         // |
	BIT_XOR,        // ^
	BIT_NOT,        // ~
	SHL,            // <<
	SHR,            // >>
	USHR,           // >>>

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
	PERCENT_ASSIGN, // %=
	BIT_AND_ASSIGN, // &=
	BIT_OR_ASSIGN,  // |=
	BIT_XOR_ASSIGN, // ^=
	SHL_ASSIGN,     // <<=
	SHR_ASSIGN,     // >>=
	USHR_ASSIGN,    // >>>=

	// 分隔符与标点
	ARROW,          // =>
	DOT,            // .
	ELLIPSIS,       // ...
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
