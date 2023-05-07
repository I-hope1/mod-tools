package modtools.ui;

import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.ScrollPane.ScrollPaneStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import mindustry.core.Version;
import mindustry.gen.Tex;
import mindustry.ui.*;

import static mindustry.gen.Tex.*;
import static mindustry.ui.Styles.*;
import static modtools.ui.IntUI.whiteui;

public class IntStyles {
	public static final TextButtonStyle blackt;
	public static final ButtonStyle     clearb;
	public static final Drawable        none = whiteui.tint(0f, 0f, 0f, 0.01f);

	/** 默认使用等宽字体，没有的话使用默认字体 */
	public static final LabelStyle MOMO_LabelStyle;


	/** Similar to flatToggle, but with a transparent base background. */
	public static TextButtonStyle flatToggleMenut,
	/** Flat, square, toggleable. */
	flatTogglet,
	/** Flat, square, opaque. */
	flatt,
	/** Flat, square, gray border. */
	flatBordert,
	/** Partially transparent square button. */
	cleart;

	/** Flat, square, black background. */
	public static ImageButtonStyle flati,
	/** clearNone, but toggleable. */
	clearNoneTogglei,
	/** No background unless focused, no border. */
	clearNonei;
	public static ScrollPaneStyle noBarPane = new ScrollPaneStyle();
	public static ButtonStyle     flatb;

	static void V6Adapter() {
		flatt = new TextButtonStyle() {{
			over = flatOver;
			font = Fonts.def;
			fontColor = Color.white;
			disabledFontColor = Color.gray;
			down = flatOver;
			up = black;
		}};
		flati = new ImageButtonStyle() {{
			down = flatOver;
			up = black;
			over = flatOver;
		}};
		flatTogglet = new TextButtonStyle() {{
			font = Fonts.def;
			fontColor = Color.white;
			checked = flatDown;
			down = flatDown;
			up = black;
			over = flatOver;
			disabled = black;
			disabledFontColor = Color.gray;
		}};
		flatToggleMenut = new TextButtonStyle() {{
			font = Fonts.def;
			fontColor = Color.white;
			checked = flatDown;
			down = flatDown;
			up = clear;
			over = flatOver;
			disabled = black;
			disabledFontColor = Color.gray;
		}};
		flatBordert = new TextButtonStyle() {{
			down = flatOver;
			up = pane;
			over = flatDownBase;
			font = Fonts.def;
			fontColor = Color.white;
			disabledFontColor = Color.gray;
		}};
		clearNoneTogglei = new ImageButtonStyle() {{
			down = flatDown;
			checked = flatDown;
			up = none;
			over = flatOver;
		}};
		clearNonei = new ImageButtonStyle() {{
			down = flatDown;
			up = none;
			over = flatOver;
			disabled = none;
			imageDisabledColor = Color.gray;
			imageUpColor = Color.white;
		}};
		cleart = new TextButtonStyle() {{
			down = flatDown;
			up = none;
			over = flatOver;
			font = Fonts.def;
			fontColor = Color.white;
			disabledFontColor = Color.gray;
		}};
	}

	static {
		blackt = new TextButtonStyle(Styles.cleart) {{
			up = pane;
			over = Tex.flatDownBase;
			down = flatDown;
		}};
		clearb = new ButtonStyle(Styles.defaultb) {{
			up = Styles.none;
			down = over = Styles.flatOver;
		}};

		MOMO_LabelStyle = new LabelStyle(Styles.defaultLabel) {{
			font = MyFonts.def;
		}};
		init();
		flatb = new ButtonStyle(flatt);
	}

	static void init() {
		if (Version.number <= 135) {
			V6Adapter();
			return;
		}
		flatt = Styles.flatt;
		flati = Styles.flati;
		flatTogglet = Styles.flatTogglet;
		flatToggleMenut = Styles.flatToggleMenut;
		flatBordert = Styles.flatBordert;
		clearNoneTogglei = Styles.clearNoneTogglei;
		clearNonei = Styles.clearNonei;
		cleart = Styles.cleart;
	}
}
