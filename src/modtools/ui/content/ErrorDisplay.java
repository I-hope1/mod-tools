package modtools.ui.content;

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
import modtools.ui.components.IntTab;
import modtools.ui.components.MyLabel;
import modtools.utils.Tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ErrorDisplay extends Content {
	public ErrorDisplay() {
		super("errordisplay");
	}

	BaseDialog ui;
	Table crashes;

	public void load() {
		crashes = new Table(p -> {
			p.defaults().grow();
			IntVars.async(() -> {
				Fi[] list = Vars.dataDirectory.child("crashes").list();
				p.left().defaults().left();
				for (var fi : list) {
					Label label = new MyLabel("");
					label.setStyle(Styles.outlineLabel);
					TextButton button = p.button(fi.nameWithoutExtension(), new TextButtonStyle(Styles.logicTogglet), () -> {
						if (label.getText().length() == 0) {
							label.setText(fi.readString().replaceAll("\\n", "\n\n"));
						}
					}).size(Vars.mobile ? 450 : 650, 45).get();
					button.getStyle().up = Tex.underline;
					p.row();
					p.collapser(new Table(Tex.pane, cont -> cont.left().add(label).growX().wrap().left()), true, button::isChecked).growX().row();
				}
			}, () -> {});
		});
	}

	public void build() {
		ui = new BaseDialog(localizedName());

		Color[] colors = {Color.sky, Color.gold};
		Seq<Table> tables = Seq.with(new Table(t -> t.pane(p -> {
			p.background(Tex.slider);
			String all = Vars.dataDirectory.child("last_log.txt").readString();
			Pattern pattern = Pattern.compile("\\[([IWE])\\]([\\s\\S]+?)(?=\n\\[[IWE]])");
			Matcher matcher = pattern.matcher(all);
			Color last = Color.white;
			p.left().defaults().left();
			while (matcher.find()) {
				String str = "";
				switch (matcher.group(1)) {
					case "I":
						str = "[blue][INFO]";
						break;
					case "W":
						str = "[yellow][WARN]";
						break;
					case "E":
						str = "[red][ERROR]";
						break;
				}
				p.add(str + "[]" + Tools.format(matcher.group(2)), last).padBottom(6f).row();
			}
		})), crashes);
		String[] names = {"last_log", "crashes"};
		IntTab tab = IntTab.set(Vars.mobile ? 400 : 600, new Seq<>(names), new Seq<>(colors), tables);
		ui.cont.add(tab.build()).grow();

		ui.addCloseButton();
		ui.show();
	}
}
