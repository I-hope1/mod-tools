package modtools.utils;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.graphics.gl.Shader;
import arc.math.geom.*;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import ihope_lib.MyReflect;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import modtools.ui.*;
import modtools.ui.components.*;
import modtools.ui.components.ListDialog.ModifiedLabel;
import modtools.ui.components.Window.*;
import modtools.ui.components.input.MyLabel;
import modtools.ui.components.limit.*;
import modtools.ui.content.ui.ReviewElement.ReviewElementWindow;
import modtools.ui.content.world.Selection;
import modtools.utils.search.FilterTable;
import rhino.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

import static modtools.ui.Contents.*;
import static modtools.ui.IntUI.topGroup;
import static modtools.utils.MySettings.D_JSFUNC;
import static modtools.utils.Tools.*;

public class JSFunc {
	public static       ClassLoader main;
	public static       Scriptable  scope;
	public static final Font        FONT    = MyFonts.MSYHMONO;
	public static final Class<?>    Reflect = MyReflect.class;

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
			keyword      = new Color(0xf92672FF),
			type         = new Color(0x66d9efFF),
			NUMBER_COLOR = new Color(0xab9df2FF),
			underline    = Color.gray.cpy().a(0.7f);
	public static final String
			keywordMark = "[#" + keyword + "]",
			typeMark    = "[#" + type + "]";

	public static final LabelStyle
			keywordStyle = new LabelStyle(FONT, keyword),
			typeStyle    = new LabelStyle(FONT, type),
	// lightGrayStyle = new LabelStyle(FONT, Color.lightGray),
	redStyle = new LabelStyle(FONT, Color.red);


