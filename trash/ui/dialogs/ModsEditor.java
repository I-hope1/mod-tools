package modmake.ui.dialogs;

import arc.Core;
import arc.files.Fi;
import arc.files.ZipFi;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.JsonValue;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.BorderImage;
import mindustry.ui.dialogs.BaseDialog;
import modmake.ui.components.JsonParser;
import modmake.ui.IntStyles;
import modmake.ui.IntUI;

public class ModsEditor extends BaseDialog {

	public ModsEditor() {
		super("$mods");
	}

	public static class Meta {
		public Fi file;
		public JsonValue meta;

		public Meta(Fi file) {
			this.file = file;
			this.meta = JsonParser.parse(
					file.child("mod.json").exists() ? file.child("mod.json").readString()
					: file.child("mod.hjson").exists() ? file.child("mod.hjson").readString() : "");
		}

		public Seq<Fi> spritesAll() {
			return file.child("sprites").exists() ? file.child("sprites").findAll() : new Seq<>();
		}

		public String displayName() {
			return (meta.has("displayName") ? meta.get("displayName") : meta.get("name")).asString();
		}

		public TextureRegion logo() {
			return file.child("sprites-override").child("logo.png").exists()
					? new TextureRegion(new Texture(file.child("sprites-override").child("logo.png")))
					: Core.atlas.find("error");
		}
	}

	Table pane;
	Fi[] mods = {};
	float h = 110,
			w = Vars.mobile ? (Core.graphics.getWidth() > Core.graphics.getHeight() ? 50 : 0) + 440 : 524;

	public void load() {
		IntUI.modEditor.load();
		IntUI.metaEditor.load();

		TextButtonStyle style = Styles.defaultt;
		float margin = 12;
		pane = new Table();

		cont.add("$mod.advice").top().row();
		cont.table(Styles.none, t -> t.pane(pane).fillX().fillY()).row();

		buttons.button("$back", Icon.left, style, this::hide).margin(margin).size(210, 60);
		buttons.button("$mod.add", Icon.add, style, () -> {
			BaseDialog dialog = new BaseDialog("$mod.add");
			TextButtonStyle bstyle = Styles.cleart;

			dialog.cont.table(Tex.button, t -> {
				t.defaults().left().size(300, 70);
				t.margin(12);

				t.button("$mod.import.file", Icon.file, bstyle, () -> {
					dialog.hide();

					Vars.platform.showMultiFileChooser(this::importMod, "zip", "jar");
				}).margin(12).row();
				t.button("$mod.add", Icon.add, bstyle, () -> {
					// ModMetaEditor.constructor(modsDirectory.child("tmp").child("mod.hjson"));
				}).margin(12);
			});
			dialog.addCloseButton();
			dialog.show();
		}).margin(margin).size(210, 64).row();

		if (!Vars.mobile)
			buttons.button("$mods.openfolder", Icon.link, style, () -> Core.app.openFolder(dataDirectory.absolutePath())).margin(margin).size(210, 64);
		buttons.button("$quit", Icon.exit, style, () -> Core.app.exit()).margin(margin).size(210, 64);

		addCloseListener();
	}

	void importMod(Fi file) {
		try {
			Fi toFile = modsDirectory.child(file.nameWithoutExtension());
			ZipFi zipFile = new ZipFi(file);
			Seq<Fi> dirs = new Seq<>();
			// if (!zipFile.child("mod.json").exists() &&
			// !zipFile.child("mod.hjson").exists()) throw ("请导入合法的mod");
			while (true) {
				Fi[] list = zipFile.list();
				for (Fi f : list) {
					if (f.isDirectory())
						dirs.add(f);
					else
						toFile.child(f.name()).writeString(f.readString());
				}
				if (dirs.size == 0)
					break;
				zipFile = (ZipFi) dirs.remove(dirs.size - 1);
				toFile = toFile.child(zipFile.name());
			}

			load();
		} catch (Throwable e) {
			Log.err(e);
			Vars.ui.showErrorMessage(e.getMessage());
		}
	}

	Fi dataDirectory = Vars.dataDirectory.child("mods(I hope...)"), modsDirectory = dataDirectory.child("mods");

	public void build() {
		Vars.ui.loadfrag.show();

		pane.clearChildren();
		mods = modsDirectory.list();
		if (mods.length == 0) {
			pane.table(Styles.black6, t -> t.add("$mods.none")).height(80);
			show();
			Vars.ui.loadfrag.hide();
			return;
		}

		for (Fi file : mods) {
			if (file.name() == "tmp")
				return;
			Meta mod = new Meta(file);
			if (mod.meta == null)
				return;

			pane.button(b -> {
				b.top().left();
				b.margin(12);
				b.defaults().left().top();

				b.table(title -> {
					title.left();

					BorderImage image = new BorderImage() {
					};
					if (mod.file.child("icon.png").exists()) {
						image.setDrawable(
								new TextureRegion(new Texture(mod.file.child("icon.png"))));
					} else {
						image.setDrawable(Tex.nomap);
					}
					image.border(Pal.accent);
					title.add(image).size(h - 8).padTop(-8).padLeft(-8).padRight(8);

					title.table(text -> text.add("[accent]" + /* Strings.stripColors */mod.displayName() + "\n[lightgray]v" +
							mod.meta.get("version").asString()).wrap().width(300).growX().left()).top().growX();

					title.add().growX().left();
				});
				b.table(right -> {
					right.right();
					right.button(Icon.edit, Styles.clearPartiali,
							() -> IntUI.metaEditor.build(mod.file.child("mod.json").exists() ? mod.file.child("mod.json") : mod.file.child("mod.hjson")
							)).size(50);
					right.button(Icon.trash, Styles.clearPartiali,
							() -> Vars.ui.showConfirm("$confirm", "$mod.remove.confirm", () -> {
								file.deleteDirectory();
								build();
							})).size(50).row();
					right.button(Icon.upload, Styles.clearPartiali, () -> {
						Fi modDir = Vars.modDirectory;

						Runnable upload = () -> {
							modDir.child(mod.file.name()).deleteDirectory();
							mod.file.copyTo(modDir);
						};
						if (modDir.child(mod.file.name()).exists())
							Vars.ui.showConfirm("替换", "同名文件已存在\n是否要替换",
									upload);
						else
							upload.run();
					}).size(50);
					right.button(Icon.link, Styles.clearPartiali,

							() -> Core.app.openFolder(mod.file.absolutePath())).size(50);
				}).growX().right().padRight(-8).padTop(-8);
			}, IntStyles.clearb, () -> IntUI.modEditor.build(mod)).size(w, h).growX().pad(4).row();
		}

		show();
		Vars.ui.loadfrag.hide();
	}
}
