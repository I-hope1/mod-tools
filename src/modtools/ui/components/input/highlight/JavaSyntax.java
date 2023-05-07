package modtools.ui.components.input.highlight;

import arc.graphics.Color;
import arc.scene.style.TextureRegionDrawable;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.gen.Tex;
import modtools.ui.components.input.area.TextAreaTable;
import rhino.*;

import java.lang.reflect.Method;
import java.util.Objects;

import static modtools.ui.components.input.highlight.JSSyntax.*;
import static modtools.utils.Tools.*;

public class JavaSyntax extends Syntax {
	public JavaSyntax(TextAreaTable table) {
		super(table);
		// defalutColor = __defalutColor__;
		area.getStyle().selection = ((TextureRegionDrawable) Tex.selection).tint(Tmp.c1.set(0x4763FFFF));
	}

	ObjectMap<ObjectSet<String>, Color> TOKEN_MAP = ObjectMap.of(
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
			), keywordC,
			ObjectSet.with("null", "true", "false"), keywordC
			, constantSet, constants
	);
	protected final DrawSymbol
			operatesSymbol = new DrawSymbol(operates, operatCharC),
			bracketsSymbol = new DrawSymbol(brackets, bracketsC);

	public        TokenDraw[] tokenDraws = {/* tokon map */task -> {
		if (lastTask == operatesSymbol && operatesSymbol.lastSymbol != null && operatesSymbol.lastSymbol == '.') {
			return null;
		}
		for (var entry : TOKEN_MAP) {
			if (entry.key.contains(task.token)) {
				return entry.value;
			}
		}
		return null;
	}, task -> {
		String token = sr(task.token.charAt(task.token.length() - 1)).get(t ->
				t == 'F' || t == 'f' || t == 'l' || t == 'L'
						? task.token.substring(0, task.token.length() - 1) : task.token);
		String s = operatesSymbol.lastSymbol != null && operatesSymbol.lastSymbol == '.'
		           && task.token.charAt(0) == 'e' && task.lastToken != null
				? task.lastToken + "." + token : token;
		return ScriptRuntime.isNaN(ScriptRuntime.toNumber(s)) ? null : numberC;
	}};
	private final DrawTask[]  taskArr0   = {
			new DrawString(stringC),
			bracketsSymbol,
			new DrawComment(commentC),
			operatesSymbol,
			// new DrawWord(keywordMap, keywordC),
			// new DrawWord(objectMap, objectsC),
			new DrawToken(tokenDraws),
			// new DrawNumber(numberC),
	};

	{
		taskArr = taskArr0;
	}


	public static IntSet operates = new IntSet();
	public static IntSet brackets = new IntSet();

	static {
		String s;
		s = "~|,+-=*/<>!%^&;.:";
		for (int i = 0, len = s.length(); i < len; i++) {
			operates.add(s.charAt(i));
		}
		s = "()[]{}";
		for (int i = 0, len = s.length(); i < len; i++) {
			brackets.add(s.charAt(i));
		}
	}
}
