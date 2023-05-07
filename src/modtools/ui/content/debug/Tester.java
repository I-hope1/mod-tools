
package modtools.ui.content.debug;

import arc.Core;
import arc.files.Fi;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.*;
import arc.util.*;
import ihope_lib.MyReflect;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.mod.Scripts;
import mindustry.ui.Styles;
import modtools.events.*;
import modtools.rhino.ForRhino;
import modtools.ui.*;
import modtools.ui.components.*;
import modtools.ui.components.input.MyLabel;
import modtools.ui.components.input.area.TextAreaTab;
import modtools.ui.components.input.area.TextAreaTab.MyTextArea;
import modtools.ui.components.input.highlight.JSSyntax;
import modtools.ui.components.linstener.SclListener;
import modtools.ui.content.Content;
import modtools.ui.windows.NameWindow;
import modtools.utils.*;
import modtools.utils.MySettings.Data;
import rhino.*;

import java.lang.reflect.*;

import static arc.Core.scene;
import static ihope_lib.MyReflect.unsafe;
import static modtools.ui.components.ListDialog.fileUnfair;
import static modtools.ui.content.SettingsContent.addSettingsTable;
import static modtools.utils.MySettings.D_TESTER;
import static modtools.utils.Tools.*;

public class Tester extends Content {
	private static final int bottomCenter   = Align.center | Align.bottom;
	private final        int maxHistorySize = 30;
	String     log = "";
	MyTextArea area;
	public boolean loop = false;
	public Object  res;

	public static boolean catchOutsizeError = false;

	private boolean
	 wrap             = false,
	 error            = false,
	 ignorePopUpError = false,
	 wrapRef          = true,
	 multiWindows     = false;

	public static final float  w = Core.graphics.isPortrait() ? 400 : 500;
	public              Window ui;
	ListDialog history, bookmark;
	public Scripts    scripts;
	public Scriptable scope;
	public Context    cx;
	public Script     script = null;
	public boolean    stopIfOvertime;

	public Tester() {
		super("tester");
	}

	private static int sort(Fi f1, Fi f2) {
		/* 按修改时间倒序  */
		if (f1.lastModified() > f2.lastModified()) return -1;
		return 0;
	}

	public JSSyntax syntax;

	/**
	 * 用于回滚历史<br>
	 * -1表示originalText<br>
	 * -2表示倒数第一个
	 */
	public        int          historyIndex    = -1;
	/** 位于0处的文本 */
	public        StringBuffer originalText    = null;
	public static boolean      rollbackHistory = E_Tester.rollback_history.enabled();

