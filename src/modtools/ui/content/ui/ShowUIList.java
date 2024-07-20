
package modtools.ui.content.ui;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.math.Interp;
import arc.scene.*;
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
import arc.util.*;
import mindustry.Vars;
import mindustry.core.UI;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import modtools.IntVars;
import modtools.ui.*;
import modtools.ui.components.*;
import modtools.ui.components.input.JSRequest;
import modtools.ui.components.utils.*;
import modtools.ui.content.*;
import modtools.utils.*;
import modtools.utils.SR.SatisfyException;
import modtools.ui.components.InterpImage;
import modtools.utils.reflect.*;
import modtools.utils.ui.*;
import modtools.utils.ui.search.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;

import static arc.scene.ui.CheckBox.CheckBoxStyle;
import static modtools.utils.Tools.Sr;
import static modtools.utils.ui.FormatHelper.fixed;

@SuppressWarnings("StringTemplateMigration")
public class ShowUIList extends Content {
	public IntTab tab;
	Window ui;

	public ShowUIList() {
		super("showuilist", Icon.imageSmall);
	}

	public void _load() {
		ui = new Window(localizedName(), getW(), 400, true);
		Table[] tables = {icons, tex, styles, colorsT, interps};
		Color[] colors = {Color.sky, Color.gold, Color.orange, Color.acid, Pal.command};

		String[] names = {"Icon", "Tex", "Styles", "Colors", "Interp"};
		tab = new IntTab(-1, names, colors, tables);
		tab.setPrefSize(getW(), -1);
		ui.cont.table(t -> {
			t.left().defaults().left();
			t.add(colorWrap);
			t.add("@mod-tools.tips.dclick_to_copy").color(Color.lightGray).padLeft(6f).row();
			t.table(t0 -> t0.check("ForceDisabled",
				forceDisabled, val -> forceDisabled = val).with(c -> c.setStyle(HopeStyles.hope_defaultCheck)))
			 .colspan(3).left()
			 .growX().padTop(-4f);
		}).row();

		Table top  = new Table();
		Table wrap = new Table();
		ui.cont.add(top).growX().row();
		ui.cont.add(wrap).grow();
		new Search((_, text) -> {
			if (!wrap.getChildren().isEmpty()) {
				pattern = PatternUtils.compileRegExpOrNull(text);
				return;
			}
			wrap.add(tab.build()).pad(10f).grow();
		}).build(top, ui.cont);
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
		iconColor = SettingsUI.colorBlock(colorWrap.table().padLeft(4f).get(),
		 "icon", data(), "iconColor",
		 -1/* white */, null);
	}

	public static Fi uiConfig = IntVars.dataDirectory.child("ui");

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

