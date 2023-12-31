package modtools.utils;

import arc.*;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.Tmp;
import ihope_lib.MyReflect;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.world.Tile;
import modtools.IntVars;
import modtools.annotations.builder.DataColorFieldInit;
import modtools.ui.*;
import modtools.ui.components.*;
import modtools.ui.components.Window.*;
import modtools.ui.components.limit.*;
import modtools.ui.components.utils.*;
import modtools.ui.content.debug.Tester;
import modtools.ui.content.ui.ReviewElement.ReviewElementWindow;
import modtools.ui.content.ui.design.DesignTable;
import modtools.ui.content.world.*;
import modtools.ui.effect.HopeFx;
import modtools.ui.IntUI;
import modtools.ui.tutorial.AllTutorial;
import modtools.ui.windows.utils.Comparator;
import modtools.utils.draw.InterpImage;
import modtools.utils.ui.*;
import modtools.utils.world.WorldDraw;
import rhino.*;

import java.lang.reflect.Array;

import static ihope_lib.MyReflect.unsafe;
import static modtools.ui.Contents.*;
import static modtools.ui.IntUI.topGroup;
import static modtools.utils.ElementUtils.*;

/** for js */
public class JSFunc {
	public static       ClassLoader main;
	public static       Scriptable  scope;
	public static final Font        FONT = MyFonts.def;
	public static void strikethrough(Runnable run) {
		MyFonts.strikethrough = true;
	}
	/* for js */
	public static final Class<?> vars    = IntVars.class;
	public static final Class<?> reflect = MyReflect.class;

	public static final Fi data = MySettings.dataDirectory;

	public static final ObjectMap<String, Scriptable> classes;
	public static final String                        defaultDelimiter = ", ";

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

	@DataColorFieldInit(data = "D_JSFUNC", needSetting = true, fieldPrefix = "c_")
	public static int
	 c_keyword      = 0xF92672_FF,
	 c_type         = 0x66D9EF_FF,
	 c_underline    = Color.lightGray.cpy().a(0.5f).rgba(),
	 c_window_title = Pal.accent.cpy().lerp(Color.gray, 0.6f).a(0.9f).rgba();
	/** 代码生成{@link ColorProcessor} */
	public static void settingColor(Table t) {}


