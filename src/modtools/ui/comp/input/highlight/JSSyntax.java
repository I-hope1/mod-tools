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
import java.util.Stack;

import static rhino.Scriptable.NOT_FOUND;

@SuppressWarnings("StringTemplateMigration")
@DebugMark
public class JSSyntax extends Syntax {
	public static Color
	 c_constants = new Color(0x4FC1FFFF),
	 c_localvar  = new Color(0x7CDCFEFF);

	public Scriptable customScope;
	ObjectSet<String> customConstantSet, customVarSet;
	Seq<ObjectSet<String>> userVarSets = new Seq<>();

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

		// --- MODIFICATION: TOKEN_MAP re-simplified ---
		// It now contains ALL keywords, including declaration keywords.
		// It no longer needs to contain localVars, localFunctions, etc.,
		// as the new "brain" rule handles them directly.
		TOKEN_MAP.putAll(
		 ObjectSet.with(
			"break", "case", "catch", "const", "continue", // "const" is back
			"default", "delete", "do", "else",
			"finally", "for", "function", "if", "in",     // "function" is back
			"instanceof", "let", "new", "of", "return",   // "let" is back
			"switch", "this", "throw", "try", "typeof",
			"var", "void", "while", "with", "yield"       // "var" is back
		 ), c_keyword,
		 ObjectSet.with("null", "undefined", "true", "false"), c_keyword
		);

		// Global scope variables are still handled by TOKEN_MAP
		TOKEN_MAP.putAll(
		 customConstantSet, c_constants,
		 customVarSet, c_localvar
		);
		userVarSets.add(customConstantSet);
		userVarSets.add(customVarSet);

