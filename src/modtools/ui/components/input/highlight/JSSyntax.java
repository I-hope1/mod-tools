package modtools.ui.components.input.highlight;

import arc.graphics.Color;
import arc.struct.*;
import mindustry.Vars;
import modtools.utils.Tools;
import rhino.*;

import java.util.*;

public class JSSyntax extends Syntax {

	public static Color
	 c_constants = new Color(/*0x39C8B0FF*/0x4FC1FFFF),
	// 常规变量
	c_localvar = new Color(0x7CDCFEFF)
	 // , __defalutColor__ = new Color(0xDCDCAAFF)
	 ;

	public JSSyntax(SyntaxDrawable drawable) {
		super(drawable);
		// defalutColor = __defalutColor__;
	}

	public static ImporterTopLevel scope = (ImporterTopLevel) Vars.mods.getScripts().scope;

	public static final ObjectSet<String> constantSet = new ObjectSet<>() {
		// final ObjectSet<String> blackList = new ObjectSet<>();
		public boolean contains(String key) {
			boolean contains = super.contains(key);
			if (contains) return true;
			char c = key.charAt(0);
			if (!(('A' <= c && c <= 'Z') || c == '$')) return false;
			Object o = scope.get(key, scope);
			if (o != Scriptable.NOT_FOUND) {
				add(key);
				return true;
			}

			return false;
		}
	};
	public static final ObjectSet<String> defVarSet   = new ObjectSet<>();

	static {
		defVarSet.addAll("arguments", "Infinity", "Packages");
		for (Object id : scope.getIds()) {
			if (!(id instanceof String key)) continue;
			try {
				ScriptableObject.redefineProperty(scope, key, false);
				defVarSet.add(key);
			} catch (RuntimeException ignored) {
				constantSet.add(key);
			}
		}
	}

	ObjectSet<String> localVars = new ObjectSet<>(), localConstants = new ObjectSet<>();

	ObjectMap<ObjectSet<CharSequence>, Color> TOKEN_MAP = ObjectMap.of(
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
	 localConstants, c_constants,
	 constantSet, c_constants,
	 defVarSet, c_localvar
	);

	ObjectSet<String> localKeywords = ObjectSet.with("let", "var");

	protected final DrawSymbol
	 operatesSymbol = new DrawSymbol(operates, c_operateChar),
	 bracketsSymbol = new DrawSymbol(brackets, c_brackets);

	public Object getPropOrNotFound(Scriptable scope, String key) {
		try {
			return ScriptableObject.getProperty(scope, key);
		} catch (Throwable e) {
			return Scriptable.NOT_FOUND;
		}
	}

	public NativeJavaPackage pkg          = null;
	public Scriptable        obj          = null;
	public boolean           enableJSProp = false;

	@SuppressWarnings("StringEqualsCharSequence")
	public TokenDraw[] tokenDraws = {
	 task -> {
		 String token = task.token + "";
		 if (lastTask == operatesSymbol && operatesSymbol.lastSymbol != '\u0000') {
			 if (operatesSymbol.lastSymbol == '.') {
				 return dealJSProp(token);
			 } else {
				 obj = null;
				 pkg = null;
			 }
		 }

		 for (var entry : TOKEN_MAP) {
			 if (!entry.key.contains(token)) continue;
			 if (!enableJSProp || (entry.key != (ObjectSet) constantSet && entry.key != (ObjectSet) defVarSet) || obj != null)
				 return entry.value;
			 Object o = scope.get(token, scope);
			 if (o instanceof NativeJavaPackage newPkg) {
				 pkg = newPkg;
				 obj = null;
			 } else if (o instanceof Scriptable newObj) {
				 pkg = null;
				 obj = newObj;
			 }
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
		 CharSequence s = operatesSymbol.lastSymbol != '\u0000' && operatesSymbol.lastSymbol == '.' && task.token.charAt(0) == 'e' && task.lastToken != null ? task.lastToken + "." + task.token : task.token;
		 return ScriptRuntime.isNaN(ScriptRuntime.toNumber(s)) && !s.equals("NaN") ? null : c_number;
	 }
	};


	HashMap<Object, HashMap<String, Object>> js_prop_map = new HashMap<>();
	private Color dealJSProp(String token) {
		if (!enableJSProp) return null;
		Object o = pkg != null || !(obj instanceof NativeJavaObject nja) ? getPropOrNotFound(pkg, token)
		 : js_prop_map.computeIfAbsent(nja.unwrap(), k -> new HashMap<>()).computeIfAbsent(token, k -> getPropOrNotFound(nja, token));
		if (o == Scriptable.NOT_FOUND) {
			obj = null;
			return null;
		}

		if (o instanceof NativeJavaPackage) {
			pkg = (NativeJavaPackage) o;
		} else if (o instanceof Scriptable) {
			pkg = null;
			obj = (Scriptable) o;
			// showTooltipMouse(task);
			// showTooltip(task);
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
			 localVars.clear();
			 localConstants.clear();
			 pkg = null;
			 obj = null;
		 }
	 },
	 };

	{
		taskArr = taskArr0;
	}


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
}
