package modtools.ui.content;

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
import mindustry.gen.Icon;
import modtools.ui.IntUI;
import modtools.ui.components.IntTab;
import modtools.ui.components.Window;
import modtools.utils.*;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

public class ContentList extends Content {
	public ContentList() {
		super("contentList");
	}

	Window ui;
	Table main;
	TextField search;
	ObjectMap<String, Effect> fxs = new ObjectMap<>();
	ObjectMap<String, BulletType> bullets = new ObjectMap<>();

	public void load() {
		ui = new Window(localizedName(), 100, 100, true);
		main = new Table();
		ui.cont.table(p -> {
			p.table(top -> {
				top.image(Icon.zoom).pad(20f);
				search = new TextField();
				top.add(search).growX();
				search.changed(() -> {
					rebuild(search.getText());
				});
			}).growX().row();
			p.add(main).grow().top();
		}).grow();
		//		ui.addCloseButton();

		Field[] fields = Fx.class.getFields();
		for (var f : fields) {
			try {
				f.setAccessible(true);
				Object obj = f.get(null);
				if (!(obj instanceof Effect)) continue;
				fxs.put(f.getName(), (Effect) obj);
			} catch (IllegalAccessException e) {
				//				Log.err(e);
				IntUI.showException(e);
			}
		}
		fields = Bullets.class.getFields();
		for (var f : fields) {
			try {
				f.setAccessible(true);
				Object obj = f.get(null);
				if (!(obj instanceof BulletType)) continue;
				bullets.put(f.getName(), (BulletType) obj);
			} catch (IllegalAccessException e) {
				//				Log.err(e);
				IntUI.showException(e);
			}
		}
	}


	public void rebuild(String text) {
		MyObjectSet<Table> tables = new MyObjectSet<>();
		Color[] colors = {Color.sky, Color.sky};
		String[] names = {"fx", "bullet"};
		Pattern pattern = null;
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
							Core.app.setClipboardText(name);
							IntUI.showInfoFade("已复制[accent]" + name).setPosition(Tools.getAbsPos(button));
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
							Core.app.setClipboardText(name);
							IntUI.showInfoFade("已复制[accent]" + name).setPosition(Tools.getAbsPos(button));
						} else {
							if (Vars.player.unit() == null) return;
							bulletType.create(Vars.player.unit(), Vars.player.x, Vars.player.y, Vars.player.unit().rotation());
						}
					});
				}).row();
			});
		}));

		IntTab tab = IntTab.set(Vars.mobile ? 400 : 600,
				new Seq<>(names),
				new Seq<>(colors),
				tables.toSeq());
		tab.title.add("@contentlist.tip").growX().row();

		main.clearChildren();
		main.add(tab.build()).grow().top();
	}

	@Override
	public void build() {
		rebuild("");
		ui.show();
	}
}
