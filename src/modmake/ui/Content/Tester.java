package modmake.ui.content;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.func.Cons2;
import arc.func.Func;
import arc.scene.Action;
import arc.scene.Scene;
import arc.scene.ui.Button;
import arc.scene.ui.Dialog;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.mod.Mods;
import mindustry.mod.Scripts;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import modmake.IntVars;
import modmake.ModMake;
import modmake.ui.IntStyles;
import modmake.ui.IntUI;
import modmake.ui.components.IntTextArea;

import java.util.ArrayList;
import java.util.Comparator;

public class Tester extends Content {
	public final Scripts scripts = new Scripts();
	String log = "";
	IntTextArea area;
	boolean loop = false, wrap = false; // scope: false,
	final float w = Core.graphics.getWidth() > Core.graphics.getHeight() ? 540f : 440f;
	public final Fi record = Vars.dataDirectory.child("mods(I hope...)").child("historical record"),
			bookmarkFi = Vars.dataDirectory.child("mods(I hope...)").child("bookmarks");

	public Tester() {
		super("tester");
	}

	BaseDialog ui;
	ListDialog history;
	ListDialog bookmark;

	public String linesStr() {
		int first = area.getFirstLineShowing(),
				len = area.getLinesShowing() - 1,
				now = area.getCursorLine();
		var str = new StringBuilder("[lightgray]");
		for (var i = 0; i < len; i++) {
			if (i + first == now) str.append("[gold]");
			str.append(i + first + 1);
			if (i + first == now) str.append("[]");
			str.append("\n");
		}
		return str + "[]";
	}

	public void show(Table table, Table buttons) {
		Table cont = new Table();

		cont.table(t -> {
			t.label(this::linesStr);
			t.add(area = new IntTextArea("")).size(w, 390);
		}).row();

		cont.button("$ok", () -> {
			area.setText(getMessage().replaceAll("\r", ""));
			evalMessage();
			if (record != null) {
				Seq<Fi> seq = new Seq<>(record.list());
				ArrayList<String> list = new ArrayList<>();
				seq.each(f -> list.add(f.name()));
				list.sort(Comparator.naturalOrder());
				/* 限制30个 */
				for (int i = 0; i < list.size() - 29; i++) {
					record.child(list.get(i)).deleteDirectory();
				}
				Fi d = record.child(Time.millis() + "");
				d.child("message.txt").writeString(getMessage());
				d.child("log.txt").writeString(log);
			}
		}).row();

		cont.table(Tex.button, t -> t.pane(p -> p.label(() -> log)).size(w, 390f));

		table.add(cont).row();

		table.pane(p -> {
			p.button("", Icon.star, Styles.cleart, () -> bookmarkFi
					.child(bookmarkFi.list().length + "-"
							+ Time.millis() + ".txt")
					.writeString(getMessage()));
			p.button(b -> b.label(() -> loop ? "$loop" : "$default"), Styles.defaultb, () -> loop = !loop).size(100f,
					55f);
			p.button(b -> b.label(() -> wrap ? "严格" : "非严格"), Styles.defaultb, () -> wrap = !wrap).size(100f, 55f);

			p.button("$historicalRecord", () -> history.show()).size(100, 55);
			p.button("$bookmark", () -> bookmark.show()).size(100f, 55f);
		}).height(60f).fillX();

		buttons.button("$back", Icon.left, () -> ui.hide()).size(210, 64);

		BaseDialog dialog = new BaseDialog("$edit");

		dialog.cont.pane(p -> {
			p.margin(10);
			p.table(Tex.button, t -> {
				TextButtonStyle style = Styles.cleart;
				t.defaults().size(280, 60).left();

				t.row();
				t.button("@schematic.copy.import", Icon.download, style, () -> {
					dialog.hide();
					area.setText(Core.app.getClipboardText());
				}).marginLeft(12f);

				t.row();
				t.button("@schematic.copy", Icon.copy, style, () -> {
					dialog.hide();
					Core.app.setClipboardText(getMessage().replaceAll("\r", "\n"));
				}).marginLeft(12f);
			});
		});

		dialog.addCloseButton();

		buttons.button("$edit", Icon.edit, dialog::show).size(210f, 64f);
	}

