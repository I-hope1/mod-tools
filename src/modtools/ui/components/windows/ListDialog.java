package modtools.ui.components.windows;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.TextField.TextFieldValidator;
import arc.scene.ui.layout.*;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import modtools.ui.*;
import modtools.ui.components.Window;
import modtools.ui.components.input.*;
import modtools.ui.components.input.area.AutoTextField;
import modtools.ui.content.debug.Tester;
import modtools.utils.*;
import modtools.utils.ui.search.*;

import java.util.*;
import java.util.regex.Pattern;

public class ListDialog extends Window {
	public List<Fi> list;

	final FilterTable<String> p = new FilterTable<>();
	Comparator<Fi> sorter;
	public Fi file;
	Func<Fi, Fi>     fileHolder;
	Cons<Fi>         consumer;
	Cons2<Fi, Table> pane;

	public ListDialog(String title, Fi file, Func<Fi, Fi> fileHolder, Cons<Fi> consumer, Cons2<Fi, Table> pane,
										Comparator<Fi> sorter) {
		super(Core.bundle.get("title." + title, title), Tester.WIDTH, 600, true);
		cont.add("@tester.tip").growX().left().row();
		new Search((_, text) -> pattern = PatternUtils.compileRegExpOrNull(text))
		 .build(cont, p);
		p.addPatternUpdateListener(() -> pattern);
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
		p.clear();

		list.forEach(this::build);
	}

	public static       Pattern            fileUnfair    = Pattern.compile("[\\\\/:*?<>\"\\[\\]]|(\\.\\s*$)");
	public static final Boolf2<Fi, String> fileNameValid = (f, text) -> {
		try {
			return !text.isBlank() && !fileUnfair.matcher(text).find()
						 && (f.name().equals(text) || !f.sibling(text).exists());
		} catch (Throwable e) {
			return false;
		}
	};


	public class Builder {
		Fi f;

		public Builder(Fi f) {
			this.f = f;
		}

		public Cell<Table> build() {
			ModifiedLabel.build(() -> f.name(), t -> fileNameValid.get(f, t), (field, label) -> {
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
						 c.add(new MyLabel(fileHolder.get(f).readString(), HopeStyles.defaultLabel)).left();
					 }).grow().left();
				 }, HopeStyles.clearb, () -> {})
				 .height(70).minWidth(400).growX().left().get();
				IntUI.longPress(btn, longPress -> {
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
						 () -> JSFunc.copyText(f.readString()));
						cont.button(Icon.trash, HopeStyles.flati, () -> IntUI.showConfirm("@confirm.remove", () -> {
							ui.hide();
							f.delete();
						})).row();
						ui.show();
						ui.setPosition(Core.input.mouse().sub(ui.getWidth() / 2, ui.getHeight() / 2));
					} else {
						consumer.get(f);
						ListDialog.this.build();
						hide();
					}

				});
				t.button("", Icon.trash, HopeStyles.cleart, () -> {
					if (!f.deleteDirectory()) {
						f.delete();
					}

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


	/**
	 * 可以修改的Label
	 */
	public static class ModifiedLabel extends NoMarkupLabel{
		public static Cell<?> build(
		 Prov<CharSequence> def,
		 TextFieldValidator validator,
		 Cons2<TextField, Label> modifier,
		 Table t) {
			return build(def, validator, modifier, t, AutoTextField::new);
		}

		TextField field;
		Cell<?> cell;
		Cons2<TextField, Label> modifier;
		public ModifiedLabel(Prov<CharSequence> sup) {
			super(sup);
		}

		public static Cell<?> build(
		 Prov<CharSequence> def,
		 TextFieldValidator validator,
		 Cons2<TextField, Label> modifier,
		 Table t, Prov<TextField> fieldProv) {
			var label = new ModifiedLabel(def);
			label.cell  = t.add(label).height(label.getPrefHeight() / Scl.scl());
			label.field = fieldProv.get();
			if (validator != null) label.field.setValidator(validator);
			label.modifier = modifier;
			label.init();
			return label.cell;
		}

		public void init() {
			field.update(() -> {
				if (Core.scene.getKeyboardFocus() != field) {
					modifier.get(field, this);
					setText(field.getText());
					cell.setElement(this);
				}
			});
			clicked(() -> {
				Core.scene.setKeyboardFocus(field);
				field.setText(getText() + "");
				field.setCursorPosition(getText().length());
				cell.setElement(field);
			});
		}
	}

	public static LabelStyle accentStyle = new LabelStyle(MyFonts.def, Pal.accent);
	Pattern pattern = null;
}