	Table icons = newTable(t -> Icon.icons.each((k, icon) -> {
		iconKeyMap.put(icon, k);
		t.bind(k);
		var region = icon.getRegion();
		setColor(t.image(icon)
		 .size(imageSize, region.height / (float) region.width * imageSize).get());
		field(t, k).row();
		t.unbind();
	})), tex    = newTable(t -> {
		String prefix = "Tex.";
		for (Field field : Tex.class.getFields()) {
			Drawable drawable = null;
			try {
				// 是否为static
				if (!Modifier.isStatic(field.getModifiers())) return;
				// 是否为Drawable
				if (!Drawable.class.isAssignableFrom(field.getType())) return;
				t.bind(field.getName());
				// 跳过private检查，减少时间
				field.setAccessible(true);
				drawable = (Drawable) field.get(null);
				texKeyMap.put(drawable, prefix + field.getName());
				addImage(t, drawable);
			} catch (Exception err) {
				t.add();// 占位
				Log.err(err);
			} finally {
				fieldWithView(t, field, drawable);
				t.row();
				t.unbind();
			}
		}
	}),
	 styles     = newTable(true, t -> {
		 listAllStyles(t, Styles.class);
		 Tools.runLoggedException(() -> {
			 Fi json = uiConfig.child("styles.json");
			 if (!json.exists()) json.writeString("[\n]");
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
			 for (Field field : cls.getFields()) {
				 if (!Modifier.isStatic(field.getModifiers())
				     || !Color.class.isAssignableFrom(field.getType())) continue;
				 try {
					 // 跳过private检查，减少时间
					 field.setAccessible(true);
					 Color color = (Color) field.get(null);
					 colorKeyMap.put(color, prefix + field.getName());

					 t.bind(field.getName());
					 t.listener(el -> IntUI.addTooltipListener(el, "" + color));
					 t.add(new BorderImage(Core.atlas.white(), 2f)
						.border(color.cpy().inv())).color(color).size(42f);
					 field(t, field.getName()).growX();
					 t.listener(null);
				 } catch (IllegalAccessException | IllegalArgumentException | ClassCastException err) {
					 Log.err(err);
				 } finally {
					 t.unbind();
				 }
				 t.row();
			 }
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
			 for (Field field : Interp.class.getDeclaredFields()) {
				 Object o = FieldUtils.getOrNull(field);
				 if (o instanceof Interp interp) {
					 table.add(new InterpImage(interp))
						.tooltip(field.getName())
						.size(120).padBottom(32f);
					 if (++c[0] % 3 == 0) table.row();
				 }
			 }
		 });
		 t.button("@add", () -> {
			 JSRequest.requestFor(Interp.class, Interp.class, interp -> {
				 table.add(new InterpImage(interp))
					.size(120).padBottom(32f);
				 if (++c[0] % 3 == 0) table.row();
			 });
		 });
	 }),
	 uis        = newTable(t -> {
		 for (Field f : UI.class.getFields()) {
			 Object o = FieldUtils.getOrNull(f, Vars.ui);
			 if (!(o instanceof Group)) continue;
			 uiKeyMap.put((Group) o, f.getName());
			 t.bind(f.getName());
			 t.add(f.getName());
			 t.add(new FieldValueLabel(ValueLabel.unset, UI.class, f, Vars.ui));
			 t.unbind();
		 }
	 });
	private static void fieldWithView(FilterTable<Object> t, Field field, Drawable drawable) {
		if (drawable != null) {
			t.table(p -> {
				field(p, field.getName());
				viewDrawable(p, drawable);
			}).growY();
		} else {
			field(t, field.getName());
		}
	}
	private static Cell<Label> field(Table p, String fieldName) {
		return p.add(fieldName).with(JSFunc::addDClickCopy).growY();
	}
	static void listAllStyles(FilterTable<Object> t, Class<?> stylesClass) {
		String  prefix = stylesClass.getSimpleName() + ".";
		Field[] fields = getStyleFields(stylesClass);
		for (Field field : fields) {
			if (!Modifier.isStatic(field.getModifiers())) continue;
			Object style = null;
			try {
				// 跳过访问检查，减少时间
				field.setAccessible(true);
				style = field.get(null);
				if (style instanceof Style style1) styleKeyMap.put(style1, prefix + field.getName());
				if (style instanceof Drawable d) styleIconKeyMap.put(d, prefix + field.getName());
				t.bind(field.getName());
				Sr(style)
				 .isInstance(ScrollPaneStyle.class, t, Builder::build)
				 .isInstance(DialogStyle.class, t, Builder::build)
				 .isInstance(LabelStyle.class, t, Builder::build)
				 .isInstance(SliderStyle.class, t, Builder::build)
				 .isInstance(TextFieldStyle.class, t, Builder::build)
				 .isInstance(CheckBoxStyle.class, t, Builder::build)
				 .isInstance(TextButtonStyle.class, t, Builder::build)
				 .isInstance(ImageButtonStyle.class, t, Builder::build)
				 .isInstance(ButtonStyle.class, t, Builder::build)
				 .isInstance(Drawable.class, t, Builder::build);
			} catch (IllegalAccessException | IllegalArgumentException err) {
				Log.err(err);
				continue;
			} catch (SatisfyException ignored) { }

			Object finalStyle = style;
			t.table(t1 -> {
				field(t1, field.getName());
				IntUI.addPreviewButton(t1, p -> SR.apply(() -> Sr(finalStyle)
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
			}).growY().row();
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
		Time.runTask(2, () -> tab.main.invalidate());
	}
	public <T> FilterTable<T> newTable(Cons<FilterTable<T>> cons) {
		return newTable(false, cons);
	}

	public <T> FilterTable<T> newTable(boolean withDisabled, Cons<FilterTable<T>> cons) {
		return new FilterTable<>(t -> {
			t.left().defaults().left().padLeft(6f);
			t.clearChildren();
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
			t.button("text button", style, () -> { }).size(96, 42);
		}
		static void build(ImageButtonStyle style, Table t) {
			t.button(Icon.ok, style, () -> { }).size(96, 42);
		}
		static void build(ButtonStyle style, Table t) {
			t.button(b -> {
				b.add("button");
			}, style, () -> { }).size(96, 42);
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

	static void viewDrawable(Table table, Drawable drawable) {
		IntUI.addPreviewButton(table, p -> Builder.view(drawable, p)).padLeft(8f);
	}
}