package modtools.utils;

import arc.*;
import arc.files.Fi;
import arc.func.*;
import arc.fx.util.FxWidgetGroup;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.Shader;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.layout.*;
import arc.struct.ObjectMap;
import arc.util.Tmp;
import ihope_lib.MyReflect;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.events.E_JSFunc;
import modtools.ui.*;
import modtools.ui.components.*;
import modtools.ui.components.Window.*;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.content.ui.ReviewElement.ReviewElementWindow;
import modtools.ui.content.ui.design.DesignTable;
import modtools.ui.content.world.Selection;
import modtools.ui.tutorial.AllTutorial;
import modtools.utils.ui.*;
import modtools.utils.world.WorldDraw;
import rhino.*;

import java.lang.reflect.Array;

import static ihope_lib.MyReflect.unsafe;
import static modtools.ui.Contents.tester;
import static modtools.ui.IntUI.topGroup;
import static modtools.utils.ElementUtils.*;
import static modtools.utils.Tools.Sr;

/** for js */
public class JSFunc {
	public static       ClassLoader main;
	public static       Scriptable  scope;
	public static final Font        FONT    = MyFonts.def;
	/* for js */
	public static final Class<?>    reflect = MyReflect.class;

	public static final Fi data = MySettings.dataDirectory;

	public static final ObjectMap<String, Scriptable> classes;
	/*public static Object eval(String code) {
		var scripts = new Scripts();
		return scripts.context.evaluateString(scripts.scope, code, "none", 1);
	}*/

	public static Window showInfo(Object o) {
		if (o == null) return null;
		return showInfo(o, o.getClass());
	}
	public static Window showInfo(Class<?> clazz) {
		return showInfo(null, clazz);
	}

	public static final Color
	 c_keyword   = new Color(0xf92672FF),
	 c_type      = new Color(0x66d9efFF),
	 c_number    = new Color(0xab9df2FF),
	 c_underline = Color.gray.cpy().a(0.7f);

	/* public static final String
	 keyword_mark = "[#" + c_keyword + "]",
	 type_mark    = "[#" + c_type + "]"; */

	public static final LabelStyle
	 keyword_style = new LabelStyle(FONT, c_keyword),
	 type_style    = new LabelStyle(FONT, c_type),
	// lightGrayStyle = new LabelStyle(FONT, Color.lightGray),
	red_style = new LabelStyle(FONT, Color.red);


	public static Window showInfo(Object o, Class<?> clazz) {
		Window[] dialog = {null};
		if (clazz != null && clazz.isArray()) {
			if (o == null) return new DisWindow("none");
			Table _cont = new LimitTable();
			_cont.defaults().grow();
			_cont.button(Icon.refresh, IntStyles.clearNonei, () -> {
				// 避免stack overflow
				Core.app.post(() -> {
					dialog[0].hide();
					var pos = getAbsPos(dialog[0]);
					try {
						showInfo(o, clazz).setPosition(pos);
					} catch (Throwable e) {
						IntUI.showException(e).setPosition(pos);
					}
					dialog[0] = null;
				});
			}).size(50).row();
			int length = Array.getLength(o);

			Class<?> componentType = clazz.getComponentType();
			for (int i = 0; i < length; i++) {
				Object item   = Array.get(o, i);
				var    button = new TextButton("", Styles.grayt);
				button.clearChildren();
				button.add(i + "[lightgray]:", IntStyles.MOMO_LabelStyle).padRight(8f);
				button.add(new ValueLabel(item, componentType, null, null)).grow();
				int j = i;
				button.clicked(() -> {
					// 使用post避免stack overflow
					if (item != null) Core.app.post(() -> showInfo(item).setPosition(getAbsPos(button)));
					else IntUI.showException(new NullPointerException("item is null"));
				});
				_cont.add(button).growX().minHeight(40);
				addWatchButton(_cont, o + "#" + i, () -> Array.get(o, j)).row();
				_cont.image().color(c_underline).colspan(2).growX().row();
			}

			dialog[0] = new DisWindow(clazz.getSimpleName(), 200, 200, true);
			dialog[0].cont.pane(_cont).grow();
			dialog[0].show();
			return dialog[0];
		}

		dialog[0] = new ShowInfoWindow(o, clazz == null ? Class.class : clazz);
		//		dialog.addCloseButton();
		dialog[0].show();
		assert dialog[0] != null;
		return dialog[0];
	}

	public static Window window(final Cons<Window> cons) {
		class JSWindow extends HiddenTopWindow implements DisposableInterface {
			{
				cons.get(this);
				// addCloseButton();
				hidden(this::clearAll);
				show();
			}

			public JSWindow(String title) {
				super(title);
				setPosition(Core.input.mouse());
			}
		}
		return new JSWindow("test");
	}

	public static Window testDraw(Runnable draw) {
		return dialog(new Image() {
			public void draw() {
				draw.run();
			}
		});
	}

	public static Window testShader(Shader shader, Runnable draw) {
		return testDraw(() -> {
			Draw.blit(WorldDraw.drawTexture(100, 100, draw), shader);
		});
	}


	public static Window dialog(Element element) {
		return window(d -> d.cont.pane(element).grow());
	}
	public static Window dialog(String text) {
		return dialog(new Label(text));
	}
	public static Window dialog(TextureRegion region) {
		return dialog(new Image(region));
	}
	public static Window dialog(Texture texture) {
		return dialog(new TextureRegion(texture));
	}
	public static Window dialog(Cons<Table> cons) {
		return dialog(new Table(cons));
	}
	public static Window dialogFx(Cons<Table> cons) {
		FxWidgetGroup group = new FxWidgetGroup();
		Table         table = new Table(cons);
		table.setClip(false);
		table.fillParent = true;
		group.addChild(table);
		return window(d -> d.add(group).grow());
	}