	public ScrollPane pane;
	public void build(Table table) {
		if (ui == null) _load();

		TextAreaTab textarea = new TextAreaTab("");
		Table cont = new Table() {
			public Element hit(float x, float y, boolean touchable) {
				Element element = super.hit(x, y, touchable);
				if (element == null) return null;
				if (element.isDescendantOf(this)) textarea.focus();
				return element;
			}
		};
		Runnable invalidate = () -> {
			// cont.invalidate();
			textarea.getArea().invalidateHierarchy();
			textarea.layout();
		};
		ui.maximized(isMax -> Time.runTask(0, invalidate));
		ui.sclListener.listener = invalidate;

		textarea.syntax = syntax = new JSSyntax(textarea);
		// JSSyntax.apply(textarea);
		area = textarea.getArea();
		boolean[] cancelEvent = {false};
		textarea.keyDownB = (event, keycode) -> {
			cancelEvent[0] = false;
			if (rollAndExec(keycode) || detailsListener(keycode)) {
				cancelEvent[0] = true;
				event.cancel();
				return true;
			}
			// Core.input.ctrl() && keycode == KeyCode.rightBracket
			if (keycode == KeyCode.tab) {
				area.insert("  ");
				area.setCursorPosition(area.getCursorPosition() + 2);
				area.updateDisplayText();
			}
			return false;
		};
		textarea.keyTypedB = (event, keycode) -> cancelEvent[0];
		textarea.keyUpB = (event, keycode) -> cancelEvent[0];
		// textarea.pack();

		Cell<?> areaCell = cont.add(textarea).grow();
		areaCell.row();
		cont.update(() -> areaCell.maxHeight(ui.cont.getHeight() / Scl.scl()));

		cont.table(t -> {
			t.defaults().padRight(8f).size(32);
			t.button(Icon.leftOpenSmall, Styles.clearNonei, area::left);
			t.button("@ok", Styles.flatt, () -> {
				error = false;
				// area.setText(getMessage().replaceAll("\\r", "\\n"));
				compileAndExec(() -> {});
			}).width(50).disabled(__ -> !finished);
			t.button(Icon.rightOpenSmall, Styles.clearNonei, area::right);
			t.button(Icon.copySmall, Styles.clearNonei, area::copy).padLeft(8f);
			t.button(Icon.pasteSmall, Styles.clearNonei, () ->
			 area.paste(Core.app.getClipboardText(), true)
			).padLeft(8f);
		}).growX().row();
		Cell<?> cell = cont.pane(p -> {
			p.background(Tex.sliderBack);
			p.add(new MyLabel(() -> log)).style(IntStyles.MOMO_LabelStyle).wrap()
			 .grow().labelAlign(Align.center, Align.left);
		}).growX().with(t -> t.touchable = Touchable.enabled);

		new SclListener(cell.get(), 0, cell.get().getPrefHeight()) {
			public boolean valid(float x, float y) {
				super.valid(x, y);
				return top && !isDisabled();
			}
			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				super.touchDragged(event, x, y, pointer);

				cell.height(Mathf.clamp(bind.getHeight(), 0, cont.getHeight()) / Scl.scl());
				cont.invalidate();
				pane.setScrollingDisabled(false, false);
			}

			{
				Time.runTask(1, () -> {
					Vec2 v1 = getAbsPos(cont);
					if (scene.touchDown((int) v1.x, (int) v1.y, 0, KeyCode.mouseLeft)) {
						scene.touchUp((int) v1.x, (int) v1.y, 0, KeyCode.mouseLeft);
					}
				});
			}

			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				super.touchUp(event, x, y, pointer, button);
				pane.setScrollingDisabled(false, true);
			}
		};

		area.invalidateHierarchy();
		table.add(cont).grow()
		 .self(c -> c.update(__ -> c.maxHeight(Core.graphics.getHeight() / Scl.scl())))
		 .row();

		table.add(
			sr(new PrefPane(p -> {
				p.button(Icon.star, IntStyles.clearNonei, this::star).size(42).padRight(6f);
				p.defaults().size(100, 56);
				ButtonStyle style = IntStyles.flatb;
				p.button(b -> {
					b.label(() -> loop ? "@tester.loop" : "@tester.notloop");
				}, style, () -> {
					loop = !loop;
				});
				p.button(b -> {
					b.label(() -> wrap ? "@tester.strict" : "@tester.notstrict");
				}, style, () -> wrap = !wrap);
				p.button(b -> {
					b.label(() -> textarea.enableHighlighting ? "@tester.highlighting" : "@tester.nothighlighting");
				}, style, () -> textarea.enableHighlighting = !textarea.enableHighlighting);

				TextButtonStyle style1 = Styles.flatt;
				p.button("@details", style1, this::showDetails);
				p.button("@historymessage", style1, history::show);
				p.button("@bookmark", style1, bookmark::show);
				// p.button("@startup", bookmark::show);
				p.check("@stopIfOvertime", stopIfOvertime, b -> stopIfOvertime = b).width(120);
			})).ifPresent(p -> p.xp = sp -> Math.min(sp, w)).get())
		 .height(56).growX()
		 .get().setScrollingDisabledY(true);

		buildEditTable();
	}
	private void showDetails() {
		if (res instanceof Class<?> cl) JSFunc.showInfo(cl);
		else JSFunc.showInfo(res);
	}
	public boolean detailsListener(KeyCode keycode) {
		if (keycode == KeyCode.d && Core.input.ctrl() && Core.input.shift()) {
			showDetails();
			return true;
		}
		return false;
	}
	private void buildEditTable() {
		// ui.buttons.row();
		var editTable = new Table(Styles.black5, p -> {
			p.fillParent = true;
			p.touchable = Touchable.enabled;
			Runnable hide = p::remove;
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
					JSFunc.copyText(getMessage().replaceAll("\r", "\n"));
				}).marginLeft(12);
				t.row();
				t.button("@back", Icon.left, style, hide).marginLeft(12);
			});
		});

		ui.button(Icon.upOpenSmall, Styles.squarei, () -> {
			ui.addChild(editTable);
			editTable.setPosition(0, 0);
			// ui.noButtons(true);
		}).update(b -> {
			b.setPosition(ui.cont.getWidth() / 2f, 0, Align.center | Align.bottom);
		}).with(b -> {
			ui.getCell(b).clearElement();
			b.remove();
			ui.addChild(b);
		});
		/* ui.buttons.button("@edit", Icon.edit, () -> {
			ui.cont.addChild(editTable);
			editTable.setPosition(0, 0);
			ui.noButtons(true);
		}).size(210, 64); */
	}
	private void star() {
		new NameWindow(res -> {
			Fi fi = bookmark.file.child(res);
			bookmark.list.insert(0, fi);
			fi.writeString(getMessage());
			bookmark.build();
		}, t -> {
			try {
				return !t.isBlank() && !fileUnfair.matcher(t).find()
							 && !bookmark.file.child(t).exists();
			} catch (Throwable e) {
				return false;
			}
		}, "").show();
	}
	private boolean rollAndExec(KeyCode keycode) {
		if (Core.input.ctrl() && Core.input.shift()) {
			if (keycode == KeyCode.enter) {
				compileAndExec(() -> {});
				return true;
			}
			if (keycode == KeyCode.up && rollHistory(true)) return true;
			if (keycode == KeyCode.down && rollHistory(false)) return true;
			return false;
		}
		return false;
	}
	private boolean rollHistory(boolean forward) {
		if (historyIndex == -1) originalText = area.getText0();
		historyIndex += forward ? 1 : -1;
		Vec2 pos = Tmp.v1.set(ui.x + ui.getWidth() / 2f, ui.y + 20);
		if (historyIndex == -1 || (rollbackHistory && historyIndex >= maxHistorySize)) {
			if (historyIndex != -1) showRollback(pos);
			historyIndex = -1;
			area.setText0(originalText);
			log = "";
			IntUI.showInfoFade("[accent]0/[lightgray]" + maxHistorySize, pos, bottomCenter);
			return true;
		}
		if (rollbackHistory) {
			historyIndex = Math.max(historyIndex, -1);
			int last = historyIndex;
			historyIndex = (historyIndex + maxHistorySize) % maxHistorySize;
			if (last != historyIndex) {
				showRollback(pos);
			}
		} else if (historyIndex < -1 || historyIndex >= maxHistorySize) {
			historyIndex = forward ? history.list.size - 1 : -1;
			return false;
		}
		IntUI.showInfoFade(historyIndex + 1 + "/[lightgray]" + maxHistorySize, pos, bottomCenter);
		Fi dir = history.list.get(historyIndex);
		area.setText(dir.child("message.txt").readString());
		log = dir.child("log.txt").readString();
		return true;
	}
	private static void showRollback(Vec2 pos) {
		IntUI.showInfoFade("@tester.rollback", Tmp.v2.set(pos).add(0, 80), bottomCenter);
	}
	public void build() {
		if (ui == null) {
			_load();
			ui.show();
			return;
		}

		if (multiWindows) {
			var newTester = new Tester();
			newTester.load();
			newTester.build();
		} else ui.show();
	}
	void setup() {
		ui.cont.pane(this::build).grow().update(pane -> {
			this.pane = pane;
			pane.setOverscroll(false, false);
		});
	}

	boolean finished = true;
	public long lastTime = Long.MAX_VALUE;

	public void compileAndExec(Runnable callback) {
		if (Context.getCurrentContext() == null) Context.enter();
		lastTime = Time.millis();
		Time.runTask(0, () -> {
			compileScript();
			execScript();

			historyIndex = -1;
			originalText = area.getText0();
			// 保存历史记录
			Fi d = history.file.child(String.valueOf(Time.millis()));
			d.child("message.txt").writeString(getMessage());
			d.child("log.txt").writeString(log);
			history.list.insert(0, d);
			if (history.isShown()) {
				history.build();
			}
			//	history.build(d).with(b -> b.setZIndex(0));

			int max = history.list.size - 1;
			/* 判断是否大于边际（min：30），大于就删除 */
			for (int i = max; i >= maxHistorySize; i--) {
				history.list.get(i).deleteDirectory();
				history.list.remove(i);
			}
			callback.run();
		});
		/*Object helper = VMBridge.getThreadContextHelper();
		new Thread(() -> {
			while (true) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				if (finished || Time.millis() - time >= 1_000) {
					Log.info("break");
					VMBridge.setContext(helper, null);
					Method m = null;
					try {
						m = ContextFactory.class.getDeclaredMethod("onContextReleased", Context.class);
						m.setAccessible(true);
						m.invoke(cx.getFactory(), cx);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
					break;
				}
			}
		}).start();*/
	}
	public void compileScript() {
		error = false;
		String def    = getMessage();
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
		if (MySettings.SETTINGS.getBool("outputToLog")) Log.err("tester", ex);
		if (!ignorePopUpError) IntUI.showException(Core.bundle.get("error_in_execution"), ex);
		log = Strings.neatError(ex);
	}


	// 执行脚本
	public void execScript() {
		if (!finished) return;
		finished = false;
		if (error) {
			finished = true;
			return;
		}
		/*V8 runtime = V8.createV8Runtime();
			Log.debug(runtime.executeIntegerScript("let x=1;x*2"));*/
		if (Context.getCurrentContext() != cx) {
			cx = Context.getCurrentContext();
		}
		// currentThread = new Thread(() -> {
		try {
			Object o = script.exec(cx, scope);
			res = o = JSFunc.unwrap(o);

			log = String.valueOf(o);
			if (log == null) log = "null";
			if (MySettings.SETTINGS.getBool("outputToLog")) {
				Log.info("[ [tester]: " + log);
			}

			finished = true;
			lastTime = Long.MAX_VALUE;
		} catch (Throwable e) {
			handleError(e);
		}
		// });
		// currentThread.start();
	}
	public void handleError(Throwable ex) {
		makeError(ex);
		finished = true;
		lastTime = Long.MAX_VALUE;
	}

	public void _load() {
		ui = new Window(localizedName(), w, 200, true, true);
		// JSFunc.watch("times", () -> ui.times);
		/*ui.update(() -> {
			ui.setZIndex(frag.getZIndex() - 1);
		});*/
		history = new ListDialog("history", MySettings.dataDirectory.child("historical record"),
		 f -> f.child("message.txt"), f -> {
			area.setText(f.child("message.txt").readString());
			log = f.child("log.txt").readString();
		}, (f, p) -> {
			p.add(new MyLabel(f.child("message.txt").readString())).row();
			p.image().color(JSFunc.c_underline).growX().padTop(6f).padBottom(6f).row();
			p.add(new MyLabel(f.child("log.txt").readString())).row();
		}, Tester::sort);
		history.hide();
		bookmark = new ListDialog("bookmark", MySettings.dataDirectory.child("bookmarks"),
		 f -> f, f -> area.setText(f.readString()),
		 (f, p) -> {
			 p.add(new MyLabel(f.readString())).row();
		 }, Tester::sort);
		bookmark.hide();

		setup();

		tasks.add(() -> {
			if (loop && script != null) {
				execScript();
			}
		});
	}
	public void load() {
		if (init) return;
		init = true;
		loadSettings();
		if (Kit.classOrNull(Tester.class.getClassLoader(), "modtools.rhino.ForRhino")
				== null) throw new RuntimeException("无法找到类: modtools.rhino.ForRhino");

		scripts = Vars.mods.getScripts();
		if (Context.getCurrentContext() == null) Context.enter();
		cx = scripts.context;
		scope = scripts.scope;

		try {
			Object obj1 = new NativeJavaClass(scope, JSFunc.class, true);
			ScriptableObject.putProperty(scope, "IntFunc", obj1);
			Object obj2 = new NativeJavaClass(scope, MyReflect.class, false);
			ScriptableObject.putProperty(scope, "MyReflect", obj2);
			ScriptableObject.putProperty(scope, "unsafe", unsafe);
			ScriptableObject.putProperty(scope, "modName", "<unknown>");
			ScriptableObject.putProperty(scope, "scriptName", "console.js");
			Field f = Context.class.getDeclaredField("factory");
			f.setAccessible(true);
			f.set(cx, ForRhino.factory);
			cx.setGenerateObserverCount(true);
		} catch (Exception ex) {
			if (ignorePopUpError) {
				Log.err(ex);
			} else {
				Vars.ui.showException("IntFunc出错", ex);
			}
		}

		// 启动脚本
		Fi dir = MySettings.dataDirectory.child("startup");
		if (dir.exists() && dir.isDirectory()) {
			dir.walk(f -> {
				if (!f.extEquals("js")) return;
				try {
					cx.evaluateString(scope, f.readString(), f.name(), 1);
				} catch (Throwable e) {
					Log.err(e);
				}
			});
		} else {
			Log.info("Loaded startup directory.");
			dir.delete();
			dir.child("README.txt").writeString("这是一个用于启动脚本（js）的文件夹\n\n所有的js文件都会执行");
		}
	}

	public static boolean init = false;
	public void loadSettings(Data settings) {
		Table table = new Table();
		table.defaults().growX();
		addSettingsTable(table, "BASE", n -> "tester." + n, D_TESTER, E_Tester.values());
		MyEvents events = new MyEvents();
		events.onIns(E_Tester.ignore_popup_error, e -> {
			ignorePopUpError = e.enabled();
		});
		events.onIns(E_Tester.catch_outsize_error, e -> {
			catchOutsizeError = e.enabled();
		});
		events.onIns(E_Tester.wrap_ref, e -> {
			wrapRef = e.enabled();
		});
		events.onIns(E_Tester.multi_windows, e -> {
			multiWindows = e.enabled();
		});
		events.onIns(E_Tester.rollback_history, e -> {
			rollbackHistory = e.enabled();
		});
		events.onIns(E_Tester.js_prop, e -> {
			syntax.enableJSProp = e.enabled();
		});

		Contents.settingsUI.add(localizedName(), table);
	}

	public String getMessage() {
		return area.getText();
	}
	public Object getWrap(Object val) {
		try {
			if (val instanceof Class<?> cl) return cx.getWrapFactory().wrapJavaClass(cx, scope, cl);
			if (val instanceof Method method) return new NativeJavaMethod(method, method.getName());
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
	public final String prefix = "tmp";
	public String put(Object val) {
		int i = 0;
		// 从0开始直到找到没有被定义的变量
		while (ScriptableObject.hasProperty(scope, prefix + i)) i++;
		String key = prefix + i;
		put(key, val);
		return key;
	}
	public void put(Element element, Object val) {
		put(getAbsPos(element), val);
	}
	public void put(Vec2 vec2, Object val) {
		IntUI.showInfoFade(Core.bundle.format("jsfunc.saved", put(val)), vec2);
	}
}