	void setup() {
		ui.cont.clear();
		ui.buttons.clear();

		ui.cont.pane(p -> show(p, ui.buttons)).fillX().fillY();
	}

	public void build() {
		ui.show();

		IntVars.frag.update(() -> {
			if (loop && !getMessage().equals(""))
				evalMessage();
		});
	}

	void evalMessage() {
		String def = getMessage();
		def = wrap ? "(function(){\"use strict\";" + def + "\n})();" : def;
		log = scripts.runConsole(def);
		log = log.replaceAll("\\[(.*?)]", "[ $1 ]");

	}

	public void load() {

		ui = new BaseDialog(localizedName());
		ui.addCloseListener();

		history = new ListDialog("history", record, f -> f.child("message.txt"), f -> {
			area.setText(f.child("message.txt").readString());
			log = f.child("log.txt").readString();
		}, (f, p) -> {
			p.add(f.child("message.txt").readString()).row();
			p.image().height(3).fillX().row();
			p.add(f.child("log.txt").readString());
		}, true);

		bookmark = new ListDialog("bookmark", Vars.dataDirectory.child("mods(I hope...)").child("bookmarks"),
				f -> f, f -> area.setText(f.readString()), (f, p) -> p.add(f.readString()).row(), false);

		Mods.LoadedMod mod = Vars.mods.locateMod(ModMake.name);
		scripts.runConsole(mod.root.child("tester.js").readString());

		setup();
	}

	public void loadString() {
	}

	public String getMessage() {
		return area.getText();
	}

	class ListDialog extends BaseDialog {
		public ListDialog(String title, Fi file, Func<Fi, Fi> fileHolder, Cons<Fi> consumer, Cons2<Fi, Table> pane,
		                  boolean sort) {
			super(Core.bundle.get("title." + title, title));
			this.file = file;
			this.fileHolder = fileHolder;
			this.consumer = consumer;
			this.pane = pane;
			this.sort = sort;
		}

		final Table p = new Table();
		boolean sort;
		Fi file;
		Func<Fi, Fi> fileHolder;
		Cons<Fi> consumer;
		Cons2<Fi, Table> pane;

		public Dialog show(Scene stage, Action action) {
			build();
			return super.show(stage, action);
		}

		ArrayList<String> sort(Fi[] list) {
			ArrayList<String> longs = new ArrayList<>();
			for (Fi fi : list) {
				longs.add(fi.name());
			}
			/* 排序 */
			if (sort)
				longs.sort(Comparator.naturalOrder());
			return longs;
		}

		void build() {
			ArrayList<String> longs = sort(file.list());
			// 颠倒
			p.clearChildren();
			for (int j = longs.size() - 1; j >= 0; j--) {
				final int i = j;
				Fi f = file.child(longs.get(j));
				p.table(Tex.button, t -> {
					Button btn = t.left()
							.button(b -> b.pane(c -> c.add(fileHolder.get(f).readString()).left()).fillY().fillX()
									.left(), IntStyles.clearb, () -> {
							})
							.height(70f).minWidth(400f).growX().fillX().left().get();
					IntUI.longPress(btn, 600f, longPress -> {
						if (longPress) {
							Dialog ui = new Dialog("");
							ui.cont.pane(p1 -> pane.get(f, p1)).size(400f).row();
							ui.cont.button(Icon.trash, () -> {
								ui.hide();
								f.delete();
							}).row();
							ui.cont.button("$ok", ui::hide).fillX().height(60);
							ui.show();
						} else {
							consumer.get(f);
							build();
							hide();
						}
					});
					t.button("", Icon.trash, Styles.cleart,
							() -> {
								if (f.deleteDirectory())
									p.getChildren().get(i)
											.remove();
								build();
							}).fill().right();
				}).width(w).row();
			}
		}

		{
			cont.pane(p).fillX().fillY();
			addCloseButton();
		}
	}
}
