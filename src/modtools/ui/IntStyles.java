package modtools.ui;

import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.ScrollPane.ScrollPaneStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import mindustry.gen.Tex;
import mindustry.ui.Styles;

public class IntStyles {
	public static ScrollPaneStyle nonePane = new ScrollPaneStyle();
	public static final TextButtonStyle cleart;
	public static final ButtonStyle clearb;

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
	}
}
