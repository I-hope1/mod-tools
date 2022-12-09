package modtools.ui.content;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Color;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import modtools.IntVars;
import modtools.ui.components.*;
import modtools.utils.Tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static modtools.utils.MySettings.settings;

public class ErrorDisplay extends Content {
	public ErrorDisplay() {
		super("errordisplay");
	}

	@Override
	public boolean loadable() {
		return settings.getBool("load-" + name, "false");
	}

	Window ui;
	Table crashes;

	public void load() {
		crashes = new Table(p -> {
			p.defaults().grow();
			IntVars.async(() -> {
				Seq<Fi> list = new Seq<>(Vars.dataDirectory.child("crashes").list()).reverse();
				p.left().defaults().left();
				for (var fi : list) {
					var label = new MyLabel("");
					TextButton button = p.button(fi.nameWithoutExtension(), new TextButtonStyle(Styles.logicTogglet), () -> {
						if (label.getText().length() == 0) {
							label.setText(fi.readString());
						}
					}).size(Core.graphics.isPortrait() ? 450 : 650, 45).get();
					button.getStyle().up = Tex.underline;
					p.row();
					p.collapser(new Table(Tex.pane, cont -> cont.left().add(label).growX().wrap().left()), true, button::isChecked).growX().row();
				}
			}, () -> {});
		});
	}

	public void rebuild() {
		ui = new Window(localizedName());

		Color[] colors = {Color.sky, Color.gold};
		Fi last_log = Vars.dataDirectory.child("last_log.txt");
		Seq<Table> tables = Seq.with(new Table(t -> t.pane(p -> p.label(() -> {
			return last_log.exists() ? last_log.readString() : "";
		}))), crashes);
		String[] names = {"last_log", "crashes"};
		IntTab tab = IntTab.set(Vars.mobile ? 400 : 600, new Seq<>(names), new Seq<>(colors), tables);
		ui.cont.add(tab.build()).grow();

		// ui.addCloseButton();
	}

	@Override
	public void build() {
		if (ui == null) rebuild();
		else ui.toFront();
		ui.show();
	}
}
