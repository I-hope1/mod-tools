
package modtools.ui.content;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.func.*;
import arc.func.Func;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Action;
import arc.scene.Element;
import arc.scene.Scene;
import arc.scene.event.*;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.*;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import hope_rhino.LinkRhino189012201;
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
import modtools.ui.components.area.TextAreaTable;
import modtools.ui.components.area.TextAreaTable.MyTextArea;
import modtools.ui.components.Window;
import modtools.ui.components.highlight.*;
import modtools.utils.*;
import ihope_lib.MyReflect;
import rhino.*;

import java.lang.reflect.*;
import java.util.Objects;
import java.util.regex.Pattern;

import static modtools.utils.Tools.getAbsPos;

public class Tester extends Content {
	String log = "";
	MyTextArea area;
	public boolean loop = false;
	public Object res;
	private boolean
			wrap = false, error, ignorePopUpError = false,
			wrapRef = true, multiWindows = false;
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
		if (ui == null) _load();
		TextAreaTable textarea = new TextAreaTable("");
		Table cont = new Table() {
			@Override
			public Element hit(float x, float y, boolean touchable) {
				Element element = super.hit(x, y, touchable);
				if (element == null) return null;
				if (element.isDescendantOf(this)) textarea.focus();
				return element;
			}
		};
		textarea.syntax = new JSSyntax(textarea);
		// JSSyntax.apply(textarea);
		area = textarea.getArea();
		boolean[] execed = {false};
		textarea.keyDonwB = (event, keycode) -> {
			if (Core.input.ctrl() && Core.input.shift() && keycode == KeyCode.enter) {
				complieAndExec(() -> {});
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
			t.defaults().padRight(8f);
			t.button(Icon.left, area::left);
			t.button("@ok", () -> {
				error = false;
				// area.setText(getMessage().replaceAll("\\r", "\\n"));
				complieAndExec(() -> {
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
				});
			});
			t.button(Icon.right, area::right);
			t.button(Icon.copy, area::copy).padLeft(8f);
			t.button(Icon.paste, () -> area.paste(Core.app.getClipboardText(), true)).padLeft(8f);
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

				cell.height(Mathf.clamp(bind.getHeight(), 100, cont.getHeight() - 140));
				cont.invalidate();
				pane.setScrollingDisabled(false, false);
				// cont.layout();
				// cell[0].height(logTable.getHeight());
				// cont.pack();
			}

			{
				cell.height(Mathf.clamp(bind.getHeight() + 1, 100, cont.getHeight() - 140));
				cont.invalidate();
			}

			@Override
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				super.touchUp(event, x, y, pointer, button);
				pane.setScrollingDisabled(false, true);
			}
		};
		ui.sclLisetener.listener = () -> {
			cont.invalidate();
		};
		table.add(cont).grow().maxHeight(Core.graphics.getHeight()).row();
		table.pane(p -> {
			p.button(Icon.star, IntStyles.clearNonei, () -> {
				Fi fi = bookmark.file.child(Time.millis() + ".txt");
				bookmark.list.add(fi);
				fi.writeString(getMessage());
				bookmark.build();
			}).size(42).padRight(6f);
			p.defaults().size(100, 60);
			p.button(b -> {
				b.label(() -> loop ? "@tester.loop" : "@tester.notloop");
			}, Styles.defaultb, () -> {
				loop = !loop;
			});
			p.button(b -> {
				b.label(() -> wrap ? "@tester.strict" : "@tester.notstrict");
			}, Styles.defaultb, () -> wrap = !wrap);
			p.button(b -> {
				b.label(() -> textarea.enableHighlighting ? "@tester.highlighting" : "@tester.nothighlighting");
			}, Styles.defaultb, () -> textarea.enableHighlighting = !textarea.enableHighlighting);
			p.button("@details", () -> JSFunc.showInfo(res));

			p.button("@historymessage", history::show);
			p.button("@bookmark", bookmark::show);
		}).height(60).width(w).growX();

		//		buttons.button("$back", Icon.left, ui::hide).size(210, 64);
		var editTable = new Table(Styles.black5, p -> {
			p.fillParent = true;
			Runnable hide = () -> {
				p.remove();
				ui.noButtons(false);
			};
			p.table(Tex.pane, t -> {
				TextButtonStyle style = IntStyles.cleart;
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
		if (ui == null) _load();
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

	public void complieAndExec(Runnable callback) {
		Time.runTask(0, () -> {
			complieScript();
			execScript();
			callback.run();
		});
	}

	public void complieScript() {
		error = false;
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
		if (MySettings.settings.getBool("outputToLog")) Log.err("tester", ex);
		if (!ignorePopUpError) IntUI.showException(Core.bundle.get("error_in_execution"), ex);
		log = Strings.neatError(ex);
	}

	public void execScript() {
		if (error) return;
		try {
			/*V8 runtime = V8.createV8Runtime();
			Log.debug(runtime.executeIntegerScript("let x=1;x*2"));*/
			if (Context.getCurrentContext() != cx) {
				cx = Context.getCurrentContext();
				LinkRhino189012201.init(cx);
			}
			Object o = script.exec(cx, scope);
			if (o instanceof Wrapper) {
				o = ((Wrapper) o).unwrap();
			}
			res = o;

			if (o instanceof Undefined) {
				o = "undefined";
			}

			log = String.valueOf(o);
			if (log == null) log = "null";
			if (MySettings.settings.getBool("outputToLog")) Log.info("tester: " + log);

			log = log.replaceAll("\\[(\\w*?)]", "[[$1]");
		} catch (Throwable ex) {
			makeError(ex);
		}
	}

	public void _load() {
		ui = new Window(localizedName(), w, 600, true, false);
		/*ui.update(() -> {
			ui.setZIndex(frag.getZIndex() - 1);
		});*/
		//		ui.addCloseListener();
		history = new ListDialog("history", MySettings.dataDirectory.child("historical record"),
				f -> f.child("message.txt"), f -> {
			area.setText(f.child("message.txt").readString());
			log = f.child("log.txt").readString();
		}, (f, p) -> {
			p.add(f.child("message.txt").readString()).row();
			p.image().color(JSFunc.underline).growX().padTop(6f).padBottom(6f).row();
			p.add(f.child("log.txt").readString()).row();
		}, Tester::sort);
		bookmark = new ListDialog("bookmark", MySettings.dataDirectory.child("bookmarks"),
				f -> f, f -> {
			area.setText(f.readString());
		}, (f, p) -> {
			p.add(f.readString()).row();
		}, Tester::sort);

		setup();
		Events.run(Trigger.update, () -> {
			//			Log.info("update");
			if (loop && script != null) {
				execScript();
			}
		});
	}

	@Override
	public void load() {
		if (!init) loadSettings();
		init = true;

		scripts = Vars.mods.getScripts();
		if (Context.getCurrentContext() == null) Context.enter();
		cx = scripts.context;
		scope = scripts.scope;

		try {
			Object obj1 = new NativeJavaClass(scope, JSFunc.class, true);
			ScriptableObject.putProperty(scope, "IntFunc", obj1);
			Object obj2 = new NativeJavaClass(scope, MyReflect.class, false);
			ScriptableObject.putProperty(scope, "MyReflect", obj2);
			ScriptableObject.putProperty(scope, "unsafe", MyReflect.unsafe);
			LinkRhino189012201.init(cx);
			//			ScriptableObject.putProperty(scope, "Window", new NativeJavaClass(scope, Window.class, true));
		} catch (Exception ex) {
			if (ignorePopUpError) {
				Log.err(ex);
			} else {
				Vars.ui.showException("IntFunc出错", ex);
			}
		}
	}

	public static boolean init = false;

	public void loadSettings() {
		Table table = new Table();
		table.defaults().growX();
		table.table(t -> {
			t.left().defaults().left();
			t.check("@settings.ignorePopUpError", MySettings.settings.getBool("ignorePopUpError"), b -> {
				MySettings.settings.put("ignorePopUpError", ignorePopUpError = b);
			}).row();
			t.check("@settings.wrapRef", wrapRef, b -> wrapRef = b);
		}).row();
		table.table(t -> {
			t.left().defaults().left();
			t.check("@settings.multiWindows", multiWindows, b -> multiWindows = b).row();
			t.check("@settings.outputToLog", MySettings.settings.getBool("outputToLog"), b -> {
				MySettings.settings.put("outputToLog", b);
			});
		});

		Contents.settings.add(localizedName(), table);
	}

	public String getMessage() {
		return area.getText();
	}

	public Object getWrap(Object val) {
		try {
			if (val instanceof Class) return new NativeJavaClass(scope, (Class<?>) val);
			if (val instanceof Method) return new NativeJavaMethod((Method) val, ((Method) val).getName());
			return Context.javaToJS(val, scope);
		} catch (Throwable e) {
			return val;
		}
	}

	public void put(String name, Object val) {
		if (wrapRef) {
			val = getWrap(val);
			//			else if (val instanceof Field) val = new NativeJavaObject(scope, val, Field.class);
		}
		ScriptableObject.putProperty(scope, name, val);
	}

	public String put(Object val) {
		int i = 0;
		String prefix = "tmp";
		while (ScriptableObject.hasProperty(scope, prefix + i)) {
			i++;
		}
		String key = prefix + i;
		put(key, val);
		return key;
	}

	public void put(Element element, Object val) {
		int i = 0;
		String prefix = "tmp";
		while (ScriptableObject.hasProperty(scope, prefix + i)) {
			i++;
		}
		put(prefix + i, val);
		IntUI.showInfoFade(Core.bundle.format("jsfunc.saved", prefix + i))
				.setPosition(getAbsPos(element));
	}

	public static Pattern fileUnfair = Pattern.compile("[\\\\/:*?<>\"\\[\\]]|(\\.\\s*$)");

	/*public class ListDialog extends Window {
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
				Fi fi = fileHolder.get(f);
				Fi[] fis = {fi};
				if (f == fi) {
					Label label = new Label(f.name());
					Cell cell = t.add(label);
					TextField field = new TextField();
					field.setValidator(text -> {
						try {
							return !text.isBlank() && !fileUnfair.matcher(text).find()
									&& (fis[0].name().equals(field.getText()) || !fis[0].sibling(text).exists());
						} catch (Throwable e) {
							return false;
						}
					});
					field.update(() -> {
						if (Core.scene.getKeyboardFocus() != field) {
							if (!fis[0].name().equals(field.getText()) && fis[0].sibling(field.getText()).exists()) {
								IntUI.showException(new IllegalArgumentException("文件夹已存在.\nFile has existed."));
							} else if (field.isValid()) {
								Fi toFi = f.sibling(field.getText());
								fis[0].moveTo(toFi);
								list.replace(fis[0], toFi);
								fis[0] = toFi;
								label.setText(field.getText());
							}
							cell.setElement(label);
						}
					});
					label.clicked(() -> {
						Core.scene.setKeyboardFocus(field);
						field.setText(fis[0].name());
						cell.setElement(field);
					});
				}
				TextButton code = new TextButton("Code");
				String cont = fi.exists() ? fi.readString() : "";
				code.clicked(() -> {
					IntUI.showSelectTable(code, (p, hide, __) -> {
						var table = new TextAreaTable(cont) {
							@Override
							public float getPrefHeight() {
								return Core.graphics.getHeight() - Scl.scl(30f);
							}
						};
						table.syntax = new JSSyntax(table);
						var area = p.add(table).width(400).get().getArea();
						// Time.runTask(5, () -> {
						area.updateDisplayText();
						Time.runTask(1, () -> {
							// area.layout();
							Timer.schedule(new Task() {
								@Override
								public void run() {
									if (!p.isDescendantOf(Core.scene.root) && fis[0].exists()) {
										if (fis[0].readString().equals(area.getText())) return;
										fis[0].writeString(area.getText());
									}
								}
							}, 0, 0.5f, -1);
						});
					}, false);
				});
				t.add(code).grow();
				t.button(Icon.download, Styles.cleari, () -> {
					area.setText(fis[0].readString());
					hide();
				}).right();
				t.button("", Icon.trash, Styles.cleart, () -> IntUI.showConfirm("@confirm", "@mod.remove.confirm", () -> {
					if (!fis[0].deleteDirectory()) {
						fis[0].delete();
					}

					list.remove(fis[0]);

					p.getCell(t).height(0).clearElement();
				}).setCenter(Core.input.mouse())).padLeft(8f).right();
			}).growX().height(70);
			p.row();
			return tmp;
		}
	}*/

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
			cont.add("@tester.tip").growX().left().row();
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
					}).grow().left();
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
				t.button("", Icon.trash, IntStyles.cleart, () -> {
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

