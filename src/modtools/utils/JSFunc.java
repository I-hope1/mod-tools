package modtools.utils;

import arc.*;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import arc.util.Tmp;
import mindustry.game.EventType.Trigger;
import mindustry.graphics.Pal;
import modtools.IntVars;
import modtools.annotations.builder.DataColorFieldInit;
import modtools.jsfunc.*;
import modtools.jsfunc.reflect.*;
import modtools.jsfunc.type.*;
import modtools.ui.*;
import modtools.ui.content.world.Selection;
import modtools.ui.effect.HopeFx;
import modtools.ui.tutorial.AllTutorial;
import modtools.ui.windows.utils.Comparator;
import modtools.utils.draw.InterpImage;
import modtools.utils.ui.WatchWindow;
import modtools.utils.world.WFunction;

import static modtools.ui.Contents.tester;
import static modtools.utils.ElementUtils.*;

/** for js */
public class JSFunc
 /* Interfaces for js */
 implements UNSAFE, WORLD, REFLECT,
 REVIEW_ELEMENT, CAST, INFO_DIALOG, PTYPE,
 StringUtils, IScript,
 ANDROID_UNSAFE {
	public static final Font FONT = MyFonts.def;
	public static void strikethrough(Runnable run) {
		MyFonts.strikethrough = true;
	}
	/* for js */

	public static final Fi     dataDir          = IntVars.dataDirectory;
	public static final String defaultDelimiter = ", ";


	public static WFunction<?> getFunc(String name) {
		return Selection.allFunctions.get(name);
	}


	public static void addDClickCopy(Label label) {
		IntUI.doubleClick(label, null, () -> {
			copyText(String.valueOf(label.getText()), label);
		});
	}

	public static void copyText(CharSequence text, Element element) {
		copyText(text, getAbsolutePos(element));
	}
	public static void copyText(CharSequence text) {
		copyText(text, Core.input.mouse());
	}
	public static void copyText(CharSequence text, Vec2 vec2) {
		Core.app.setClipboardText(String.valueOf(text));
		IntUI.showInfoFade(Core.bundle.format("IntUI.copied", text), vec2);
	}
	/* public static void copyValue(Object value, Element element) {
		copyValue(value, Tools.getAbsPos(element));
	} */
	public static void copyValue(String text, Object value) {
		copyValue(text, value, Core.input.mouse());
	}
	public static void copyValue(String text, Object value, Vec2 vec2) {
		IntUI.showInfoFade(Core.bundle.format("jsfunc.savedas", text,
		 tester.put(value)), vec2);
	}


	/*static Field rowField;

	static {
		try {
			rowField = Cell.class.getDeclaredField("row");
			rowField.setAccessible(true);
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
	}*/

	public static WatchWindow watch() {
		return new WatchWindow();
	}
	public static WatchWindow watch(WatchWindow watch) {
		return watch == null ? new WatchWindow() : watch;
	}

	/*public static WatchWindow watch(String info, MyProv<Object> value) {
		return watch(info, value, 0);
	}
	public static WatchWindow watch(String info, MyProv<Object> value, float interval) {
		var w = new WatchWindow().watch(info, value, interval);
		w.show();
		return w;
	}*/
	/* public static <T extends Group> void design(T element) {
		dialog(d -> d.add(new DesignTable<>(element)));
	} */

	public static class MyProvIns<T> implements MyProv<T> {
		Func<Object, String> stringify;
		Prov<T>              prov;
		public MyProvIns(Prov<T> prov, Func<Object, String> stringify) {
			this.prov = prov;
			this.stringify = stringify;
		}
		public T get() throws Exception {
			return prov.get();
		}
		public String stringify(Object o) {
			return stringify.get(o);
		}
	}

	public static void scl() {
		Camera camera = Core.scene.getCamera();
		float  mul    = camera.height / camera.width;
		camera.position.set(Core.input.mouse());
		camera.width = 200;
		camera.height = 200 * mul;
	}
	public static void scl(Element elem) {
		AllTutorial.f2(elem);
	}
	public static void showInterp(Interp interp) {
		INFO_DIALOG.dialog(new InterpImage(interp));
	}
	public static void rotateCamera(float degree) {
		Core.scene.getCamera().mat.rotate(degree);
	}

	public static void focusElement(Element element) {
		applyDraw(() -> AllTutorial.drawFocus(Tmp.c1.set(Color.black).a(0.4f), () -> {
			Vec2 point = getAbsPosCenter(element);
			Fill.circle(point.x, point.y, Mathf.dst(element.getWidth(), element.getHeight()) / 2f);
		}));
	}
	public static void applyDraw(Runnable run) {
		Events.run(Trigger.uiDrawEnd, run);
	}

	// Internal Method.
	public static void compare(Object o1, Object o2) {
		Comparator.compare(o1, o2);
	}
	/** 相当于js中的严格等于：<code><b><i>{@code ===}</i></b></code>
	 * <br>（不一定） */
	public static boolean eq(Object a, Object b) {
		return a == b;
	}

	public static Element fx(String text) {
		return HopeFx.colorFulText(text);
	}


	public interface MyProv<T> {
		T get() throws Exception;
		default String stringify(Object o) {
			return String.valueOf(o);
		}
	}

	public static class JColor {
		@DataColorFieldInit(data = "D_JSFUNC", needSetting = true, fieldPrefix = "c_")
		public static int
		 c_keyword      = 0xF92672_FF,
		 c_type         = 0x66D9EF_FF,
		 c_underline    = Color.lightGray.cpy().a(0.5f).rgba(),
		 c_window_title = Pal.accent.cpy().lerp(Color.gray, 0.6f).a(0.9f).rgba();
		/** 代码生成{@link ColorProcessor} */
		public static void settingColor(Table t) {}
	}
}