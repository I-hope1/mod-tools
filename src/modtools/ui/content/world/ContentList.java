package modtools.ui.content.world;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import mindustry.Vars;
import mindustry.content.Bullets;
import mindustry.content.Fx;
import mindustry.entities.Effect;
import mindustry.entities.bullet.BulletType;
import mindustry.gen.*;
import modtools.ui.IntUI;
import modtools.ui.components.IntTab;
import modtools.ui.components.Window;
import modtools.ui.content.Content;
import modtools.utils.*;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

public class ContentList extends Content {
	public ContentList() {
		super("contentList");
	}

	Window ui;
	Table  main;
	final ObjectMap<String, Effect>     fxs     = new ObjectMap<>();
	final ObjectMap<String, BulletType> bullets = new ObjectMap<>();

	public void load() {
		ui = new Window(localizedName(), getWidth(), 100, true);
		main = new Table();
		Table top = new Table();
		ui.cont.add(top).row();
		ui.cont.add(main).grow();
		new Search((__, t) -> rebuild(t)).build(top, main);

		Field[] fields = Fx.class.getFields();
		for (var f : fields) {
			try {
				if (!Effect.class.isAssignableFrom(f.getType())) continue;
				f.setAccessible(true);
				Object obj = f.get(null);
				fxs.put(f.getName(), (Effect) obj);
			} catch (IllegalAccessException e) {
				//				Log.err(e);
				IntUI.showException(e);
			}
		}
		fields = Bullets.class.getFields();
		for (var f : fields) {
			try {
				if (!BulletType.class.isAssignableFrom(f.getType())) continue;
				f.setAccessible(true);
				Object obj = f.get(null);
				bullets.put(f.getName(), (BulletType) obj);
			} catch (IllegalAccessException e) {
				//				Log.err(e);
				IntUI.showException(e);
			}
		}
	}
	private static int getWidth() {
		return 200;
	}
	public void rebuild(String text) {
		MySet<Table> tables  = new MySet<>();
		Color[]      colors  = {Color.sky, Color.sky};
		String[]     names   = {"fx", "bullet"};
		Pattern      pattern = null;
		try {
			pattern = Pattern.compile(text, Pattern.CASE_INSENSITIVE);
		} catch (Exception ignored) {}
		Pattern finalPattern = pattern;
		tables.add(new Table(t -> {
			if (finalPattern == null) return;
			fxs.each((name, effect) -> {
				if (!name.isEmpty() && !finalPattern.matcher(name).find()) return;
				t.button(name, () -> {}).growX().height(64).with(button -> {
					IntUI.longPress(button, 600, b -> {
						if (b) {
							JSFunc.copyText(name, button);
						} else {
							if (Vars.player.unit() == null) return;
							effect.at(Vars.player.x, Vars.player.y, Vars.player.unit().rotation());
						}
					});
				}).row();
			});
		}));
		tables.add(new Table(t -> {
			if (finalPattern == null) return;
			bullets.each((name, bulletType) -> {
				if (!name.isEmpty() && !finalPattern.matcher(name).find()) return;
				t.button(name, () -> {}).growX().height(64).with(button -> {
					IntUI.longPress(button, 600, b -> {
						if (b) {
							JSFunc.copyText(name, button);
						} else {
							if (Vars.player.unit() == null) return;
							bulletType.create(Vars.player.unit(), Vars.player.x, Vars.player.y, Vars.player.unit().rotation());
						}
					});
				}).row();
			});
		}));

		IntTab tab = new IntTab(-1,
		                        new Seq<>(names),
		                        new Seq<>(colors),
		                        tables.toSeq());
		tab.title.add("@contentlist.tip").growX().row();

		main.clearChildren();
		main.add(tab.build()).grow().top();
	}
	public void build() {
		rebuild("");
		ui.show();
	}
}
