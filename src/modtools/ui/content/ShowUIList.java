
package modtools.ui.content;

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
import modtools.ui.IntUI;
import modtools.ui.components.IntTab;

import java.lang.reflect.Field;

public class ShowUIList extends Content {
	BaseDialog ui;

	public ShowUIList() {
		super("showuilist");
	}

	public void load() {
		ui = new BaseDialog(name);
		Color[] colors = {Color.sky, Color.gold, Color.orange};
		Seq<Table> tables = Seq.with(
				new Table(t -> {
					Icon.icons.each((k, icon) -> {
						t.image(new TextureRegionDrawable(icon)).size(32);
						t.add("" + k).with(l -> {
							l.clicked(() -> {
								Core.app.setClipboardText("" + l.getText());
								Vars.ui.showInfoFade("已复制");
							});
						}).row();
					});
				}),
				new Table(t -> {
					Field[] fields = Tex.class.getFields();

					for (Field field : fields) {
						try {
							t.image((Drawable) field.get(null)).size(32);
						} catch (IllegalAccessException | IllegalArgumentException var7) {
							Log.err(var7);
						}

						t.add(field.getName()).with(l -> {
							l.clicked(() -> {
								Core.app.setClipboardText("" + l.getText());
							});
						}).row();
					}

				}),
				new Table(IntUI.whiteui.tint(1, 0.6f, 0.6f, 1), t -> {
					Field[] fields = Styles.class.getFields();

					for (Field field : fields) {
						try {
							Object style = field.get(null);
							if (style instanceof LabelStyle) {
								t.add("label", (LabelStyle) style).size(32);
							} else if (style instanceof SliderStyle) {
								t.slider(0, 10, 1, f -> {
								});
							} else if (style instanceof TextFieldStyle) {
								t.field("field", (TextFieldStyle) style, text -> {
								});
							} else if (style instanceof TextButtonStyle) {
								t.button("text button", (TextButtonStyle) style, () -> {
								}).size(96, 42);
							} else if (style instanceof ImageButtonStyle) {
								t.button(Icon.ok, (ImageButtonStyle) style, () -> {
								}).size(96, 42);
							} else if (style instanceof ButtonStyle) {
								t.button(b -> {
									b.add("button");
								}, (ButtonStyle) style, () -> {
								}).size(260, 42);
							} else {
								if (!(style instanceof Drawable)) continue;

								t.table((Drawable) style, __ -> {}).size(42);
							}
						} catch (IllegalAccessException | IllegalArgumentException err) {
							Log.err(err);
							continue;
						}

						t.add(field.getName()).with(l -> {
							l.clicked(() -> {
								Core.app.setClipboardText("" + l.getText());
							});
						}).row();
					}

				}));

		String[] names = {"icon", "tex", "styles"};
		IntTab tab = IntTab.set(Vars.mobile ? 400 : 600, new Seq<>(names), new Seq<>(colors), tables);
		ui.cont.add(tab.build());
		ui.addCloseButton();
	}

	public void build() {
		ui.show();
	}
}