package test_arc;

import arc.*;
import arc.Files.FileType;
import arc.assets.AssetManager;
import arc.backend.sdl.*;
import arc.files.Fi;
import arc.freetype.FreeTypeFontGenerator;
import arc.freetype.FreeTypeFontGenerator.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.Mathf;
import arc.scene.Scene;
import arc.scene.actions.Actions;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Dialog;
import arc.scene.ui.Dialog.DialogStyle;
import arc.scene.ui.Label.LabelStyle;
import arc.util.*;


public class Main {
	static Font MSYHMONO;
	public static Watch watch = new Watch() {
		@Override
		public void watch(String title, MyProv<CharSequence> text) {
			Main.watch(title, text);
		}
	};
	;

	public static void main(String[] args) throws Exception {
		watch("aaa", () -> String.valueOf(System.nanoTime()));
	}

	public static void watch(String title, MyProv<CharSequence> text) {
		new SdlApplication(new ApplicationCore() {

			@Override
			public void update() {
				super.update();

				// renderer.draw();
				// Gl.clearColor(0, 0, 0, 0);
				Core.graphics.clear(Color.clear);
				Draw.shader();

				Core.scene.act();
				Core.scene.draw();
				// Draw.color(Color.red);
				// Fill.crect(0, 0, Cor e.graphics.getWidth(), Core.graphics.getHeight());
				// MSYHMONO.setColor(Color.white);
				// MSYHMONO.draw("aaa", Core.graphics.getWidth() / 2f, Core.graphics.getHeight() / 2f, Align.center).free();
			}

			@Override
			public void setup() {
				Core.assets = new AssetManager();
				// renderer = new LoadRenderer();

				// Core.assets.getAll(Font.class, new Seq<>()).each(font -> font.setUseIntegerPositions(true));
				Core.scene = new Scene();
				Time.setDeltaProvider(() -> {
					float result = Core.graphics.getDeltaTime() * 60f;
					return (Float.isNaN(result) || Float.isInfinite(result)) ? 1f : Mathf.clamp(result, 0.0001f, 60f / 10f);
				});
				Core.batch = new SortedSpriteBatch();
				loadFont();
				Core.input.addProcessor(Core.scene);
				int[] insets = Core.graphics.getSafeInsets();
				Core.scene.marginLeft = insets[0];
				Core.scene.marginRight = insets[1];
				Core.scene.marginTop = insets[2];
				Core.scene.marginBottom = insets[3];
				Core.assets.load("sprites/error.png", Texture.class);
				Core.atlas = TextureAtlas.blankAtlas();

				var whiteui = new TextureRegionDrawable(Pixmaps.blankTextureRegion());
				var black = whiteui.tint(0, 0, 0, 0.9f);
				Core.scene.addStyle(DialogStyle.class, new DialogStyle() {{
					stageBackground = black;
					titleFont = MSYHMONO;
					background = black;
					titleFontColor = Color.valueOf("ffd37f");
				}});
				Core.scene.addStyle(LabelStyle.class, new LabelStyle() {{
					font = MSYHMONO;
					fontColor = Color.white;
				}});
				Dialog.setShowAction(() -> Actions.sequence(Actions.alpha(0f), Actions.fadeIn(0.1f)));
				Dialog.setHideAction(() -> Actions.sequence(Actions.fadeOut(0.1f)));

				new Dialog(title) {{
					title.setColor(Color.acid);
					cont.label(text::get);
				}}.show();

				/*Core.scene.root.fill((x, y, w, h) -> {
					Draw.color(Color.white);
					Fill.rect(x, y, w, h);
				});*/
				// new Dialog("aaa").show();
			}

			public void resize(int width, int height) {
				if (Core.assets == null) return;

				Draw.proj().setOrtho(0, 0, width, height);
				Core.scene.resize(width, height);
			}
		}, new SdlConfig() {{
			title = "watch";
			width = 200;
			height = 100;

			setWindowIcon(FileType.internal, "icons/icon_64.png");
		}});
	}

	static void loadFont() {
		String dataDir = OS.env("MINDUSTRY_DATA_DIR");
		if (dataDir != null) {
			Core.settings.setDataDirectory(Core.files.absolute(dataDir));
		}
		Fi fontFi = Core.settings.getDataDirectory().child("b0kkihope").child("font.ttf");
		FreeTypeFontGenerator generator = new FreeTypeFontGenerator(fontFi);
		FreeTypeFontParameter parameter = new FreeTypeFontParameter() {{
			size = 22;
			shadowColor = Color.darkGray;
			shadowOffsetY = 2;
			incremental = true;
		}};
		// final boolean[] generating = {false};
		MSYHMONO = generator.generateFont(parameter, new FreeTypeFontData() {{
			markupEnabled = true;
		}});

		MSYHMONO.setOwnsTexture(false);
	}
}
