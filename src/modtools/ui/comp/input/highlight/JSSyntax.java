package modtools.ui.comp.input.highlight;

import arc.graphics.Color;
import arc.struct.*;
import arc.struct.ObjectMap.Entry;
import arc.util.pooling.Pool.Poolable;
import arc.util.pooling.Pools;
import mindustry.graphics.Pal;
import modtools.Constants;
import modtools.Constants.RHINO;
import modtools.annotations.DebugMark;
import modtools.jsfunc.IScript;
import modtools.jsfunc.reflect.UNSAFE;
import rhino.*;

import java.lang.reflect.Method;
import java.util.*;

import static rhino.Scriptable.NOT_FOUND;

@SuppressWarnings("StringTemplateMigration")
@DebugMark
// @WatchClass(groups = {"aa"})
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
	Seq<ObjectSet<String>> userVarSets = new Seq<>();
	public JSSyntax(SyntaxDrawable drawable) {
		this(drawable, null);
	}
	public JSSyntax(SyntaxDrawable drawable, Scriptable customScope) {
		super(drawable);
		this.customScope = customScope;
		// INFO_DIALOG.showInfo(customScope);

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
		userVarSets.add(customConstantSet);
		userVarSets.add(customVarSet);

		outerTask = new DrawCompletion();
	}

	public static ImporterTopLevel topScope = (ImporterTopLevel) IScript.scope;

	public static final ObjectSet<String> constantSet = new ScopeObjectSet(topScope);
	public static final ObjectSet<String> varSet      = new ObjectSet<>();

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
	ObjectSet<String> constKeywords = ObjectSet.with("const");


	protected final DrawSymbol
	 operatesSymbol = new JSDrawSymbol(),
	 bracketsSymbol = new DrawBracket();

	public static Object getPropOrNotFound(Scriptable scope, String key) {
		Object o;
		do {
			try {
				o = ScriptableObject.getProperty(scope, key);
				if (o != NOT_FOUND) return o;
			} /* catch (EvaluatorException e) {

			}  */ catch (Throwable e) {
				return NOT_FOUND;
			}
			scope = scope.getParentScope();
		} while (scope != null);
		return NOT_FOUND;
	}

	public NativeJavaPackage pkg = null;
	/** 默认为{@link #customScope} */
	public Object            currentObject;

	public boolean js_prop = false;

	private final Integer OBJ_NUMBER = -189021039;

	public TokenDraw[] tokenDraws = {
	 task -> {
		 for (Entry<ObjectSet<String>, Color> entry : TOKEN_MAP) {
			 if (entry.key.contains(task.token)) {
				 if (userVarSets.contains(entry.key)) {
					 if (currentObject != customScope && currentObject != null && operatesSymbol.lastSymbol != '\0') continue;
					 currentObject = getNextObject(task.token);
				 } else {
					 currentObject = null;
				 }
				 updateCursorObj();
				 return entry.value;
			 }
		 }
		 if (!js_prop) return null;

		 if (operatesSymbol.lastSymbol == '\0') {
			 currentObject = customScope;
			 updateCursorObj();
		 }

		 // Log.info(currentObject);
		 // resolveToken(currentObject, task.token);
		 return dealJSProp(task.token);
	 },
	 task -> "function".equals(task.lastToken) && lastTask == task ? c_functions : null,
	 task -> {
		 String  s = operatesSymbol.lastSymbol != '\0' && operatesSymbol.lastSymbol == '.' && task.token.charAt(0) == 'e' && task.lastToken != null ? task.lastToken + "." + task.token : task.token;
		 boolean b = "NaN".equals(s) || !ScriptRuntime.isNaN(ScriptRuntime.toNumber(s));
		 if (b) currentObject = OBJ_NUMBER;
		 return b ? c_number : null;
	 }
	};
	private void updateCursorObj() {
		setCursorObj(drawToken.lastIndex);
	}
	private void resolveToken(Scriptable scope, String token) {
		Object o = getPropOrNotFound(scope, token);
		if (o instanceof NativeJavaPackage newPkg) {
			pkg = newPkg;
			currentObject = customScope;
		} else if (o instanceof Scriptable newObj) {
			pkg = null;
			currentObject = newObj;
		}
		updateCursorObj();
	}
	int lastCursorObjIndex = -1;
	private void setCursorObj(int lastIndex) {
		if (drawable != null && lastCursorObjIndex < lastIndex && lastIndex <= drawable.cursor()) {
			lastCursorObjIndex = lastIndex;
			cursorObj = currentObject instanceof Scriptable sc ? sc : null;
		}
	}

	HashMap<Object, HashMap<String, Object>> js_prop_map = new HashMap<>();
	private Color dealJSProp(String token) {
		if (!js_prop) return null;
		if (currentObject == Undefined.SCRIPTABLE_UNDEFINED || currentObject == NOT_FOUND) return null;
		if (currentObject == null || (lastTask == operatesSymbol && operatesSymbol.lastSymbol != '.')) return null;

		Object o = getNextObject(token);

		if (o == NOT_FOUND && pkg == null) {
			// Instead of setting the main currentObject to NOT_FOUND and polluting the state,
			// just return the default color and let the state reset naturally on the next token.
			return defaultColor;
		}

		// Create a temporary object for the next step in the chain,
		// and only assign it to currentObject on success.
		Object nextObject;
		Color  colorToReturn;

		if (o instanceof NativeJavaPackage p) {
			pkg = p;
			nextObject = p;
			colorToReturn = defaultColor;
		} else if (o instanceof Scriptable sc) {
			pkg = null;
			nextObject = sc;
			colorToReturn = c_localvar;
		} else {
			// This case handles other valid properties (like numbers, strings) that aren't scriptable.
			nextObject = Undefined.SCRIPTABLE_UNDEFINED;
			colorToReturn = null;
		}

		currentObject = nextObject;
		updateCursorObj();
		return colorToReturn;
	}
	private Object getNextObject(String token) {
		if (true) {
			return currentObject instanceof Scriptable sc ? getPropOrNotFound(sc, token) : null;
		}
		/* if (currentObject == customScope) {
			return getPropOrNotFound(customScope, token);
		} else if (pkg == null && currentObject instanceof NativeJavaObject nja) {
			return js_prop_map.computeIfAbsent(nja instanceof NativeJavaClass ? Class.class : nja.unwrap(), _ -> new HashMap<>())
			 .computeIfAbsent(token, _ -> getPropOrNotFound(nja, token));
		} else {
			return getPropOrNotFound(pkg != null ? pkg : currentObject, token);
		} */
		return null;
	}

	private final DrawTask[] taskArr0 = {
	 new DrawString(c_string) {
		 /** @see NativeString  */
		 public static final Scriptable StringObj = Context.toObject("", IScript.scope);
		 public void drawText(int i) {
			 super.drawText(i);
			 currentObject = StringObj;
			 setCursorObj(i);
		 }
	 },
	 bracketsSymbol,
	 new DrawComment(c_comment),
	 operatesSymbol,
	 drawToken = new JSDrawToken(),
	 };

	{ taskArr = taskArr0; }

	public static IntSet operateSet = new IntSet();
	public static IntSet bracketSet = new IntSet();

	static {
		for (char c : "~|,+-=*/<>!%^&;.:?".toCharArray()) {
			operateSet.add(c);
		}
		for (char c : "()[]{}".toCharArray()) {
			bracketSet.add(c);
		}
	}

	public Script compile() {
		return IScript.cx.compileString(drawable.getText(), "<compile>", 1);
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
			if (o == NOT_FOUND) return false;

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

	private class DrawCompletion extends DrawOuterTask {
		final Stack<RMethod> stack = new Stack<>();
		void init() {
			// Log.info("-----------");
			stack.clear();
			pkg = null;
			currentObject = customScope;
			lastCursorObjIndex = -1;
			cursorObj = null;
			lastTokenStackSize = -1;

			localVars.clear();
			localConstants.clear();
		}
		private static class RMethod implements Poolable {
			Scriptable base;
			final Seq<Object> args = new Seq<>();
			private RMethod init(Scriptable base) {
				this.base = base;
				args.clear();
				return this;
			}
			public static RMethod obtain(Scriptable base) {
				return Pools.obtain(RMethod.class, RMethod::new).init(base);
			}
			public void reset() {
				base = null;
				args.clear();
			}
		}
		public boolean inFunction() {
			return !stack.isEmpty();
		}
		/** 一个用于承载函数返回值的{@link NativeJavaObject} */
		private final NativeJavaObject receiver = new NativeJavaObject();
		private       int              lastTokenStackSize;
		private void forToken(int i) {
			if (!(lastTask == operatesSymbol || lastTask == bracketsSymbol)) return;

			char c = lastTask == operatesSymbol ? operatesSymbol.lastSymbol : bracketsSymbol.lastSymbol;
			if (c == ';' || (c == '=' && lastTokenStackSize == stack.size())
			    || ((currentObject == NOT_FOUND || currentObject == Undefined.SCRIPTABLE_UNDEFINED) && c == '\n')) {
				currentObject = customScope;
				updateCursorObj();
				operatesSymbol.lastSymbol = '\0';
			}
			if (c == '(') {
				if (currentObject instanceof Scriptable sc) {
					stack.add(RMethod.obtain(sc));
					/* currentObject = customScope;
					updateCursorObj(); */
				}
			}
			if (c == ',' && inFunction()) {
				stack.lastElement().args.add(currentObject);
			}
			if (c == ')' && inFunction()) {
				if (lastTask != bracketsSymbol || currentObject != stack.lastElement().base) {
					stack.lastElement().args.add(currentObject);
				}

				RMethod     pop  = stack.pop();
				Seq<Object> args = pop.args;

				l:
				if (pop.base instanceof NativeJavaMethod m) {
					Object[] allMethods = (Object[]) UNSAFE.getObject(m, RHINO.methods);
					Method   method     = null;
					Integer  integer    = Constants.iv(RHINO.findCachedFunction, m, IScript.cx, args.toArray());
					if (integer == null) break l;
					int index = integer;
					if (index >= 0) method = (Method) UNSAFE.getObject(allMethods[index], RHINO.memberObject);
					// Log.info(args);
					// Log.info(method);
					if (method == null || method.getReturnType() == void.class) break l;

					Constants.iv(RHINO.initNativeJavaObject, receiver, customScope, null, method.getReturnType());
					currentObject = receiver;
					// Log.info(currentObject);
				} else {
					currentObject = NOT_FOUND;
				}
				Pools.free(pop);
				setCursorObj(i);
			}
		}

		public void after(int i) {
			if (cTask == drawToken/* 刚执行完 */ && lastTask == drawToken) {
				if (localKeywords.contains(drawToken.lastToken)) {
					localVars.add(drawToken.token);
				}
				if (constKeywords.contains(drawToken.lastToken)) {
					localConstants.add(drawToken.token);
				}
			}
		}
		public boolean draw(int i) {
			// @WatchVar(group = "aa") var x =  cursorObj;
			forToken(i);
			return false;
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
		public DrawBracket() { super(bracketSet, new Color()); }
		void init() {
			super.init();
			surpassSize = /* hasChanged ? 0 :  */stack.size;
			stack.clear();
		}
		// ['(1', '(2', '2)' ] -> [ '(1' ]
		Color[] colors = {Pal.accent, c_brackets, Color.cyan};
		private Color colorOf(int i) {
			return colors[i % colors.length];
		}
		@Override
		boolean draw(int i) {
			if (!super.draw(i)) return false;
			if (leftBracket.containsKey(c)) {
				color.set(stack.size < surpassSize/* 左括号多于上一次统计的 */
				 ? c_error : colorOf(stack.size));
				stack.add(c);
			} else if (stack.isEmpty()) {
				color.set(c_error);
			} else if (leftBracket.get(stack.peek()) == c) {
				stack.pop();
				color.set(colorOf(stack.size));
			} else {
				color.set(c_error);
			}

			return true;
		}

		{
			color.set(textColor);
		}
	}
	private class JSDrawToken extends DrawToken {
		public JSDrawToken() { super(JSSyntax.this.tokenDraws); }
	}
	private class JSDrawSymbol extends DrawSymbol {
		boolean error;
		public JSDrawSymbol() { super(operateSet, Syntax.c_operateChar); }
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
					currentObject = NOT_FOUND;
					setCursorObj(i);
				}
				return true;
			}
			return false;
		}
	}
}
