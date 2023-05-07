package modtools.utils;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.Shader;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import ihope_lib.MyReflect;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.events.E_JSFunc;
import modtools.ui.*;
import modtools.ui.components.ListDialog.ModifiedLabel;
import modtools.ui.components.*;
import modtools.ui.components.Window.*;
import modtools.ui.components.input.MyLabel;
import modtools.ui.components.input.MyLabel.CacheProv;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.content.ui.ReviewElement.ReviewElementWindow;
import modtools.ui.content.ui.design.DesignTable;
import modtools.ui.content.world.Selection;
import modtools.utils.reflect.ClassUtils;
import modtools.utils.search.FilterTable;
import rhino.*;

import java.lang.reflect.*;
import java.util.StringJoiner;

import static ihope_lib.MyReflect.unsafe;
import static modtools.ui.Contents.tester;
import static modtools.ui.IntUI.topGroup;
import static modtools.utils.Tools.*;

/** for js  */
public class JSFunc {
	public static       ClassLoader main;
	public static       Scriptable  scope;
	public static final Font        FONT    = MyFonts.def;
	public static final Class<?>    Reflect = MyReflect.class;

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
	public static final String
	 keywordMark = "[#" + c_keyword + "]",
	 typeMark    = "[#" + c_type + "]";

	public static final LabelStyle
	 keywordStyle = new LabelStyle(FONT, c_keyword),
	 typeStyle    = new LabelStyle(FONT, c_type),
	// lightGrayStyle = new LabelStyle(FONT, Color.lightGray),
	redStyle = new LabelStyle(FONT, Color.red);


