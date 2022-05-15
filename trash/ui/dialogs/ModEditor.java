package modmake.ui.dialogs;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons2;
import arc.graphics.Color;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.scene.event.HandCursorListener;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Dialog;
import arc.scene.ui.Image;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.serialization.JsonValue;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import modmake.ui.components.IntTab;
import modmake.ui.components.TabSelection;
import modmake.ui.IntFunc;
import modmake.ui.IntUI;

public class ModEditor extends BaseDialog {
	public ModEditor() {
		super("");
	}

	// 语言
	Seq<String> bundles = Seq.with(
			"bundle",
			"bundle_be",
			"bundle_cs",
			"bundle_da",
			"bundle_de",
			"bundle_es",
			"bundle_et",
			"bundle_eu",
			"bundle_fi",
			"bundle_fil",
			"bundle_fr",
			"bundle_fr_BE",
			"bundle_hu",
			"bundle_in_ID",
			"bundle_it",
			"bundle_ja",
			"bundle_ko",
			"bundle_lt",
			"bundle_nl",
			"bundle_nl_BE",
			"bundle_pl",
			"bundle_pt_BR",
			"bundle_pt_PT",
			"bundle_ro",
			"bundle_ru",
			"bundle_sv",
			"bundle_th",
			"bundle_tk",
			"bundle_tr",
			"bundle_uk_UA",
			"bundle_zh_CN",
			"bundle_zh_TW");

	ObjectMap<String, ObjectMap<String, String>> frameworks = ObjectMap.of(
			"block", ObjectMap.of(
					"No Framework", "",
					"Block",
					"type: Block\nname: \"\"\ndescription: \"\"\nhealth: 40\nupdate: true\nresearch: core-shard\nrequirements: []\ncategory: distribution"),
			"item", ObjectMap.of(
					"Item",
					"name: \"\"\ndescription: \"\"\nexplosiveness: 0\nflammability: 0\nradioactivity: 0\ncost: 1"),
			"liquid", ObjectMap.of(
					"Liquid",
					"name: \"\"\ndescription: \"\"\ncolor: \"000000\"\nflammability: 0\nviscosity: 0.5\nexplosiveness: 0.1\nlightColor: \"00000000\"\nheatCapacity: 0.5\ntemperature: 0.5\neffect: none"));

	Table desc;
	float w = Core.graphics.getWidth() > Core.graphics.getHeight() ? 540 : 440;

	public void load() {
		IntUI.jsonDialog.load();

		addCloseButton();

		desc = new Table();
		desc.center();
		desc.defaults().padTop(10).left();

		cont.pane(desc).fillX().fillY().get().setScrollingDisabled(true, false);

		addCloseListener();
	}

