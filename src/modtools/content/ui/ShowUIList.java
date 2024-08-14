
package modtools.content.ui;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.math.Interp;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.style.*;
import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.*;
import arc.scene.ui.Dialog.DialogStyle;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.ScrollPane.ScrollPaneStyle;
import arc.scene.ui.Slider.SliderStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.TextField.TextFieldStyle;
import arc.scene.ui.layout.Stack;
import arc.scene.ui.layout.*;
import arc.scene.utils.Disableable;
import arc.struct.Seq;
import arc.util.*;
import arc.util.pooling.*;
import mindustry.Vars;
import mindustry.core.UI;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import modtools.*;
import modtools.content.*;
import modtools.jsfunc.reflect.InitMethodHandle;
import modtools.ui.*;
import modtools.ui.IntUI.SelectTable;
import modtools.ui.comp.*;
import modtools.ui.comp.input.JSRequest;
import modtools.ui.comp.utils.*;
import modtools.ui.control.HopeInput;
import modtools.ui.reflect.RBuilder;
import modtools.utils.*;
import modtools.utils.SR.SatisfyException;
import modtools.utils.io.FileUtils;
import modtools.utils.reflect.FieldUtils;
import modtools.utils.search.*;
import modtools.utils.ui.*;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static arc.scene.ui.CheckBox.CheckBoxStyle;
import static modtools.utils.Tools.*;
import static modtools.utils.ui.FormatHelper.fixed;

@SuppressWarnings("StringTemplateMigration")
public class ShowUIList extends Content {
	public static final  int    cols       = 3;
	private static final String INIT_ARRAY =
	 """
		[
		 // Write your qualified class names here.
		]
		""";

	IntTab tab;
	Window ui;

	public ShowUIList() {
		super("showuilist", Icon.imageSmall);
	}

	public void _load() {
		ui = new Window(localizedName(), getW(), 400, true);
		Table[] tables = {icons, tex, styles, colorsT, interps, actions};
		Color[] colors = {Color.sky, Color.gold, Color.orange, Color.acid, Pal.command, Color.cyan};

		String[] names = {"Icon", "Tex", "Styles", "Colors", "Interp", "Actions"};
		tab = new IntTab(-1, names, colors, tables);
		tab.eachWidth = getW() / 4.3f;
		tab.setPrefSize(getW(), -1);
		ui.cont.table(t -> {
			t.left().defaults().left();
			t.add(colorWrap);
			t.add("@mod-tools.tips.dclick_to_copy").color(Color.lightGray).padLeft(6f).row();

			t.table(t0 -> {
				t0.check("ForceDisabled",
					forceDisabled, val -> forceDisabled = val)
				 .with(c -> c.setStyle(HopeStyles.hope_defaultCheck));
			}).colspan(3).growX().padTop(-4f);
		}).row();

		Table top  = new Table();
		ui.cont.add(top).growX().row();
		ui.cont.top();
		ui.cont.add(tab.build()).grow();
		new Search((_, pattern0) -> pattern = pattern0).build(top, ui.cont);
		// ui.addCloseButton();
	}

	boolean forceDisabled;
	Pattern pattern;

	Color bgColor;
	static Color iconColor;
	Table colorWrap = new Table();

	{
		bgColor = SettingsUI.colorBlock(colorWrap.table().get(),
		 "bg", data(), "bgColor",
		 0x877F5E_FF, null);
		iconColor = SettingsUI.colorBlock(colorWrap.table().padLeft(6f).get(),
		 "icon", data(), "iconColor",
		 -1/* white */, null);
	}

	public static Fi uiConfig = IntVars.dataDirectory.child("ui");

	// 这里用HashMap是因为ObjectMap刚好会hash碰撞。。。
	public static Map<Drawable, String> iconKeyMap  = new HashMap<>();
	public static Map<Drawable, String> texKeyMap   = new HashMap<>();
	public static Map<Color, String>    colorKeyMap = new HashMap<>();

	public static Map<Style, String>    styleKeyMap     = new HashMap<>();
	public static Map<Drawable, String> styleIconKeyMap = new HashMap<>();

	public static Map<Group, String> uiKeyMap   = new HashMap<>();
	public static Map<Font, String>  fontKeyMap = new HashMap<>();

	static {
		fontKeyMap.put(Fonts.icon, "icon");
		fontKeyMap.put(Fonts.def, "def");
		fontKeyMap.put(Fonts.iconLarge, "iconLarge");
		fontKeyMap.put(Fonts.tech, "tech");
		fontKeyMap.put(Fonts.outline, "outline");
	}

