package modtools.ui.content.world;

import arc.graphics.Color;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.Effect;
import mindustry.entities.bullet.BulletType;
import modtools.ui.IntUI;
import modtools.ui.components.*;
import modtools.ui.content.Content;
import modtools.utils.*;
import modtools.utils.search.FilterTable;

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

	Pattern pattern = null;
	public void load() {
		ui = new Window(localizedName(), getWidth(), 100, true);
		main = new Table();
		Table top = new Table();
		ui.cont.add(top).row();
		ui.cont.add(main).grow();
		new Search((__, text) -> {
			pattern = Tools.complieRegExp(text);
		}).build(top, main);

		Field[] fields = Fx.class.getFields();
		buildTable(fields, Effect.class, fxs);
		fields = Bullets.class.getFields();
		buildTable(fields, BulletType.class, bullets);
		rebuild();
	}
	private <T> void buildTable(Field[] fields, Class<T> cl, ObjectMap<String, T> map) {
		for (var f : fields) {
			try {
				if (!cl.isAssignableFrom(f.getType())) continue;
				f.setAccessible(true);
				Object obj = f.get(null);
				map.put(f.getName(), cl.cast(obj));
			} catch (IllegalAccessException e) {
				//				Log.err(e);
				IntUI.showException(e);
			}
		}
	}
	private static int getWidth() {
		return 200;
	}
	public void rebuild() {
		MySet<Table> tables = new MySet<>();
		Color[]      colors = {Color.sky, Color.sky};
		String[]     names  = {"fx", "bullet"};
		tables.add(new FilterTable(t -> {
			fxs.each((name, effect) -> {
				t.bind(name);
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
			t.addUpdateListener(() -> pattern);
		}));
		tables.add(new FilterTable(t -> {
			bullets.each((name, bulletType) -> {
				t.bind(name);
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
			t.addUpdateListener(() -> pattern);
		}));

		IntTab tab = new IntTab(-1,
		                        new Seq<>(names),
		                        new Seq<>(colors),
		                        tables.toSeq());
		tab.title.add("@contentlist.tip").growX().row();

		main.add(tab.build()).grow().top();
	}
	public void build() {
		ui.show();
	}
}
