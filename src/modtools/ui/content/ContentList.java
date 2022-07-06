package modtools.ui.content;

import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Bullets;
import mindustry.content.Fx;
import mindustry.entities.Effect;
import mindustry.entities.bullet.BulletType;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;
import modtools.ui.components.IntTab;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

public class ContentList extends Content {
	public ContentList() {
		super("内容列表");
	}

	BaseDialog ui;
	Table main;
	TextField search;
	ObjectMap<String, Effect> fxs = new ObjectMap<>();
	ObjectMap<String, BulletType> bullets = new ObjectMap<>();

	public void load() {
		ui = new BaseDialog(name);
		main = new Table();
		ui.cont.pane(p -> {
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
		ui.addCloseButton();

		Field[] fields = Fx.class.getFields();
		for (var f : fields) {
			try {
				f.setAccessible(true);
				Object obj = f.get(null);
				if (!(obj instanceof Effect)) continue;
				fxs.put(f.getName(), (Effect) obj);
			} catch (IllegalAccessException e) {
//				Log.err(e);
				Vars.ui.showException(e);
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
				Vars.ui.showException(e);
			}
		}
	}


	public void rebuild(String text) {
		Seq<Color> colors = new Seq<>();
		Seq<Table> tables = new Seq<>() {
			@Override
			public Seq<Table> add(Table value) {
				colors.add(Color.sky);
				return super.add(value);
			}
		};
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
				t.button(name, () -> {
					effect.at(Vars.player.x, Vars.player.y);
					ui.hide();
				}).growX().height(64).row();
			});
		}));
		tables.add(new Table(t -> {
			if (finalPattern == null) return;
			bullets.each((name, bulletType) -> {
				if (!name.isEmpty() && !finalPattern.matcher(name).find()) return;
				t.button(name, () -> {
					bulletType.create(Vars.player.unit(), Vars.player.x, Vars.player.y, Mathf.random(360f));
					ui.hide();
				}).growX().height(64).row();
			});
		}));

		IntTab tab = IntTab.set(Vars.mobile ? 400 : 600, new Seq<>(names), colors, tables);
		main.clearChildren();
		main.add(tab.build());
	}

	@Override
	public void build() {
		rebuild("");
		ui.show();
	}
}
