package modtools.ui.content.world;

import arc.func.Cons;
import arc.graphics.Color;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.ObjectMap;
import arc.util.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.ctype.UnlockableContent;
import mindustry.entities.Effect;
import mindustry.entities.bullet.BulletType;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.Block;
import modtools.ui.*;
import modtools.ui.gen.HopeIcons;
import modtools.ui.components.*;
import modtools.ui.components.input.MyLabel;
import modtools.ui.content.Content;
import modtools.utils.*;
import modtools.utils.JSFunc.JColor;
import modtools.utils.ui.search.*;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

public class ContentList extends Content {
	public ContentList() {
		super("contentList", Icon.listSmall);
	}

	Window ui;
	Table  main;

	ObjectMap<String, Effect>     fxs     = null;
	ObjectMap<String, BulletType> bullets = null;
	ObjectMap<String, Block>      blocks  = null;
	ObjectMap<String, Item>       items   = null;
	ObjectMap<String, Liquid>     liquids = null;
	ObjectMap<String, Planet>     planets = null;

	Pattern pattern = null;
	public void load0() {
		ui = new Window(localizedName(), 200, 420, true);
		main = new Table();
		Table top = new Table();
		ui.cont.add(top).row();
		ui.cont.add(main).grow();
		new Search((_, text) -> pattern = PatternUtils.compileRegExpOrNull(text))
		 .build(top, main);
	}
	void loadFields() {
		fxs = fieldsToMap(Fx.class.getFields(), Effect.class);
		bullets = fieldsToMap(Bullets.class.getFields(), BulletType.class);
		blocks = Vars.content.blocks().asMap(b -> b.name);
		items = Vars.content.items().asMap(b -> b.name);
		liquids = Vars.content.liquids().asMap(b -> b.name);
		planets = Vars.content.planets().asMap(b -> b.name);
		buildAll();
	}

	private <T> ObjectMap<String, T> fieldsToMap(Field[] fields, Class<T> cl) {
		ObjectMap<String, T> map = new ObjectMap<>(fields.length);
		for (var f : fields) {
			try {
				if (!cl.isAssignableFrom(f.getType())) continue;
				f.setAccessible(true);
				Object obj = f.get(null);
				map.put(f.getName(), cl.cast(obj));
			} catch (IllegalAccessException e) {
				// Log.err(e);
				IntUI.showException(e);
			}
		}
		return map;
	}
	IntTab tab;
	public void buildAll() {
		main.clear();
		String[] names = {"Fx", "Bullet", "Blocks", "Items", "Liquids", "Planets"};
		Cons switchUnlock = (Cons<UnlockableContent>) (u -> {
			if (u.locked()) u.unlock();
			else u.clearUnlock();
		});
		Table[] tables = {
		 defaultTable(fxs, effect -> {
			 if (Vars.player.unit() == null) return;
			 effect.at(Vars.player.x, Vars.player.y, Vars.player.unit().rotation());
		 }), defaultTable(bullets, bulletType -> {
			if (Vars.player.unit() == null) return;
			bulletType.create(Vars.player.unit(), Vars.player.x, Vars.player.y, Vars.player.unit().rotation());
		}), defaultTable(blocks, switchUnlock),
		 defaultTable(items, switchUnlock), defaultTable(liquids, switchUnlock),
		 defaultTable(planets, switchUnlock)};

		tab = new IntTab(main.getWidth(), names, Color.sky, tables);
		tab.eachWidth = 86;
		main.update(() -> tab.setTotalWidth(main.getWidth() / Scl.scl()));
		tab.title.add("@mod-tools.tips.longprees_to_cppy")
		 .colspan(tables.length).growX().row();
		tab.setPrefSize(tab.eachWidth * 3, -1);

		main.add(tab.build()).grow().top();
	}
	private <T> FilterTable<String> defaultTable(ObjectMap<String, T> map) {
		return defaultTable(map, null);
	}

	private <T> FilterTable<String> defaultTable(ObjectMap<String, T> map, Cons<T> clicked) {
		return new FilterTable<>(t -> {
			map.each((name, item) -> {
				t.bind(name);
				MyLabel label = new MyLabel(name);
				t.image().color(Tmp.c1.set(JColor.c_underline)).height(2).growX().colspan(2).row();
				if (item instanceof UnlockableContent u) {
					t.add(new Image(u.uiIcon == null ? HopeIcons.interrupt.getRegion() : u.uiIcon)).size(32f)
					 .with(img -> IntUI.longPress(img, b -> {
						 if (b) {
							 u.load();
							 u.loadIcon();
							 u.init();
							 IntUI.showInfoFade("Loaded " + u.localizedName);
						 } else label.setText((label.getText() + "").equals(name) ? u.localizedName : name);
					 }));
				} else t.add();
				t.button(b -> b.add(label).grow().padLeft(8f).padRight(8f),
					HopeStyles.clearb, () -> {})
				 .growX().height(42)
				 .with(button -> IntUI.longPress(button, b -> {
					if (b) {
						JSFunc.copyText(name, button);
					} else {
						if (clicked != null) clicked.get(item);
					}
				})).update(_ -> t.layout()).row();
			});
			t.addPatternUpdateListener(() -> pattern);
		});
	}
	public void build() {
		if (ui == null) load0();
		loadFields();
		ui.show();
		Time.runTask(2, () -> tab.main.invalidate());
	}
}
