package modtools.ui.components.input.highlight;

import arc.graphics.Color;
import arc.scene.style.TextureRegionDrawable;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.gen.Tex;
import modtools.ui.IntUI;
import modtools.ui.components.input.area.TextAreaTable;
import rhino.*;

import java.lang.reflect.Method;
import java.util.*;

import static modtools.utils.Tools.invoke;

public class JSSyntax extends Syntax {

	public static Color
			constants = new Color(/*0x39C8B0FF*/0x4FC1FFFF),
	// 常规变量
	defVarColor = new Color(0x7CDCFEFF)
			// , __defalutColor__ = new Color(0xDCDCAAFF)
			;

	public JSSyntax(TextAreaTable table) {
		super(table);
		// defalutColor = __defalutColor__;
		area.style.selection = ((TextureRegionDrawable) Tex.selection).tint(Tmp.c1.set(0x4763FFFF));
	}

	static ImporterTopLevel scope = (ImporterTopLevel) Vars.mods.getScripts().scope;
	static Method           getPkgs;

	static {
		if (OS.isWindows) {
			try {
				getPkgs = ImporterTopLevel.class.getDeclaredMethod("getPackageProperty", String.class, Scriptable.class);
				getPkgs.setAccessible(true);
			} catch (NoSuchMethodException e) {
				// throw new RuntimeException(e);
			}
		}
	}

	public static final ObjectSet<String> constantSet = new ObjectSet<>() {
		{
			addAll("arguments", "Infinity");
		}

		public boolean contains(String key) {
			boolean val = super.contains(key);
			if (getPkgs != null && !val &&
			    invoke(getPkgs, scope, key, scope) != Scriptable.NOT_FOUND) {
				char c = key.charAt(0);
				if (('A' <= c && c <= 'Z') || c == '$') {
					add(key);
				}
				return true;
			}

			return val;
		}
	};
	public static final ObjectSet<String> defVarSet   = new ObjectSet<>();

	static {
		for (Object id : scope.getIds()) {
			if (!(id instanceof String)) continue;
			String key = (String) id;
			try {
				ScriptableObject.redefineProperty(scope, key, false);
				defVarSet.add(key);
			} catch (RuntimeException ignored) {
				constantSet.add(key);
			}
		}
	}


	ObjectMap<ObjectSet<String>, Color> TOKEN_MAP = ObjectMap.of(
			ObjectSet.with(
					"break", "case", "catch", "const", "continue",
					"default", "delete", "do", "else",
					"finally", "for", "function", "if",
					"instanceof", "let", "new", "return", "switch",
					"this", "throw", "try", "typeof", "var",
					"void", "while", "with",
					"yield"
			), keywordC,
			ObjectSet.with("null", "undefined", "true", "false"), keywordC,
			constantSet, constants,
			defVarSet, defVarColor
	);
	protected final DrawSymbol
			operatsSymbol  = new DrawSymbol(operats, operatCharC),
			bracketsSymbol = new DrawSymbol(brackets, bracketsC);

	public        TokenDraw[] tokenDraws = {task -> {
		if (lastTask == operatsSymbol && operatsSymbol.lastSymbol != null && operatsSymbol.lastSymbol == '.') {
			return null;
		}
		for (var entry : TOKEN_MAP) {
			if (entry.key.contains(task.token)) {
				return entry.value;
			}
		}
		return null;
	}, task -> {
		if (Objects.equals(task.lastToken, "function")
		    && lastTask == task/*中间不能有其他字符*/) {
			return functionsC;
		}
		return null;
	}, task -> {
		String s = operatsSymbol.lastSymbol != null && operatsSymbol.lastSymbol == '.'
		           && task.token.charAt(0) == 'e' && task.lastToken != null
				? task.lastToken + "." + task.token : task.token;
		return ScriptRuntime.isNaN(ScriptRuntime.toNumber(s)) && !s.equals("NaN")
				? null : numberC;
	}};
	private final DrawTask[]  taskArr0   = {
			new DrawString(stringC),
			bracketsSymbol,
			new DrawComment(commentC),
			operatsSymbol,
			// new DrawWord(keywordMap, keywordC),
			// new DrawWord(objectMap, objectsC),
			new DrawToken(tokenDraws),
			// new DrawNumber(numberC),
	};

	{
		taskArr = taskArr0;
	}

	class DrawComment extends DrawTask {

		public DrawComment(Color color) {
			super(color, true);
		}

		@Override
		void reset() {
			super.reset();
			body = false;
			finished = false;
		}

		@Override
		boolean isFinished() {
			return finished;
		}

		private boolean finished, body, multi;

		@Override
		boolean draw(int i) {
			if (body) {
				if (multi ? lastChar == '*' && c == '/' : c == '\n' || i + 1 >= len) {
					finished = true;
				}
				return true;
			}
			if (c == '/' && i + 1 < len) {
				char next = displayText.charAt(i + 1);
				if (next == '*' || next == '/') {
					lastIndex = i;
					multi = next == '*';
					body = true;
					return true;
				}
			}
			return false;
		}
	}

	class DrawString extends DrawTask {

		public DrawString(Color color) {
			super(color, true);
		}

		@Override
		void reset() {
			super.reset();
			leftQuote = rightQuote = false;
		}

		@Override
		boolean isFinished() {
			return rightQuote;
		}

		boolean leftQuote, rightQuote;
		char quote;

		@Override
		boolean draw(int i) {
			if (!leftQuote) {
				if (c == '\'' || c == '"' || c == '`') {
					quote = c;
					leftQuote = true;
					lastIndex = i;
					return true;
				} else {
					return false;
				}
			}
			if (quote == c || (c == '\n' && quote != '`')) {
				rightQuote = true;
				leftQuote = false;
				return true;
			}
			return true;
		}
	}


	static IntSet operats  = new IntSet();
	static IntSet brackets = new IntSet();

	static {
		String s;
		s = "~|,+-=*/<>!%^&;.";
		for (int i = 0, len = s.length(); i < len; i++) {
			operats.add(s.charAt(i));
		}
		s = "()[]{}";
		for (int i = 0, len = s.length(); i < len; i++) {
			brackets.add(s.charAt(i));
		}
	}
}
