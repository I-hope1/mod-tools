package modtools.ui;

import arc.graphics.Color;
import arc.scene.style.*;
import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.ScrollPane.ScrollPaneStyle;
import arc.scene.ui.Slider.SliderStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import mindustry.core.Version;
import mindustry.gen.Tex;
import mindustry.ui.*;

import static mindustry.gen.Tex.*;
import static mindustry.ui.Styles.*;
import static modtools.ui.IntUI.whiteui;

public class HopeStyles {
	public static final TextButtonStyle blackt;
	public static final ButtonStyle     clearb;
	public static final Drawable        none = whiteui.tint(0f, 0f, 0f, 0.01f);

	/** 默认使用等宽字体，没有的话使用默认字体 */
	public static final LabelStyle MOMO_LabelStyle;
	// public static final CheckBoxStyle checkbox;


	public static ImageButtonStyle
	 hope_clearNonei,
	 hope_clearNoneTogglei;
	public static ButtonStyle
	 hope_defaultb;
	public static SliderStyle
	 hope_defaultSlider;

	/* ---------TODO：以下是为了适配V6----------- */


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
		/* checkbox = new CheckBoxStyle() {{
			checkboxOn = checkOn;
			checkboxOff = checkOff;
			checkboxOnOver = checkOnOver;
			checkboxOver = checkOver;
			checkboxOnDisabled = checkOnDisabled;
			checkboxOffDisabled = checkDisabled;
			font = Fonts.def;
			fontColor = Color.white;
			disabledFontColor = Color.gray;
		}}; */
		init();
		hope_clearNonei = new ImageButtonStyle(clearNonei) {{
			down = ((TextureRegionDrawable) over).tint(Color.gray);
		}};
		hope_clearNoneTogglei = new ImageButtonStyle(clearNoneTogglei) {{
			over = flatOver;
			checkedOver = flatDown;
			checked = down = buttonSelect;
		}};
		hope_defaultb = new ButtonStyle(clearNonei);
		/* hope_defaultSlider = new SliderStyle() {{
			background = sliderBack;
			knob = sliderKnob;
			knobOver = sliderKnobOver;
			knobDown = sliderKnobDown;
		}}; */
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