	public static Window showInfo(Object o, Class<?> clazz) {
		Window[] dialog = {null};
		if (clazz.isArray()) {
			if (o == null) return new DisWindow("none");
			Table _cont = new LimitTable();
			_cont.defaults().grow();
			_cont.button(Icon.refresh, IntStyles.clearNonei, () -> {
				// 避免stack overflow
				Core.app.post( () -> {
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

			for (int i = 0; i < length; i++) {
				Object item   = Array.get(o, i);
				var    button = new TextButton("", Styles.grayt);
				button.clearChildren();
				button.add(new ValueLabel(item, clazz, null, null));
				int    j      = i;
				addWatchButton(button, o + "#" + i, () -> Array.get(o, j));
				button.clicked(() -> {
					// 使用Time.runTask避免stack overflow
					if (item != null) Time.runTask(0, () -> showInfo(item).setPosition(getAbsPos(button)));
					else IntUI.showException(new NullPointerException("item is null"));
				});
				_cont.add(button).growX().minHeight(40).row();
				_cont.image().color(c_underline).growX().row();
			}

			dialog[0] = new DisWindow(clazz.getSimpleName(), 200, 200, true);
			dialog[0].cont.pane(_cont).grow();
			dialog[0].show();
			return dialog[0];
		}


		dialog[0] = new ShowInfoWindow(o, clazz);
		//		dialog.addCloseButton();
		dialog[0].show();
		assert dialog[0] != null;
		return dialog[0];
	}

	public static Window window(final Cons<Window> cons) {
		return new DisWindow("test") {{
			cons.get(this);
			//			addCloseButton();
			show();
			hidden(() -> {
				clearChildren();
				System.gc();
			});
		}};
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
		return window(d -> {
			d.cont.pane(element).grow();
			d.setPosition(Core.input.mouse());
			Time.runTask(1, d::display);
		});
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


	public static void reviewElement(Element element) {
		new ReviewElementWindow().show(element);
	}

	public static Selection.Function<?> getFunction(String name) {
		return Selection.allFunctions.get(name);
	}

	public static Object unwrap(Object o) {
		if (o instanceof Wrapper wrapper) {
			return wrapper.unwrap();
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
		copyText(text, Tools.getAbsPos(element));
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

	public static class ReflectTable extends FilterTable<String> {
		public final Seq<ValueLabel> labels = new Seq<>();
		public ReflectTable() {
			left().defaults().left().top();
		}

		public void build(Class<?> cls) {
			String name;
			if (cls.isAnonymousClass()) {
				name = "[Anonymous]: " + getGenericString(cls);
			} else name = getGenericString(cls);
			unbind();
			add(new MyLabel(name, IntStyles.MOMO_LabelStyle)).labelAlign(Align.left).color(Pal.accent).row();
			image().color(Color.lightGray).growX().padTop(6).colspan(8).row();
		}
	}


	public static StringBuilder
	buildArgsAndExceptions(Executable executable) {
		Class<?>[] args = executable.getParameterTypes(),
		 exceptions = executable.getExceptionTypes();
		StringBuilder sb = new StringBuilder();
		sb.append("[lightgray](");

		for (int i = 0, length = args.length; i < length; i++) {
			Class<?> parameterType = args[i];
			sb.append(typeMark);
			sb.append(getGenericString(parameterType));
			if (i != length - 1) {
				sb.append(", ");
			}
		}
		sb.append("[lightgray])");

		if (exceptions.length > 0) {
			sb.append(' ').append(keywordMark).append("throws ");
			for (int i = 0, length = exceptions.length; i < length; i++) {
				Class<?> parameterType = exceptions[i];
				sb.append(typeMark);
				sb.append(getGenericString(parameterType));
				if (i != length - 1) {
					sb.append(", ");
				}
			}
		}
		return sb;
	}

	public static String getGenericString(Class<?> cls) {
		if (!E_JSFunc.display_generic.enabled()) return cls.getSimpleName();
		StringBuilder sb         = new StringBuilder();
		String        simpleName;
		int           arrayDepth = 0;
		while (cls.isArray()) {
			arrayDepth++;
			cls = cls.getComponentType();
		}
		simpleName = cls.getName();
		simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1); // strip the package name
		sb.append(simpleName);
		TypeVariable<?>[] typeparms = cls.getTypeParameters();
		if (typeparms.length > 0) {
			StringJoiner sj = new StringJoiner(",", "<", ">");
			for (TypeVariable<?> typeparm : typeparms) {
				sj.add(typeparm.getTypeName());
			}
			sb.append(sj);
		}

		sb.append("[]".repeat(Math.max(0, arrayDepth)));

		return sb.toString();
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

	@SuppressWarnings("UnusedReturnValue")
	public static class WatchWindow extends HiddenTopWindow implements DisposableInterface {
		public static final MyProv<Object> NORMAL = () -> null;
		FilterTable<MyProv<Object>> template = new FilterTable<>();
		Table                       pane     = new Table();
		public boolean disabled;

		public WatchWindow() {
			super("Watch");
			// pane.add(template);
			pane.update(() -> {
				if (disabled) return;
				template.filter(p -> {
					if (all || p == NORMAL) return true;
					try {
						Object o = p.get();
						try {
							Double.parseDouble("" + o);
						} catch (NumberFormatException ex) {
							return true;
						}
						return ScriptRuntime.toNumber(o) != 0;
					} catch (Exception e) {
						return false;
					}
				});
				pane.clearChildren();
				var cells = template.getCells();
				for (int i = 0; i < cells.size; i++) {
					var c = cells.get(i);
					if (c.get() == null) continue;
					pane.add(c.get()).set(c);
					if (cells.get(pane.getChildren().size - 1).isEndRow()) pane.row();
				}
				// Log.info(cells);
				/*var seq  = pane.getCells();
				int size = seq.size;
				for (int i = 0; i < size; i++) {
					if (seq.get(i).get() != null) continue;
					for (int j = i; j < size; j++) {
						if (seq.get(j).get() != null) {
							try {
								rowField.setInt(seq.get(j), rowField.getInt(seq.get(i)));
							} catch (Throwable ignored) {}
							seq.swap(i, j);
							break;
						}
					}
				}*/
			});
			/*sclLisetener.listener = () -> {
				pane.invalidateHierarchy();
			};*/
			ScrollPane sc = new ScrollPane(pane) {
				@Override
				public float getPrefWidth() {
					return Math.max(220, super.getPrefWidth());
				}
				@Override
				public float getPrefHeight() {
					return Math.max(120, super.getPrefHeight());
				}
			};
			fireMoveElems.add(title);
			title.touchable = Touchable.enabled;
			cont.add(sc).grow().row();
		}
		boolean all;
		/** 添加用于切换是否显示所有的单选框 */
		public void addAllCheckbox() {
			cont.check("all", all, b -> all = b).growX();
		}
		public void newLine() {
			template.row();
		}

		public WatchWindow watch(String info, Object value, Func<Object, String> stringify) {
			return watch(info, new MyProvIns<>(() -> value, stringify), 0);
		}
		public WatchWindow watch(String info, Object value) {
			return watch(info, () -> value, 0);
		}

		/** value变为常量，不再更改watch值 */
		public WatchWindow watchConst(String info, Object value) {
			return watch(info, () -> value, Float.POSITIVE_INFINITY);
		}

		public WatchWindow watch(String info, MyProv<Object> value) {
			return watch(info, value, 0);
		}

		public WatchWindow watch(Drawable icon, MyProv<Object> value) {
			return watch(icon, value, 0);
		}

		public WatchWindow watchWithSetter(Drawable icon, MyProv<Object> value, Cons<String> setter, float interval) {
			Object[] callback = {null};
			template.bind(value);
			template.stack(new Table(o -> {
				o.left();
				o.add(new Image(icon)).size(32f).scaling(Scaling.fit);
			}), new Table(t -> {
				t.left().bottom();
				ModifiedLabel.build(new CacheProv(value), Tools::isNum, (field, label) -> {
					if (!field.isValid() || setter == null) return;
					setter.get(field.getText());
				}, interval, t).style(Styles.outlineLabel);
				t.pack();
			}));
			template.unbind();
			return this;
		}

		public WatchWindow watch(Drawable icon, MyProv<Object> value, float interval) {
			template.bind(value);
			template.stack(new Table(o -> {
				o.left();
				o.add(new Image(icon)).size(32f).scaling(Scaling.fit);
			}), new Table(t -> {
				t.left().bottom();
				CacheProv prov  = new CacheProv(value);
				MyLabel   label = new MyLabel(prov);
				label.prov = prov;
				t.add(label).style(Styles.outlineLabel);
				label.interval = interval;
				t.pack();
			}));
			template.unbind();
			return this;
		}

		public WatchWindow watch(String info, MyProv<Object> value, float interval) {
			template.bind(NORMAL);
			template.add(info).color(Pal.accent).growX().left().colspan(2).row();
			template.image().color(Pal.accent).growX().colspan(2).row();
			CacheProv prov  = new CacheProv(value);
			MyLabel   label = new MyLabel(prov);
			label.prov = prov;
			label.interval = interval;
			addDetailsButton(template, prov, Object.class);
			template.add(label).name(info).style(IntStyles.MOMO_LabelStyle).growX().left().padLeft(6f).row();
			template.image().color(c_underline).growX().colspan(2).row();
			template.unbind();
			pack();
			return this;
		}
		public Object getWatch(String info) {
			return ((MyLabel) template.find(info)).prov.value;
		}

		public WatchWindow show() {
			return (WatchWindow) super.show();
		}
		public WatchWindow clearWatch() {
			template.clear();
			return this;
		}
		public String toString() {
			return "Watch@" + hashCode();
		}
	}
	public static WatchWindow watch() {
		return new WatchWindow();
	}
	public static WatchWindow watch(WatchWindow watch) {
		return watch == null ? new WatchWindow() : watch;
	}

	/* for js */
	public static boolean strictEquals(Object a, Object b) {
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
			Time.runTask(0, () -> showInfo(o, o != null ? o.getClass() : clazz));
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
	public static void addWatchButton(Table buttons, String info, MyProv<Object> value) {
		buttons.button(Icon.eyeSmall, Styles.squarei, () -> {}).with(b -> b.clicked(() -> {
			sr((!isMultiWatch() && Tools.getNull(topGroup.acquireShownWindows(), -2) instanceof WatchWindow w
			 ? w : watch()).watch(info, value).show())
			 .ifRun(isMultiWatch(), t -> t.setPosition(getAbsPos(b)));
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


	public static Runnable makeRun(String text) throws Exception {
		return ClassUtils.makeRun(ClassUtils.toBytecode(ClassUtils.defName, text));
	}
	public interface MyProv<T> {
		T get() throws Exception;

		default String stringify(Object o) {
			return String.valueOf(o);
		}
	}
}