	static Field colorField = FieldUtils.getFieldAccess(Element.class, "color");
	private static void setColor(Image image) {
		FieldUtils.setValue(colorField, image, iconColor);
	}
	public static final int imageSize = 36;

	Table icons = newTable(t -> FieldUtils.walkAllConstOf(Icon.class, (field, icon) -> {
		String k = field.getName();
		iconKeyMap.put(icon, k);
		t.bind(k);
		var region = icon.getRegion();
		setColor(t.image(icon)
		 .size(imageSize, region.height / (float) region.width * imageSize).get());
		field(t, k).row();
		t.unbind();
	}, TextureRegionDrawable.class)),
	 tex        = newTable(t -> {
		 String prefix = "Tex.";
		 FieldUtils.walkAllConstOf(Tex.class, (field, drawable) -> {
			 t.bind(field.getName());
			 texKeyMap.put(drawable, prefix + field.getName());
			 addImage(t, drawable);
			 fieldWithView(t, field, drawable);
			 t.unbind();
			 t.row();
		 }, Drawable.class);
	 }),
	 styles     = newTable(true, t -> {
		 t.add("Custom Styles: ");
		 t.button("styles.json", () -> FileUtils.openFile(uiConfig.child("styles.json"))).growX();
		 t.row();

		 listAllStyles(t, Styles.class);
		 Tools.runLoggedException(() -> {
			 Fi json = uiConfig.child("styles.json");
			 if (!json.exists()) json.writeString(INIT_ARRAY);
			 Class<?>[] classes = IntVars.json.fromJson(Class[].class, json);
			 for (Class<?> cl : classes) {
				 listAllStyles(t, cl);
			 }
		 });
	 }),
	 colorsT    = newTable(t -> {
		 Cons<Class<?>> buildColor = cls -> {
			 t.add(cls.getSimpleName()).colspan(2).color(Pal.accent).growX().row();
			 t.image().color(Pal.accent).colspan(2).growX().row();

			 String prefix = cls.getSimpleName() + ".";
			 FieldUtils.walkAllConstOf(cls, (field, color) -> {
				 colorKeyMap.put(color, prefix + field.getName());

				 t.bind(field.getName());
				 t.listener(el -> IntUI.addTooltipListener(el, () -> FormatHelper.color(color)));
				 t.add(new BorderImage(Core.atlas.white(), 2f)
					.border(color.cpy().inv())).color(color).size(42f);
				 field(t, field.getName()).growX();
				 t.listener(null);
				 t.unbind();
				 t.row();
			 }, Color.class);
		 };
		 buildColor.get(Color.class);
		 buildColor.get(Pal.class);
	 }),
	 interps    = newTable(t -> {
		 Table table = new Table();
		 t.pane(table).pad(10f).grow().colspan(2).get();
		 t.row();
		 t.defaults().growX();
		 int[] c = {0};
		 t.button("Built-in", () -> {
			 table.clearChildren();
			 c[0] = 0;
			 FieldUtils.walkAllConstOf(Interp.class, (f, interp) -> {
				 table.add(new InterpImage(interp))
					.tooltip(f.getName())
					.size(120).padBottom(32f);
				 if (++c[0] % cols == 0) table.row();
			 }, Interp.class);
		 });
		 t.button("@add", () -> {
			 JSRequest.requestFor(Interp.class, Interp.class, interp -> {
				 table.add(new InterpImage(interp))
					.size(120).padBottom(32f);
				 if (++c[0] % cols == 0) table.row();
			 });
		 });
	 }),
	 actions    = newTable((Cons) new ActionComp()),
	 uis        = newTable(t -> {
		 UI obj = Vars.ui;
		 FieldUtils.walkAllConstOf(UI.class, (f, group) -> {
			 uiKeyMap.put(group, f.getName());
			 t.bind(f.getName());
			 t.add(f.getName());
			 t.add(new FieldValueLabel(ValueLabel.unset, f, obj));
			 t.unbind();
		 }, Group.class, obj);
	 });
	private static void fieldWithView(FilterTable<Object> t, Field field, Drawable drawable) {
		if (drawable != null) {
			t.table(p -> {
				field(p, field.getName());
				viewDrawable(p, drawable);
			});
		} else {
			field(t, field.getName());
		}
	}
	private static Cell<Label> field(Table p, String fieldName) {
		return p.add(fieldName).with(EventHelper::addDClickCopy);
	}
	static void listAllStyles(FilterTable<Object> t, Class<?> stylesClass) {
		String  prefix = stylesClass.getSimpleName() + ".";
		Field[] fields = getStyleFields(stylesClass);
		for (Field field : fields) {
			if (!Modifier.isStatic(field.getModifiers())) continue;
			Object obj = null;
			try {
				// 跳过访问检查，减少时间
				field.setAccessible(true);
				obj = field.get(null);
				if (obj instanceof Style style1) styleKeyMap.put(style1, prefix + field.getName());
				if (obj instanceof Drawable d) styleIconKeyMap.put(d, prefix + field.getName());
				t.bind(field.getName());
				SR.of(obj)
				 .isInstance(ScrollPaneStyle.class, t, Builder::build)
				 .isInstance(DialogStyle.class, t, Builder::build)
				 .isInstance(LabelStyle.class, t, Builder::build)
				 .isInstance(SliderStyle.class, t, Builder::build)
				 .isInstance(TextFieldStyle.class, t, Builder::build)
				 .isInstance(CheckBoxStyle.class, t, Builder::build)
				 .isInstance(TextButtonStyle.class, t, Builder::build)
				 .isInstance(ImageButtonStyle.class, t, Builder::build)
				 .isInstance(ButtonStyle.class, t, Builder::build)
				 .isInstance(Drawable.class, t, Builder::build)
				 .isInstance(Object.class, _ -> t.add());
			} catch (IllegalAccessException | IllegalArgumentException err) {
				Log.err(err);
				continue;
			} catch (SatisfyException ignored) { }

			Object finalObj = obj;
			t.table(t1 -> {
				field(t1, field.getName());
				PreviewUtils.addPreviewButton(t1, p -> SR.apply(() -> SR.of(finalObj)
				 .isInstance(ScrollPaneStyle.class, p, Builder::view)
				 .isInstance(DialogStyle.class, p, Builder::view)
				 .isInstance(LabelStyle.class, p, Builder::view)
				 .isInstance(SliderStyle.class, p, Builder::view)
				 .isInstance(TextFieldStyle.class, p, Builder::view)
				 .isInstance(CheckBoxStyle.class, p, Builder::view)
				 .isInstance(TextButtonStyle.class, p, Builder::view)
				 .isInstance(ImageButtonStyle.class, p, Builder::view)
				 .isInstance(ButtonStyle.class, p, Builder::view)
				 .isInstance(Drawable.class, p, Builder::view))).padLeft(8f);
			}).left().row();
			t.unbind();
		}
	}

