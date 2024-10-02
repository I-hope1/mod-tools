package modtools.content;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.input.KeyCode.KeyType;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.struct.LazyValue;
import modtools.ui.*;
import modtools.ui.comp.*;
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

	HKeyCode bindKeyCode;

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
				IntUI.showInfoFade("Temporary binding.");
			}
		};
	}
	public void load() {
		super.load();
		bindKeyCode = HKeyCode.data.dynamicKeyCode("bindKeyCode", () -> new HKeyCode(KeyCode.r).ctrl().shift());
		final Seq<Button> toRemoveSeq = new Seq<>();
		scene.addCaptureListener(new InputListener() {
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (bindKeyCode.isPress()) {
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
	public Button buildButton(boolean isSmallized) {
		Button button = super.buildButton(isSmallized);
		button.addListener(new ITooltip(() -> tipKey("shortcuts", bindKeyCode.toString())));
		return button;
	}
	public static void clicked(Element el) {
		el.fireClick();
	}
	/** 与{@link #getElementByKey(String)} 互为逆运算 */
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
	/** 与{@link #getBindKey(Button)} 互为逆运算 */
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

			shown(this::rebuild);
			IntVars.resizeListeners.add(this::display);
		}
		public void display() {
			if (button != null) IntUI.positionTooltip(button, Align.topLeft, this, Align.bottomLeft);
		}
		/** 被用来定位的 */
		Button         button;
		Cons<HKeyCode> callback;
		public void show(Button button, Cons<HKeyCode> callback) {
			this.button = button;
			this.callback = callback;
			show();
		}
		TextField field;
		private void rebuild() {
			Table cont = this.cont;
			cont.clearChildren();
			field = cont.field("", _ -> { })
			 .colspan(2).growX()
			 .get();
			field.setText("Press a key to bind");
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
	Window  ui;
	public void lazyLoad() {
		ui = new IconWindow(120, 400, true);
		Table cont = ui.cont;

		IntTab tab = new IntTab(CellTools.unset, new String[]{"Custom", "Internal"}, new Color[]{Color.acid, Color.sky},
		 new Table[]{customTable(), interanlTable()});

		new Search<>((_, text) -> pattern = text).build(cont, null);

		cont.add(tab.build()).grow();
	}
	private Table interanlTable() {
		FilterTable<String> pane = new FilterTable<>();
		pane.addPatternUpdateListener(() -> pattern);
		// 监控keys
		Cons<KeyCodeData> rebuild = new Cons<>() {
			public void get(KeyCodeData data) {
				if (data == HKeyCode.data) {
					pane.clear();
					pane.add(data.name).color(Pal.accent).expandX().left().colspan(2).row();
					pane.image().color(Pal.accent).fillX().colspan(2).row();
				}
				data.each((key, keyCode) -> {
					if (keyCode == elementKeyCode) return;
					if (keyCode instanceof KeyCodeData d) {
						Core.app.post(() -> {
							pane.add(Strings.capitalize(key)).color(Pal.accent).expandX().left().colspan(2).row();
							pane.image().color(Pal.accent).fillX().colspan(2).row();

							get(d);
						});
						return;
					}
					pane.bind(key);
					pane.add(key).left();
					keyCodeButton(pane, data, new HKeyCode[]{data.keyCode(key)}, new String[]{key});
					pane.row();
					pane.unbind();
				});
			}
		};
		rebuild.get(HKeyCode.data);
		return pane;

	}
	private Table customTable() {
		Table cont = new Table();

		FilterTable<String[]> pane = new FilterTable<>();
		pane.addPatternUpdateListener(() -> pattern);
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
				keyCodeButton(pane, elementKeyCode, keyCode, elementKey);
				pane.button(Icon.trash, Styles.flati, () -> IntUI.shiftIgnoreConfirm(elementKey[0], () -> {
					elementKeyCode.remove(elementKey[0]);
					pane.removeCells(elementKey);
				}));
				pane.row();
			});
		};
		rebuild.run();

		// 正式开始布局

		cont.defaults().height(42).margin(4).pad(4).growX();
		cont.button("Bind key", Icon.pencilSmall, Styles.flatt, this::selectButton);
		cont.button("Flush", Icon.refreshSmall, Styles.flatt, rebuild).row();

		cont.defaults().colspan(2).height(CellTools.unset);

		cont.add(pane).grow();
		return cont;
	}
	private static void keyCodeButton(FilterTable<?> pane, KeyCodeData data, HKeyCode[] keyCode, String[] elementKey) {
		TextButton button = pane.button(keyCode[0].toString(), Styles.flatt, () -> { })
		 .minWidth(100).height(45).growX().get();
		button.clicked(() -> keyCodeBindWindow.get().show(button, newKeyCode -> {
			button.setText(newKeyCode.toString());
			data.setKeyCode(
			 elementKey[0],
			 keyCode[0] = newKeyCode
			);
		}));
	}
	public void selectButton() {
		topGroup.requestSelectElem(TopGroup.defaultDrawer, Button.class, button -> {
			keyCodeBindWindow.get().show(button, keyCodeSetter(button));
		});
	}
	public void build() {
		ui.show();
	}
}
