package modtools.ui;

import arc.scene.style.Drawable;
import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.ScrollPane.ScrollPaneStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import mindustry.gen.Tex;
import mindustry.ui.Styles;

import static modtools.ui.IntUI.whiteui;

public class IntStyles {
	public static ScrollPaneStyle nonePane = new ScrollPaneStyle();
	public static final TextButtonStyle cleart;
	public static final ButtonStyle clearb;
	public static final Drawable none = whiteui.tint(0f, 0f, 0f, 0.01f);


	public static final LabelStyle myLabel;

	static {
		cleart = new TextButtonStyle(Styles.cleart) {{
			up = Tex.pane;
			over = Tex.flatDownBase;
			down = Styles.flatDown;
		}};
		clearb = new ButtonStyle(Styles.defaultb) {{
			up = Styles.none;
			down = over = Styles.flatOver;
		}};

		myLabel = new LabelStyle(Styles.defaultLabel) {{
			font = MyFonts.MSYHMONO;
		}};
	}
}
