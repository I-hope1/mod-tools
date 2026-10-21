package modtools.ui.comp.input.highlight;

import arc.func.Cons;
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

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
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

	public static class LocalScope {
		public final ObjectSet<String> vars      = new ObjectSet<>();
		public final ObjectSet<String> constants = new ObjectSet<>();
		public final ObjectSet<String> functions = new ObjectSet<>();
		/** 名字绑定的运行时对象：目前只有箭头函数参数能从被调方法的泛型实参推出来 */
		public final ObjectMap<String, Object> bound = new ObjectMap<>();

		public void clear() {
			vars.clear();
			constants.clear();
			functions.clear();
			bound.clear();
		}
	}

	final Seq<LocalScope> scopeStack            = new Seq<>();
	final LocalScope      rootScope             = new LocalScope();
	final Seq<String>     pendingFunctionParams = new Seq<>();
	/** 等待 '{' 的箭头函数参数：名字 → 从被调方法泛型实参推出来的类型对象 */
	final ObjectMap<String, Object> pendingArrowParams = new ObjectMap<>();
	/** `let v = ...` 里刚声明、还没等到右值的名字 */
	String  pendingDeclName;
	/** 声明语句开始时的括号深度：区分「声明项之间的逗号」和「调用/字面量里的逗号」 */
	int     declDepth;
	/** 右值里出现过数组/对象字面量，值不是单个对象，不绑 */
	boolean declValueNested;
	/** 当前声明是解构模式：`let [a, b] = ...` 是 '[', `let {x, y} = ...` 是 '{'，否则 '\0' */
	char  destructureKind;
	/** 解构目标里的名字，按出现顺序 */
	final Seq<String> destructureNames = new Seq<>();
	/** 解构目标里的第几个位置，用来对齐数组字面量的元素（空位会跳号） */
	int destructurePos;
	/** 解构目标里有空位（`let [a, , c]`），位置对不上元素，绑定时一律跳过 */
	boolean destructureHole;
	/** 解构目标里出现过 `{a: alias}` 这种重命名，绑定时一律跳过（key 对不上） */
	boolean destructureRenamed;
	/** 右值是数组字面量时，按顺序收集到的元素 */
	final Seq<Object> declElements = new Seq<>();
	/** 正在收集数组字面量的元素 */
	boolean collectingElements;

	public enum DeclType { NONE, VAR, CONST }
	public enum DeclPhase { ID, VALUE }

	public DeclType  currentDeclType  = DeclType.NONE;
	public DeclPhase currentDeclPhase = DeclPhase.ID;
	public DeclPhase paramPhase       = DeclPhase.ID;

	public boolean isLocalVar(String token) {
		for (int j = scopeStack.size - 1; j >= 0; j--) {
			if (scopeStack.get(j).vars.contains(token)) return true;
		}
		return false;
	}

	public boolean isLocalConst(String token) {
		for (int j = scopeStack.size - 1; j >= 0; j--) {
			if (scopeStack.get(j).constants.contains(token)) return true;
		}
		return false;
	}

	public boolean isLocalFunction(String token) {
		for (int j = scopeStack.size - 1; j >= 0; j--) {
			if (scopeStack.get(j).functions.contains(token)) return true;
		}
		return false;
	}

	public void addLocalVar(String token) {
		if (scopeStack.isEmpty()) scopeStack.add(rootScope);
		scopeStack.peek().vars.add(token);
	}

	public void addLocalConst(String token) {
		if (scopeStack.isEmpty()) scopeStack.add(rootScope);
		scopeStack.peek().constants.add(token);
	}

	public void addLocalFunction(String token) {
		if (scopeStack.isEmpty()) scopeStack.add(rootScope);
		scopeStack.peek().functions.add(token);
	}

	/** 名字绑到一个已解析出来的运行时对象，供 `v.xxx` 取基对象 */
	void addLocalBound(String token, Object value) {
		if (scopeStack.isEmpty()) scopeStack.add(rootScope);
		scopeStack.peek().bound.put(token, value);
	}

	public void eachLocalName(Cons<String> cons) {
		for (int j = scopeStack.size - 1; j >= 0; j--) {
			LocalScope scope = scopeStack.get(j);
			scope.vars.each(cons);
			scope.constants.each(cons);
			scope.functions.each(cons);
		}
	}

	/** 名字在某一层作用域里绑定的对象；没有绑定时返回 null */
	Object findBoundLocal(String token) {
		for (int j = scopeStack.size - 1; j >= 0; j--) {
			Object o = scopeStack.get(j).bound.get(token);
			if (o != null) return o;
		}
		return null;
	}

	/**
	 * 声明结束时，把 `let v = <expr>` 里已经解析出来的右值绑到 v 上，`v.` 才有基对象。
	 * 解构声明的右值只绑一次，名字取自 {@link #destructureNames}。
	 * <p>
	 * 只绑 Scriptable：数字、未定义这类值没有可补的成员，绑上去反而让基对象变成 null，
	 * 比退化成作用域更糟。右值是 customScope 说明这一步什么也没查到，同样不绑。
	 */
	void bindDeclValue() {
		String  name  = pendingDeclName;
		char    kind  = destructureKind;
		boolean alias = destructureRenamed;
		boolean hole  = destructureHole;
		pendingDeclName = null;
		destructureKind = '\0';
		destructureRenamed = false;
		destructureHole = false;

		if (currentDeclType == DeclType.NONE || currentDeclPhase != DeclPhase.VALUE) return;

		Object value = currentObject;
		// receiver 是调用返回值的复用槽位，下一次调用会把它改掉；绑定前先复制一份类型
		if (value == receiver) {
			if (receiverType == null) return;
			value = new NativeJavaObject(customScope, null, receiverType);
		}
		if (!(value instanceof Scriptable) || value == customScope) return;

		// 对象解构：`let {xxx, yy} = obj` 里每个名字就是 obj 的同名属性
		if (kind == '{') {
			if (alias) return;
			for (String n : destructureNames) {
				Object member = getPropOrNotFound((Scriptable) value, n);
				if (member instanceof Scriptable) addLocalBound(n, member);
			}
			return;
		}
		// 数组解构：只有右值也是数组字面量时才知道每个位置是什么，
		// 空位和重命名会让位置和名字错位，直接放弃
		if (kind == '[') {
			if (hole || alias) return;
			int n = Math.min(destructureNames.size, declElements.size);
			for (int i = 0; i < n; i++) {
				Object el = declElements.get(i);
				if (el instanceof Scriptable) addLocalBound(destructureNames.get(i), el);
			}
			return;
		}

		if (name == null || declValueNested) return;
		addLocalBound(name, value);
	}

	/** 开始一条新的声明语句，把上一条残留的状态清干净 */
	void beginDecl(DeclType type) {
		currentDeclType = type;
		currentDeclPhase = DeclPhase.ID;
		pendingDeclName = null;
		declDepth = bracketDepth();
		declValueNested = false;
		destructureKind = '\0';
		destructureNames.clear();
		destructurePos = 0;
		destructureHole = false;
		destructureRenamed = false;
		declElements.clear();
		collectingElements = false;
	}

	final ObjectSet<String> localKeywords = ObjectSet.with("let", "var");
	final ObjectSet<String> constKeywords = ObjectSet.with("const");

	private final ObjectMap<ObjectSet<String>, Color> TOKEN_MAP = new OrderedMap<>();

	protected final DrawSymbol operatesSymbol = new JSDrawSymbol(), bracketsSymbol = new DrawBracket();

	/** 当前还没闭合的括号层数，用来区分「声明项之间的逗号」和「调用/字面量里的逗号」 */
	int bracketDepth() {
		return ((DrawBracket) bracketsSymbol).stack.size;
	}

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
	/**
	 * 调用返回值的复用槽位：只知道类型、没有实例。每次调用都会重置它，
	 * 所以任何要长期持有的引用都得先复制（见 {@link #bindDeclValue()}）。
	 */
	final NativeJavaObject receiver     = new NativeJavaObject();
	/** {@link #receiver} 当前代表的类型 */
	Class<?>              receiverType;
	private final Integer           OBJ_NUMBER = -189021039;


	public char getNextNonWhitespaceChar(DrawToken task) {
		if (displayText == null || task == null || task.token == null) return '\0';
		int fromIndex = task.lastIndex + task.token.length();
		int length = displayText.length();
		for (int i = fromIndex; i < length; i++) {
			char ch = displayText.charAt(i);
			if (ch != ' ' && ch != '\t' && ch != '\r' && ch != '\n') {
				return ch;
			}
		}
		return '\0';
	}

	/**
	 * token 前面（跳过空白）是不是 '.'。
	 * <p>
	 * 用文本判断而不是 {@code operatesSymbol.lastSymbol}：括号字符归 {@code bracketsSymbol} 管，
	 * 不会清掉 {@code operatesSymbol} 里更早的 '.'，于是 {@code a.b(inner.c)} 的 {@code inner}
	 * 会被误当成属性名跳过。
	 */
	/** index 之前（跳过空白）是不是字符 ch */
	private boolean precededBy(int index, char ch) {
		int i = index;
		while (i > 0 && Character.isWhitespace(displayText.charAt(i - 1))) i--;
		return i > 0 && displayText.charAt(i - 1) == ch;
	}

	/** token 后面（允许空白）是不是 {@code =>}，即它是箭头函数的参数 */
	private boolean followedByArrow(DrawToken task) {
		int i = skipWhitespace(task.lastIndex + task.token.length());
		if (i >= displayText.length() || displayText.charAt(i) != '=') return false;
		i = skipWhitespace(i + 1);
		return i < displayText.length() && displayText.charAt(i) == '>';
	}

	private int skipWhitespace(int i) {
		while (i < displayText.length() && Character.isWhitespace(displayText.charAt(i))) i++;
		return i;
	}

	// --- Unified Identifier Handling Logic ---
	public TokenDraw[] tokenDraws = {
	 // Rule 1: The "Brain". Handles all identifier logic: declaration, parameters, and usage.
	 task -> {
		 DrawCompletion completion = (DrawCompletion) outerTask;
		 String         token      = task.token;
		 String         lastToken  = task.lastToken;

		 // 1. Function declaration name
		 if ("function".equals(lastToken)) {
			 addLocalFunction(token);
			 completion.state = DrawCompletion.ParseState.AWAITING_PARAMS_START;
			 currentDeclType = DeclType.NONE;
			 return c_functions;
		 }

		 // 2. Declaration keywords
		 if (localKeywords.contains(token)) {
			 beginDecl(DeclType.VAR);
			 return null;
		 }
		 if (constKeywords.contains(token)) {
			 beginDecl(DeclType.CONST);
			 return null;
		 }
		 if ("function".equals(token)) {
			 completion.state = DrawCompletion.ParseState.AWAITING_PARAMS_START;
			 currentDeclType = DeclType.NONE;
			 pendingDeclName = null;
			 return null;
		 }

		 // 3. Inside function parameters
		 if (completion.state == DrawCompletion.ParseState.INSIDE_PARAMS) {
			 if (paramPhase == DeclPhase.ID && !token.isEmpty() && Character.isJavaIdentifierStart(token.charAt(0))) {
				 if (getNextNonWhitespaceChar(task) == ':') {
					 return c_objects;
				 }
				 pendingFunctionParams.add(token);
				 return c_localvar;
			 }
		 }

		 // 4. Variable / Constant declaration expecting an identifier
		 if (currentDeclType != DeclType.NONE && currentDeclPhase == DeclPhase.ID) {
			 if (!token.isEmpty() && Character.isJavaIdentifierStart(token.charAt(0))) {
				 if (getNextNonWhitespaceChar(task) == ':') {
					 // `let {xxx: alias} = obj`：绑定要的 key 和名字对不上，放弃
					 if (destructureKind != '\0') destructureRenamed = true;
					 return c_objects;
				 }
				 // 记住名字，等右值解析出来后绑上去（见 bindDeclValue）
				 pendingDeclName = token;
				 if (destructureKind != '\0') destructureNames.add(token);
				 if (currentDeclType == DeclType.CONST) {
					 addLocalConst(token);
					 return c_constants;
				 } else {
					 addLocalVar(token);
					 return c_localvar;
				 }
			 }
		 }

		 // 5. Arrow parameter: `d => ...`。类型只能从被调方法的泛型实参推出来
		 if (followedByArrow(task)) {
			 Object type = completion.arrowParamObject();
			 if (type != null) pendingArrowParams.put(token, type);
			 return c_localvar;
		 }

		 // 6. Usage of defined local identifiers
		 Object bound = findBoundLocal(token);
		 if (bound != null) {
			 currentObject = bound;
			 updateCursorObj();
			 return c_localvar;
		 }
		 if (isLocalFunction(token)) return c_functions;
		 if (isLocalVar(token)) return c_localvar;
		 if (isLocalConst(token)) return c_constants;
		 if (pendingFunctionParams.contains(token)) return c_localvar;

		 return null;
	 },

	 // Rule 2: Generic lookup for everything else (keywords, globals). This is now simpler.
	 task -> {
		 for (Entry<ObjectSet<String>, Color> entry : TOKEN_MAP) {
			 if (entry.key.contains(task.token)) {
				 if (userVarSets.contains(entry.key)) {
					 // '.' 之后的 token 是属性名，要沿当前对象解析，交给下面的 dealJSProp
					 if (precededBy(task.lastIndex, '.')) continue;
					 // 其余位置（语句开头、'(' ',' '=' 之后）都是从作用域重新开始一个表达式。
					 // 不能沿用 currentObject：call(a.b, c.d) 里 c 会被拿到 a.b 里面去找，
					 // 而 let v = 之后 currentObject 是 null，连作用域都不查。
					 currentObject = getPropOrNotFound(customScope, task.token);
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
		} else if (o != null && o != NOT_FOUND) {
			try {
				Object wrapped = Context.javaToJS(o, customScope);
				if (wrapped instanceof Scriptable sc) {
					pkg = null;
					nextObject = sc;
					colorToReturn = c_localvar;
				} else {
					nextObject = Undefined.SCRIPTABLE_UNDEFINED;
					colorToReturn = null;
				}
			} catch (Throwable t) {
				nextObject = Undefined.SCRIPTABLE_UNDEFINED;
				colorToReturn = null;
			}
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
			receiverType = null;
			currentObject = customScope;
			lastCursorObjIndex = -1;
			cursorObj = null;
			lastTokenStackSize = -1;

			scopeStack.clear();
			rootScope.clear();
			scopeStack.add(rootScope);
			pendingFunctionParams.clear();
			pendingArrowParams.clear();
			beginDecl(DeclType.NONE);

			paramPhase = DeclPhase.ID;

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

		/**
		 * 当前调用正在接收的下一个实参如果是回调，从被调方法的泛型实参推出它的参数类型。
		 * <p>
		 * {@code dialog(Cons<Table>)} 的运行时值只有在回调被调用时才存在，所以类型只能从
		 * 签名里拿。推不出来（不是 Java 方法、泛型是类型变量、重载给出了不同类型）就返回
		 * null——宁可补不出成员，也不要补出另一个对象的成员。
		 */
		Object arrowParamObject() {
			if (stack.isEmpty()) return null;
			RMethod frame = stack.lastElement();
			if (!(frame.base instanceof NativeJavaMethod m)) return null;

			int      argIndex = frame.args.size;
			Class<?> found    = null;
			for (Method method : JavaMethods.of(m)) {
				if (method == null || method.getParameterCount() <= argIndex) continue;
				Class<?> type = callbackTypeOf(method.getGenericParameterTypes()[argIndex]);
				if (type == null) continue;
				if (found != null && found != type) return null;
				found = type;
			}
			return found == null ? null : new NativeJavaObject(customScope, null, found);
		}

		/** 泛型实参是具体类时返回它：{@code Cons<Table>} → {@code Table} */
		private static Class<?> callbackTypeOf(Type type) {
			if (!(type instanceof ParameterizedType p)) return null;
			Type[] args = p.getActualTypeArguments();
			return args.length == 1 && args[0] instanceof Class<?> c ? c : null;
		}
		private       int              lastTokenStackSize;
		private void forToken(int i) {
			if (!(lastTask == operatesSymbol || lastTask == bracketsSymbol)) return;
			// lastTask 是「上一个被吃掉的字符」，drawText 把它的 lastIndex 停在字符后一位，
			// 所以只在紧邻的下一格算一次。符号后面跟空白时 lastTask 不会变，重复处理会把
			// `{\n\t` 压成三层作用域，`}` 只弹一层就泄漏了。
			if (lastTask.lastIndex != i) return;
			char c = lastTask == operatesSymbol ? operatesSymbol.lastSymbol : bracketsSymbol.lastSymbol;

			// 右值里出现数组/对象字面量时，currentObject 只是里面最后一个元素，不能当成整个值。
			// 只有 VALUE 阶段才算右值：`let {a} = obj` 里的 '{' 是解构目标，不是字面量。
			if (currentDeclType != DeclType.NONE && currentDeclPhase == DeclPhase.VALUE
			    && (c == '[' || c == '{')) declValueNested = true;
			// 声明目标位置上的 '['/'{' 是解构模式
			if (currentDeclType != DeclType.NONE && currentDeclPhase == DeclPhase.ID
			    && (c == '[' || c == '{')) {
				destructureKind = c;
				destructureNames.clear();
				destructurePos = 0;
				destructureHole = false;
				destructureRenamed = false;
			}
			// 右值以数组字面量开头：按位置收集元素，`let [a, b] = [x, y]` 才能对上
			if (currentDeclType != DeclType.NONE && currentDeclPhase == DeclPhase.VALUE
			    && c == '[' && precededBy(lastTask.lastIndex - 1, '=')) {
				collectingElements = true;
				declElements.clear();
			}
			if (c == ']' && collectingElements && bracketDepth() == declDepth) {
				declElements.add(currentObject);
				collectingElements = false;
			}

			switch (state) {
				case AWAITING_PARAMS_START:
					if (c == '(') {
						state = ParseState.INSIDE_PARAMS;
						paramPhase = DeclPhase.ID;
					} else if (c != ' ' && c != '\n') {
						state = ParseState.DEFAULT;
					}
					break;
				case INSIDE_PARAMS:
					if (c == ')') {
						state = ParseState.DEFAULT;
						paramPhase = DeclPhase.ID;
					}
					break;
			}

			if (c == '{') {
				if (!pendingFunctionParams.isEmpty()) {
					LocalScope fnScope = new LocalScope();
					fnScope.vars.addAll(pendingFunctionParams);
					pendingFunctionParams.clear();
					scopeStack.add(fnScope);
				} else if (!pendingArrowParams.isEmpty()) {
					LocalScope arrowScope = new LocalScope();
					for (String name : pendingArrowParams.keys()) {
						arrowScope.vars.add(name);
						arrowScope.bound.put(name, pendingArrowParams.get(name));
					}
					pendingArrowParams.clear();
					scopeStack.add(arrowScope);
				} else if (currentDeclType == DeclType.NONE || currentDeclPhase != DeclPhase.ID) {
					scopeStack.add(new LocalScope());
				}
			}
			if (c == '}') {
				if (scopeStack.size > 1 && (currentDeclType == DeclType.NONE || currentDeclPhase != DeclPhase.ID)) {
					scopeStack.pop();
				}
			}

			if (c == '=') {
				if (state == ParseState.INSIDE_PARAMS) {
					paramPhase = DeclPhase.VALUE;
				} else if (currentDeclType != DeclType.NONE) {
					currentDeclPhase = DeclPhase.VALUE;
					// 右值从这里重新开始，上一条的收集结果作废
					collectingElements = false;
					declElements.clear();
				}
			}
			if (c == ',' || c == ':') {
				if (state == ParseState.INSIDE_PARAMS) {
					paramPhase = DeclPhase.ID;
				} else if (currentDeclType != DeclType.NONE) {
					boolean sameLevel = bracketDepth() == declDepth;
					if (c == ',') {
						if (sameLevel) {
							// `let v = a, w = b`：逗号前那一项的右值已经解析完了。
							// 只有和声明同一层的逗号才是声明项分隔符，`f(a, b)` 里的不是
							bindDeclValue();
							currentDeclPhase = DeclPhase.ID;
						} else if (currentDeclPhase == DeclPhase.VALUE) {
							// 数组字面量这一层的元素分隔符
							if (collectingElements && bracketDepth() == declDepth + 1) declElements.add(currentObject);
						} else if (destructureKind != '\0') {
							// 解构目标里的逗号：推进位置。上一位置没名字说明是空位
							if (destructureNames.size != destructurePos + 1) destructureHole = true;
							destructurePos++;
						}
					} else if (sameLevel) {
						// 三元表达式里 `a ? b : c` 的 b 不是声明的右值，只改阶段不绑
						currentDeclPhase = DeclPhase.ID;
					}
				}
			}

			if (c == ';' || (c == '=' && lastTokenStackSize == stack.size())
			    || ((currentObject == NOT_FOUND || currentObject == Undefined.SCRIPTABLE_UNDEFINED) && c == '\n')) {
				// 必须在 currentObject 被重置成作用域之前绑
				if (c == ';') bindDeclValue();
				currentObject = customScope;
				updateCursorObj();
				operatesSymbol.lastSymbol = '\0';
			}

			if (c == ';' || (c == '\n' && stack.isEmpty())) {
				currentDeclType = DeclType.NONE;
				currentDeclPhase = DeclPhase.ID;
				pendingFunctionParams.clear();
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
					receiverType = method.getReturnType();
					currentObject = receiver;
				} else if (pop.base instanceof NativeJavaClass njc) {
					// 处理 Java 类作为构造函数调用的情况 (例如 Fi("...") 或 new Fi("..."))
					Class<?> javaClass = njc.getClassObject();
					if (javaClass != null) {
						// 将 currentObject 模拟为该类的实例，以便后续补全
						Constants.iv(RHINO.initNativeJavaObject, receiver, customScope, null, javaClass);
						receiverType = javaClass;
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
			// 无分号的声明靠换行结束，这时才拿得到「换行」这个信号（forToken 里只有符号）
			if (newLine) endDeclAtNewLine(i);
			forToken(i);
			return false;
		}

		/**
		 * {@code let v = left} 换行就是语句结束（ASI）。下一行以运算符或点开头说明表达式
		 * 还在继续，这时不能结束——否则 `let v = left\n.xxx` 会被当成两条语句。
		 */
		private void endDeclAtNewLine(int i) {
			if (currentDeclType == DeclType.NONE || currentDeclPhase != DeclPhase.VALUE) return;
			int j = skipWhitespace(i + 1);
			if (j < displayText.length() && ".,+-*/%&|^?:<>=!".indexOf(displayText.charAt(j)) >= 0) return;
			bindDeclValue();
			currentDeclType = DeclType.NONE;
			currentDeclPhase = DeclPhase.ID;
		}
	}
	/**
	 * 取出 {@link NativeJavaMethod} 承载的 Java 方法。
	 * <p>
	 * {@code methods} 和 {@code MemberBox.memberObject} 都不在公开 API 里。不用
	 * {@code Constants.RHINO} 的那套偏移量：它依赖 IntVars 的静态初始化（需要 Core），
	 * 在没有游戏环境的测试里起不来。这里用普通反射，Field 只查一次；查不到就退化成
	 * 空数组，补全只是少一个类型来源，不影响高亮。
	 * <p>
	 * {@code memberObject} 的声明类型是 {@link Member}（构造器也在里面），所以按
	 * {@link Method} 取不到的地方留 null，由调用方跳过。
	 */
	static final class JavaMethods {
		private static final Field BOXES  = field(NativeJavaMethod.class, "methods");
		private static final Field MEMBER = BOXES == null ? null
		 : field(BOXES.getType().getComponentType(), "memberObject");

		static Method[] of(NativeJavaMethod method) {
			if (BOXES == null || MEMBER == null) return new Method[0];
			try {
				Object[] boxes = (Object[]) BOXES.get(method);
				Method[] out   = new Method[boxes.length];
				for (int i = 0; i < boxes.length; i++) {
					Object member = MEMBER.get(boxes[i]);
					out[i] = member instanceof Method m ? m : null;
				}
				return out;
			} catch (ReflectiveOperationException | RuntimeException e) {
				return new Method[0];
			}
		}

		private static Field field(Class<?> owner, String name) {
			try {
				Field f = owner.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (ReflectiveOperationException | RuntimeException e) {
				return null;
			}
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