package modtools.ui.comp.input.highlight;

import arc.graphics.Color;
import arc.struct.*;
import modtools.Constants;
import modtools.Constants.RHINO;
import modtools.jsfunc.IScript;
import modtools.jsfunc.reflect.UNSAFE;
import rhino.*;

import java.lang.reflect.Method;
import java.util.*;

@SuppressWarnings("StringTemplateMigration")
public class JSSyntax extends Syntax {
	public static Color
	 c_constants = new Color(/*0x39C8B0FF*/0x4FC1FFFF),
	// 常规变量
	c_localvar = new Color(0x7CDCFEFF)
	 // , __defalutColor__ = new Color(0xDCDCAAFF)
	 ;

	public Scriptable customScope;
	ObjectSet<String> customConstantSet,
	 customVarSet;
	public JSSyntax(SyntaxDrawable drawable) {
		this(drawable, null);
	}
	public JSSyntax(SyntaxDrawable drawable, Scriptable customScope) {
		super(drawable);
		this.customScope = customScope;
		if (customScope == null || customScope == scope) {
			customConstantSet = constantSet;
			customVarSet = varSet;
		} else {
			customConstantSet = new ScopeObjectSet(customScope);
			customConstantSet.addAll(constantSet);
			customVarSet = new ObjectSet<>(varSet);
			addValueToSet(customScope, customVarSet, customConstantSet, false);
		}
		TOKEN_MAP.putAll(
		 customConstantSet, c_constants,
		 customVarSet, c_localvar
		);
	}

	public static ImporterTopLevel scope = (ImporterTopLevel) IScript.scope;

	public static final ObjectSet<String> constantSet = new ScopeObjectSet(scope);
	public static final ObjectSet<String> varSet      = new ObjectSet<>();

	private static final NativeJavaObject NJO = new NativeJavaObject();

	public Scriptable cursorObj = null;

	static {
		varSet.addAll("arguments", "Infinity", "Packages");
		addValueToSet(scope, varSet, constantSet, true);
	}

	private static void addValueToSet(
	 Scriptable scope, ObjectSet<String> varSet, ObjectSet<String> constantSet,
	 boolean searchParent) {
		do {
			for (Object id : scope.getIds()) {
				if (!(id instanceof String key)) continue;
				try {
					ScriptableObject.putProperty(scope, key,
					 ScriptableObject.getProperty(scope, key));
					varSet.add(key);
				} catch (RuntimeException ignored) {
					constantSet.add(key);
				}
			}
			if (!searchParent || scope.getParentScope() == null) break;
			scope = scope.getParentScope();
		} while (true);
	}

	// 根据代码，解析出的变量
	ObjectSet<String> localVars = new ObjectSet<>(), localConstants = new ObjectSet<>();

	ObjectMap<ObjectSet<String>, Color> TOKEN_MAP = ObjectMap.of(
	 ObjectSet.with(
		"break", "case", "catch", "const", "continue",
		"default", "delete", "do", "else",
		"finally", "for", "function", "if", "in",
		"instanceof", "let", "new", "of", "return", "switch",
		"this", "throw", "try", "typeof", "var",
		"void", "while", "with",
		"yield"
	 ), c_keyword,
	 ObjectSet.with("null", "undefined", "true", "false"), c_keyword,
	 localVars, c_localvar,
	 localConstants, c_constants
	);

	ObjectSet<String> localKeywords = ObjectSet.with("let", "var");


	protected final DrawSymbol
	 operatesSymbol = new DrawSymbol(operates, c_operateChar),
	 bracketsSymbol = new DrawBracket();

	public Object getPropOrNotFound(Scriptable scope, String key) {
		try {
			return ScriptableObject.getProperty(scope, key);
		} catch (Throwable e) {
			return Scriptable.NOT_FOUND;
		}
	}

	public NativeJavaPackage pkg = null;
	public Scriptable        obj = null;

	public boolean enableJSProp = false;

	public TokenDraw[] tokenDraws = {
	 task -> {
		 String token = task.token;
		 if (lastTask == operatesSymbol && operatesSymbol.lastSymbol != '\0') {
			 if (operatesSymbol.lastSymbol == '.') {
				 return dealJSProp(token);
			 }
		 }
		 obj = null;
		 pkg = null;

		 for (var entry : TOKEN_MAP) {
			 if (!entry.key.contains(token)) continue;
			 if (!enableJSProp
			     || !(entry.key == customConstantSet || entry.key == customVarSet)
			     || obj != null) {
				 obj = Undefined.SCRIPTABLE_UNDEFINED;
				 return entry.value;
			 }

			 resolveToken(customScope, token);
			 return entry.value;
		 }
		 obj = Undefined.SCRIPTABLE_UNDEFINED;
		 if (lastTask != task) return null;
		 String lastToken = task.lastToken;
		 if (lastToken != null && localKeywords.contains(lastToken)) {
			 localVars.add(token);
			 return c_localvar;
		 }
		 if ("const".equals(lastToken)) {
			 localConstants.add(token);
			 return c_constants;
		 }

		 return null;
	 },
	 task -> "function".equals(task.lastToken) && lastTask == task ? c_functions : null,
	 task -> {
		 String s = operatesSymbol.lastSymbol != '\0' && operatesSymbol.lastSymbol == '.' && task.token.charAt(0) == 'e' && task.lastToken != null ? task.lastToken + "." + task.token : task.token;
		 return "NaN".equals(s) || !ScriptRuntime.isNaN(ScriptRuntime.toNumber(s)) ? c_number : null;
	 }
	};
	private void resolveToken(Scriptable scope, String token) {
		Object o = ScriptableObject.getProperty(scope, token);
		if (o instanceof NativeJavaPackage newPkg) {
			pkg = newPkg;
			obj = null;
		} else if (o instanceof Scriptable newObj) {
			pkg = null;
			obj = newObj;
		}
		setCursorObj();
	}
	private void setCursorObj() {
		if (drawable != null && drawToken.lastIndex <= drawable.cursor()) {
			cursorObj = obj;
		}
	}