	// 安卓上不支持Comparator
	@SuppressWarnings("ComparatorCombinators")
	private static Field[] getStyleFields(Class<?> stylesClass) {
		return OS.isAndroid ? Arrays.stream(stylesClass.getFields())
		 .sorted((a, b) -> a.getName().compareTo(b.getName()))
		 .toArray(Field[]::new) : stylesClass.getFields();
	}
	static void addImage(Table t, Drawable drawable) {
		Image image = new Image(drawable);
		setColor(image);
		image.fillParent = true;
		Label label = new Label(drawable instanceof TextureRegionDrawable ? "texture" : "nine");
		label.setColor(Color.lightGray);
		label.setFontScale(0.7f);
		label.fillParent = true;
		label.setAlignment(Align.topLeft);
		Stack stack = t.stack(image, label).size(32).get();
		IntUI.hoverAndExit(stack,
		 () -> label.visible = false,
		 () -> label.visible = true);
	}
	private static float getW() {
		return Core.graphics.isPortrait() ? 320 : 400;
	}
	public void build() {
		if (ui == null) _load();
		ui.show();
	}
	public <T> FilterTable<T> newTable(Cons<FilterTable<T>> cons) {
		return newTable(false, cons);
	}

	public <T> FilterTable<T> newTable(boolean withDisabled, Cons<FilterTable<T>> cons) {
		return new FilterTable<>(t -> {
			t.top().left().defaults().left().padLeft(6f);
			t.addChild(new FillElement() {
				public void draw() {
					Draw.color(bgColor, bgColor.a * parentAlpha);
					IntUI.whiteui.draw(t.x, t.y, t.getWidth(), t.getHeight());
				}
			});
			cons.get(t);
			t.addPatternUpdateListener(() -> pattern);
		}) {
			public <T1 extends Element> Cell<T1> add(T1 element) {
				if (withDisabled && element instanceof Disableable button) {
					element.update(() -> button.setDisabled(forceDisabled));
				}
				return super.add(element);
			}
		};
	}

