package modtools.content.world;

import arc.func.*;
import arc.graphics.Color;
import arc.scene.style.*;
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
import modtools.IntVars;
import modtools.content.Content;
import modtools.ui.*;
import modtools.ui.comp.*;
import modtools.ui.comp.input.MyLabel;
import modtools.ui.gen.HopeIcons;
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

	Prov<ObjectMap<String, Effect>> fxs = () -> fieldsToMap(Fx.class.getFields(), Effect.class);
	Prov<ObjectMap<String, BulletType>> bullets = () -> fieldsToMap(Bullets.class.getFields(), BulletType.class);
	Prov<ObjectMap<String, Block>> blocks = () -> Vars.content.blocks().asMap(b -> b.name);
	Prov<ObjectMap<String, Item>> items = () -> Vars.content.items().asMap(b -> b.name);
	Prov<ObjectMap<String, Liquid>> liquids = () -> Vars.content.liquids().asMap(b -> b.name);
	Prov<ObjectMap<String, Planet>> planets = () -> Vars.content.planets().asMap(b -> b.name);

	Pattern pattern = null;
	public void load0() {
		ui = new Window(localizedName(), 200, 420, true);
		main = new Table();
		Table top = new Table();
		ui.cont.add(top).row();
		ui.cont.add(main).grow();
		new Search((_, text) -> pattern = text).build(top, main);
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

	@SuppressWarnings("unchecked")
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
		main.update(() -> tab.setTitleWidth(main.getWidth() / Scl.scl()));
		tab.title.add("@mod-tools.tips.longprees_to_cppy")
		 .colspan(tables.length).growX().row();
		tab.setPrefSize(tab.eachWidth * 3, -1);

		main.add(tab.build()).grow().top();
	}
	private <T> FilterTable<T> defaultTable(ObjectMap<String, T> map) {
		return defaultTable(() -> map, null);
	}

	private <T> FilterTable<T> defaultTable(Prov<ObjectMap<String, T>> mapProv, Cons<T> clicked) {
		Cons<FilterTable<T>>[] rebuild = new Cons[]{null};
		rebuild[0] = t -> {
			t.clear();
			t.button("RebuildAll", Styles.flatt, () -> {
				rebuild[0].get(t);
			}).colspan(2).height(36).growX().row();
			mapProv.get().each((name, item) -> {
				t.bind(item);
				MyLabel label = new MyLabel(name);
				t.image().color(Tmp.c1.set(JColor.c_underline)).height(2).growX().colspan(2).row();
				if (item instanceof UnlockableContent u) {
					t.add(new Image(getDrawable(u))).size(32f)
					 .with(img -> EventHelper.longPress(img, b -> {
						 if (!b) {
							 label.setText((label.getText() + "").equals(name) ? u.localizedName : name);
						 } else {
							 u.load();
							 u.loadIcon();
							 u.init();
							 IntUI.showInfoFade("Loaded " + u.localizedName);
						 }
					 }));
				} else t.add();
				t.button(b -> b.add(label).grow().padLeft(8f).padRight(8f),
					HopeStyles.clearb, IntVars.EMPTY_RUN)
				 .growX().height(42)
				 .with(button -> EventHelper.longPress(button, b -> {
					 if (b) {
						 JSFunc.copyText(name, button);
					 } else {
						 if (clicked != null) clicked.get(item);
					 }
				 })).row();
			});
		};
		return new FilterTable<>(t -> {
			rebuild[0].get(t);
			t.addPatternUpdateListener(() -> pattern);
		});
	}
	/** @see mindustry.ui.dialogs.PlanetDialog#setup()  */
	private static Drawable getDrawable(UnlockableContent u) {
		return u.uiIcon == null ? new TextureRegionDrawable(HopeIcons.interrupt.getRegion()) :
		 u instanceof Planet planet ? Icon.icons.get(planet.icon + "Small", Icon.icons.get(planet.icon, Icon.commandRallySmall)).tint(planet.iconColor)
		  : new TextureRegionDrawable(u.uiIcon);
	}
	public void build() {
		if (ui == null) load0();
		buildAll();
		ui.show();
		Time.runTask(2, () -> tab.main.invalidate());
	}
}
