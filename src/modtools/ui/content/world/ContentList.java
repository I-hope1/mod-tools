package modtools.ui.content.world;

import arc.func.Cons;
import arc.graphics.Color;
import arc.scene.ui.Image;
import arc.scene.ui.layout.*;
import arc.struct.ObjectMap;
import arc.util.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.ctype.UnlockableContent;
import mindustry.entities.Effect;
import mindustry.entities.bullet.BulletType;
import mindustry.gen.Icon;
import mindustry.type.*;
import mindustry.ui.Styles;
import mindustry.world.Block;
import modtools.ui.IntUI;
import modtools.ui.components.*;
import modtools.ui.content.Content;
import modtools.utils.*;
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
		ui = new Window(localizedName(), getWidth(), 100, true);
		main = new Table();
		Table top = new Table();
		ui.cont.add(top).row();
		ui.cont.add(main).grow();
		new Search((__, text) -> pattern = PatternUtils.compileRegExpCatch(text))
		 .build(top, main);

		fxs = fieldsToMap(Fx.class.getFields(), Effect.class);
		bullets = fieldsToMap(Bullets.class.getFields(), BulletType.class);
		blocks = fieldsToMap(Blocks.class.getFields(), Block.class);
		items = fieldsToMap(Items.class.getFields(), Item.class);
		liquids = fieldsToMap(Liquids.class.getFields(), Liquid.class);
		planets = fieldsToMap(Planets.class.getFields(), Planet.class);
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
	private static int getWidth() {
		return 200;
	}
	IntTab tab;
	public void buildAll() {
		String[] names = {"fx", "bullet", "blocks", "items", "liquids", "planets"};
		Table[] tables = {
		 defaultTable(fxs, effect -> {
			 if (Vars.player.unit() == null) return;
			 effect.at(Vars.player.x, Vars.player.y, Vars.player.unit().rotation());
		 }), defaultTable(bullets, bulletType -> {
			if (Vars.player.unit() == null) return;
			bulletType.create(Vars.player.unit(), Vars.player.x, Vars.player.y, Vars.player.unit().rotation());
		}), defaultTable(blocks), defaultTable(items), defaultTable(liquids),
		 defaultTable(planets)};

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

	private <T> FilterTable<String> defaultTable(ObjectMap<String, T> map, Cons<T> longPress) {
		return new FilterTable<>(t -> {
			map.each((name, item) -> {
				t.bind(name);
				if (item instanceof UnlockableContent u) {
					t.add(new Image(u.fullIcon)).size(32f);
				} else t.add();
				t.button(name, Styles.flatt, () -> {}).growX().height(42).with(button -> {
					IntUI.longPress(button, 600, b -> {
						if (b) JSFunc.copyText(name, button);
						else if (longPress != null) longPress.get(item);
					});
				}).row();
			});
			t.addPatternUpdateListener(() -> pattern);
		});
	}
	public void build() {
		if (ui == null) load0();
		ui.show();
		Time.runTask(2, () -> tab.main.invalidate());
	}
}
