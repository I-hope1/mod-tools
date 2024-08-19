package modtools.ui.comp.windows;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.scene.*;
import arc.scene.ui.Button;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import modtools.IntVars;
import modtools.content.debug.Tester;
import modtools.ui.*;
import modtools.ui.comp.*;
import modtools.ui.comp.input.MyLabel;
import modtools.utils.*;
import modtools.utils.io.FileUtils;
import modtools.utils.search.*;

import java.util.*;
import java.util.regex.Pattern;

import static modtools.IntVars.mouseVec;
import static modtools.utils.Tools.readFiOrEmpty;

public class ListDialog extends Window {
	public List<Fi> list;

	final FilterTable<String> p = new FilterTable<>();
	Comparator<Fi> sorter;
	public Fi file;
	Func<Fi, Fi>     fileHolder;
	Cons<Fi>         consumer;
	Cons2<Fi, Table> pane;

	public ListDialog(String title, Fi file,
	                  Func<Fi, Fi> fileHolder, Cons<Fi> consumer,
	                  Cons2<Fi, Table> pane,
										Comparator<Fi> sorter) {
		super(Core.bundle.get("title." + title, title), Tester.WIDTH, 600, true);
		cont.add("@tester.tip").growX().left().row();
		new Search((_, p0) -> pattern = p0)
		 .build(cont, p);
		p.addPatternUpdateListener(() -> pattern);
		p.top().defaults().top();
		cont.pane(p).grow();

		this.file = file;
		list = new ArrayList<>(Arrays.asList(file.list()));
		this.fileHolder = fileHolder;
		this.consumer = consumer;
		this.pane = pane;
		this.sorter = sorter;

		list.sort(sorter);
	}

	public Window show(Scene stage, Action action) {
		build();
		return super.show(stage, action);
	}

	public void build() {
		p.clearChildren();

		list.forEach(this::build);
	}

	public static       Pattern            fileUnfair    = Pattern.compile("[\\\\/:*?<>\"\\[\\]]|(\\.\\s*?$)");
	public static final Boolf2<Fi, String> fileNameValid = (f, text) -> {
		try {
			return !text.isBlank() && !fileUnfair.matcher(text).find()
						 && (f.name().equals(text) || !f.sibling(text).exists());
		} catch (Throwable e) {
			return false;
		}
	};
	public void removeItemAt(int i) {
		list.get(i).deleteDirectory();
		list.remove(i);
	}
	public void addItem(Fi lastDir) {
		list.add(0, lastDir);
		if (isShown()) {
			build();
		}
	}


	public class Builder {
		Fi f;

		public Builder(Fi f) {
			this.f = f;
		}

		public Cell<Table> build() {
			FilterTable<String> p = ListDialog.this.p;
			ModifiableLabel.build(() -> f.name(), t -> fileNameValid.get(f, t), (field, label) -> {
				if (!f.name().equals(field.getText()) && f.sibling(field.getText()).exists()) {
					IntUI.showException(new IllegalArgumentException("文件夹已存在.\nFile has existed."));
				} else if (field.isValid()) {
					Fi toFi = f.sibling(field.getText());
					f.moveTo(toFi);
					list.set(list.indexOf(f), toFi);
					f = toFi;
					label.setText(field.getText());
					ListDialog.this.build();
				}
			}, p).left().color(Pal.accent).growX();
			p.row();
			// p.add(f.name(), Pal.accent).left().row();
			p.image().color(Pal.accent).growX();
			p.row();
			var tmp = p.table(Window.myPane, t -> {
				Button btn = t.left().button(b -> {
					 b.pane(c -> {
						 c.add(new MyLabel(readFiOrEmpty(fileHolder.get(f)), HopeStyles.defaultLabel)).left();
					 }).grow().left();
				 }, HopeStyles.clearb, IntVars.EMPTY_RUN)
				 .height(70).minWidth(400).growX().left().get();
				EventHelper.longPress(btn, longPress -> {
					if (longPress) {
						Window ui   = new DisWindow("@info", 300, 80);
						var    cont = ui.cont;
						cont.add(new MyLabel(f.name(), accentStyle)).left().colspan(2).row();
						cont.image().color(Pal.accent).growX().colspan(2);
						cont.row();
						cont.pane(p1 -> pane.get(f, p1))
						 .colspan(2)
						 .grow();
						cont.row();

						cont.button(Icon.copy, HopeStyles.flati,
						 () -> JSFunc.copyText(readFiOrEmpty(f)));
						cont.button(Icon.trash, HopeStyles.flati, () -> IntUI.showConfirm("@confirm.remove", () -> {
							ui.hide();
							f.delete();
						})).row();
						ui.show();
						ui.setPosition(Tmp.v1.set(mouseVec).sub(ui.getWidth() / 2, ui.getHeight() / 2));
					} else {
						consumer.get(f);
						ListDialog.this.build();
						hide();
					}
				});
				t.button("", Icon.trash, HopeStyles.cleart, () -> {
					FileUtils.delete(f);

					list.remove(f);
					ListDialog.this.build();
				}).fill().right();
			}).width(Tester.WIDTH);
			p.row();
			return tmp;
		}
	}

	public Cell<Table> build(Fi f) {
		p.bind(f.name());
		return new Builder(f).build();
	}

	public static LabelStyle accentStyle = new LabelStyle(MyFonts.def, Pal.accent);
	Pattern pattern = null;
}
