package modtools.ui.comp.input.highlight;

import arc.graphics.Color;
import arc.struct.*;
import arc.util.Log;
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

		if (customScope == null || customScope == topScope) {
			customConstantSet = constantSet;
			customVarSet = varSet;
		} else {
			customConstantSet = new ScopeObjectSet(customScope);
			customVarSet = new WithObjectSet(varSet);
			addValueToSet(customScope, customVarSet, customConstantSet, false);
		}
		TOKEN_MAP.putAll(
		 customConstantSet, c_constants,
		 customVarSet, c_localvar
		);
	}

	public static ImporterTopLevel topScope = (ImporterTopLevel) IScript.scope;

	public static final ObjectSet<String> constantSet = new ScopeObjectSet(topScope);
	public static final ObjectSet<String> varSet      = new ObjectSet<>();

	private static final NativeJavaObject NJO = new NativeJavaObject();

	public Scriptable cursorObj = null;

	static {
		varSet.addAll("arguments", "Infinity");
		addValueToSet(topScope, varSet, constantSet, true);
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
	final ObjectSet<String> localVars = new ObjectSet<>(), localConstants = new ObjectSet<>();

	private final ObjectMap<ObjectSet<String>, Color> TOKEN_MAP = OrderedMap.of(
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
	 operatesSymbol = new JSDrawSymbol(),
	 bracketsSymbol = new DrawBracket();

	public static Object getPropOrNotFound(Scriptable scope, String key) {
		Object o;
		do {
			try {
				o = ScriptableObject.getProperty(scope, key);
				if (o != Scriptable.NOT_FOUND) return o;
			} catch (Throwable e) {
				return Scriptable.NOT_FOUND;
			}
			scope = scope.getParentScope();
		} while (scope != null);
		return Scriptable.NOT_FOUND;
	}

	public NativeJavaPackage pkg = null;
	public Scriptable        obj = null;

	public boolean js_prop = false;

	public TokenDraw[] tokenDraws = {
	 task -> {
		 String token = task.token;

		 if ((obj != null || pkg != null) && lastTask == operatesSymbol && operatesSymbol.lastSymbol != '\0') {
			 if (operatesSymbol.lastSymbol == '.' && !((JSDrawSymbol) operatesSymbol).error) {
				 return dealJSProp(token);
			 }
		 }
		 obj = null;
		 pkg = null;

		 for (var entry : TOKEN_MAP) {
			 if (!entry.key.contains(token)) continue;
			 Log.info("Contains: " + entry);

			 if (!(js_prop
			       && (entry.key == customConstantSet || entry.key == customVarSet)
			       && obj == null)) {
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
		Object o = getPropOrNotFound(scope, token);
		if (o instanceof NativeJavaPackage newPkg) {
			pkg = newPkg;
			obj = null;
		} else if (o instanceof Scriptable newObj) {
			pkg = null;
			obj = newObj;
		}
		setCursorObj(drawToken.lastIndex);
	}
	int lastCursorObjIndex = -2;
	private void setCursorObj(int lastIndex) {
		if (drawable != null && lastCursorObjIndex < lastIndex && lastIndex <= drawable.cursor()) {
			lastCursorObjIndex = lastIndex;
			cursorObj = obj;
		}
	}


	HashMap<Object, HashMap<String, Object>> js_prop_map = new HashMap<>();
	private Color dealJSProp(String token) {
		if (!js_prop) return null;
		if (obj == Undefined.SCRIPTABLE_UNDEFINED) return null;

		Object o = pkg == null && obj instanceof NativeJavaObject nja ?
		 js_prop_map.computeIfAbsent(nja instanceof NativeJavaClass ? Class.class : nja.unwrap(), _ -> new HashMap<>())
			.computeIfAbsent(token, _ -> getPropOrNotFound(nja, token))
		 : getPropOrNotFound(pkg != null ? pkg : obj, token);
		if (o == Scriptable.NOT_FOUND && obj != NJO && pkg == null) {
			obj = null;
			return c_error;
		}
		if (o instanceof NativeJavaPackage) {
			pkg = (NativeJavaPackage) o;
		} else if (o instanceof Scriptable sc) {
			pkg = null;
			obj = sc;
			setCursorObj(drawToken.lastIndex);
			return c_localvar;
		}
		obj = null;
		return null;
	}

	private final DrawTask[] taskArr0 = {
	 new DrawString(c_string) {
		 /** @see NativeString  */
		 public static final Scriptable STR = Context.toObject("", IScript.scope);
		 public void drawText(int i) {
			 super.drawText(i);
			 obj = STR;
			 setCursorObj(lastIndex);
		 }
	 },
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
		public int hashCode() {
			return scope.hashCode();
		}
		public boolean contains(String key) {
			boolean contains = super.contains(key);
			if (contains) return true;
			char c = key.charAt(0);
			if (!Character.isJavaIdentifierStart(c)) return false;
			Object o = getPropOrNotFound(scope, key);
			if (o == Scriptable.NOT_FOUND) return false;

			add(key);
			return true;
		}
	}
	private static class WithObjectSet extends ObjectSet<String> {
		final ObjectSet<String> with;
		public WithObjectSet(ObjectSet<String> with) {
			this.with = with;
		}
		public int hashCode() {
			return with.hashCode() + super.hashCode();
		}
		public String get(String key) {
			String s = super.get(key);
			return s == null ? with.get(key) : s;
		}
		public boolean contains(String key) {
			return super.contains(key) || with.contains(key);
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
		private final IntSeq lastIndexStack = new IntSeq();
		private void forToken(int i) {
			if (c == '(' && obj != null) {
				((JSDrawToken) drawToken).stack.add(obj);
				lastIndexStack.add(JSSyntax.this.drawToken.lastIndex);
			}
			if (c == ')' && !((JSDrawToken) drawToken).stack.isEmpty()) {
				obj = ((JSDrawToken) drawToken).stack.pop();
				int index = lastIndexStack.pop();
				l:
				if (obj instanceof NativeJavaMethod m) {
					Object[] o      = (Object[]) UNSAFE.getObject(m, RHINO.methods);
					Method   method = (Method) UNSAFE.getObject(o[0], RHINO.memberObject);
					if (method.getReturnType() == void.class) break l;
					Constants.iv(RHINO.initNativeJavaObject, NJO, customScope, null, method.getReturnType());
					obj = NJO;
				} else {
					obj = Undefined.SCRIPTABLE_UNDEFINED;
				}
				setCursorObj(index);
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
			lastCursorObjIndex = -2;
			cursorObj = null;
			localVars.clear();
			localConstants.clear();
		}
	}
	private class JSDrawSymbol extends DrawSymbol {
		boolean error;
		public JSDrawSymbol() { super(JSSyntax.operates, Syntax.c_operateChar); }
		void reset() {
			super.reset();
			error = false;
		}
		public void drawText(int i) {
			drawText0(error ? c_error : color, lastIndex, i + 1);
			lastIndex = i + 1;
		}
		boolean draw(int i) {
			if (super.draw(i)) {
				error = lastSymbol == '.' && lastTask == this && c == '.';
				if (error) {
					obj = Undefined.SCRIPTABLE_UNDEFINED;
					setCursorObj(i);
				}
				return true;
			}
			return false;
		}
	}
}
