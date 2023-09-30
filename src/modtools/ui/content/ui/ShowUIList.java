
package modtools.ui.content.ui;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.math.Interp;
import arc.scene.Element;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.Dialog.DialogStyle;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.ScrollPane.ScrollPaneStyle;
import arc.scene.ui.Slider.SliderStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.TextField.TextFieldStyle;
import arc.scene.ui.layout.*;
import arc.scene.utils.Disableable;
import arc.struct.ObjectMap;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import modtools.annotations.builder.DataBoolFieldInit;
import modtools.ui.IntUI;
import modtools.ui.components.*;
import modtools.ui.content.*;
import modtools.utils.*;
import modtools.utils.SR.SatisfyException;
import modtools.utils.draw.InterpImage;
import modtools.utils.reflect.FieldUtils;
import modtools.utils.ui.search.*;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.regex.Pattern;

import static arc.scene.ui.CheckBox.CheckBoxStyle;
import static modtools.utils.Tools.Sr;

public class ShowUIList extends Content {
	public IntTab tab;
	Window ui;

	public ShowUIList() {
		super("showuilist", Icon.imageSmall);
	}

	public void _load() {
		ui = new Window(localizedName(), getW(), 500, true);
		Table[] tables = {icons, tex, styles, colorsT, interps};
		Color[] colors = {Color.sky, Color.gold, Color.orange, Color.acid, Pal.command};

		String[] names = {"icon", "tex", "styles", "colors", "interp"};
		tab = new IntTab(-1, names, colors, tables);
		tab.setPrefSize(getW(), -1);
		ui.cont.table(t -> {
			t.left().defaults().left();
			t.add(bgColorWrap);
			t.add("@mod-tools.tips.dclick_to_copy").color(Color.lightGray).padLeft(6f).row();
			t.table(t0 -> t0.check("forceDisabled",
				forceDisabled, val -> forceDisabled = val))
			 .colspan(3).left()
			 .growX().padTop(-4f);
		}).row();

		Table top  = new Table();
		Table wrap = new Table();
		ui.cont.add(top).growX().row();
		ui.cont.add(wrap).grow();
		new Search((cont, text) -> {
			if (!wrap.getChildren().isEmpty()) {
				pattern = PatternUtils.compileRegExpCatch(text);
				return;
			}
			wrap.add(tab.build()).pad(10f).grow();
		}).build(top, ui.cont);
		// ui.addCloseButton();
	}

	@DataBoolFieldInit(data = "")
	boolean forceDisabled;
	Pattern pattern;

	Color bgColor;
	Table bgColorWrap = new Table();

	{
		bgColor = SettingsUI.colorBlock(bgColorWrap,
		 "bgColor", data(), "bgColor",
		 0x877F5E_FF, null);
	}

	public static ObjectMap<Drawable, String> iconKeyMap  = new ObjectMap<>();
	public static ObjectMap<Color, String> colorKeyMap = new ObjectMap<>();
	public static ObjectMap<Style, String>    styleKeyMap = new ObjectMap<>();

