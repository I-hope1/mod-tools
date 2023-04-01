
package modtools.ui.content.ui;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.CheckBox;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.Slider.SliderStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.TextField.TextFieldStyle;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import modtools.ui.IntUI;
import modtools.ui.components.*;
import modtools.ui.content.Content;
import modtools.utils.*;
import modtools.utils.search.FilterTable;

import java.lang.reflect.*;
import java.util.regex.Pattern;

import static arc.scene.ui.CheckBox.CheckBoxStyle;
import static modtools.utils.Tools.sr;

public class ShowUIList extends Content {
	Window ui;

	public ShowUIList() {
		super("showuilist");
	}

	public void _load() {
		ui = new Window(localizedName(), getW(), 500, true);
		Seq<Table> tables = Seq.with(
				icons, tex, styles, colorsT
		);
		Color[] colors = {Color.sky, Color.gold, Color.orange, Color.acid};

		String[] names = {"icon", "tex", "styles", "colors"};
		IntTab   tab   = new IntTab(-1, new Seq<>(names), new Seq<>(colors), tables);
		tab.setPrefSize(getW(), -1);
		Table top  = new Table();
		Table wrap = new Table();
		ui.cont.add(top).growX().row();
		ui.cont.add(wrap).grow();
		new Search((cont, text) -> {
			if (!wrap.getChildren().isEmpty()) {
				try {
					pattern = Pattern.compile(text);
				} catch (Exception ignored) {
					pattern = null;
				}
				return;
				// tab.pane.getWidget().clear();
			}
			wrap.add(tab.build()).pad(10f).update(t -> {
				ui.minWidth = getW();
				tab.setPrefSize(getW(), -1);
			});
		}).build(top, ui.cont);
		//		ui.addCloseButton();
	}

	Pattern pattern;
	Table   icons = new FilterTable<>(t -> {
		t.clearChildren();
		Icon.icons.each((k, icon) -> {
			t.bind(k);
			var region = icon.getRegion();
			t.image(icon).size(32, region.height / (float) region.width * 32);
			t.add(k).with(JSFunc::addDClickCopy).growY().row();
			t.unbind();
		});
		t.addUpdateListener(() -> pattern);
	}), tex       = new FilterTable<>(t -> {
		Field[] fields = Tex.class.getFields();
		t.clearChildren();
		for (Field field : fields) {
			try {
				// 是否为Drawable
				if (!Drawable.class.isAssignableFrom(field.getType())) continue;
				t.bind(field.getName());
				// 跳过private检查，减少时间
				field.setAccessible(true);
				t.image((Drawable) field.get(null)).size(32);
			} catch (Exception err) {
				t.add();// 占位
				Log.err(err);
			} finally {
				t.add(field.getName()).with(JSFunc::addDClickCopy).growY().row();
				t.unbind();
			}

		}
		t.addUpdateListener(() -> pattern);
	}), styles    = new FilterTable<>(IntUI.whiteui.tint(1, 0.6f, 0.6f, 1), t -> {
		Field[] fields = Styles.class.getFields();

		t.clearChildren();
		Builder.t = t;
		for (Field field : fields) {
			try {
				// 跳过访问检查，减少时间
				field.setAccessible(true);
				Object style = field.get(null);
				t.bind(field.getName());
				sr(style)
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
			} catch (RuntimeException ignored) {}

			t.add(field.getName()).with(JSFunc::addDClickCopy).growY().row();
			t.unbind();
		}
		t.addUpdateListener(() -> pattern);

	}), colorsT   = new FilterTable<>(t -> {
		t.defaults().left().growX();
		t.addUpdateListener(() -> pattern);

		Cons<Class<?>> buildColor = cls -> {
			t.add(cls.getSimpleName()).color(Pal.accent).colspan(2).row();
			t.image().color(Pal.accent).colspan(2).row();
			Field[] fields = cls.getFields();

			for (Field field : fields) {
				if (!Modifier.isStatic(field.getModifiers())
				    || !Color.class.isAssignableFrom(field.getType())) continue;
				try {
					// 跳过private检查，减少时间
					field.setAccessible(true);
					Color color = (Color) field.get(null);

					t.bind(field.getName());
					t.add(new BorderImage(Core.atlas.white(), 2f)
							      .border(color.cpy().inv())).color(color).size(42f).with(b -> {
						IntUI.doubleClick(b, () -> {}, () -> {
							Vars.ui.picker.show(color, color::set);
						});
					});
					t.add(field.getName()).with(JSFunc::addDClickCopy).growY();
				} catch (IllegalAccessException | IllegalArgumentException err) {
					Log.err(err);
				} finally {
					t.unbind();
				}
				t.row();
			}
		};
		buildColor.get(Color.class);
		buildColor.get(Pal.class);
	});
	private static int getW() {
		return Core.graphics.isPortrait() ? 270 : 400;
	}
	public void build() {
		if (ui == null) _load();
		ui.show();
	}

	public static class Builder {
		static Table t;
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
			}, style, () -> {
			}).size(96, 42);
		}
		public static void build(TextFieldStyle style) {
			t.field("field", style, text -> {});
		}
		static void build(CheckBoxStyle style) {
			t.add(new CheckBox("checkbox", style)).height(42);
		}
		static void build(Drawable drawable) {
			t.table(drawable, __ -> {}).size(42);
		}
	}

}