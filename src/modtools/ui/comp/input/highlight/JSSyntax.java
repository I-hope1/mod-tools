package modtools.ui.comp.input.highlight;

import arc.graphics.Color;
import arc.struct.*;
import modtools.jsfunc.IScript;
import rhino.*;

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
	 bracketsSymbol = new DrawSymbol(brackets, new Color()) {
		 static final IntIntMap leftBracket = IntIntMap.of(
			'(', ')',
			'[', ']',
			'{', '}');

		 final Color            textColor = c_brackets;
		 final Stack<Character> stack     = new Stack<>();

		 int surpassSize = 0;
		 void init() {
			 super.init();
			 surpassSize = hasChanged ? 0 : stack.size();
			 stack.clear();
		 }
		 // ['(1', '(2', '2)' ] -> [ '(1' ]
		 @Override
		 boolean draw(int i) {
			 if (!super.draw(i)) return false;
			 if (leftBracket.containsKey(c)) {
				 color.set(stack.size() < surpassSize ? c_error : textColor);
				 stack.push(c);
			 } else if (stack.isEmpty()) {
				 color.set(c_error);
			 } else if (leftBracket.get(stack.peek()) == c) {
				 stack.pop();
				 color.set(textColor);
			 } else {
				 color.set(c_error);
			 }
			 return true;
		 }
		 {
			 color.set(textColor);
		 }
	 };

	public Object getPropOrNotFound(Scriptable scope, String key) {
		try {
			return ScriptableObject.getProperty(scope, key);
		} catch (Throwable e) {
			return Scriptable.NOT_FOUND;
		}
	}

	public NativeJavaPackage pkg = null;
	public Scriptable        obj = null;

	public IntMap<Scriptable> indexToObj;
	public boolean            enableJSProp = false;

	@SuppressWarnings("StringEqualsCharSequence")
	public TokenDraw[] tokenDraws = {
	 task -> {
		 String token = task.token + "";
		 if (lastTask == operatesSymbol && operatesSymbol.lastSymbol != '\0') {
			 if (operatesSymbol.lastSymbol == '.') return dealJSProp(token);
			 obj = null;
			 pkg = null;
		 }

		 for (var entry : TOKEN_MAP) {
			 if (!entry.key.contains(token)) continue;
			 if (!enableJSProp || (
				entry.key != customConstantSet && entry.key != customVarSet
			 ) || obj != null) return entry.value;

			 resolveToken(customScope, task, token);
			 return entry.value;
		 }
		 if (lastTask != task) return null;
		 String lastToken = task.lastToken + "";
		 if (localKeywords.contains(lastToken)) {
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
		 CharSequence s = operatesSymbol.lastSymbol != '\0' && operatesSymbol.lastSymbol == '.' && task.token.charAt(0) == 'e' && task.lastToken != null ? task.lastToken + "." + task.token : task.token;
		 return ScriptRuntime.isNaN(ScriptRuntime.toNumber(s)) && !s.equals("NaN") ? null : c_number;
	 }
	};
	private void resolveToken(Scriptable scope, DrawToken task, String token) {
		Object o = ScriptableObject.getProperty(scope, token);
		appendIndexToObj(task, o);
		if (o instanceof NativeJavaPackage newPkg) {
			pkg = newPkg;
			obj = null;
		} else if (o instanceof Scriptable newObj) {
			pkg = null;
			obj = newObj;
		}
	}
	private void appendIndexToObj(DrawToken task, Object o) {
		if (indexToObj != null && o instanceof Scriptable sc) indexToObj.put(task.lastIndex + task.token.length(), sc);
	}


	HashMap<Object, HashMap<String, Object>> js_prop_map = new HashMap<>();
	private Color dealJSProp(String token) {
		if (!enableJSProp) return null;
		Object o = pkg == null && obj instanceof NativeJavaObject nja ?
		 js_prop_map.computeIfAbsent(nja.unwrap(), _ -> new HashMap<>())
			.computeIfAbsent(token, _ -> getPropOrNotFound(nja, token))
		 : getPropOrNotFound(pkg, token);
		if (o == Scriptable.NOT_FOUND) {
			obj = null;
			return null;
		}
		appendIndexToObj(drawToken, o);
		if (o instanceof NativeJavaPackage) {
			pkg = (NativeJavaPackage) o;
		} else if (o instanceof Scriptable sc) {
			pkg = null;
			obj = sc;
			return c_localvar;
		}
		obj = null;
		return null;
	}
	/* private void showTooltipMouse(DrawToken task) {
		if (areaTable.tooltip.container.parent != null) return;
		Vec2  v    = getRelativePos(task.lastIndex);
		float minX = v.x, minY = v.y - area.lineHeight() * 0.1f;
		v = getRelativePos(task.currentIndex);
		Vec2 abs   = ElementUtils.getAbsPos(area.parent);
		Vec2 mouse = Core.input.mouse();
		Vec2 sub   = mouse.sub(abs);
		if (Tmp.r1.set(minX, minY, v.x, v.y + area.lineHeight() * 1.1f).contains(sub)) {
			Table p = areaTable.tooltip.p;
			p.clearChildren();
			Object obj = this.obj;
			JSFunc.addDetailsButton(p, () -> obj, obj.getClass());
			areaTable.tooltip.show(Core.scene.root, mouse.x, mouse.y);
			Time.runTask(60 * 0.6f, () -> {
				areaTable.tooltip.hide();
			});
		}
	}

	private void showTooltip(DrawToken task) {
		float cursor = area.getRelativeCursor();
		if (task.lastIndex < cursor && cursor <= task.currentIndex) {
			Table p = areaTable.tooltip.p;
			p.clearChildren();
			areaTable.clearChildren();
			for (Object id : obj.getIds()) {
				areaTable.tooltip.p.add(new MyLabel("" + id)).row();
			}
			areaTable.tooltip.container.invalidateHierarchy();
		}
	}*/

	private final DrawTask[] taskArr0 = {
	 new DrawString(c_string),
	 bracketsSymbol,
	 new DrawComment(c_comment),
	 operatesSymbol,
	 drawToken = new DrawToken(tokenDraws) {
		 void init() {
			 super.init();
			 pkg = null;
			 obj = null;
			 localVars.clear();
			 localConstants.clear();
			 if (indexToObj != null) indexToObj.clear();
		 }
	 },
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
}
