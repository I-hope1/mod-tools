
package modtools.content.debug;

import arc.Core;
import arc.files.Fi;
import arc.func.Func;
import arc.graphics.*;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.event.InputEvent.InputEventType;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.layout.*;
import arc.struct.ObjectMap.Entry;
import arc.struct.Seq;
import arc.util.*;
import arc.util.Log.*;
import arc.util.Timer.Task;
import arc.util.pooling.Pools;
import arc.util.serialization.Jval.JsonMap;
import mindustry.Vars;
import mindustry.android.AndroidRhinoContext.AndroidContextFactory;
import mindustry.game.EventType;
import mindustry.game.EventType.Trigger;
import mindustry.gen.*;
import mindustry.mod.Scripts;
import modtools.*;
import modtools.content.Content;
import modtools.content.SettingsUI.SettingsBuilder;
import modtools.events.*;
import modtools.jsfunc.*;
import modtools.jsfunc.type.CAST;
import modtools.misc.AddedSeq;
import modtools.override.ForRhino;
import modtools.struct.LazyValue;
import modtools.struct.v6.AThreads;
import modtools.ui.*;
import modtools.ui.comp.Window;
import modtools.ui.comp.buttons.FoldedImageButton;
import modtools.ui.comp.input.MyLabel;
import modtools.ui.comp.input.area.TextAreaTab;
import modtools.ui.comp.input.area.TextAreaTab.MyTextArea;
import modtools.ui.comp.input.highlight.JSSyntax;
import modtools.ui.comp.input.highlight.Syntax.*;
import modtools.ui.comp.limit.PrefPane;
import modtools.ui.comp.linstener.*;
import modtools.ui.comp.utils.*;
import modtools.ui.comp.windows.ListDialog;
import modtools.ui.control.HKeyCode;
import modtools.ui.gen.HopeIcons;
import modtools.ui.menu.MenuItem;
import modtools.utils.*;
import modtools.utils.JSFunc.JColor;
import modtools.utils.MySettings.Data;
import modtools.utils.io.FileUtils;
import modtools.utils.ui.FormatHelper;
import rhino.*;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.concurrent.ThreadPoolExecutor;

import static ihope_lib.MyReflect.unsafe;
import static modtools.content.debug.Tester.Settings.*;
import static modtools.utils.Tools.*;

public class Tester extends Content {
	private static final int FADE_ALIGN = Align.bottomLeft;

	public static final float WIDTH = Core.graphics.isPortrait() ? 400 : 420;

	public static Scripts    scripts;
	public static Scriptable topScope, scope;
	private static Context cx;

	LazyValue<ThreadPoolExecutor> executor = LazyValue.of(() -> (ThreadPoolExecutor) AThreads.impl.boundedExecutor(Tester.class.getSimpleName(), 1));

	public static final Data EXEC_DATA         = MySettings.SETTINGS.child("execution_js");
	public static final Fi   bookmarkDirectory = IntVars.dataDirectory.child("bookmarks");
	public static final Fi   startupDir        = IntVars.dataDirectory.child("startup");

	private static TaskNode startupTask;
	static TaskNode startupTask() {
		if (startupTask == null) startupTask = ExecuteTree.nodeRoot(null, "JS startup", "startup",
		 Icon.craftingSmall, IntVars.EMPTY_RUN);
		return startupTask.menuList(() -> Seq.with(
		 MenuItem.with("fi.open", Icon.fileSmall, "Open Dir", runT(() -> {
			 Fi[]   list = startupDir.list();
			 String path = list.length == 0 ? startupDir.path() : list[0].path();
			 FileUtils.openFile(path);
		 })))
		);
	}

	public static void initExecution() {
		initScript();
		if (EXEC_DATA.isEmpty()) return;
		ExecuteTree.context(startupTask(), () -> {
			for (Entry<String, Object> entry : EXEC_DATA) {
				// Log.info("[modtools]: loaded fi: " + entry.value.getClass());
				if (!(entry.value instanceof Data map)) continue;

				String taskName = startupTask().name;
				String mainCode =
				 map.getBool("disposable") && map.containsKey("type") ?
					STR."""
					Events.on(\{map.get("type")}, $e$ => {\
					 try {\
					  \{readFiOrEmpty(bookmarkDirectory.child(entry.key))};
					} catch(e) { Log.err(e); }});
					"""
					: readFiOrEmpty(bookmarkDirectory.child(entry.key));
				String source = STR."""
				(() => {\
				modName='\{taskName}';\
				scriptName=`\{entry.key}`;\
				\{mainCode}
				})()""";
				ExecuteTree.node(() -> {
					 cx.evaluateString(scope, source, STR."<\{taskName}>\{entry.key}", 1);
				 }, taskName, entry.key, Icon.none, IntVars.EMPTY_RUN)
				 .intervalSeconds(map.getFloat("intervalSeconds", 0.1f))
				 .repeatCount(map.getBool("disposable") ? 0 : map.getInt("repeatCount", 0))
				 .code(source)
				 .apply();
			}
		});
	}
	private static void initScript() {
		if (scripts != null) return;
		scripts = Vars.mods.getScripts();
		topScope = scripts.scope;
		scope = new ScriptableObject(topScope, topScope) {
			public String getClassName() {
				return "TesterScope";
			}
		};
		cx = scripts.context;
	}