		outerTask = new DrawCompletion();
	}
	public JSSyntax(SyntaxDrawable drawable) { this(drawable, null); }

	public static ImporterTopLevel topScope = (ImporterTopLevel) IScript.scope;

	public static final ObjectSet<String> constantSet = new ScopeObjectSet(topScope);
	public static final ObjectSet<String> varSet      = new ObjectSet<>();
	public              Scriptable        cursorObj   = null;

	static {
		varSet.addAll("arguments", "Infinity");
		addValueToSet(topScope, varSet, constantSet, true);
	}

	private static void addValueToSet(
	 Scriptable scope, ObjectSet<String> varSet, ObjectSet<String> constantSet,
	 boolean searchParent) {
		do {
			if (!(scope instanceof ScriptableObject so)) {
				for (Object id : scope.getIds()) if (id instanceof String key) varSet.add(key);
			} else {
				for (Object id : so.getIds()) {
					if (!(id instanceof String key)) continue;
					try {
						if ((so.getAttributes(key) & ScriptableObject.READONLY) != 0) { constantSet.add(key); } else {
							varSet.add(key);
						}
					} catch (Exception e) {
						varSet.add(key);
					}
				}
			}
			if (!searchParent || scope.getParentScope() == null) break;
			scope = scope.getParentScope();
		} while (true);
	}

	final ObjectSet<String> localVars      = new ObjectSet<>();
	final ObjectSet<String> localConstants = new ObjectSet<>();
	final ObjectSet<String> localFunctions = new ObjectSet<>();

	final ObjectSet<String> localKeywords = ObjectSet.with("let", "var");
	final ObjectSet<String> constKeywords = ObjectSet.with("const");

	private final ObjectMap<ObjectSet<String>, Color> TOKEN_MAP = new OrderedMap<>();

	protected final DrawSymbol operatesSymbol = new JSDrawSymbol(), bracketsSymbol = new DrawBracket();

	public static Object getPropOrNotFound(Scriptable scope, String key) {
		Object o;
		do {
			try {
				o = ScriptableObject.getProperty(scope, key);
				if (o != NOT_FOUND) return o;
			} catch (Throwable e) {
				return NOT_FOUND;
			}
			scope = scope.getParentScope();
		} while (scope != null);
		return NOT_FOUND;
	}

	public        NativeJavaPackage pkg        = null;
	public        Object            currentObject;
	public        boolean           js_prop    = false;
	private final Integer           OBJ_NUMBER = -189021039;


	// --- FINAL FIX: Unified Identifier Handling Logic ---
	public TokenDraw[] tokenDraws = {
	 // Rule 1: The "Brain". Handles all identifier logic: declaration, parameters, and usage.
	 task -> {
		 DrawCompletion completion = (DrawCompletion) outerTask;
		 String         token      = task.token;
		 String         lastToken  = task.lastToken;

		 // Priority 1: Check for declaration context.
		 if (lastToken != null) {
			 // Is it a variable declaration?
			 if (localKeywords.contains(lastToken)) {
				 localVars.add(token);
				 return c_localvar;
			 }
			 // Is it a constant declaration?
			 if (constKeywords.contains(lastToken)) {
				 localConstants.add(token);
				 return c_constants;
			 }
			 // Is it a function declaration?
			 if ("function".equals(lastToken)) {
				 localFunctions.add(token);
				 completion.state = DrawCompletion.ParseState.AWAITING_PARAMS_START;
				 return c_functions;
			 }
		 }

		 // Priority 2: Check if we are inside function parameters.
		 if (completion.state == DrawCompletion.ParseState.INSIDE_PARAMS) {
			 if (!token.isEmpty() && Character.isJavaIdentifierStart(token.charAt(0))) {
				 localVars.add(token); // Parameters are local variables.
				 return c_localvar;
			 }
		 }

		 // Priority 3: If not a declaration, check if it's a *usage* of an already-defined local.
		 // This is the key fix for highlighting the 'x' on the second line.
		 if (localFunctions.contains(token)) return c_functions;
		 if (localVars.contains(token)) return c_localvar;
		 if (localConstants.contains(token)) return c_constants;

		 // If none of the above, let subsequent rules handle it (e.g., keywords, globals).
		 return null;
	 },

	 // Rule 2: Generic lookup for everything else (keywords, globals). This is now simpler.
	 task -> {
		 for (Entry<ObjectSet<String>, Color> entry : TOKEN_MAP) {
			 if (entry.key.contains(task.token)) {
				 if (userVarSets.contains(entry.key)) {
					 if (currentObject != customScope && currentObject != null && operatesSymbol.lastSymbol != '\0') { continue; }
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
		 return dealJSProp(task.token);
	 },

	 // Rule 3: Number highlighting (unchanged).
	 task -> {
		 String  s = operatesSymbol.lastSymbol != '\0' && operatesSymbol.lastSymbol == '.' && task.token.charAt(0) == 'e' && task.lastToken != null ? task.lastToken + "." + task.token : task.token;
		 boolean b = "NaN".equals(s) || !ScriptRuntime.isNaN(ScriptRuntime.toNumber(s));
		 if (b) currentObject = OBJ_NUMBER;
		 return b ? c_number : null;
	 }
	};

	private void updateCursorObj() { setCursorObj(drawToken.lastIndex); }

	int lastCursorObjIndex = -1;
	private void setCursorObj(int lastIndex) {
		if (drawable != null && lastCursorObjIndex < lastIndex && lastIndex <= drawable.cursor()) {
			lastCursorObjIndex = lastIndex;
			cursorObj = currentObject instanceof Scriptable sc ? sc : null;
		}
	}

	private Color dealJSProp(String token) {
		if (!js_prop) return null;
		if (currentObject == Undefined.SCRIPTABLE_UNDEFINED || currentObject == NOT_FOUND) return null;
		if (currentObject == null || (lastTask == operatesSymbol && operatesSymbol.lastSymbol != '.')) return null;

		Object o = getNextObject(token);
		if (o == NOT_FOUND && pkg == null) return defaultColor;

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
			nextObject = Undefined.SCRIPTABLE_UNDEFINED;
			colorToReturn = null;
		}
		currentObject = nextObject;
		updateCursorObj();
		return colorToReturn;
	}

	private Object getNextObject(String token) {
		if (currentObject instanceof Scriptable sc) {
			return getPropOrNotFound(sc, token);
		}
		return NOT_FOUND;
	}

	private final DrawTask[] taskArr0 = {
	 new DrawString(c_string) {
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
		for (char c : "~|,+-=*/<>!%^&;.:?".toCharArray()) operateSet.add(c);
		for (char c : "()[]{}".toCharArray()) bracketSet.add(c);
	}

	public Script compile() {
		return IScript.cx.compileString(drawable.getText(), "<compile>", 1);
	}

	private static class ScopeObjectSet extends ObjectSet<String> {
		Scriptable scope;
		public ScopeObjectSet(Scriptable scope) { this.scope = scope; }
		public int hashCode() { return scope.hashCode(); }
		public boolean contains(String key) {
			if (super.contains(key)) return true;
			if (key.isEmpty() || !Character.isJavaIdentifierStart(key.charAt(0))) return false;
			Object o = getPropOrNotFound(scope, key);
			if (o == NOT_FOUND) return false;
			add(key);
			return true;
		}
	}
	private static class WithObjectSet extends ObjectSet<String> {
		final ObjectSet<String> with;
		public WithObjectSet(ObjectSet<String> with) { this.with = with; }
		public int hashCode() { return with.hashCode() + super.hashCode(); }
		public String get(String key) {
			String s = super.get(key);
			return s == null ? with.get(key) : s;
		}
		public boolean contains(String key) { return super.contains(key) || with.contains(key); }
	}

	private class DrawCompletion extends DrawOuterTask {
		// The state machine is now simpler, mainly for tracking parameter lists.
		enum ParseState {
			DEFAULT,
			AWAITING_PARAMS_START,
			INSIDE_PARAMS
		}
		ParseState state = ParseState.DEFAULT;

		final Stack<RMethod> stack = new Stack<>();

		void init() {
			stack.clear();
			pkg = null;
			currentObject = customScope;
			lastCursorObjIndex = -1;
			cursorObj = null;
			lastTokenStackSize = -1;

			localVars.clear();
			localConstants.clear();
			localFunctions.clear();
			state = ParseState.DEFAULT;
		}
		private static class RMethod implements Poolable {
			Scriptable base;
			final Seq<Object> args = new Seq<>();
			private RMethod init(Scriptable base) {
				this.base = base;
				args.clear();
				return this;
			}
			public static RMethod obtain(Scriptable base) { return Pools.obtain(RMethod.class, RMethod::new).init(base); }
			public void reset() {
				base = null;
				args.clear();
			}
		}
		public boolean inFunction() { return !stack.isEmpty(); }
		private final NativeJavaObject receiver = new NativeJavaObject();
		private       int              lastTokenStackSize;
		private void forToken(int i) {
			if (!(lastTask == operatesSymbol || lastTask == bracketsSymbol)) return;
			char c = lastTask == operatesSymbol ? operatesSymbol.lastSymbol : bracketsSymbol.lastSymbol;

			switch (state) {
				case AWAITING_PARAMS_START:
					if (c == '(') { state = ParseState.INSIDE_PARAMS; } else if (c != ' ' && c != '\n')
						state = ParseState.DEFAULT;
					break;
				case INSIDE_PARAMS:
					if (c == ')') state = ParseState.DEFAULT;
					break;
			}

			if (c == ';' || (c == '=' && lastTokenStackSize == stack.size())
			    || ((currentObject == NOT_FOUND || currentObject == Undefined.SCRIPTABLE_UNDEFINED) && c == '\n')) {
				currentObject = customScope;
				updateCursorObj();
				operatesSymbol.lastSymbol = '\0';
			}
			if (c == '(') if (currentObject instanceof Scriptable sc) stack.add(RMethod.obtain(sc));
			if (c == ',' && inFunction()) stack.lastElement().args.add(currentObject);
			if (c == ')' && inFunction()) {
				if (lastTask != bracketsSymbol || currentObject != stack.lastElement().base) {
					stack.lastElement().args.add(currentObject);
				}
				RMethod pop = stack.pop();
				l:
				if (pop.base instanceof NativeJavaMethod m) {
					Object[] allMethods = (Object[]) UNSAFE.getObject(m, RHINO.methods);
					Method   method     = null;
					Integer  integer    = Constants.iv(RHINO.findCachedFunction, m, IScript.cx, pop.args.toArray());
					if (integer == null) break l;
					int index = integer;
					if (index >= 0) method = (Method) UNSAFE.getObject(allMethods[index], RHINO.memberObject);
					if (method == null || method.getReturnType() == void.class) break l;
					Constants.iv(RHINO.initNativeJavaObject, receiver, customScope, null, method.getReturnType());
					currentObject = receiver;
				} else if (pop.base instanceof NativeJavaClass njc) {
					// 处理 Java 类作为构造函数调用的情况 (例如 Fi("...") 或 new Fi("..."))
					Class<?> javaClass = njc.getClassObject();
					if (javaClass != null) {
						// 将 currentObject 模拟为该类的实例，以便后续补全
						Constants.iv(RHINO.initNativeJavaObject, receiver, customScope, null, javaClass);
						currentObject = receiver;
					} else {
						currentObject = customScope;
					}
				} else {
					currentObject = customScope;
				}
				Pools.free(pop);
				setCursorObj(i);
			}
		}

		public void after(int i) { }

		public boolean draw(int i) {
			forToken(i);
			return false;
		}
	}
	private class DrawBracket extends DrawSymbol {
		static final IntIntMap leftBracket = IntIntMap.of('(', ')', '[', ']', '{', '}');
		final        Color     textColor   = c_brackets;
		final        IntSeq    stack       = new IntSeq();
		public DrawBracket() { super(bracketSet, new Color()); }
		void init() {
			super.init();
			stack.clear();
		}
		Color[] colors = {Pal.accent, c_brackets, Color.cyan};
		private Color colorOf(int i) { return colors[i % colors.length]; }
		@Override
		boolean draw(int i) {
			if (!super.draw(i)) return false;
			if (leftBracket.containsKey(c)) {
				color.set(colorOf(stack.size));
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
		{ color.set(textColor); }
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