	public static Window showInfo(Object o, Class<?> clazz) {
		Window[] dialog = {null};
		if (clazz != null && clazz.isArray()) {
			if (o == null) return new DisWindow("none");
			Table _cont = new LimitTable();

			int length = Array.getLength(o);
			_cont.add("Length: " + length).left();

			_cont.button(Icon.refresh, HopeStyles.clearNonei, () -> {
				Core.app.post(() -> {
					dialog[0].hide();
					var pos = getAbsolutePos(dialog[0]);
					try {
						showInfo(o, clazz).setPosition(pos);
					} catch (Throwable e) {
						IntUI.showException(e).setPosition(pos);
					}
					dialog[0] = null;
				});
			}).left().size(50).row();


			buildArrayCont(o, clazz, length, _cont);
			_cont.row();

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
	private static void buildArrayCont(Object o, Class<?> clazz, int length, Table cont) {
		Class<?> componentType = clazz.getComponentType();
		Table    c1            = null;
		for (int i = 0; i < length; i++) {
			if (i % 100 == 0) c1 = cont.row().table().grow().colspan(2).get();
			var button = new LimitTextButton("", HopeStyles.cleart);
			button.clearChildren();
			button.add(i + "[lightgray]:", HopeStyles.defaultLabel).padRight(8f);
			int j = i;
			button.add(new PlainValueLabel<Object>((Class) componentType, () -> Array.get(o, j))).grow();
			button.clicked(() -> {
				Object item = Array.get(o, j);
				// 使用post避免stack overflow
				if (item != null) Core.app.post(() -> showInfo(item).setPosition(getAbsolutePos(button)));
				else IntUI.showException(new NullPointerException("item is null"));
			});
			c1.add(button).growX().minHeight(40);
			IntUI.addWatchButton(c1, o + "#" + i, () -> Array.get(o, j)).row();
			c1.image().color(Tmp.c1.set(c_underline)).colspan(2).growX().row();
		}
	}

	public static Window window(final Cons<Window> cons) {
		class JSWindow extends HiddenTopWindow implements IDisposable {
			{
				title.setFontScale(0.7f);
				for (Cell<?> child : titleTable.getCells()) {
					if (child.get() instanceof ImageButton) {
						child.size(24);
					}
				}
				titleHeight = 28;
				((Table) titleTable.parent).getCell(titleTable).height(titleHeight);
				cons.get(this);
				// addCloseButton();
				hidden(this::clearAll);
				setPosition(Core.input.mouse());
				show();
			}

			public JSWindow() {
				super("TEST", 64, 64);
			}
		}
		return new JSWindow();
	}
	public static Window btn(String text, Runnable run) {
		return dialog(t -> t.button(text, Styles.flatt, run).size(64, 45));
	}

	public static Window testDraw(Runnable draw) {
		return testDraw0(__ -> draw.run());
	}

	private static Window testDraw0(Cons<Group> draw) {
		return dialog(new Group() {
			{transform = true;}

			public void drawChildren() {
				draw.get(this);
			}
		});
	}

	// static FrameBuffer buffer = new FrameBuffer();
	public static Window testShader(Shader shader, Runnable draw) {
		return testDraw0(t -> {
			// buffer.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
			FrameBuffer buffer  = new FrameBuffer(Core.graphics.getWidth(), Core.graphics.getHeight());
			Texture     texture = WorldDraw.drawTexture(buffer, draw);
			Draw.blit(texture, shader);
			buffer.dispose();
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
	public static Window dialog(Drawable drawable) {
		return dialog(new Image(drawable));
	}
	public static Window pixmap(int size, Cons<Pixmap> cons) {
		return pixmap(size, size, cons);
	}
	public static Window pixmap(int width, int height, Cons<Pixmap> cons) {
		Pixmap pixmap = new Pixmap(width, height);
		cons.get(pixmap);
		Window dialog = dialog(new TextureRegion(new Texture(pixmap)));
		dialog.hidden(pixmap::dispose);
		return dialog;
	}

	public static Window dialog(Cons<Table> cons) {
		return dialog(new Table(cons));
	}


	public static void inspect(Element element) {
		new ReviewElementWindow().show(element);
	}

	public static WFunction<?> getFunc(String name) {
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
	public static Object asJS(Object o) {
		return Context.javaToJS(o, scope);
	}


	public static Scriptable findClass(String name) throws ClassNotFoundException {
		if (classes.containsKey(name)) {
			return classes.get(name);
		} else {
			Scriptable clazz = Tester.cx.getWrapFactory().wrapJavaClass(Tester.cx, scope, main.loadClass(name));
			classes.put(name, clazz);
			return clazz;
		}
	}
	public static Class<?> forName(String name) throws ClassNotFoundException {
		return Class.forName(name, false, Vars.mods.mainLoader());
	}

	public static void setDrawPadElem(Element elem) {
		topGroup.setDrawPadElem(elem);
	}
	public static void toggleDrawPadElem(Element elem) {
		topGroup.setDrawPadElem(topGroup.drawPadElem == elem ? null : elem);
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

	/** 相当于js中的严格等于：<code><b><i>{@code ===}</i></b></code>
	 * <br>（不一定） */
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
			default -> throw new UnsupportedOperationException("Unsupported address size: " + unsafe.addressSize());
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
	public static void showInterp(Interp interp) {
		dialog(new InterpImage(interp));
	}
	public static void rotateCamera(float degree) {
		Core.scene.getCamera().mat.rotate(degree);
	}

	public static void focusWorld(Tile obj) {
		selection.focusInternal.add(obj);
	}
	public static void focusWorld(Building obj) {
		selection.focusInternal.add(obj);
	}
	public static void focusWorld(Unit obj) {
		selection.focusInternal.add(obj);
	}
	public static void focusWorld(Bullet obj) {
		selection.focusInternal.add(obj);
	}
	public static void focusWorld(Seq obj) {
		selection.focusInternal.add(obj);
	}
	public static void removeFocusAll() {
		selection.focusInternal.clear();
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

	/** 如果不用Object，安卓上会出问题 */
	public static void openModule(Object module, String pn) throws Throwable {
		MyReflect.openModule((Module) module, pn);
	}

	public static void compare(Object o1, Object o2) {
		Comparator.compare(o1, o2);
	}

	public static Element fx(String text) {
		return HopeFx.colorFulText(text);
	}

	public static void requestEl(Cons<Element> cons) {
		topGroup.requestSelectElem(TopGroup.defaultDrawer, cons);
	}
	public interface MyProv<T> {
		T get() throws Exception;
		default String stringify(Object o) {
			return String.valueOf(o);
		}
	}
}