
package modtools.ui.content;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.func.Cons;
import arc.func.Cons2;
import arc.func.Floatf;
import arc.func.Func;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Action;
import arc.scene.Element;
import arc.scene.Scene;
import arc.scene.event.InputEvent;
import arc.scene.event.Touchable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button;
import arc.scene.ui.Dialog;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.mod.Scripts;
import mindustry.ui.Styles;
import modtools.ui.Contents;
import modtools.ui.IntStyles;
import modtools.ui.IntUI;
import modtools.ui.components.MyLabel;
import modtools.ui.components.SclLisetener;
import modtools.ui.components.TextAreaTable;
import modtools.ui.components.TextAreaTable.MyTextArea;
import modtools.ui.components.Window;
import modtools.utils.JSFunc;
import modtools_lib.MyReflect;
import rhino.*;

import java.util.Objects;

import static modtools.utils.Tools.getAbsPos;

public class Tester extends Content {
	String log = "";
	MyTextArea area;
	public boolean loop = false, wrap = false, error, ignoreError = false,
			checkUI = true, wrapRef = true, multiWindows = false;
	static final float w = Core.graphics.isPortrait() ? 440 : 540;
	Window ui;
	ListDialog history, bookmark;
	public Scripts scripts;
	public Scriptable scope;
	public Context cx;
	public Script script = null;

	public Tester() {
		super("tester");
	}

	private static float sort(Fi f) {
		try {
			return -Long.parseLong(f.nameWithoutExtension());
		} catch (Exception e) {
			return Long.MAX_VALUE;
		}
	}

