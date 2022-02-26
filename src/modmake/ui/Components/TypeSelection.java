package modmake.ui.components;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.Button;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modmake.ui.IntStyles;
import modmake.ui.IntUI;

import java.util.ArrayList;
import java.util.regex.Pattern;

public class TypeSelection {
	public Table table;
	public String typeName;
	public Class<?> type;

	public TypeSelection(Class<?> type, String typeName, ObjectMap<String, ArrayList<Class<?>>> types, boolean other) {
		this.typeName = typeName;
		this.type = type;
		this.table = new Table(Tex.clear, t -> {
			t.defaults().fillX();
			t.add("$type").padRight(2);
			var button = new Button(IntStyles.clearb);
			t.add(button).size(190, 40);
			button.label(() -> Core.bundle.get(this.typeName.toLowerCase(), this.typeName)).center().grow().row();
			button.image().color(Color.gray).fillX();
			button.clicked(() -> IntUI.showSelectTable(button, (p, hide, val) -> {
				p.clearChildren();
				Pattern reg = Pattern.compile("" + val, Pattern.CASE_INSENSITIVE);
				types.each((k, v) -> {
					p.add(k, Pal.accent).growX().left().row();
					p.image().color(Pal.accent).fillX().row();

					v.forEach(clazz -> {
						if (val != "" && !reg.asMatchPredicate().test(clazz.getSimpleName())) return;
						p.button(Core.bundle.get(clazz.getSimpleName().toLowerCase(), clazz.getSimpleName()), Styles.cleart, () -> {
							this.type = clazz;
							this.typeName = clazz.getSimpleName();
							hide.run();
						}).pad(5).size(200, 65).disabled(this.type == clazz).row();
					});
				});

				if (!other) return;
				p.add("other", Pal.accent).growX().left().row();
				p.image().color(Pal.accent).fillX().row();
				p.button(Core.bundle.get("none", "none"), Styles.cleart, () -> {
					this.type = null;
					this.typeName = "none";
					hide.run();
				}).pad(5).size(200, 65).disabled(typeName == "none").row();
			}, true));
		});
	}
}
