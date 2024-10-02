package modtools.content;

import arc.*;
import arc.func.Boolf2;
import arc.graphics.Color;
import arc.scene.ui.Label;
import arc.struct.ObjectMap;
import arc.util.Reflect;
import mindustry.graphics.Pal;
import modtools.ui.IntUI;
import modtools.ui.comp.*;
import modtools.ui.comp.utils.ClearValueLabel;
import modtools.utils.search.*;
import modtools.utils.search.Search.SearchItem;

import java.util.HashMap;
import java.util.regex.Pattern;

public class GameSettings extends Content {
	public GameSettings() {
		super("gameSettings");
	}

	Window ui;
	static ObjectMap<String, Object> defaults;
	static HashMap<String, Object>   values;

	Pattern pattern;
	public void lazyLoad() {
		super.lazyLoad();
		defaults = Reflect.get(Settings.class, Core.settings, "defaults");
		values = Reflect.get(Settings.class, Core.settings, "values");

		ui = new Window("GameSettings", 300, 600, true);
		var cont = new FilterTable<String>();
		cont.top().left().defaults().top().left();
		cont.marginLeft(6f);

		Search<String> search = new Search<>((_, pattern) -> this.pattern = pattern);
		search.addFilter("Filter", Status.values(), Status.hideNoName);
		cont.addConditionUpdateListener(item -> search.valid(pattern, item));
		// 将设置的所以值都列出来
		// example1 true* 示例设置1 R(重置) S(修改)
		// example2 10 示例设置2 R(重置) S(修改)
		for (var entry : values.entrySet()) {
			String key    = entry.getKey();
			Object defVal = defaults.get(key);
			cont.bind(key);
			// cont.left().defaults().left();
			cont.add(key).color(Pal.accent).padRight(8f).left();
			cont.table(t -> {
				/*
					设置有3种特殊状态
					unset 这时候会fallback到默认值
					set 可能和默认值相同
					changed 和默认值不同
				*/
				t.add("*").expandY().top().marginRight(8f)
				 .update(i -> i.setColor(
					!Core.settings.has(key) ? Color.clear
					 : defVal == Core.settings.get(key, defVal) ? Color.gray
					 : Color.white
				 ));
				t.add(new ClearValueLabel<>(Object.class, () -> Core.settings.get(key, defVal), null))
				 .minWidth(120).growX();
			}).growY().growX();
			cont.button("R", () -> Core.settings.remove(key));
			cont.button("S", () -> { });

			cont.row();
			Label label = cont.add(Core.bundle.get("setting." + key + ".name", "[gray]No bundle name"))
			 .fontScale(0.9f).get();
			String s = "setting." + key + ".description";
			if (Core.bundle.has(s)) {
				IntUI.addTooltipListener(label, () -> Core.bundle.get(s, ""));
			}
			Underline.of(cont, 5);

			cont.unbind();
		}

		search.build(ui.cont, cont);
		ui.cont.pane(cont).grow();
	}

	public enum Status implements SearchItem<String> {
		all((_, _) -> true),
		hideNoName((_, key) -> Core.bundle.has("setting." + key + ".name")),
		hideNoDesc((_, key) -> Core.bundle.has("setting." + key + ".description"));

		final Boolf2<Pattern, String> func;
		Status(Boolf2<Pattern, String> func) {
			this.func = func;
		}
		public boolean get(Pattern pattern, String member) {
			return func.get(pattern, member);
		}
	}
	public void build() {
		ui.show();
	}
}