	public void build(Table table, Table buttons) {
		Table cont = new Table();
		TextAreaTable textarea = new TextAreaTable("");
		area = textarea.getArea();
		boolean[] execed = {false};
		textarea.keyDonwB = (event, keycode) -> {
			if (Core.input.ctrl() && Core.input.shift() && keycode == KeyCode.enter) {
				complieScript();
				execScript();
				execed[0] = true;
				return false;
			}
			//			Core.input.ctrl() && keycode == KeyCode.rightBracket
			if (keycode == KeyCode.tab) {
				area.insert("  ");
				area.setCursorPosition(area.getCursorPosition() + 2);
				area.updateDisplayText();
			}
			return true;
		};
		textarea.keyTypedB = (event, character) -> !execed[0];
		textarea.keyUpB = (event, keycode) -> {
			execed[0] = false;
			return true;
		};

		Cell<?> areaCell = cont.add(textarea).grow().minHeight(100).maxHeight(ui.cont.getHeight());
		areaCell.row();
		cont.update(() -> areaCell.maxHeight(ui.cont.getHeight()));

		cont.table(t -> {
			t.button(Icon.left, area::left);
			t.button("@ok", () -> {
				error = false;
				area.setText(getMessage().replaceAll("\r", ""));
				complieScript();
				execScript();
				Fi d = history.file.child(String.valueOf(Time.millis()));
				d.child("message.txt").writeString(getMessage());
				d.child("log.txt").writeString(log);
				history.list.insert(0, d);
				if (history.isShown()) {
					history.build();
				}
				//	history.build(d).with(b -> b.setZIndex(0));

				int max = history.list.size - 1;
				int min = 30;
				for (int i = max; i >= min; i--) {
					history.list.get(i).deleteDirectory();
					history.list.remove(i);
				}
			}).padLeft(8f).padRight(8f);
			t.button(Icon.right, area::right);
		}).growX().row();
		Cell<?> cell = cont.table(Tex.sliderBack, t -> t.pane(p -> {
			p.add(new MyLabel(() -> log)).style(IntStyles.myLabel).wrap().growX().labelAlign(Align.center, Align.left);
		}).growX()).growX().height(100).with(t -> t.touchable = Touchable.enabled);

		// Vec2 last = new Vec2(ui.getWidth(), ui.getHeight());
		// ui.sclLisetener.listener = () -> {
		// cell.height(cell.get().getHeight() * ui.getHeight() / last.y);
		// };
		new SclLisetener(cell.get(), 0, 100) {
			@Override
			public boolean valid() {
				return !left && !right && !bottom && top;
			}

			@Override
			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				super.touchDragged(event, x, y, pointer);
				;
				cell.height(Mathf.clamp(bind.getHeight(), 100, cont.getHeight() - 140));
				cont.invalidate();
				pane.setScrollingDisabled(false, false);
				// cont.layout();
				// cell[0].height(logTable.getHeight());
				// cont.pack();
			}

			@Override
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				super.touchUp(event, x, y, pointer, button);
				pane.setScrollingDisabled(false, true);
			}
		};
		table.add(cont).grow().maxHeight(Core.graphics.getHeight()).row();
		table.pane(p -> {
			p.button("", Icon.star, Styles.cleart, () -> {
				Fi fi = bookmark.file.child(Time.millis() + ".txt");
				bookmark.list.add(fi);
				fi.writeString(getMessage());
				bookmark.build();
			}).size(42).padRight(6f);
			p.button(b -> {
				b.label(() -> loop ? "循环" : "默认");
			}, Styles.defaultb, () -> {
				loop = !loop;
			}).size(100, 55);
			p.button(b -> {
				b.label(() -> wrap ? "严格" : "非严格");
			}, Styles.defaultb, () -> wrap = !wrap).size(100, 55);
			p.button(b -> {
				b.label(() -> textarea.enableHighlighting ? "高亮" : "不高亮");
			}, Styles.defaultb, () -> textarea.enableHighlighting = !textarea.enableHighlighting).size(100, 55);

			p.button("历史记录", history::show).size(100, 55);
			p.button("收藏夹", bookmark::show).size(100, 55);
		}).height(60).growX();

		//		buttons.button("$back", Icon.left, ui::hide).size(210, 64);
		var editTable = new Table(Styles.black5, p -> {
			p.fillParent = true;
			Runnable hide = () -> {
				p.remove();
				ui.noButtons(false);
			};
			p.table(Tex.pane, t -> {
				TextButtonStyle style = Styles.cleart;
				t.defaults().size(280, 60).left();
				t.row();
				t.button("@schematic.copy.import", Icon.download, style, () -> {
					hide.run();
					area.setText(Core.app.getClipboardText());
				}).marginLeft(12);
				t.row();
				t.button("@schematic.copy", Icon.copy, style, () -> {
					hide.run();
					Core.app.setClipboardText(getMessage().replaceAll("\r", "\n"));
				}).marginLeft(12);
				t.row();
				t.button("@back", Icon.left, style, hide).marginLeft(12);
			});
		});
		TextureRegionDrawable drawable = Icon.edit;
		buttons.button("@edit", drawable, () -> {
			ui.cont.addChild(editTable);
			editTable.setPosition(0, 0);
			ui.noButtons(true);
		}).size(210, 64);
	}

	public ScrollPane pane;

	void setup() {
		//		ui.cont.clear();
		//		ui.buttons.clear();
		ui.cont.pane(p -> build(p, ui.buttons)).grow().update(pane -> {
			this.pane = pane;
			pane.setOverscroll(false, false);
		});
	}

	public void build() {
		if (multiWindows) {
			var newTester = new Tester();
			newTester.load();
			newTester.build();
		} else ui.show();
		//		ui.show();
		/*if (ui.isShown()) {
			ui.setZIndex(Integer.MAX_VALUE);
		} else ui.show();*/
	}

	public void complieScript() {
		String def = getMessage();
		String source = wrap ? "(function(){\"use strict\";" + def + "\n})();" : def;
		try {
			script = cx.compileString(source, "console.js", 1);
		} catch (Throwable ex) {
			makeError(ex);
		}
	}

	public void makeError(Throwable ex) {
		error = true;
		loop = false;
		if (!ignoreError) IntUI.showException(Core.bundle.get("error_in_execution"), ex);
		log = Strings.neatError(ex);
	}

	public void execScript() {
		try {
			/*V8 runtime = V8.createV8Runtime();
			Log.debug(runtime.executeIntegerScript("let x=1;x*2"));*/
			Object o = script.exec(cx, scope);
			if (o instanceof NativeJavaObject) {
				o = ((NativeJavaObject) o).unwrap();
			}

			if (o instanceof Undefined) {
				o = "undefined";
			}

			log = String.valueOf(o);
			if (log == null) log = "null";
			else log = log.replaceAll("\\[(\\w*?)]", "[\u0001$1]");
		} catch (Throwable ex) {
			makeError(ex);
		}
	}

	public void load() {
		ui = new Window(localizedName(), w, 600, true, false);
		/*ui.update(() -> {
			ui.setZIndex(frag.getZIndex() - 1);
		});*/
		//		ui.addCloseListener();
		history = new ListDialog("history", Vars.dataDirectory.child("mods(I hope...)").child("historical record"),
				f -> f.child("message.txt"), f -> {
			area.setText(f.child("message.txt").readString());
			log = f.child("log.txt").readString();
		}, (f, p) -> {
			p.add(f.child("message.txt").readString()).row();
			p.image().height(3).fillX().row();
			p.add(f.child("log.txt").readString());
		}, Tester::sort);
		bookmark = new ListDialog("bookmark", Vars.dataDirectory.child("mods(I hope...)").child("bookmarks"),
				f -> f, f -> {
			area.setText(f.readString());
		}, (f, p) -> {
			p.add(f.readString()).row();
		}, Tester::sort);
		scripts = Vars.mods.getScripts();
		if (Context.getCurrentContext() == null) Context.enter();
		cx = scripts.context;
		scope = scripts.scope;

		try {
			Object obj = new NativeJavaClass(scope, JSFunc.class, true);
			ScriptableObject.putProperty(scope, "IntFunc", obj);
			obj = new NativeJavaClass(scope, MyReflect.class, false);
			ScriptableObject.putProperty(scope, "MyReflect", obj);
			ScriptableObject.putProperty(scope, "unsafe", MyReflect.unsafe);
			//			ScriptableObject.putProperty(scope, "Window", new NativeJavaClass(scope, Window.class, true));
		} catch (Exception ex) {
			if (ignoreError) {
				Log.err(ex);
			} else {
				Vars.ui.showException("IntFunc出错", ex);
			}

		}

		setup();
		Events.run(Trigger.update, () -> {
			//			Log.info("update");
			if (loop && script != null) {
				execScript();
			}
		});
		if (!init) loadSettings();
		init = true;
	}

	public static boolean init = false;

	public void loadSettings() {
		Table table = new Table();
		table.table(t -> {
			t.left().defaults().left();
			t.check("忽略报错", ignoreError, b -> ignoreError = b);
			t.check("自动转换储存的js变量", wrapRef, b -> wrapRef = b);
		}).row();
		table.table(t -> {
			t.left().defaults().left();
			t.check("窗口多开", multiWindows, b -> multiWindows = b);
		});

		Contents.settings.add(localizedName(), table);
	}

	public String getMessage() {
		return area.getText();
	}

	public Object getWrap(Object val) {
		return Context.javaToJS(val, scope);
	}

	public void put(String name, Object val) {
		if (wrapRef) {
			val = getWrap(val);
			//			else if (val instanceof Field) val = new NativeJavaObject(scope, val, Field.class);
		}
		ScriptableObject.putProperty(scope, name, val);
	}

	public void put(Object val) {
		int i = 0;
		String prefix = "temp";
		while (ScriptableObject.hasProperty(scope, prefix + i)) {
			i++;
		}
		put(prefix + i, val);
	}

	public void put(Element element, Object val) {
		int i = 0;
		String prefix = "temp";
		while (ScriptableObject.hasProperty(scope, prefix + i)) {
			i++;
		}
		put(prefix + i, val);
		IntUI.showInfoFade("已储存为[accent]" + prefix + i).setPosition(getAbsPos(element));
	}

	static class ListDialog extends Window {
		public Seq<Fi> list = new Seq<>();
		final Table p = new Table();
		Floatf<Fi> sorter;
		Fi file;
		Func<Fi, Fi> fileHolder;
		Cons<Fi> consumer;
		Cons2<Fi, Table> pane;

		public ListDialog(String title, Fi file, Func<Fi, Fi> fileHolder, Cons<Fi> consumer, Cons2<Fi, Table> pane, Floatf<Fi> sorter) {
			super(Core.bundle.get("title." + title, title), w, 600, true);
			cont.pane(p).grow();
			//			addCloseButton();
			this.file = file;
			list.addAll(file.list());
			this.fileHolder = fileHolder;
			this.consumer = consumer;
			this.pane = pane;
			this.sorter = sorter;

			list.sort(sorter);
		}

		public Window show(Scene stage, Action action) {
			build();
			return super.show(stage, action);
		}

		public void build() {
			p.clearChildren();

			list.each(this::build);
		}


		public Cell<Table> build(Fi f) {
			var tmp = p.table(Window.myPane, t -> {
				Button btn = t.left().button(b -> {
					b.pane(c -> {
						c.add(fileHolder.get(f).readString()).left();
					}).fillY().fillX().left();
				}, IntStyles.clearb, () -> {}).height(70).minWidth(400).growX().left().get();
				IntUI.longPress(btn, 600, longPress -> {
					if (longPress) {
						Dialog ui = new Dialog("");
						ui.cont.pane(p1 -> {
							pane.get(f, p1);
						}).size(400).row();
						ui.cont.button(Icon.trash, () -> {
							ui.hide();
							f.delete();
						}).row();
						Objects.requireNonNull(ui);
						ui.cont.button("@ok", ui::hide).fillX().height(60);
						ui.show();
					} else {
						consumer.get(f);
						build();
						hide();
					}

				});
				t.button("", Icon.trash, Styles.cleart, () -> {
					if (!f.deleteDirectory()) {
						f.delete();
					}

					list.remove(f);
					build();
				}).fill().right();
			}).width(w);
			p.row();
			return tmp;
		}
	}
}
