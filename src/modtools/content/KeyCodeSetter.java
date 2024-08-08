package modtools.content;

import arc.Core;
import arc.func.Cons;
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
import modtools.ui.comp.Window;
import modtools.ui.comp.Window.NoTopWindow;
import modtools.ui.comp.utils.ClearValueLabel;
import modtools.ui.control.*;
import modtools.ui.control.HKeyCode.KeyCodeData;
import modtools.ui.gen.HopeIcons;
import modtools.utils.*;
import modtools.utils.search.*;
import modtools.utils.ui.CellTools;

import java.util.regex.Pattern;

import static arc.Core.scene;
import static modtools.ui.IntUI.*;

public class KeyCodeSetter extends Content {
	public KeyCodeSetter() {
		super("keycodeSetter", HopeIcons.keyboard);
	}

	HKeyCode recordKeyCode;

	public static KeyCodeData                  elementKeyCode    = HKeyCode.data.child("element");
	public static ObjectMap<Button, HKeyCode>  tmpKeyCode        = new ObjectMap<>();
	public static LazyValue<KeyCodeBindWindow> keyCodeBindWindow = LazyValue.of(KeyCodeBindWindow::new);

	public static Cons<HKeyCode> keyCodeSetter(Button button) {
		return value -> {
			if (button.name != null || button instanceof TextButton tb && tb.getText() != null) {
				String key = getBindKey(button);
				elementKeyCode.setKeyCode(key, value);
			} else {
				tmpKeyCode.put(button, value);
			}
		};
	}
	public void load() {
		super.load();
		recordKeyCode = HKeyCode.data.keyCode("recordKeyCode", () -> new HKeyCode(KeyCode.r).ctrl().shift());
		final Seq<Button> toRemoveSeq = new Seq<>();
		scene.addCaptureListener(new InputListener() {
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (recordKeyCode.isPress()) {
					Element element = HopeInput.mouseHit();
					Button  button  = ElementUtils.findParent(element, Button.class);
					keyCodeBindWindow.get().show(button, keyCodeSetter(button));
					return false;
				}
				elementKeyCode.each((key, _) -> {
					HKeyCode keyCode = elementKeyCode.keyCode(key);
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
			sj.add(e.name != null ? "#" + e.name : "" + e.getZIndex());
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
				boolean isIndex = NumberHelper.isDigital(key, start, end);

				if (isIndex) {
					int index = NumberHelper.parseDigital(key, start, end);
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
					start++; // 跳过#
					for (int i = 0, size = group.getChildren().size; i < size; i++) {
						Element child = group.getChildren().get(i);
						if (StringUtils.equals(key, start, end, child.name)) {
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
		public KeyCodeBindWindow() {
			super("Keycode Set");

			rebuild();
			update(() -> {
				if (button != null) IntUI.positionTooltip(button, Align.topLeft, this, Align.bottomLeft);
			});
		}
		/** 被用来定位的  */
		Button         button;
		Cons<HKeyCode> callback;
		public void show(Button button, Cons<HKeyCode> callback) {
			this.button = button;
			this.callback = callback;
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
					if (HKeyCode.isFnKey(keycode)) return false;

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
				callback.get(value);
				hide();
			});
		}
	}

	Pattern pattern;
	Window ui;
	public void buildUI() {
		ui = new Window(localizedName(), 120, 400, true);
		Table cont = ui.cont;
		cont.defaults().height(42).pad(4).growX();
		cont.button("Bind key", Icon.pencilSmall, Styles.flatt, this::selectButton);
		FilterTable<String[]> pane = new FilterTable<>();
		// 监控keys
		Runnable rebuild = () -> {
			pane.clear();
			elementKeyCode.eachKey((elementKey0, keyCode0) -> {
				String[] elementKey = {elementKey0};
				pane.bind(elementKey);
				HKeyCode[] keyCode = {keyCode0};
				var vl = new ClearValueLabel<>(Button.class, () -> getElementByKey(elementKey[0]), null,
				 el -> {
					 elementKeyCode.remove(elementKey[0]);
					 elementKeyCode.setKeyCode(
						elementKey[0] = getBindKey(el),
						keyCode[0]
					 );
				 });
				vl.elementType = Button.class;
				vl.addCaptureListener(new ITooltip(() -> elementKey[0] + "\n" + EventHelper.longPressOrRclickKey() + " to edit"));
				pane.add(vl).size(220, 45).labelAlign(Align.left);
				TextButton button = pane.button(keyCode[0].toString(), Styles.flatt, () -> { })
				 .size(100, 45).get();
				button.clicked(() -> keyCodeBindWindow.get().show(button, newKeyCode -> {
					button.setText(newKeyCode.toString());
					elementKeyCode.setKeyCode(
					 elementKey[0],
					 keyCode[0] = newKeyCode
					);
				}));
				var current = pane.getCurrent();
				pane.button(Icon.trash, Styles.flati, () -> IntUI.shiftIgnoreConfirm(() -> {
					elementKeyCode.remove(elementKey[0]);
					current.removeElement();
				}));
				pane.row();
			});
		};
		cont.button("Flush", Icon.refreshSmall, Styles.flatt, rebuild).row();

		cont.defaults().colspan(2).height(CellTools.unset);
		new Search((_, text) -> pattern = text).build(cont, null);

		rebuild.run();
		pane.addPatternUpdateListener(() -> pattern);
		cont.pane(Styles.smallPane, pane).grow();
	}
	public void selectButton() {
		topGroup.requestSelectElem(TopGroup.defaultDrawer, Button.class, button -> {
			keyCodeBindWindow.get().show(button, keyCodeSetter(button));
		});
	}
	public void build() {
		if (ui == null) buildUI();
		ui.show();
	}
}
