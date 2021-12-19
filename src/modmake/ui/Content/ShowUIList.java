package modmake.ui.Content;

import modmake.ui.Components.IntTab;

import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.Slider.SliderStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.TextField.TextFieldStyle;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;

import java.lang.reflect.Field;

import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;

public class ShowUIList extends Content {

	public ShowUIList() {
		super("showuilist");
	}

	BaseDialog ui;

	public void load() {
		ui = new BaseDialog(this.name);
		Color[] colors = { Color.sky, Color.gold, Color.orange };
		Table[] tables = {
				// icon
				new Table(t -> {
					Icon.icons.each((k, icon) -> {
						t.image(new TextureRegionDrawable(icon)).size(32);
						t.add("" + k).row();
					});
				}),
				// tex
				new Table(t -> {
					Tex tex = new Tex();
					Field[] fields = tex.getClass().getFields();
					for (Field field : fields) {
						try {
							t.image((Drawable) field.get(tex)).size(32);
						} catch (IllegalArgumentException | IllegalAccessException e) {
							Log.err(e);
						}
						t.add(field.getName()).row();
					}
				}),
				// styles
				new Table(((TextureRegionDrawable) Tex.whiteui).tint(1f, 0.6f, 0.6f, 1f), t -> {
					Styles styles = new Styles();
					Field[] fields = styles.getClass().getFields();
					for (Field field : fields) {
						try {
							var style = field.get(styles);

							if (style instanceof LabelStyle) {
								t.add("label", (LabelStyle) style).size(32);
							} else if (style instanceof SliderStyle) {
								t.slider(0f, 10f, 1f, f -> {
								});
							} else if (style instanceof TextFieldStyle) {
								t.field("field", (TextFieldStyle) style, text -> {
								});
							} else if (style instanceof TextButtonStyle) {
								t.button("abcd", (TextButtonStyle) style, () -> {
								}).size(96, 42);
							} else if (style instanceof ImageButtonStyle) {
								t.button(Icon.ok, (ImageButtonStyle) style, () -> {
								}).size(96, 42);
							} else if (style instanceof ButtonStyle) {
								t.button(b -> b.add("ButtonStyle"), (ButtonStyle) style, () -> {
								}).size(260, 42);
							} else if (style instanceof Drawable) {
								t.table((Drawable) style, table -> {
								}).size(42);
							} else
								continue;
						} catch (IllegalArgumentException | IllegalAccessException e) {
							Log.err(e);
							continue;
						}

						t.add(field.getName()).row();
					}
				})
		};
		String[] names = { "icon", "tex", "styles" };

		IntTab tab = new IntTab(Vars.mobile ? 400f : 600f, new Seq<>(names), new Seq<>(colors), new Seq<>(tables));
		ui.cont.add(tab.build());
		ui.addCloseButton();
	}

	public void build() {
		ui.show();
	}
}