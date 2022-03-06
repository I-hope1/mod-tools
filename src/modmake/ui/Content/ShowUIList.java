package modmake.ui.content;

import arc.Core;
import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.Slider.SliderStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.TextField.TextFieldStyle;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import modmake.ui.components.IntTab;
import modmake.ui.IntUI;

import java.lang.reflect.Field;

public class ShowUIList extends Content {

	public ShowUIList() {
		super("showuilist");
	}

	BaseDialog ui;

	@Override
	public void load() {
		ui = new BaseDialog(this.name);
		Color[] colors = { Color.sky, Color.gold, Color.orange };
		Table[] tables = {
				// Icon
				new Table(t -> Icon.icons.each((k, icon) -> {
					t.image(new TextureRegionDrawable(icon)).size(32);
					t.add("" + k).with(l -> l.clicked(() -> Core.app.setClipboardText("" + l.getText()))).row();
				})),
				// Tex
				new Table(t -> {
					Field[] fields = Tex.class.getFields();
					for (Field field : fields) {
						try {
							t.image((Drawable) field.get(null)).size(32);
						} catch (IllegalArgumentException | IllegalAccessException e) {
							Log.err(e);
						}
						t.add(field.getName()).with(l -> l.clicked(() -> Core.app.setClipboardText("" + l.getText())))
								.row();
					}
				}),
				// Styles
				new Table(IntUI.whiteui.tint(1f, 0.6f, 0.6f, 1f), t -> {
					Field[] fields = Styles.class.getFields();
					for (Field field : fields) {
						try {
							var style = field.get(null);

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

						t.add(field.getName()).with(l -> l.clicked(() -> Core.app.setClipboardText("" + l.getText())))
								.row();
					}
				})
		};
		String[] names = { "icon", "tex", "styles" };

		IntTab tab = IntTab.set(Vars.mobile ? 400f : 600f, new Seq<>(names), new Seq<>(colors), new Seq<>(tables));
		ui.cont.add(tab.build());
		ui.addCloseButton();
	}

	@Override
	public void build() {
		ui.show();
	}
}