	public static void reviewElement(Element element) {
		new ReviewElementWindow().show(element);
	}

	public static Selection.Function<?> getFunction(String name) {
		return Selection.allFunctions.get(name);
	}

	public static Object unwrap(Object o) {
		if (o instanceof Wrapper) {
			return ((Wrapper) o).unwrap();
		}
		if (o instanceof Undefined) {
			return "undefined";
		}

		return o;
	}

	public static Scriptable findClass(String name, boolean isAdapter) throws ClassNotFoundException {
		if (classes.containsKey(name)) {
			return classes.get(name);
		} else {
			Scriptable clazz = tester.cx.getWrapFactory().wrapJavaClass(tester.cx, scope, main.loadClass(name));
			classes.put(name, clazz);
			return clazz;
		}
	}
	public static Scriptable findClass(String name) throws ClassNotFoundException {
		return findClass(name, true);
	}
	public static Class<?> forName(String name) throws ClassNotFoundException {
		return Class.forName(name, false, Vars.mods.mainLoader());
	}

	public static void setDrawPadElem(Element elem) {
		TopGroup.drawHiddenPad = false;
		topGroup.drawPadElem = elem;
	}
	public static void toggleDrawPadElem(Element elem) {
		TopGroup.drawHiddenPad = true;
		topGroup.drawPadElem = topGroup.drawPadElem == elem ? null : elem;
	}

	public static Object asJS(Object o) {
		return Context.javaToJS(o, scope);
	}

	// public static Window frameLabel(String text) {
	// 	return testElement(new FrameLabel(text));
	// }

	static {
		main = Vars.mods.mainLoader();
		scope = Vars.mods.getScripts().scope;
		classes = new ObjectMap<>();
		// V8.createV8Runtime();
	}

	public static void addDClickCopy(Label label) {
		IntUI.doubleClick(label, null, () -> {
			copyText(String.valueOf(label.getText()), label);
		});
	}

	public static void copyText(CharSequence text, Element element) {
		copyText(text, getAbsPos(element));
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

	/** 相当于js中的<b><i>{@code ===}</i></b> */
	public static boolean eq(Object a, Object b) {
		return a == b;
	}
	private static final Object[] ONE_ARRAY = {null};
	public static long addressOf(Object o) {
		ONE_ARRAY[0] = o;
		long baseOffset = unsafe.arrayBaseOffset(Object[].class);
		return switch (unsafe.addressSize()) {
			case 4 -> unsafe.getInt(ONE_ARRAY, baseOffset);
			case 8 -> unsafe.getLong(ONE_ARRAY, baseOffset);
			default -> throw new Error("unsupported address size: " + unsafe.addressSize());
		};
	}

	/*public static WatchWindow watch(String info, MyProv<Object> value) {
		return watch(info, value, 0);
	}
	public static WatchWindow watch(String info, MyProv<Object> value, float interval) {
		var w = new WatchWindow().watch(info, value, interval);
		w.show();
		return w;
	}*/

	public static void addLabelButton(Table table, Prov<?> prov, Class<?> clazz) {
		addDetailsButton(table, prov, clazz);
		// addStoreButton(table, Core.bundle.get("jsfunc.value", "value"), prov);
	}
	public static void addDetailsButton(Table table, Prov<?> prov, Class<?> clazz) {
		table.button("@details", IntStyles.flatBordert, () -> {
			Object o = prov.get();
			Core.app.post(() -> showInfo(o, o != null ? o.getClass() : clazz));
		}).size(96, 45);
	}

	public static void addStoreButton(Table table, String key, Prov<?> prov) {
		table.button(buildStoreKey(key),
			IntStyles.flatBordert, () -> {}).padLeft(8f).size(180, 40)
		 .with(b -> {
			 b.clicked(() -> {
				 tester.put(b, prov.get());
			 });
		 });
	}
	public static String buildStoreKey(String key) {
		return key == null || key.isEmpty() ? Core.bundle.get("jsfunc.store_as_js_var2")
		 : Core.bundle.format("jsfunc.store_as_js_var", key);
	}

	public static boolean isMultiWatch() {
		return Core.input.ctrl() || E_JSFunc.watch_multi.enabled();
	}
	public static Cell<?> addWatchButton(Table buttons, String info, MyProv<Object> value) {
		return buttons.button(Icon.eyeSmall, Styles.squarei, () -> {}).with(b -> b.clicked(() -> {
			Sr((!isMultiWatch() && Tools.getBound(topGroup.acquireShownWindows(), -2) instanceof WatchWindow w
			 ? w : watch()).watch(info, value).show())
			 .cons(WatchWindow::isEmpty, t -> t.setPosition(getAbsPos(b)));
		})).size(45);
	}

	public static <T extends Group> void design(T element) {
		dialog(d -> d.add(new DesignTable<>(element)));
	}

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
	public static void rotateCamera(float degree) {
		Core.scene.getCamera().mat.rotate(degree);
	}
	public static void drawFocus(Element element) {
		applyDraw(() -> AllTutorial.drawFocus(Tmp.c1.set(Color.black).a(0.4f), () -> {
			Vec2 point = getAbsPosCenter(element);
			Fill.circle(point.x, point.y, Mathf.dst(element.getWidth(), element.getHeight()) / 2f);
		}));
	}
	public static void applyDraw(Runnable run) {
		Events.run(Trigger.uiDrawEnd, run);
	}
	public interface MyProv<T> {
		T get() throws Exception;
		default String stringify(Object o) {
			return String.valueOf(o);
		}
	}
}
