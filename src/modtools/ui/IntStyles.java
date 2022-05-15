package modtools.ui;

import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.ScrollPane.ScrollPaneStyle;
import mindustry.ui.Styles;

public class IntStyles {
	public static ScrollPaneStyle nonePane = new ScrollPaneStyle();
	public static final ButtonStyle clearb = new ButtonStyle(Styles.defaultb) {
		{
			up = Styles.none;
			down = over = Styles.flatOver;
		}
	};
}