	// =------------------------------=

	Fi          lastDir;
	String      log = "";
	TextAreaTab textarea;
	MyTextArea  area;

	public boolean loop = false;
	public Object  res;

	private boolean
	 strict = false,
	 error  = false;


	public Window ui;
	ListDialog history, bookmark;

	public Script  script = null;
	public boolean multiThread;
	public boolean stopIfOvertime;
	Task killTask;

	public Tester() {
		super("tester", Icon.terminalSmall);
	}

	/* 按修改时间倒序  */
	private static int sort(Fi f1, Fi f2) {
		return Long.compare(f2.lastModified(), f1.lastModified());
	}


	/**
	 * 用于回滚历史<br>
	 * -1表示originalText<br>
	 * -2表示倒数第一个
	 */
	public int    historyPos   = -1;
	/** 位于0处的文本 */
	public String originalText = null;

	public ScrollPane  pane;
	public SclListener logSclListener;
	public void build(Table table) {
		if (ui == null) _load();

		textarea = new TextAreaTab("");
		Table _cont = new Table();
		if (!Vars.mobile) textarea.addListener(new EscapeAndAxesClearListener(area));

		Runnable areaInvalidate = () -> {
			textarea.getArea().invalidateHierarchy();
			textarea.layout();
		};
		ui.maximized(_ -> Time.runTask(0, areaInvalidate));
		ui.sclListener.listener = areaInvalidate;

		addListenerToArea(textarea);

		Cell<?> areaCell = _cont.add(textarea).grow();
		_cont.row();
		ui.cont.update(() -> ((JSSyntax) textarea.syntax).enableJSProp = js_prop.enabled());

		Table center = _cont.table(t -> {
			t.defaults()
			 .padRight(4f).padRight(4f)
			 .size(45, 42);

			t.button(Icon.leftOpenSmall, HopeStyles.clearNonei, area::left);
			t.button("@ok", HopeStyles.flatBordert, () -> {
				error = false;
				// area.setText(getMessage().replaceAll("\\r", "\\n"));
				compileAndExec(IntVars.EMPTY_RUN);
			}).width(64).disabled(_ -> !finished);
			t.button(Icon.rightOpenSmall, HopeStyles.clearNonei, area::right);
			t.button(Icon.copySmall, HopeStyles.clearNonei, area::copy);
			t.button(Icon.pasteSmall, HopeStyles.clearNonei, () ->
			 area.paste(Core.app.getClipboardText(), true)
			);
			t.button(Icon.eyeSmall, HopeStyles.clearNonei, () -> {
				compileScript();
				Script script = this.script;
				setContextToThread();
				JSFunc.watch().watch(textarea.getText(), () -> {
					return CAST.unwrap(script.exec(cx, scope));
				});
			});
		}).growX().minWidth(WIDTH).get();
		_cont.getChildren().each(btn -> {
			btn.addListener(new KeepFocusListener(area));
		});
		_cont.row();
		Cell<?> logCell = _cont.table(Tex.sliderBack, t -> {
			Element actor = new Element();
			t.addChild(actor);
			actor.update(() -> actor.setBounds(0, t.getHeight(), t.getWidth(), center.getPrefHeight()));
			t.pane(p -> {
				p.left().top();
				buildLog(p);
				p.image(Icon.leftOpenSmall).color(Color.gray).size(24).top();
				p.add(new ClearValueLabel<>(Object.class, () -> res, null) {
					 protected boolean autoUpdate() {
						 return true;
					 }
				 }).wrap().style(HopeStyles.defaultLabel)
				 .labelAlign(Align.left).growX();
			}).grow().with(p -> p.setScrollingDisabled(true, false));
		}).growX().touchable(Touchable.enabled);

		ScrollPane pane = new ScrollPane(_cont);
		pane.setScrollingDisabled(true, true);
		int areaSpace = 10;
		Runnable invalid = () -> {
			float height = pane.getHeight() - _cont.getChildren().sumf(
			 e -> e == textarea ? 0 : e.getHeight()
			);
			if (height < 0) {
				logCell.height((pane.getHeight() - center.getHeight() - areaSpace) / Scl.scl());
				return;
			}
			logCell.get().toBack();
			areaCell.height(height / Scl.scl());
			_cont.invalidate();
		};
		pane.update(invalid);
		logSclListener = new SclListener(logCell.get(), 0, logCell.get().getPrefHeight()) {
			public boolean valid(float x, float y) {
				y -= bind.getHeight();
				top = 0 < y && y < offset;
				return top && !isDisabled();
			}
			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				super.touchDragged(event, x, y, pointer);

				float val = Mathf.clamp(bind.getHeight(), 0, pane.getHeight() - center.getHeight() - areaSpace);
				bind.setHeight(val);
				// areaCell.height(sum - val / Scl.scl());
				logCell.height(val / Scl.scl());
				invalid.run();
				pane.setScrollingDisabled(true, true);
			}
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				super.touchUp(event, x, y, pointer, button);
				// pane.setScrollingDisabled(true, false);
			}
		};
		logSclListener.offset = center.getPrefHeight();
		Time.runTask(1, () -> {
			InputEvent event = EventHelper.obtainEvent(InputEventType.touchDown, 0, 0, 0, KeyCode.mouseLeft);
			if (logSclListener.touchDown(event, 0, 0, 0, KeyCode.mouseLeft)) {
				logSclListener.touchUp(event, 0, 0, 0, KeyCode.mouseLeft);
			}
			Pools.free(event);
		});

		logCell.height(64f).padLeft(8f);

		table.add(pane).grow();
		table.row();

		bottomBar(table, textarea);
	}
	private void addListenerToArea(TextAreaTab textarea) {
		textarea.syntax = new JSSyntax(textarea, scope);
		area = textarea.getArea();
		boolean[] stopEvent = {false};
		textarea.keyDownB = (event, keycode) -> {
			stopEvent[0] = false;
			if (rollAndExec(keycode) || detailsListener(keycode)) {
				stopEvent[0] = true;
				if (event != null) event.cancel();
				return true;
			}
			if (!hasFunctionKey() && keycode == KeyCode.tab) {
				stopEvent[0] = true;
				area.paste("  ", true);
			}
			return false;
		};
		textarea.keyTypedB = (_, _) -> stopEvent[0];
		textarea.keyUpB = (_, _) -> stopEvent[0];
		area.addCaptureListener(new ComplementListener());
	}
	static boolean hasFunctionKey() {
		return Core.input.shift() || Core.input.ctrl() || Core.input.alt();
	}

	private void buildLog(Table p) {
		p.table(lg -> lg.left().update(() -> {
			if (logs.isResolved()) return;
			lg.getCells().clear();
			lg.clearChildren();
			logs.each(item -> {
				lg.add(STR."[\{item.charAt(0)}]")
				 .style(HopeStyles.defaultLabel)
				 .color(logLevelToColor(item)).top();
				lg.add(item.substring(1))
				 .wrap().growX().style(HopeStyles.defaultLabel)
				 .labelAlign(Align.left).row();
			});
		})).growX().left().colspan(2).row();
	}

	private static Color logLevelToColor(String item) {
		return switch (item.charAt(0)) {
			case 'D' -> Color.green;
			case 'I' -> Color.royal;
			case 'W' -> Color.yellow;
			case 'E' -> Color.scarlet;
			case ' ' -> Color.white;
			default -> throw new IllegalStateException("Unexpected value: " + item.charAt(0));
		};
	}

	void bottomBar(Table table, TextAreaTab textarea) {
		FoldedImageButton folder = new FoldedImageButton(true, HopeStyles.hope_flati);
		Table             p      = new Table();
		PrefPane          pane   = new PrefPane(p);
		int               height = 56;
		pane.xp = _ -> WIDTH * Scl.scl();
		pane.yp = _ -> folder.hasChildren() ? height : 0;
		pane.setScrollingDisabledY(true);
		folder.setContainer(table.add(pane).growX().padLeft(6f));

		Table folderContainer = new Table();
		folderContainer.left().bottom().add(folder).size(36f);
		folderContainer.setFillParent(true);
		folderContainer.update(() -> folderContainer.y = folder.cell.hasElement() ? pane.getHeight() : 0);
		table.addChild(folderContainer);
		folder.rebuild = () -> {
			Time.runTask(1, folderContainer::toFront);
			p.clearChildren();
			p.getCells().clear();
			ImageButtonStyle istyle = HopeStyles.clearNonei;
			int              isize  = 26;
			p.defaults().size(45).padLeft(2f);
			p.button(Icon.starSmall, istyle, isize, this::star);

			IntUI.addCheck(p.button(HopeIcons.loop, new ImageButtonStyle(istyle), isize, () -> {
				loop = !loop;
			}), () -> loop, "@tester.loop", "@tester.notloop");

			IntUI.addCheck(p.button(Icon.lockSmall, new ImageButtonStyle(istyle), isize, () -> {
				strict = !strict;
			}), () -> strict, "@tester.strict", "@tester.notstrict");

			// highlight
			IntUI.addCheck(p.button(HopeIcons.highlight, new ImageButtonStyle(istyle), isize, () -> {
				textarea.enableHighlighting = !textarea.enableHighlighting;
			}), () -> textarea.enableHighlighting, "@tester.highlighting", "@tester.nothighlighting");

			ImageButton details = IntUI.addDetailsButton(p, () -> res, null);
			details.resizeImage(isize);

			p.button(HopeIcons.history, istyle, isize, history::show)
			 .get().addCaptureListener(new ElementGestureListener() {
				 public void fling(InputEvent event, float velocityX, float velocityY, KeyCode button) {
					 if (velocityY > 10) {
						 rollHistory(false);
					 }
					 if (velocityY < -10) {
						 rollHistory(true);
					 }
				 }
			 });
			p.button(HopeIcons.favorites, istyle, isize, bookmark::show);
			// p.button("@startup", bookmark::show);

			IntUI.addCheck(p.button(HopeIcons.interrupt, new ImageButtonStyle(istyle), isize,
				() -> stopIfOvertime = !stopIfOvertime),
			 () -> stopIfOvertime, "@tester.stopIfOvertime", "@tester.neverStop");
			IntUI.addCheck(p.button(Icon.waves, new ImageButtonStyle(istyle), isize,
				() -> multiThread = !multiThread),
			 () -> multiThread, "@tester.multiThread", "@tester.mainThread");
		};
		folder.fireCheck(false);
	}

	private void showDetails() {
		if (res instanceof Class) INFO_DIALOG.showInfo((Class<?>) res);
		else INFO_DIALOG.showInfo(res);
	}

	public HKeyCode detailKeyCode = keyCodeData().keyCode("detail", () -> new HKeyCode(KeyCode.d).ctrl().shift());
	public HKeyCode viewKeyCode   = keyCodeData().keyCode("view", () -> new HKeyCode(KeyCode.v).alt());
	public boolean detailsListener(KeyCode keycode) {
		if (detailKeyCode.isPress()) {
			showDetails();
			return true;
		}
		if (viewKeyCode.isPress()) {
			SR.apply(() -> SR.of(res)
			 .isInstance(Element.class, true, INFO_DIALOG::dialog)
			 .isInstance(String.class, true, INFO_DIALOG::dialog)
			 .isInstance(TextureRegion.class, true, INFO_DIALOG::dialog)
			 .isInstance(Texture.class, true, INFO_DIALOG::dialog)
			 .isInstance(Drawable.class, true, INFO_DIALOG::dialog)
			 .isInstance(Color.class, true, INFO_DIALOG::dialog)
			);

			return true;
		}
		return false;
	}
	private void star() {
		IntUI.fileNameWindow().show(res -> {
			Fi fi = bookmark.file.child(res);
			//noinspection SequencedCollectionMethodCanBeUsed
			bookmark.list.add(0, fi);
			fi.writeString(getMessage());
			bookmark.build();
		}, "", bookmark.file);
	}

	private boolean rollAndExec(KeyCode keycode) {
		if (Core.input.ctrl() && Core.input.shift()) {
			if (keycode == KeyCode.enter) {
				compileAndExec(IntVars.EMPTY_RUN);
				return true;
			}
			if (Core.input.alt() && keycode == KeyCode.up && rollHistory(true)) return true;
			if (Core.input.alt() && keycode == KeyCode.down && rollHistory(false)) return true;

			return false;
		}
		return false;
	}

	// roll history：回滚历史
	private static final Vec2 tmpV = new Vec2();
	private boolean rollHistory(boolean forward) {
		if (max_history_size.getInt() == 0) return false;
		if (historyPos == -1) originalText = area.getText();
		historyPos += Mathf.sign(forward);
		Vec2 pos            = tmpV.set(ui.x, ui.y + 20);
		int  maxHistorySize = max_history_size.getInt();
		if (historyPos == -1 || (rollback_history.enabled() && historyPos >= maxHistorySize)) {
			if (historyPos != -1) showRollback(pos);
			historyPos = -1;
			area.setText(originalText);
			log = "";
			IntUI.showInfoFade("[accent]0[]/[lightgray]" + maxHistorySize, pos, FADE_ALIGN);
			return true;
		}
		if (rollback_history.enabled()) {
			historyPos = Math.max(historyPos, -1);
			int last = historyPos;
			historyPos = (historyPos + maxHistorySize) % maxHistorySize;
			if (last != historyPos) {
				showRollback(pos);
			}
		} else if (historyPos < -1 || historyPos >= maxHistorySize) {
			historyPos = forward ? history.list.size() - 1 : -1;
			return false;
		}
		IntUI.showInfoFade(STR."\{historyPos + 1}/[lightgray]\{maxHistorySize}", pos, FADE_ALIGN);
		Fi dir = history.list.get(historyPos);
		area.setText(readFiOrEmpty(dir.child("message.txt")));
		log = readFiOrEmpty(dir.child("log.txt"));
		return true;
	}
	private static void showRollback(Vec2 pos) {
		IntUI.showInfoFade("@tester.rollback", pos.add(0, 80), FADE_ALIGN);
	}


	public void build() {
		if (ui == null) {
			_load();
			ui.show();
			return;
		}

		if (multi_windows.enabled()) {
			var newTester = new Tester();
			newTester.load();
			newTester.build();
		} else ui.show();
	}
	void setup() {
		ui.cont.pane(this::build).grow().update(pane -> {
			this.pane = pane;
			pane.setScrollingDisabled(true, true);
			pane.setOverscroll(false, false);
		});
	}

	public boolean finished = true;
	public void compileAndExec(Runnable callback) {
		setContextToThread();

		logs.clear();
		compileScript();
		execScript();

		historyPos = -1;
		originalText = area.getText();
		if (max_history_size.getInt() <= 0) {
			lastDir = null;
			return;
		}
		// 保存历史记录
		lastDir = history.file.child(String.valueOf(Time.millis()));
		lastDir.child("message.txt").writeString(getMessage());
		//noinspection SequencedCollectionMethodCanBeUsed
		history.list.add(0, lastDir);
		if (history.isShown()) {
			history.build();
		}
		int max = history.list.size() - 1;
		/* 判断是否大于边际（maxHistorySize），大于就删除 */
		for (int i = max, maxHistorySize = max_history_size.getInt(); i >= maxHistorySize; i--) {
			history.list.get(i).deleteDirectory();
			history.list.remove(i);
		}
		callback.run();
	}
	public void compileScript() {
		error = false;
		try {
			boolean def = true;
			if (def != strict) Reflect.set(Context.class, cx, "isTopLevelStrict", strict);
			cx.setGenerateObserverCount(true);
			script = cx.compileString(getMessage(), "console.js", 1);
			cx.setGenerateObserverCount(false);
			if (def != strict) Reflect.set(Context.class, cx, "isTopLevelStrict", def);
		} catch (Throwable ex) {
			makeError(ex, false);
		}
	}

	public void makeError(Throwable ex, boolean fromExecutor) {
		error = true;
		loop = false;
		if (output_to_log.enabled()) Log.err(name, ex);
		if (!ignore_popup_error.enabled())
			IntUI.showException(Core.bundle.get("error_in_execution"), ex);
		log = fromExecutor && ex instanceof RhinoException
		 ? STR."\{ex.getMessage()}\n\{((RhinoException) ex).getScriptStackTrace()}"
		 : Strings.neatError(ex);
	}

	public boolean killScript;
	public long    startTime;
	// 执行脚本
	public void execScript() {
		if (!finished) return;
		finished = false;
		if (executor.get().getActiveCount() > 0) {
			return;
		}
		killScript = false;
		if (error) {
			finished = true;
			return;
		}
		if (multiThread) {
			executor.get().submit(this::execAndDealRes);
		} else {
			startTime = Time.millis();
			Core.app.post(this::execAndDealRes);
		}
		if (!killTask.isScheduled()) {
			Timer.schedule(killTask, 4, 0.1f, -1);
		}
	}

	AddedSeq<String> logs = new AddedSeq<>();
	public final LogHandler logHandler = new MyLogHandler();
	private void execAndDealRes() {
		try {
			setContextToThread();
			Object o = capture_logger.enabled() ?
			 setLogger(logHandler, () -> script.exec(cx, scope))
			 : script.exec(cx, scope);
			o = CAST.unwrap(o);
			if (o instanceof NativeArray na) o = na.toArray();
			if (finished) return;
			res = o;

			log = String.valueOf(o);
			if (log == null) log = "null";
			if (output_to_log.enabled()) {
				Log.info("[[TESTER]: " + log);
			}

			if (lastDir != null) lastDir.child("log.txt").writeString(log);
			lastDir = null;
		} catch (Throwable e) {
			Core.app.post(() -> handleError(e));
		} finally {
			finished = true;
		}
	}
	private static void setContextToThread() {
		if (Context.getCurrentContext() != cx)
			VMBridge.setContext(VMBridge.getThreadContextHelper(), cx);
	}
	public void handleError(Throwable ex) {
		makeError(ex, true);
		finished = true;
	}

	public void _load() {
		ui = new Window(localizedName(), 0, 100, true);
		// JSFunc.watch("times", () -> ui.times);
		/*ui.update(() -> {
			ui.setZIndex(frag.getZIndex() - 1);
		});*/
		history = new ListDialog("history", IntVars.dataDirectory.child("historical record"),
		 f -> f.child("message.txt"), f -> {
			area.setText(readFiOrEmpty(f.child("message.txt")));
			log = readFiOrEmpty(f.child("log.txt"));
		}, (f, p) -> {
			p.add(new MyLabel(readFiOrEmpty(f.child("message.txt")), HopeStyles.defaultLabel)).row();
			p.image().color(Tmp.c1.set(JColor.c_underline)).growX().padTop(6f).padBottom(6f).row();
			p.add(new MyLabel(readFiOrEmpty(f.child("log.txt")), HopeStyles.defaultLabel)).row();
		}, Tester::sort);
		history.hide();
		bookmark = new ListDialog("bookmark", bookmarkDirectory,
		 f -> f, f -> area.setText(readFiOrEmpty(f)),
		 Tester::buildBookmark, Tester::sort);
		bookmark.hide();

		setup();

		killTask = new Task() {
			public void run() {
				if ((!multiThread || executor.get().getActiveCount() > 0) && stopIfOvertime) {
					killScript = true;
					cancel();
				}
			}
		};

		TASKS.add(() -> {
			if (loop && script != null) {
				execScript();
			}
		});
	}
	private static void buildBookmark(Fi f, Table p) {
		var classes = new Seq<>()
		 .add(EventType.class.getClasses())
		 .add(Trigger.values())
		 .addAll(Content.all);
		classes.remove(Trigger.class);

		p.left().defaults().left();
		p.table(Tex.pane, t -> {
			t.left().defaults().left();
			t.add(IntUI.tips("execution.js.added")).row();
			t.add(IntUI.tips("execution.js.var")).row();
			SettingsBuilder.build(t);
			{// block
				boolean[] enabled = {EXEC_DATA.containsKey(f.name())};
				final Data JS = enabled[0] ? EXEC_DATA.child(f.name()) :
				 new Data(EXEC_DATA, new JsonMap());
				SettingsBuilder.check("Add to executor", c -> {
					enabled[0] = c;
					if (enabled[0]) {
						EXEC_DATA.put(f.name(), JS);
					} else {
						EXEC_DATA.remove(f.name());
					}
				}, () -> EXEC_DATA.containsKey(f.name()));

				SettingsBuilder.number("@task.intervalseconds",
				 JS, "intervalSeconds", 0.1f
				 , () -> enabled[0], 0.01f, Float.MAX_VALUE);

				SettingsBuilder.check("@task.trigger", JS, "disposable", () -> enabled[0]);
				SettingsBuilder.numberi("@task.repeatcount",
				 JS, "repeatCount", 0,
				 () -> enabled[0] && !JS.getBool("disposable"),
				 -1, Integer.MAX_VALUE);

				Func<Object, String> stringify =
				 val -> val instanceof Class<?> cl ? cl.getSimpleName() : String.valueOf(val);
				Func<Object, String> valuify = val -> val instanceof Class<?> cl ? cl.getSimpleName()
				 : val instanceof Content ? STR."Vars.mods.mainLoader().loadClass('\{clName(val)}')"
				 : String.valueOf(val);
				SettingsBuilder.list("Event", val -> JS.put("type", valuify.get(val)),
				 () -> JS.get("type"), classes,
				 stringify, () -> JS.getBool("disposable"));
			}
			SettingsBuilder.clearBuild();
		}).row();
		p.add(new MyLabel(readFiOrEmpty(f), HopeStyles.defaultLabel)).row();
	}


	public void load() {
		if (loaded) return;
		loaded = true;
		initScript();
		loadSettings();
		try {
			Class.forName("modtools.override.ForRhino");
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}

		Core.app.post(this::addJSInternalProperty);
	}
	void addJSInternalProperty() {
		setContextToThread();
		try {
			Object obj1 = new JSFuncClass(scope);
			ScriptableObject.putProperty(scope, "IntFunc", obj1);
			ScriptableObject.putProperty(scope, "$", obj1);
			ScriptableObject.putProperty(scope, "unsafe", unsafe);
			ScriptableObject.putProperty(topScope, "modName", "<?>");
			ScriptableObject.putProperty(topScope, "scriptName", "console.js");

			NativeJavaPackage pkg = (NativeJavaPackage) ScriptableObject.getProperty(topScope, "Packages");
			ScriptableObject.putProperty(scope, "$p", pkg);
			ClassLoader loader = Vars.mods.mainLoader();
			Reflect.set(NativeJavaPackage.class, pkg, "classLoader", loader);
			if (cx.getFactory() != ForRhino.factory) {
				Reflect.set(Context.class, cx, "factory", ForRhino.factory);
			}
			setAppClassLoader(loader);
		} catch (Throwable th) {
			if (ignore_popup_error.enabled()) {
				Log.err(th);
			} else {
				Vars.ui.showException("IntFunc出错", th);
			}
		}

		// 启动脚本
		Fi dir = startupDir;
		if (dir.exists() && dir.isDirectory()) {
			Log.info("Loading startup directory.");
			ExecuteTree.context(startupTask(), () -> dir.walk(f -> {
				if (!f.extEquals("js")) return;
				ExecuteTree.node(f.name(),
					() -> cx.evaluateString(scope, readFiOrEmpty(f), f.name(), 1))
				 .code(readFiOrEmpty(f))
				 .apply();
			}));
		} else {
			Log.info("Creating startup directory.");
			dir.delete();
			dir.child("README.txt").writeString("这是一个用于启动脚本（js）的文件夹\n\n所有的js文件都会执行");
		}
	}

	private static void setAppClassLoader(ClassLoader loader) {
		try {
			ForRhino.factory.getApplicationClassLoader().loadClass(ModTools.class.getName());
		} catch (Throwable __) {
			loader = OS.isAndroid ? AndroidLoader.loader(loader) : loader;
			try {
				ForRhino.factory.initApplicationClassLoader(loader);
			} catch (Throwable e) {
				Reflect.set(ContextFactory.class, ForRhino.factory
				 , "applicationClassLoader", loader);
			}
		}
		cx.setApplicationClassLoader(loader);
	}
	static class AndroidLoader {
		static ClassLoader loader(ClassLoader loader) {
			return ((AndroidContextFactory) ForRhino.factory).createClassLoader(loader);
		}
	}

	public static boolean loaded = false;
	public void loadSettings(Data settings) {
		Table table = new Table();
		table.defaults().growX();
		dataInit();
		ISettings.buildAll("tester", table, Settings.class);

		Contents.settings_ui.add(localizedName(), icon, table);
	}

	public void dataInit() { }
	public String getMessage() {
		return area.getText();
	}

	public static Object wrap(Object val) {
		try {
			if (val instanceof Class)
				return cx.getWrapFactory().wrapJavaClass(cx, topScope, (Class<?>) val);
			if (val instanceof Method method)
				return new NativeJavaMethod(method, method.getName());
			if (val instanceof MethodHandle handle)
				return new NativeJavaHandle(scope, handle);

			return Context.javaToJS(val, topScope);
		} catch (Throwable e) {
			return val;
		}
	}

	public static void quietPut(String name, Object val) {
		if (wrap_ref.enabled()) {
			val = wrap(val);
			//			else if (val instanceof Field) val = new NativeJavaObject(scope, val, Field.class);
		}
		ScriptableObject.putProperty(topScope, name, val);
	}
	public static final String prefix = "tmp";
	public static String quietPut(Object val) {
		int i = 0;
		// 从0开始直到找到没有被定义的变量
		while (ScriptableObject.hasProperty(topScope, prefix + i)) i++;
		String key = prefix + i;
		quietPut(key, val);
		return key;
	}
	public static void put(Element element, Object val) {
		put(ElementUtils.getAbsolutePos(element), val);
	}
	/** put之后，会弹窗提示 */
	public static void put(Vec2 vec2, Object val) {
		IntUI.showInfoFade(Core.bundle.format("jsfunc.saved", quietPut(val)), vec2);
	}

	public enum Settings implements ISettings {
		ignore_popup_error, catch_outsize_error, wrap_ref,
		rollback_history, multi_windows, output_to_log, js_prop,
		capture_logger,
		auto_complement,
		/** @see ISettings#$(Integer) */
		max_history_size(int.class, 40/* def */, 0/* min */, 100/* max */);

		Settings() { }
		Settings(Class<?> a, int... args) { }
		static {
			wrap_ref.defTrue();
			capture_logger.defTrue();
		}
	}

	public class ComplementListener extends InputListener {
		/** @see TextField#BACKSPACE */
		static final char BACKSPACE = 8;
		/** @see TextField#DELETE */
		static final char DELETE    = 127;

		public int lastCompletionCursor = -1;
		public int lastCompletionIndex  = 0;
		JSSyntax syntax = (JSSyntax) textarea.syntax;
		boolean  cancel;
		// CodeTooltip codeTooltip = new CodeTooltip();
		public boolean keyDown(InputEvent event, KeyCode keycode) {
			cancel = false;
			if (syntax.virtualString != null) {
				if (!HKeyCode.isFnKey(keycode)) {
					keyListener(keycode);
				}
			}
			check(event);
			return true;
		}
		private void keyListener(KeyCode keycode) {
			switch (keycode) {
				case tab -> {
					cancel = true;
					area.selectBackward();
					area.paste(syntax.virtualString.text, true);
					syntax.virtualString = null;
				}
				case enter -> {
					cancel = true;
					area.paste(syntax.virtualString.text, true);
					syntax.virtualString = null;
				}
				case up -> {
					cancel = true;
					if (lastCompletionCursor == area.getCursorPosition()) {
						lastCompletionIndex = (lastCompletionIndex - 1 + complements.size) % complements.size;
						syntax.virtualString.text = complements.get(lastCompletionIndex);
					}
				}
				case down -> {
					cancel = true;
					if (lastCompletionCursor == area.getCursorPosition()) {
						lastCompletionIndex = (lastCompletionIndex + 1) % complements.size;
						syntax.virtualString.text = complements.get(lastCompletionIndex);
					}
				}
				default -> syntax.virtualString = null;
			}
		}
		private void complement() {
			int lastCursor = area.getCursorPosition();
			try {
				complement0();
			} catch (Throwable err) {
				Log.err(err);
				return;
			}
			area.setCursorPosition(lastCursor);
		}

		private final Seq<String> complements = new Seq<>();
		private final Seq<Object> keys        = new Seq<>();
		private void complement0() {
			area.selectForward();
			String searchingKey = area.getSelection();
			int    start        = area.getSelectionStart();
			area.clearSelection();
			lastCompletionCursor = area.getCursorPosition();

			while (area.checkIndex(start - 1) && Character.isWhitespace(area.charAtUncheck(start - 1))) start--;

			Scriptable obj;
			if (area.checkIndex(start - 1) && area.charAtUncheck(start - 1) == '.') {
				obj = syntax.cursorObj;
			} else {
				obj = scope;
			}

			if (obj == null || obj == Undefined.SCRIPTABLE_UNDEFINED) return;

			keys.clear().addAll(obj instanceof ScriptableObject so ? so.getAllIds() : obj.getIds());
			if (obj == scope) keys.addAll(JSSyntax.varSet.toSeq().list());
			if (obj instanceof NativeJavaClass) keys.add("__javaObject__");

			complements.clear();
			keys.each(o -> {
				String key = String.valueOf(o);
				if (key.startsWith(searchingKey)
				    && searchingKey.length() < key.length()) {
					complements.add(key.substring(searchingKey.length()));
				}
			});
			if (complements.isEmpty()) return;

			if (syntax.virtualString == null) syntax.virtualString = new VirtualString();
			syntax.virtualString.index = area.getCursorPosition();
			syntax.virtualString.text = complements.get(lastCompletionIndex++ % complements.size);
		}

		public boolean keyTyped(InputEvent event, char character) {
			check(event);
			if (!event.stopped && character != DELETE && character != BACKSPACE &&
			    (area.isWordCharacter(character) || character == '.') &&
			    (syntax.cursorTask == null || syntax.cursorTask instanceof DrawToken) &&
			    auto_complement.enabled()) {
				if (syntax.virtualString != null) event.stop();
				Core.app.post(this::complement);
			}
			return false;
		}
		public boolean keyUp(InputEvent event, KeyCode keycode) {
			check(event);
			return super.keyUp(event, keycode);
		}
		private void check(InputEvent event) {
			if (cancel) event.cancel();
		}
	}

	private class MyLogHandler extends DefaultLogHandler {
		public void log(LogLevel level, String text) {
			if (level == LogLevel.err) super.log(level, text);
			String s = switch (level) {
				case debug -> "D";
				case info -> "I";
				case warn -> "W";
				case err -> "E";
				default -> " ";
			} + text;
			logs.add(FormatHelper.format(s));
		}
	}
}