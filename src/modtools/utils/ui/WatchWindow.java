package modtools.utils.ui;

import arc.Core;
import arc.func.*;
import arc.math.Mathf;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.util.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.events.E_JSFunc;
import modtools.ui.*;
import modtools.ui.comp.ModifiableLabel;
import modtools.ui.comp.utils.TemplateTable;
import modtools.ui.comp.Window.*;
import modtools.ui.comp.input.MyLabel;
import modtools.ui.comp.input.MyLabel.CacheProv;
import modtools.ui.IntUI;
import modtools.utils.*;
import modtools.utils.JSFunc.*;

public class WatchWindow extends HiddenTopWindow implements IDisposable {
	private static final MyProv<Object> NORMAL = () -> null;
	TemplateTable<MyProv<Object>> template = new TemplateTable<>(NORMAL, p -> {
		try {
			return !Mathf.equal(Strings.parseFloat(String.valueOf(p.get()), 0f), 0);
		} catch (Exception e) {return false;}
	});


	private static WatchWindow def;
	public static WatchWindow getDefault() {
		if (def == null) {
			def = new WatchWindow();
		}
		return def;
	}
	public static boolean isMultiWatch() {
		return Core.input.ctrl() || E_JSFunc.watch_multi.enabled();
	}
	public static final ObjectMap<String, WatchWindow> instances = new ObjectMap<>();
	public WatchWindow fromInstance(String id) {
		return instances.get(id, WatchWindow::new);
	}

	public WatchWindow() {
		this("Watch");
	}

	public WatchWindow(String title) {
		super(title);
		ScrollPane sc = new ScrollPane(template) {
			@Override
			public float getPrefWidth() {
				return Math.max(220, super.getPrefWidth());
			}
			@Override
			public float getPrefHeight() {
				return Math.max(120, super.getPrefHeight());
			}
		};

		cont.add(sc).grow().row();
	}

	/** 添加用于切换是否显示所有的单选框 */
	public void addAllCheckbox() {
		template.addAllCheckbox(cont);
	}

	public void newLine() {
		template.newLine();
	}

	public WatchWindow watch(String info, Object value, Func<Object, String> stringify) {
		return watch(info, new MyProvIns<>(() -> value, stringify), 0);
	}
	public WatchWindow watch(String info, Object value) {
		return watch(info, () -> value, 0);
	}

	/** value变为常量，不再更改watch值 */
	public WatchWindow watchConst(String info, Object value) {
		return watch(info, () -> value, Float.POSITIVE_INFINITY);
	}

	public WatchWindow watch(String info, MyProv<Object> value) {
		return watch(info, value, 0);
	}

	public WatchWindow watch(Drawable icon, MyProv<Object> value) {
		return watch(icon, value, 0);
	}

	public WatchWindow watchWithSetter(Drawable icon, MyProv<Object> value, Cons<String> setter) {
		Object[] callback = {null};
		template.bind(value);
		template.stack(new Table(o -> {
			o.left();
			o.add(new Image(icon)).size(32f).scaling(Scaling.fit);
		}), new Table(t -> {
			t.left().bottom();
			ModifiableLabel.build(new CacheProv(value).getStringProv(), NumberHelper::isNumber, (field, label) -> {
				if (!field.isValid() || setter == null) return;
				setter.get(field.getText());
			}, t).style(Styles.outlineLabel);
			t.pack();
		}));
		template.unbind();
		return this;
	}

	public WatchWindow watch(Drawable icon, MyProv<Object> value, float interval) {
		template.bind(value);
		template.stack(new Table(o -> {
			o.left();
			o.add(new Image(icon)).size(32f).scaling(Scaling.fit);
		}), new Table(t -> {
			t.left().bottom();
			CacheProv prov  = new CacheProv(value);
			MyLabel   label = new MyLabel(prov.getStringProv());
			label.prov = prov;
			t.add(label).style(Styles.outlineLabel);
			label.interval = interval;
			t.pack();
		}));
		template.unbind();
		return this;
	}

	public WatchWindow watch(String info, MyProv<Object> value, float interval) {
		MyLabel l = getWatchLabel(info);
		if (l != null) {
			Tools.runIgnoredException(() -> l.prov.prov = value);
			return this;
		}
		template.bind(NORMAL);
		template.top().left().defaults().left().top();
		template.add(info).ellipsis(true).color(Pal.accent).growX().colspan(2).row();
		template.image().color(Pal.accent).growX().colspan(2).row();
		var prov  = new CacheProv(value);
		var label = new MyLabel(prov.getStringProv());
		label.prov = prov;
		label.interval = interval;
		IntUI.addDetailsButton(template, () -> prov.value, Void.class);
		template.add(label).name(info).style(HopeStyles.defaultLabel).growX().padLeft(6f).row();
		template.image().color(Tmp.c1.set(JColor.c_underline)).growX().colspan(2).row();
		template.unbind();
		return this;
	}
	public Object getWatch(String info) {
		return getWatchLabel(info).prov.value;
	}
	public MyLabel getWatchLabel(String info) {
		return template.find(info);
	}

	@Override
	public WatchWindow show() {
		template.updateNow();
		return (WatchWindow) super.show();
	}

	public WatchWindow showIfOk() {
		if (Core.scene.root != null && !isEmpty()) show();
		return this;
	}
	public WatchWindow clearWatch() {
		template.clear();
		return this;
	}
	public boolean isEmpty() {
		return template.isEmpty();
	}

	@Override
	public String toString() {
		return "Watch@" + hashCode();
	}


	/* primitive */
	public WatchWindow watch(String info, float value) {
		return watch(info, () -> value, 0);
	}
	public WatchWindow watch(String info, int value) {
		return watch(info, () -> value, 0);
	}
	public WatchWindow watch(String info, boolean value) {
		return watch(info, () -> value, 0);
	}
}