	public static class Builder {
		static void build(ScrollPaneStyle style, Table t) {
			t.pane(style, p -> {
				p.add("pane").row();
				p.add("test-test-test").color(Color.gray).row();
				p.add("test-test-test").color(Color.gray).row();
			}).growX().maxWidth(144).height(64);
		}
		static void build(DialogStyle style, Table t) {
			t.pane(p -> p.add(new Dialog("dialog", style))).growX().height(42);
		}
		static void build(LabelStyle style, Table t) {
			t.add("label", style);
		}
		static void build(SliderStyle style, Table t) {
			t.slider(0, 10, 1, f -> { })
			 .get().setStyle(style);
		}
		static void build(TextButtonStyle style, Table t) {
			t.button("text button", style, IntVars.EMPTY_RUN).size(96, 42);
		}
		static void build(ImageButtonStyle style, Table t) {
			t.button(Icon.ok, style, IntVars.EMPTY_RUN).size(96, 42);
		}
		static void build(ButtonStyle style, Table t) {
			t.button(b -> {
				b.add("button");
			}, style, IntVars.EMPTY_RUN).size(96, 42);
		}
		static void build(TextFieldStyle style, Table t) {
			t.field("field", style, _ -> { });
		}
		static void build(CheckBoxStyle style, Table t) {
			t.add(new CheckBox("checkbox", style)).height(42);
		}
		static void build(Drawable drawable, Table t) {
			addImage(t, drawable);
		}

		static void view(Style style, Table t) {
			t.button("Copy",  HopeStyles.flatBordert, () -> {
				ValueLabel.copyStyle(style);
			}).size(96, 42);
		}

		static void view(Drawable drawable, Table t) {
			KeyValue keyValue = KeyValue.THE_ONE;
			keyValue.keyValue(t, "Left", () -> fixed(drawable.getLeftWidth()));
			keyValue.keyValue(t, "Right", () -> fixed(drawable.getRightWidth()));
			keyValue.keyValue(t, "Top", () -> fixed(drawable.getTopHeight()));
			keyValue.keyValue(t, "Bottom", () -> fixed(drawable.getBottomHeight()));
			keyValue.keyValue(t, "MinWidth", () -> fixed(drawable.getMinHeight()));
			keyValue.keyValue(t, "MinHeight", () -> fixed(drawable.getMinHeight()));
			keyValue.keyValue(t, "ImageSize", () -> fixed(drawable.imageSize()));
		}
	}


	static class ActionComp implements Cons<Table> {
		final        Element      element   = new Image();
		static       MethodHandle init      = Constants.nl(() -> InitMethodHandle.findInit(Image.class.getConstructor()));
		static final Seq<String>  blackList = Seq.with("time", "began", "complete", "lastPercent", "color",
		 "start",
		 "startR", "startG", "startB", "startA",
		 "startX", "startY");

		public void get(Table cont) {
			cont.center();
			Cell<Element> cell = cont.add(element).size(64).pad(24);
			cont.row();
			element.update(() -> element.setOrigin(Align.center));
			// element.setOrigin(element.getWidth() / 2f, element.getHeight() / 2f);
			// element.translation.set(element.getWidth() / 2f, element.getHeight() / 2f);
			Set<Class<?>> classes = Arrays.stream(Actions.class.getDeclaredMethods())
			 .map(Method::getReturnType).collect(Collectors.toCollection(LinkedHashSet::new));
			cont.button("Reset", Styles.flatt, runT(() -> {
				element.rotation = 0;
				cell.setElement(element);
				init.invoke(element);
				cont.layout();
			})).growX().height(42).row();
			cont.pane(t -> {
				int c = 0;
				for (Class<?> action : classes) {
					if (!Action.class.isAssignableFrom(action)) continue;
					if (Modifier.isAbstract(action.getModifiers())) continue;
					String text = action.getSimpleName();
					t.button(text.substring(0, text.length() - 6/* Action */), HopeStyles.flatt, () -> {
						applyToAction(as(action));
					}).size(140, 42);
					if (++c % 2 == 0) t.row();
				}
			}).grow();
		}
		private <T extends Action> void applyToAction(Class<T> actionClass) {
			Pool<T> pool = Pools.get(actionClass, () -> {
				try {
					return actionClass.getConstructor().newInstance();
				} catch (NoSuchMethodException | InvocationTargetException | InstantiationException |
				         IllegalAccessException e) {
					throw new RuntimeException(e);
				}
			});
			T action = pool.obtain();
			action.setPool(pool);
			action.reset();
			SelectTable table = IntUI.showSelectTable(HopeInput.mouseHit(), (p, hide, _) -> {
				RBuilder.build(p);
				RBuilder.buildFor(actionClass, action, blackList);
				RBuilder.clearBuild();
			}, false);
			table.hidden(() -> element.addAction(action));
		}
	}

	static void viewDrawable(Table table, Drawable drawable) {
		PreviewUtils.addPreviewButton(table, p -> Builder.view(drawable, p)).padLeft(8f);
	}
}