	public ModEditor build(ModsEditor.Meta mod) {
		JsonValue meta = mod.meta;
		String displayName = mod.displayName();
		title.setText(displayName);

		desc.clearChildren();

		if (meta.size == 0) {
			desc.add("$error", Color.red);
			return (ModEditor) show();
		}

		TextureRegion logo = mod.logo();
		if (logo != Core.atlas.find("error"))
			desc.add(new Image(logo)).row();

		desc.add("$editor.name", Color.gray).padRight(10).padTop(0).row();
		desc.add(displayName).growX().wrap().padTop(2).row();

		if (meta.has("author")) {
			desc.add("$editor.author", Color.gray).padRight(10).row();
			desc.add(meta.get("author").asString()).growX().wrap().padTop(2).row();
		}
		if (meta.has("version")) {
			desc.add("$editor.version", Color.gray).padRight(10).row();
			desc.add(meta.get("version").asString()).growX().wrap().padTop(2).row();
		}
		if (meta.has("description")) {
			desc.add("$editor.description").padRight(10).color(Color.gray).top().row();
			desc.add(meta.get("description").asString()).growX().wrap().padTop(2).row();
		}

		desc.row();
		IntTab tab = IntTab.set(w, Seq.with("$editor.content", "$bundles", "$scripts"),
				Seq.with(Color.gold, Color.pink, Color.sky),
				Seq.with(/* content */new Table(Styles.none, t -> {
					t.center();
					t.defaults().padTop(10).left();
					Fi content = mod.file.child("content");
					Table cont = new Table();
					cont.defaults().padTop(10).left();
					String filter = "";
					var ref1 = new Object() {
						public Runnable setup = () -> {
						};
					};
					ref1.setup = () -> {
						cont.clearChildren();
						Table body = new Table();
						body.top().left();
						body.defaults().padTop(2).top().left();
						cont.pane(p -> p.add(body).left().grow().get().left()).fillX().minWidth(450).row();
						String reg = "^\\/.+?\\/$";
						content.findAll().each(json -> body.button(b -> {
							b.left();
							Image image = b.image(IntFunc.find(mod, json.nameWithoutExtension())).size(32)
									.padRight(6).left().get();
							if (!Vars.mobile)
								image.addListener(new HandCursorListener());
							b.add(json.name()).top();
							IntUI.longPress(b, 600, longPress -> {
								if (longPress) {
									Vars.ui.showConfirm("$confirm",
											Core.bundle.format("confirm.remove", json.nameWithoutExtension()),
											() -> {
												json.delete();
												ref1.setup.run();
											});
								} else {
									JsonDialog _dialog = IntUI.jsonDialog.show(json, mod);
									if (_dialog != null)
										_dialog.hidden(ref1.setup);
								}
							});
						}, Styles.defaultb, () -> {
						}).fillX().minWidth(400).pad(2).padLeft(4).left().row());
					};
					ref1.setup.run();
					t.add("$content.info").row();
					t.add(cont).growX().width(w).row();
					t.button("$add", Icon.add, () -> {
						Dialog ui = new Dialog();
						TextField name = new TextField();
						ui.cont.table(t1 -> {
							t1.add("$name");
							t1.add(name).fillX();
						}).fillX().row();
						Seq<String> keys = new Seq<>();
						Seq<String> values = new Seq<>();
						ObjectMap<Integer, String> ints = new ObjectMap<>();
						var ref = new Object() {
							public int i = 0, selected = 0;
							public String key, type;
						};
						frameworks.each((clazz, value) -> {
							value.each((k, v) -> {
								ref.i++;
								keys.add(k);
								values.add(v);
							});
							ints.put(ref.i, clazz);
						});
						Table p = TabSelection.build(keys, () -> ref.key, (item, i) -> {
							ref.key = item;
							ref.selected = i;
							ints.each((j, clazz) -> {
								if (i > j)
									ref.type = clazz;
							});
						}, Styles.clearTogglet, 150, 62, 2);
						ui.cont.pane(p).width(300).height(400);
						ui.buttons.button("$back", ui::hide).size(150, 64);
						ui.buttons.button("$ok", () -> {
							Fi file = content.child(ref.type + "s").child(name.getText() + ".json");
							file.writeString(values.get(ref.selected));
							/* dialog.hide(); */
							ui.hide();
							/* Editor.edit(file, mod); */
						}).size(150, 64);
						/* Editor.ui.hidden(run(() => setup())); */
						ui.show();
					}).width(w - 20f).row();
					Fi spritesDirectory = mod.file.child("sprites");
					t.button("查看图片库", () -> {
						BaseDialog ui = new BaseDialog("图片库");
						Cons2<Table, Fi> buildImage = (_t, file) -> {
							if (file.extension() != "png")
								return;
							_t.table(table -> {
								table.left();
								TextField field = table.field(file.nameWithoutExtension(), text -> {
									Fi toFile = file.parent().child(text + ".png");
									file.moveTo(toFile);
								}).growX().left().get();
								t.row();
								t.image().color(Color.gray).minWidth(440).row();
								t.image(new TextureRegion(new Texture(file))).size(96);
							}).padTop(10).left().row();
						};
						Table _cont = new Table(t12 -> {
							t12.top();
							Seq<Fi> all = mod.spritesAll();
							for (Fi f : all) {
								buildImage.get(t12, f);
							}
						});
						ui.cont.pane(_cont).fillX().fillY();
						ui.addCloseButton();
						ui.buttons.button("$add", Icon.add,
								() -> IntFunc.selectFile(true, "import file to add sprite", "png", f -> {
									Fi toFile = spritesDirectory.child(f.name());
									Runnable go = () -> {
										f.copyTo(toFile);
										buildImage.get(cont, f);
									};
									if (toFile.exists())
										Vars.ui.showConfirm("$confirm", "是否要覆盖", go);
									else
										go.run();
								})).size(90, 64);
						ui.hidden(ref1.setup);
						ui.show();
					}).width(w - 20f);
				}), /* bundles */new Table(((TextureRegionDrawable) Tex.whiteui).tint(1, .8f, 1, .8f),
						table -> bundles.each(v -> {
							table.add(Core.bundle.get("bundle." + v, v)).padLeft(4).width(400).left();
							table.button(Icon.pencil, Styles.clearTransi, () -> {
								/* Editor.edit(mod.file.child("bundles").child(v +".properties"), mod) */
							}).growX()
									.right()
									.pad(10).row();
							/*
							 * if (Core.graphics.getWidth() > Core.graphics.getHeight() && i % 2== 1)
							 * t.row();
							 */
						})),
						/* scripts */new Table(((TextureRegionDrawable) Tex.whiteui).tint(.7f, .7f, 1, .8f),
								table -> table.add("未完成"))));

		desc.add(tab.build());

		return (ModEditor) show();
	}
}
