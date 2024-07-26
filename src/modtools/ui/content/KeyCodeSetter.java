package modtools.ui.content;

import arc.Core;
import arc.input.KeyCode;
import arc.input.KeyCode.KeyType;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.struct.LazyValue;
import modtools.ui.*;
import modtools.ui.IntUI.IHitter;
import modtools.ui.comp.Window;
import modtools.ui.comp.Window.NoTopWindow;
import modtools.ui.comp.limit.LimitTable;
import modtools.ui.comp.utils.*;
import modtools.ui.control.*;
import modtools.ui.control.HKeyCode.KeyCodeData;
import modtools.utils.*;

import static arc.Core.scene;
import static modtools.ui.IntUI.topGroup;

public class KeyCodeSetter extends Content {
	public KeyCodeSetter() {
		super("keycodeSetter");
	}

	HKeyCode recordKeyCode;

	public static KeyCodeData                  elementKeyCode    = HKeyCode.data.child("element");
	public static ObjectMap<Button, HKeyCode>  tmpKeyCode        = new ObjectMap<>();
	public static LazyValue<KeyCodeBindWindow> keyCodeBindWindow = LazyValue.of(() -> new KeyCodeBindWindow("Keycode Set"));
	public void load() {
		super.load();
		recordKeyCode = HKeyCode.data.keyCode("recordKeyCode", () -> new HKeyCode(KeyCode.r).ctrl().shift());
		final Seq<Button> toRemoveSeq = new Seq<>();
		scene.addCaptureListener(new InputListener() {
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (recordKeyCode.isPress()) {
					Element element = HopeInput.mouseHit();
					keyCodeBindWindow.get().show(ElementUtils.findParent(element, Button.class));
					return false;
				}
				elementKeyCode.each((key, _) -> {
					HKeyCode keyCode = elementKeyCode.keyCode(key, () -> HKeyCode.NONE);
					if (keyCode.isPress()) {
						Element elem = getElementByKey(key);
						if (elem != null) clicked(elem);
					}
				});
				toRemoveSeq.clear();
				tmpKeyCode.each((el, keyCode) -> {
					if (el.getScene() == null) {
						toRemoveSeq.add(el);
						return;
					}
					if (keyCode.isPress()) {
						clicked(el);
					}
				});
				toRemoveSeq.each(tmpKeyCode::remove);
				return false;
			}
		});
	}
	public static void clicked(Element el) {
		el.fireClick();
	}
	/** 与{@link #getElementByKey(String)} 互为逆运算  */
	public static String getBindKey(Button button) {
		if (button.getScene() == null) return null;
		Seq<String> sj = new Seq<>();
		if (button.name != null) {
			sj.add("#" + button.name);
		} else if (button instanceof TextButton tb) {
			sj.add(String.valueOf(tb.getText()));
		}
		ElementUtils.findParent(button.parent, e -> {
			if (e == scene.root) return true;
			sj.add(e.name != null ? e.name : "" + e.getZIndex());
			return false;
		});
		return sj.isEmpty() ? null : sj.reverse().toString(".");
	}
	/** 与{@link #getBindKey(Button)} 互为逆运算  */
	public static Button getElementByKey(String key) {
		if (key == null || key.isEmpty()) return null;

		Element current = scene.root;
		int     start   = 0;
		int     end     = 0;
		int     length  = key.length();

		while (end < length) {
			// 找到下一个点的位置
			while (end < length && key.charAt(end) != '.') {
				end++;
			}

			// 获取当前部分的子字符串
			if (current == null) return null;

			Element next = null;
			if (current instanceof Group group) {
				boolean isIndex = true;
				for (int i = start; i < end; i++) {
					if (!Character.isDigit(key.charAt(i))) {
						isIndex = false;
						break;
					}
				}

				if (isIndex) {
					int index = 0;
					for (int i = start; i < end; i++) {
						index = index * 10 + (key.charAt(i) - '0');
					}
					if (index < group.getChildren().size) {
						next = group.getChildren().get(index);
					}
				} else if (key.charAt(start) != '#') {
					for (int i = 0, size = group.getChildren().size; i < size; i++) {
						Element child = group.getChildren().get(i);
						if (!(child instanceof TextButton tb)) continue;
						if (StringUtils.equals(key, start, end, tb.getText())) {
							next = tb;
							break;
						}
					}
				} else {
					for (int i = 0, size = group.getChildren().size; i < size; i++) {
						Element child = group.getChildren().get(i);
						if (StringUtils.equals(key, start + 1/* 跳过# */, end, child.name)) {
							next = child;
							break;
						}
					}
				}
			}

			// 如果没有找到匹配的子元素，则无法继续查找
			if (next == null) return null;

			current = next;
			end++;  // 跳过点
			start = end;  // 更新起始位置
		}

		return current instanceof Button b ? b : null;
	}
	public static class KeyCodeBindWindow extends NoTopWindow implements IHitter {
		public KeyCodeBindWindow(String title) {
			super(title);

			rebuild();
			update(() -> {
				if (button != null) IntUI.positionTooltip(button, this);
			});
		}
		Button button;
		public void show(Button button) {
			this.button = button;
			field.setText("Press a key to bind");
			show();
		}
		TextField field;
		private void rebuild() {
			Table cont = this.cont;
			cont.clearChildren();
			field = cont.field("", _ -> { })
			 .colspan(2).growX()
			 .get();
			field.update(field::requestKeyboard);
			field.addCaptureListener(new InputListener() {
				public boolean keyDown(InputEvent event, KeyCode keycode) {
					if (keycode.type != KeyType.key) return false;
					if (keycode == KeyCode.controlLeft || keycode == KeyCode.controlRight) return false;
					if (keycode == KeyCode.shiftLeft || keycode == KeyCode.shiftRight) return false;
					if (keycode == KeyCode.altLeft || keycode == KeyCode.altRight) return false;

					if (keycode == KeyCode.escape) {
						field.setText("None");
					} else {
						StringBuilder text = new StringBuilder();
						if (Core.input.ctrl()) text.append("Ctrl + ");
						if (Core.input.shift()) text.append("Shift + ");
						if (Core.input.alt()) text.append("Alt + ");
						text.append(
						 keycode == KeyCode.backspace ? "Backspace" :
							keycode.value.length() == 1 ? keycode.value.toUpperCase() :
							 Strings.capitalize(keycode.value)
						);
						field.setText(text.toString());
					}
					field.setCursorPosition(100);
					event.cancel();
					return false;
				}
				public boolean keyTyped(InputEvent event, char character) {
					event.cancel();
					return false;
				}
			});
			cont.row();
			cont.defaults().size(120, 48);
			cont.button("@cancel", Icon.left, Styles.flatt, this::hide);
			cont.button("@ok", Icon.ok, Styles.flatt, () -> {
				HKeyCode value = HKeyCode.parse(field.getText());
				if (button.name != null || button instanceof TextButton tb && tb.getText() != null) {
					String key = getBindKey(button);
					elementKeyCode.setKeyCode(key, value);
				} else {
					tmpKeyCode.put(button, value);
				}
				hide();
			});
		}
	}

	Window ui;
	public void buildUI() {
		ui = new Window(localizedName(), 120, 400);
		Table cont = ui.cont;
		cont.defaults().pad(4).growX();
		cont.button("Bind key", Icon.pencil, Styles.flatt, this::selectButton);
		Table pane = new LimitTable();
		// 监控keys
		Runnable rebuild = () -> {
			pane.clearChildren();
			elementKeyCode.eachKey((elementKey, keyCode) -> {
				var vl = new ClearValueLabel<>(Button.class, () -> getElementByKey(elementKey), null,
				 el -> {
					 elementKeyCode.remove(elementKey);
					 elementKeyCode.setKeyCode(getBindKey(el), keyCode);
				 });
				pane.add(vl).size(220, 45);
				pane.button(keyCode.toString(), Styles.flatt, () -> { }).size(100, 45);
				pane.row();
			});
		};
		rebuild.run();
		cont.button("Flush", Icon.refresh, Styles.flatt, rebuild).row();
		cont.pane(Styles.smallPane, pane).grow().colspan(2);
	}
	public void selectButton() {
		topGroup.requestSelectElem(TopGroup.defaultDrawer, Button.class, el -> {
			keyCodeBindWindow.get().show(el);
		});
	}
	public void build() {
		if (ui == null) buildUI();
		ui.show();
	}
}
