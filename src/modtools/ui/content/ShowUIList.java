
package modtools.ui.content;

import arc.Core;
import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.CheckBox;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.Label;
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
import modtools.ui.IntUI;
import modtools.ui.components.IntTab;
import modtools.ui.components.Window;
import modtools.utils.Tools;

import java.lang.reflect.Field;

import static arc.scene.ui.CheckBox.CheckBoxStyle;

public class ShowUIList extends Content {
	Window ui;

	public ShowUIList() {
		super("showuilist");
	}

	public void copyText(Label label) {
		IntUI.showInfoFade(Core.bundle.format("IntFunc.copied", label.getText())).setPosition(Tools.getAbsPos(label));
		Core.app.setClipboardText(label.getText().toString());
	}

	public void _load() {
		ui = new Window(localizedName(), 400, 500, true);
		Color[] colors = {Color.sky, Color.gold, Color.orange};
		Seq<Table> tables = Seq.with(
				new Table(t -> {
					Icon.icons.each((k, icon) -> {
						t.image(new TextureRegionDrawable(icon)).size(32);
						t.add("" + k).with(l -> l.clicked(() -> copyText(l))).growY().row();
					});
				}),
				new Table(t -> {
					Field[] fields = Tex.class.getFields();

					for (Field field : fields) {
						try {
							// 跳过private检查，减少时间
							field.setAccessible(true);
							t.image((Drawable) field.get(null)).size(32);
						} catch (IllegalAccessException | IllegalArgumentException err) {
							Log.err(err);
						}

						t.add(field.getName()).with(l -> l.clicked(() -> copyText(l))).growY().row();
					}

				}),
				new Table(IntUI.whiteui.tint(1, 0.6f, 0.6f, 1), t -> {
					Field[] fields = Styles.class.getFields();

					for (Field field : fields) {
						try {
							// 跳过访问检查，减少时间
							field.setAccessible(true);
							Object style = field.get(null);
							if (style instanceof LabelStyle) {
								t.add("label", (LabelStyle) style).size(32);
							} else if (style instanceof SliderStyle) {
								t.slider(0, 10, 1, f -> {
								});
							} else if (style instanceof TextFieldStyle) {
								t.field("field", (TextFieldStyle) style, text -> {
								});
							} else if (style instanceof CheckBoxStyle) {
								t.add(new CheckBox("checkbox", (CheckBoxStyle) style)).height(42);
							} else if (style instanceof TextButtonStyle) {
								t.button("text button", (TextButtonStyle) style, () -> {}).size(96, 42);
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

						t.add(field.getName()).with(l -> l.clicked(() -> copyText(l))).growY().row();
					}

				}));

		String[] names = {"icon", "tex", "styles"};
		IntTab tab = IntTab.set(Vars.mobile ? 400 : 600, new Seq<>(names), new Seq<>(colors), tables);
		ui.cont.add(tab.build()).pad(10f);
		//		ui.addCloseButton();
	}

	public void build() {
		if (ui == null) _load();
		ui.show();
	}
}