	Table icons = newTable(t -> {
		Icon.icons.each((k, icon) -> {
			iconKeyMap.put(icon, k);
			t.bind(k);
			var region = icon.getRegion();
			t.image(icon).size(32, region.height / (float) region.width * 32);
			t.add(k).with(JSFunc::addDClickCopy).growY().row();
			t.unbind();
		});
	}), tex     = newTable(t -> {
		for (Field field : Tex.class.getFields()) {
			try {
				// 是否为Drawable
				if (!Drawable.class.isAssignableFrom(field.getType())) continue;
				t.bind(field.getName());
				// 跳过private检查，减少时间
				field.setAccessible(true);
				addImage(t, (Drawable) field.get(null));
			} catch (Exception err) {
				t.add();// 占位
				Log.err(err);
			} finally {
				t.add(field.getName()).with(JSFunc::addDClickCopy).growY().row();
				t.unbind();
			}

		}
	}), styles  = newTable(true, t -> {
		Builder.t = t;
		Field[] fields = OS.isAndroid ? Arrays.stream(Styles.class.getFields()).sorted((a, b) -> {
			return a.getType().hashCode() - b.getType().hashCode();
		}).toArray(Field[]::new) : Styles.class.getFields();
		for (Field field : fields) {
			if (!Modifier.isStatic(field.getModifiers())) continue;
			try {
				// 跳过访问检查，减少时间
				field.setAccessible(true);
				Object style = field.get(null);
				if (style instanceof Style style1) styleKeyMap.put(style1, field.getName());
				t.bind(field.getName());
				Sr(style)
				 .isInstance(ScrollPaneStyle.class, Builder::build)
				 .isInstance(DialogStyle.class, Builder::build)
				 .isInstance(LabelStyle.class, Builder::build)
				 .isInstance(SliderStyle.class, Builder::build)
				 .isInstance(TextFieldStyle.class, Builder::build)
				 .isInstance(CheckBoxStyle.class, Builder::build)
				 .isInstance(TextButtonStyle.class, Builder::build)
				 .isInstance(ImageButtonStyle.class, Builder::build)
				 .isInstance(ButtonStyle.class, Builder::build)
				 .isInstance(Drawable.class, Builder::build);
			} catch (IllegalAccessException | IllegalArgumentException err) {
				Log.err(err);
				continue;
			} catch (SatisfyException ignored) {}

			t.add(field.getName()).with(JSFunc::addDClickCopy).growY().row();
			t.unbind();
		}
	}), colorsT = newTable(t -> {
		t.defaults().left().growX();

		Cons<Class<?>> buildColor = cls -> {
			t.add(cls.getSimpleName()).color(Pal.accent).colspan(2).row();
			t.image().color(Pal.accent).colspan(2).row();

			for (Field field : cls.getFields()) {
				if (!Modifier.isStatic(field.getModifiers())
						|| !Color.class.isAssignableFrom(field.getType())) continue;
				try {
					// 跳过private检查，减少时间
					field.setAccessible(true);
					Color color = (Color) field.get(null);
					colorKeyMap.put(color, field.getName());

					t.bind(field.getName());
					var tooltip = new IntUI.Tooltip(tl -> tl.table(Tex.pane, t2 -> t2.add("" + color)));
					t.listener(el -> el.addListener(tooltip));
					t.add(new BorderImage(Core.atlas.white(), 2f)
					 .border(color.cpy().inv())).color(color).size(42f);
					t.add(field.getName()).with(JSFunc::addDClickCopy).growY();
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
		 t.pane(table).pad(10f).grow().get();
		 t.row();
		 t.button("fun", () -> {
			 table.clearChildren();
			 int c = 0;
			 for (Field field : Interp.class.getDeclaredFields()) {
				 Object o = FieldUtils.getOrNull(field);
				 if (o instanceof Interp interp) {
					 table.add(new InterpImage(interp))
						.tooltip(field.getName())
						.size(120).padBottom(32f);
					 if (++c % 3 == 0) table.row();
				 }
			 }
		 });
	 });
	private static void addImage(Table t, Drawable drawable) {
		Image image = new Image(drawable);
		image.fillParent = true;
		Label label = new Label(drawable instanceof TextureRegionDrawable ? "texture" : "nine");
		label.setColor(Color.lightGray);
		label.setFontScale(0.7f);
		label.fillParent = true;
		label.setAlignment(Align.topLeft);
		Stack stack = t.stack(image, label).size(32).get();
		stack.hovered(() -> label.visible = false);
		stack.exited(() -> label.visible = true);
	}
	private static int getW() {
		return Core.graphics.isPortrait() ? 300 : 400;
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
			t.clearChildren();
			t.add(new Element()).colspan(0).update(__ -> {
				t.background(IntUI.whiteui.tint(bgColor));
			}).left();
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
		static Table t;
		static void build(ScrollPaneStyle style) {
			t.pane(style, p -> {
				p.add("pane").row();
				p.add("test-test-test").color(Color.gray).row();
				p.add("test-test-test").color(Color.gray).row();
			}).growX().maxWidth(96).height(42);
		}
		static void build(DialogStyle style) {
			t.pane(p -> p.add(new Dialog("dialog", style))).growX().height(42);
		}
		static void build(LabelStyle style) {
			t.add("label", style).size(32);
		}
		static void build(SliderStyle style) {
			t.slider(0, 10, 1, f -> {})
			 .get().setStyle(style);
		}
		static void build(TextButtonStyle style) {
			t.button("text button", style, () -> {}).size(96, 42);
		}
		static void build(ImageButtonStyle style) {
			t.button(Icon.ok, style, () -> {}).size(96, 42);
		}
		static void build(ButtonStyle style) {
			t.button(b -> {
				b.add("button");
			}, style, () -> {}).size(96, 42);
		}
		static void build(TextFieldStyle style) {
			t.field("field", style, text -> {});
		}
		static void build(CheckBoxStyle style) {
			t.add(new CheckBox("checkbox", style)).height(42);
		}
		static void build(Drawable drawable) {
			addImage(t, drawable);
		}
	}

}