	public static Window showInfo(Object o, Class<?> clazz) {
		//			if (!clazz.isInstance(o)) return;

		Window[] dialog = {null};
		if (clazz.isArray()) {
			if (o == null) return new DisWindow("none");
			Table _cont = new LimitTable();
			_cont.defaults().grow();
			_cont.button(Icon.refresh, IntStyles.clearNonei, () -> {
				// 使用Time.runTask避免stack overflow
				Time.runTask(0, () -> {
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
				var    button = new LimitTextButton("" + item, Styles.grayt);
				int    j      = i;
				addWatchButton(button, o + "#" + i, () -> Array.get(o, j));
				button.clicked(() -> {
					// 使用Time.runTask避免stack overflow
					if (item != null) Time.runTask(0, () -> showInfo(item).setPosition(getAbsPos(button)));
					else IntUI.showException(new NullPointerException("item is null"));
				});
				_cont.add(button).fillX().minHeight(40).row();
				_cont.image().color(underline).growX().row();
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
		return testElement(new Image() {
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

	public static Window testElement(Element element) {
		return window(d -> {
			d.cont.pane(element).grow();
		});
	}
	public static Window testElement(String text) {
		return testElement(new Label(text));
	}
	public static Window testElement(TextureRegion region) {
		return testElement(new Image(region));
	}

	public static Window testElement(Cons<Table> cons) {
		return testElement(new Table(cons));
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
		IntUI.doubleClick(label, () -> {}, () -> {
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
		IntUI.showInfoFade(Core.bundle.format("IntUI.copied", text))
				.setPosition(vec2);
	}

	static class ReflectTable extends FilterTable<String> {
		public Seq<ValueLabel> labels = new Seq<>();
		public ReflectTable() {
			left().defaults().left().top();
		}
		public void build(Class<?> cls) {
			add(new MyLabel(cls.getSimpleName(), IntStyles.MOMO_Label)).labelAlign(Align.left).row();
			image().color(Color.lightGray).fillX().padTop(6).colspan(6).row();
		}
	}


	public static StringBuilder
	buildArgsAndExceptions(Class<?>[] args,
	                       Class<?>[] exceptions) {
		StringBuilder sb = new StringBuilder();
		sb.append("[lightgray](");

		for (int i = 0, length = args.length; i < length; i++) {
			Class<?> parameterType = args[i];
			sb.append(typeMark);
			sb.append(parameterType.getSimpleName());
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
				sb.append(parameterType.getSimpleName());
				if (i != length - 1) {
					sb.append(", ");
				}
			}
		}
		return sb;
	}

	public static CharSequence getGenericString(Class<?> cls) {
		if (D_JSFUNC.getBool("displayGeneric")) return cls.getSimpleName();
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

		return sb;
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

	public static class WatchWindow extends NoTopWindow implements DisposableInterface {
		public static final MyProv<Object> NORMAL = () -> null;
		FilterTable<MyProv<Object>> template = new FilterTable<>();
		Table                       pane     = new Table();

		public WatchWindow() {
			super("Watch");
			// pane.add(template);
			pane.update(() -> {
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

		public WatchWindow watch(String info, MyProv<Object> value) {
			return watch(info, value, 0);
		}

		public WatchWindow watch(Drawable icon, MyProv<Object> value) {
			return watch(icon, value, 0);
		}
		public void newLine() {
			template.row();
		}

		public WatchWindow watchWithSetter(Drawable icon, MyProv<Object> value, Cons<String> setter, float interval) {
			Object[] callback = {null};
			template.bind(value);
			template.stack(new Table(o -> {
				o.left();
				o.add(new Image(icon)).size(32f).scaling(Scaling.fit);
			}), new Table(t -> {
				t.left().bottom();
				ModifiedLabel.build(getSup(value, callback), Tools::isNum, (field, label) -> {
					if (!field.isValid() || setter == null) return;
					setter.get(field.getText());
				}, interval, t).style(Styles.outlineLabel);
				t.pack();
			}));
			template.unbind();
			return this;
		}

		public WatchWindow watch(Drawable icon, MyProv<Object> value, float interval) {
			Object[] callback = {null};
			template.bind(value);
			template.stack(new Table(o -> {
				o.left();
				o.add(new Image(icon)).size(32f).scaling(Scaling.fit);
			}), new Table(t -> {
				t.left().bottom();
				MyLabel label;
				t.add(label = new MyLabel(getSup(value, callback))).style(Styles.outlineLabel);
				label.interval = interval;
				t.pack();
			}));
			template.unbind();
			return this;
		}

		public static final Object[] TMP = {null};
		public WatchWindow watch(String info, MyProv<Object> value, float interval) {
			template.bind(NORMAL);
			template.add(info).color(Pal.accent).growX().left().row();
			template.image().color(Pal.accent).growX().row();
			var label = new MyLabel(getSup(value, TMP));
			label.interval = interval;
			template.add(label).style(IntStyles.MOMO_Label).growX().left().padLeft(6f).row();
			template.image().color(underline).growX().row();
			template.unbind();
			pack();
			return this;
		}
		private static Prov<CharSequence> getSup(MyProv<Object> value, Object[] callback) {
			return () -> {
				try {
					callback[0] = value.get();
					return String.valueOf(callback[0]);
				} catch (Throwable e) {
					StringWriter sw = new StringWriter();
					PrintWriter  pw = new PrintWriter(sw);
					e.printStackTrace(pw);
					return sw.toString();
				}
			};
		}

		public String toString() {
			return "Watch@" + hashCode();
		}
	}
	public static WatchWindow watch() {
		return new WatchWindow();
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
		table.button("@details", IntStyles.flatBordert, () -> {
			Object o = prov.get();
			showInfo(o, o != null ? o.getClass() : clazz);
		}).size(96, 45);
		addStoreButton(table, Core.bundle.get("jsfunc.value", "value"), prov);
	}

	public static void addStoreButton(Table table, String key, Prov<?> prov) {
		table.button(key.isEmpty() ? Core.bundle.get("jsfunc.store_as_js_var2")
								: Core.bundle.format("jsfunc.store_as_js_var", key),
						IntStyles.flatBordert, () -> {}).padLeft(10f).size(180, 40)
				.with(b -> {
					b.clicked(() -> {
						tester.put(b, prov.get());
					});
				});
	}

	public static boolean isMultiWatch() {
		return Core.input.ctrl() || D_JSFUNC.getBool("multi-watch");
	}
	public static void addWatchButton(Table buttons, String info, MyProv<Object> value) {
		buttons.button(Icon.eyeSmall, Styles.squarei, () -> {}).with(b -> b.clicked(() -> {
			sr((!isMultiWatch() && topGroup.shownWindows.size() >= 2 && topGroup.shownWindows.get(topGroup.shownWindows.size() - 2) instanceof WatchWindow ?
					(WatchWindow) topGroup.shownWindows.get(topGroup.shownWindows.size() - 2) :
					watch()).watch(info, value).show())
					.ifRun(isMultiWatch(), t -> t.setPosition(b.localToStageCoordinates(Tmp.v1.set(0, 0))));
		})).size(45);
	}

	public interface MyProv<T> {
		T get() throws Exception;
	}
}
