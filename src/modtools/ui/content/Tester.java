
package modtools.ui.content;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.func.Cons2;
import arc.func.Floatf;
import arc.func.Func;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.scene.Action;
import arc.scene.Scene;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button;
import arc.scene.ui.Dialog;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.mod.Scripts;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import modtools.ui.Contents;
import modtools.ui.IntStyles;
import modtools.ui.IntUI;
import modtools.ui.components.TextAreaTable;
import modtools.ui.components.TextAreaTable.MyTextArea;
import modtools.utils.JSFunc;
import modtools_lib.MyReflect;
import rhino.*;

import java.util.Objects;

public class Tester extends Content {
	String log = "";
	MyTextArea area;
	boolean loop = false, wrap = false, error, ignoreError = false,
			checkUI = true, wrapRef = true;
	final float w = Core.graphics.isPortrait() ? 440 : 540;
	BaseDialog ui;
	ListDialog history, bookmark;
	public Scripts scripts;
	public Scriptable scope;
	public Context cx;

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

	public void show(Table table, Table buttons) {
		Table cont = new Table();
		TextAreaTable textarea = new TextAreaTable("");
		area = textarea.getArea();
		boolean[] execed = {false};
		textarea.keyDonwB = (event, keycode) -> {
			if (Core.input.ctrl() && Core.input.shift() && keycode == KeyCode.enter) {
				evalMessage();
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

		cont.add(textarea).size(w, 390).row();
		cont.image().color(Color.gray).growX().row();
		cont.table(t -> {
			t.button(Icon.left, area::left);
			t.button("@ok", () -> {
				error = false;
				area.setText(getMessage().replaceAll("\r", ""));
				evalMessage();
				Fi d = history.file.child("" + Time.millis());
				d.child("message.txt").writeString(getMessage());
				d.child("log.txt").writeString(log);
				history.list.insert(0, d);

				int max = history.list.size - 1;
				int min = Math.min(30, max);
				for (int i = min; i < max; i++) {
					history.list.get(i).deleteDirectory();
					history.list.remove(i);
				}
			}).padLeft(8f).padRight(8f);
			t.button(Icon.right, area::right);
		}).row();
		cont.table(Tex.button, t -> t.pane(p -> {
			p.label(() -> log);
		}).size(w, 390));
		table.add(cont).row();
		table.pane(p -> {
			p.button("", Icon.star, Styles.cleart, () -> {
				Fi fi = bookmark.file.child(Time.millis() + ".txt");
				bookmark.list.add(fi);
				fi.writeString(getMessage());
			}).size(32).padRight(6f);
			p.button(b -> {
				b.label(() -> loop ? "循环" : "默认");
			}, Styles.defaultb, () -> {
				loop = !loop;
			}).size(100, 55);
			p.button(b -> {
				b.label(() -> wrap ? "严格" : "非严格");
			}, Styles.defaultb, () -> wrap = !wrap).size(100, 55);
			p.button("历史记录", history::show).size(100, 55);
			p.button("收藏夹", bookmark::show).size(100, 55);
		}).height(60).fillX();

		buttons.button("$back", Icon.left, ui::hide).size(210, 64);
		BaseDialog dialog = new BaseDialog("$edit");
		dialog.cont.pane(p -> {
			p.margin(10);
			p.table(Tex.button, t -> {
				TextButtonStyle style = Styles.cleart;
				t.defaults().size(280, 60).left();
				t.row();
				t.button("@schematic.copy.import", Icon.download, style, () -> {
					dialog.hide();
					area.setText(Core.app.getClipboardText());
				}).marginLeft(12);
				t.row();
				t.button("@schematic.copy", Icon.copy, style, () -> {
					dialog.hide();
					Core.app.setClipboardText(getMessage().replaceAll("\r", "\n"));
				}).marginLeft(12);
			});
		});
		dialog.addCloseButton();
		TextureRegionDrawable drawable = Icon.edit;
		Objects.requireNonNull(dialog);
		buttons.button("$edit", drawable, dialog::show).size(210, 64);
	}

	void setup() {
		ui.cont.clear();
		ui.buttons.clear();
		ui.cont.pane(p -> show(p, ui.buttons)).fillX().fillY();
	}

	public void build() {
		ui.show();
	}

	void evalMessage() {
		String def = getMessage();
		String source = wrap ? "(function(){\"use strict\";" + def + "\n})();" : def;

		try {
			Object o = cx.evaluateString(scope, source, null, 1);
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
			error = true;
			loop = false;
			if (!ignoreError) Vars.ui.showException("执行出错", ex);
			String type = ex.getClass().getSimpleName();
			log = "[red][" + type + "][]" + ex.getMessage();
		}
	}

	public void load() {
		ui = new BaseDialog(localizedName());
		ui.addCloseListener();
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
		cx = scripts.context;
		scope = scripts.scope;

		try {
			Object obj = new NativeJavaClass(scope, JSFunc.class, true);
			ScriptableObject.putProperty(scope, "IntFunc", obj);
			obj = new NativeJavaClass(scope, MyReflect.class, false);
			ScriptableObject.putProperty(scope, "MyReflect", obj);
			ScriptableObject.putProperty(scope, "unsafe", MyReflect.unsafe);
		} catch (Exception ex) {
			if (ignoreError) {
				Log.err(ex);
			} else {
				Vars.ui.showException("IntFunc出错", ex);
			}

		}

		setup();
		btn.update(() -> {
			if (checkUI) {
				if (Core.scene.root.getChildren().select(el -> el.visible).size > 50) {
					loop = false;
					Dialog dialog;
					while (true) {
						dialog = Core.scene.getDialog();
						if (dialog == null) break;
						dialog.hide();
					}
//					Core.scene.root.clearChildren();
//					Vars.ui.init();

//					Events.fire(new ClientLoadEvent())
				}
			}
			if (loop && !getMessage().equals("")) {
				evalMessage();
			}
		});
		loadSettings();
	}

	public void loadSettings() {
		Table table = new Table();
		table.check("忽略报错", ignoreError, b -> ignoreError = b);
		table.check("ui过多检查", checkUI, b -> checkUI = b).row();
		table.check("自动转换储存的js变量", wrapRef, b -> wrapRef = b);

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
		Vars.ui.showInfoFade("已储存为[accent]" + prefix + i);
	}

	class ListDialog extends BaseDialog {
		public Seq<Fi> list = new Seq<>();
		final Table p = new Table();
		Floatf<Fi> sorter;
		Fi file;
		Func<Fi, Fi> fileHolder;
		Cons<Fi> consumer;
		Cons2<Fi, Table> pane;

		public ListDialog(String title, Fi file, Func<Fi, Fi> fileHolder, Cons<Fi> consumer, Cons2<Fi, Table> pane, Floatf<Fi> sorter) {
			super(Core.bundle.get("title." + title, title));
			cont.pane(p).fillX().fillY();
			addCloseButton();
			this.file = file;
			list.addAll(file.list());
			this.fileHolder = fileHolder;
			this.consumer = consumer;
			this.pane = pane;
			this.sorter = sorter;

			list.sort(sorter);
		}

		public Dialog show(Scene stage, Action action) {
			build();
			return super.show(stage, action);
		}

		public void build() {
			p.clearChildren();

			list.each(f -> {
				p.table(Tex.button, t -> {
					Button btn = t.left().button(b -> {
								b.pane(c -> {
									c.add(fileHolder.get(f).readString()).left();
								}).fillY().fillX().left();
							}, IntStyles.clearb, () -> {}).height(70)
							.minWidth(400).growX().fillX().left().get();
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
				}).width(w).row();
			});
		}
	}
}