	HashMap<Object, HashMap<String, Object>> js_prop_map = new HashMap<>();
	private Color dealJSProp(String token) {
		if (!enableJSProp) return null;
		if (obj == Undefined.SCRIPTABLE_UNDEFINED) return null;

		Object o = pkg == null && obj instanceof NativeJavaObject nja ?
		 js_prop_map.computeIfAbsent(nja.unwrap(), _ -> new HashMap<>())
			.computeIfAbsent(token, _ -> getPropOrNotFound(nja, token))
		 : getPropOrNotFound(pkg, token);
		if (o == Scriptable.NOT_FOUND && obj != NJO) {
			obj = null;
			return c_error;
		}
		if (o instanceof NativeJavaPackage) {
			pkg = (NativeJavaPackage) o;
		} else if (o instanceof Scriptable sc) {
			pkg = null;
			obj = sc;
			setCursorObj();
			return c_localvar;
		}
		obj = null;
		return null;
	}

	private final DrawTask[] taskArr0 = {
	 new DrawString(c_string),
	 bracketsSymbol,
	 new DrawComment(c_comment),
	 operatesSymbol,
	 drawToken = new JSDrawToken(),
	 };

	{ taskArr = taskArr0; }

	public static IntSet operates = new IntSet();
	public static IntSet brackets = new IntSet();

	static {
		for (char c : "~|,+-=*/<>!%^&;.:?".toCharArray()) {
			operates.add(c);
		}
		for (char c : "()[]{}".toCharArray()) {
			brackets.add(c);
		}
	}
	private static class ScopeObjectSet extends ObjectSet<String> {
		Scriptable scope;
		public ScopeObjectSet(Scriptable scope) {
			this.scope = scope;
		}
		public boolean contains(String key) {
			boolean contains = super.contains(key);
			if (contains) return true;
			char c = key.charAt(0);
			if (!(('A' <= c && c <= 'Z') || c == '$')) return false;
			Object o = scope.get(key, scope);
			if (o == Scriptable.NOT_FOUND) return false;

			add(key);
			return true;
		}
	}
	private class DrawBracket extends DrawSymbol {
		static final IntIntMap leftBracket = IntIntMap.of(
		 '(', ')',
		 '[', ']',
		 '{', '}');

		final Color textColor = c_brackets;

		final IntSeq stack = new IntSeq();

		int surpassSize = 0;
		public DrawBracket() { super(JSSyntax.brackets, new Color()); }
		void init() {
			super.init();
			surpassSize = hasChanged ? 0 : stack.size;
			stack.clear();
		}
		// ['(1', '(2', '2)' ] -> [ '(1' ]
		@Override
		boolean draw(int i) {
			if (!super.draw(i)) return false;
			if (leftBracket.containsKey(c)) {
				color.set(stack.size < surpassSize ? c_error : textColor);
				stack.add(c);
			} else if (stack.isEmpty()) {
				color.set(c_error);
			} else if (leftBracket.get(stack.peek()) == c) {
				stack.pop();
				color.set(textColor);
			} else {
				color.set(c_error);
			}
			forToken(i);
			return true;
		}
		private void forToken(int i) {
			if (c == '(') {
				((JSDrawToken) drawToken).stack.add(obj);
			}
			if (c == ')') {
				obj = ((JSDrawToken) drawToken).stack.pop();
				if (obj != null) switch (obj) {
					case NativeJavaMethod m -> {
						Object[] o      = (Object[]) UNSAFE.getObject(m, RHINO.methods);
						Method   method = (Method) UNSAFE.getObject(o[0], RHINO.memberObject);
						Constants.iv(RHINO.initNativeJavaObject, NJO, customScope, null, method.getReturnType());
						obj = NJO;
						setCursorObj();
					}
					default -> { }
				}
			}
		}
		{
			color.set(textColor);
		}
	}
	private class JSDrawToken extends DrawToken {
		public JSDrawToken() { super(JSSyntax.this.tokenDraws); }
		final Stack<Scriptable> stack = new Stack<>();
		void init() {
			super.init();
			stack.clear();
			pkg = null;
			obj = null;
			cursorObj = null;
			localVars.clear();
			localConstants.clear();
		}
	}
}
