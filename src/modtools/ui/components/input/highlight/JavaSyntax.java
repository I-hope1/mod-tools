package modtools.ui.components.input.highlight;

import arc.graphics.Color;
import arc.struct.*;
import rhino.ScriptRuntime;

import static modtools.ui.components.input.highlight.JSSyntax.*;
import static modtools.utils.Tools.Sr;

public class JavaSyntax extends Syntax {
	public JavaSyntax(SyntaxDrawable drawable) {
		super(drawable);
	}

	ObjectMap<ObjectSet<CharSequence>, Color> TOKEN_MAP = ObjectMap.of(
	  ObjectSet.with(
		"final", "public", "private", "protected", "static",
		"interface",
		"package", "class", "extends", "implements",
		"break", "case", "catch", "continue",
		"default", "delete", "do", "else",
		"finally", "for", "function", "if",
		"instanceof", "new", "return", "switch",
		"this", "throw", "throws", "try", "var",
		"void", "while", "with",
		"yield", "import", "volatile", "transient", "native",
		"float", "double", "int", "long", "boolean", "byte", "short", "char"
	  ), c_keyword,
	  ObjectSet.with("null", "true", "false"), c_keyword
	  , constantSet, c_constants
	);
	protected final DrawSymbol
	  operatesSymbol = new DrawSymbol(operates, c_operateChar),
	  bracketsSymbol = new DrawSymbol(brackets, c_brackets);

	public        TokenDraw[] tokenDraws = {/* tokon map */task -> {
		if (lastTask == operatesSymbol && operatesSymbol.lastSymbol != '\0' && operatesSymbol.lastSymbol == '.') {
			return null;
		}
		for (var entry : TOKEN_MAP) {
			if (entry.key.contains(task.token)) {
				return entry.value;
			}
		}
		return null;
	}, task -> {
		CharSequence token = Sr(task.token.charAt(task.token.length() - 1))
		                       .get(t -> t == 'F' || t == 'f' || t == 'l' || t == 'L'
			                               ? task.token.subSequence(0, task.token.length() - 1) : task.token);
		CharSequence s = operatesSymbol.lastSymbol != '\0' && operatesSymbol.lastSymbol == '.'
		                 && task.token.charAt(0) == 'e' && task.lastToken != null
		                   ? task.lastToken + "." + token : token;
		return ScriptRuntime.isNaN(ScriptRuntime.toNumber(s)) ? null : c_number;
	}};
	private final DrawTask[]  taskArr0   = {
	  new DrawString(c_string),
	  bracketsSymbol,
	  new DrawComment(c_comment),
	  operatesSymbol,
	  // new DrawWord(keywordMap, keywordC),
	  // new DrawWord(objectMap, objectsC),
	  drawToken = new DrawToken(tokenDraws),
	  // new DrawNumber(numberC),
	};

	{
		taskArr = taskArr0;
	}


	public static IntSet operates = JSSyntax.operates;
	public static IntSet brackets = JSSyntax.brackets;
}
