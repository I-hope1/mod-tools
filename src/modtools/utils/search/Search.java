package modtools.utils.search;

import arc.func.*;
import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import modtools.ui.*;
import modtools.utils.PatternUtils;
import modtools.utils.reflect.ModifierR;
import modtools.utils.ui.ReflectTools;
import modtools.utils.ui.ReflectTools.MarkedCode;

import java.util.regex.Pattern;

public class Search<T> {
	public Search(Cons2<Table, Pattern> rebuild) {
		this.rebuild = rebuild;
	}
	public Table     top = new Table();
	public TextField field;

	private final ObjectMap<String, SearchItem<T>> filters = new ObjectMap<>();
	public SearchItem<T> item(String key) {
		return filters.get(key);
	}
	public <E extends MarkedCode> Search<T> addStatusFilter(String text, MarkedCode[] items, int cols,
	                                                        Intc listener, Intp prov, Intf<T> func) {
		ReflectTools.addCodedBtn(top, text, cols, listener, prov, items);
		filters.put(text, (_, item) -> containsFlags(prov.get(), func.get(item)));
		return this;
	}
	public boolean containsFlags(int filterModifiers, int modifiers) {
		if (modifiers == 0) return true;
		// Log.info("f: @, r: @", Modifier.toString(modifiers), Modifier.toString((short) this.modifiers));
		for (ModifierR value : ModifierR.values()) {
			int mod = 1 << value.ordinal();
			if ((modifiers & mod) != 0 && (filterModifiers & mod) != 0) return true;
		}
		return false;
	}
	public Search<T> addFilter(String key, SearchItem<T>[] items, SearchItem<T> def) {
		return addFilter(Icon.filter, key, items, def);
	}
	public Search<T> addFilter(Drawable icon, String key, SearchItem<T>[] items, SearchItem<T> def) {
		filters.put(key, def);
		top.button(icon, HopeStyles.clearNonei, () -> { })
		 .with(b -> b.clicked(() -> {
			 IntUI.showSelectListTable(b, new Seq<>(items),
				() -> filters.get(key), s -> filters.put(key, s),
				String::valueOf,
				250, 45, false, Align.top);
		 })).size(42);
		return this;
	}
	public boolean valid(Pattern pattern, T item) {
		return pattern == PatternUtils.ANY || (pattern != null && valid0(pattern, item) != isBlack);
	}
	private boolean valid0(Pattern pattern, T item) {
		if (filters.isEmpty()) {
			return PatternUtils.testAny(pattern, item);
		}
		for (var filter : filters) {
			if (!filter.value.get(pattern, item)) return false;
		}
		return true;
	}
	public void build(Table title, Table cont) {
		title.add(top).padRight(8f).growX().top().row();
		top.image(Icon.zoomSmall);
		field = new TextField();
		field.setMessageText("@players.search");
		top.add(field).growX();
		field.changed(() -> {
			rebuild(cont, PatternUtils.compileRegExpOrNull(field.getText()));
		});
		rebuild(cont, PatternUtils.ANY);
	}
	public final Cons2<Table, Pattern> rebuild;
	protected void rebuild(Table cont, Pattern pattern) {
		if (rebuild != null) rebuild.get(cont, pattern);
	}
	boolean isBlack = false;
	public Search<T> addBlackList(Runnable rebuild0) {
		top.button(Tex.whiteui, 32, null).size(42).with(img -> {
			img.clicked(() -> {
				isBlack = !isBlack;
				img.getStyle().imageUpColor = isBlack ? Color.darkGray : Color.white;
				rebuild0.run();
			});
		});
		return this;
	}

	public interface SearchItem<T> {
		boolean get(Pattern pattern, T item);
	}
}