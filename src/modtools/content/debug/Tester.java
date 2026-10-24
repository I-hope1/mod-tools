
package modtools.content.debug;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
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
import modtools.Constants.RHINO;
import modtools.*;
import modtools.annotations.asm.CopyConstValue;
import modtools.annotations.settings.*;
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
import modtools.ui.comp.completion.Completion;
import modtools.ui.comp.completion.CompletionPopup;
import modtools.ui.comp.input.*;
import modtools.ui.comp.input.area.TextAreaTab;
import modtools.ui.comp.input.area.TextAreaTab.MyTextArea;
import modtools.ui.comp.input.highlight.JSSyntax;
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
import modtools.utils.search.BindCell;
import modtools.utils.ui.FormatHelper;
import rhino.*;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.concurrent.ThreadPoolExecutor;

import static ihope_lib.MyReflect.unsafe;
import static modtools.content.debug.Tester.Settings.*;
import static modtools.ui.IntUI.topGroup;
import static modtools.utils.Tools.*;

public class Tester extends Content {
	private static final int FADE_ALIGN = Align.bottomLeft;

	public static final float WIDTH = 420;

	public static Scripts    scripts;
	public static Scriptable topScope, customScope;
	private static Context cx;

	LazyValue<ThreadPoolExecutor> executor = LazyValue.of(() -> (ThreadPoolExecutor) AThreads.impl.boundedExecutor(Tester.class.getSimpleName(), 1));

	public static final Data EXEC_DATA         = MySettings.SETTINGS.child("execution_js");
	public static final Fi   bookmarkDirectory = IntVars.dataDirectory.child("bookmarks");
	public static final Fi   startupDir        = IntVars.dataDirectory.child("startup");

	private static TaskNode                startupTask;
	public         ClearValueLabel<Object> logLabel;
	public         BindCell                logLabelCell;
	public         LazyValue<Label>        errorLabel = LazyValue.of(() -> {
		NoMarkupLabel label = new NoMarkupLabel("", HopeStyles.defaultLabel);
		label.setAlignment(Align.topLeft);
		label.setWrap(true);
		return label;
	});
	static TaskNode startupTask() {
		if (startupTask == null) {
			startupTask = ExecuteTree.nodeRoot(null, "JS startup", "startup",
			 Icon.craftingSmall, IntVars.EMPTY_RUN);
		}
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
					"""
					 Events.on({%type%}, $e$ => {\
					  try {\
					   {%code%};
					  } catch(e) { Log.err(e); }});
					 """.replace("%type%", "" + map.get("type"))
					 .replace("%code%", readFiOrEmpty(bookmarkDirectory.child(entry.key)))
					: readFiOrEmpty(bookmarkDirectory.child(entry.key));
				String source = "(() => { modName=`" + taskName + "`; scriptName=`" + entry.key + "` " + mainCode + "})();";
				ExecuteTree.node(() -> {
					 cx.evaluateString(customScope, source, "<" + taskName + ">" + entry.key, 1);
				 }, taskName, entry.key, Icon.none, IntVars.EMPTY_RUN)
				 .intervalSeconds(map.getFloat("intervalSeconds", 0.1f))
				 .repeatCount(map.getBool("disposable") ? 0 : map.getInt("repeatCount", 0))
				 .code(source)
				 .apply();
			}
		});
	}
	/** 可以自定义修改 */
	public static Scriptable userScope;
	private static void initScript() {
		if (scripts != null) return;
		scripts = Vars.mods.getScripts();
		topScope = scripts.scope;
		customScope = new ScriptableObject(topScope, topScope) {
			public void put(String name, Scriptable start, Object value) {
				if ("$$scope".equals(name) && value != customScope) {
					userScope = value == null ? topScope : (Scriptable) value;
					Scriptable scope = userScope.getParentScope();
					while (scope != topScope && scope != null) {
						scope = scope.getParentScope();
						if (scope.getParentScope() == this) {
							scope.setParentScope(topScope);
						}
					}
					setParentScope(userScope);
					// Log.info(userScope);
					return;
				}
				super.put(name, start, value);
			}
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
	public Object  res  = ValueLabel.unset;

	private boolean
	 strict = false,
	 error  = false;


	public Window ui;
	ListDialog history, bookmark;

	public Script  script = null;
	public boolean multiThread;
	public boolean stopIfOvertime;
	Task killTask;

	private CompletionPopup completionPopup;

	public Tester() {
		super("tester", Icon.terminalSmall);
	}

	/** 按修改时间倒序 */
	static int sort(Fi f1, Fi f2) {
		return Long.compare(f2.lastModified(), f1.lastModified());
	}


	/**
	 * 用于回滚历史<br>
	 * -1表示originalText<br>
	 * -2表示倒数第一个
	 */
	public int    historyIndex = -1;
	/** 位于0处的文本 */
	public String originalText = null;

	public ScrollPane  pane;
	public SclListener barListener;
	public void build(Table table) {
		if (ui == null) _load();

		textarea = new TextAreaTab("", d -> new JSSyntax(d, customScope)) {
			public int cursor() {
				return completionPopup.isShown() ? completionPopup.cursorBeforeDot : super.cursor();
			}
		};
		area = textarea.getArea();

		if (area != null && ui != null) { // Added ui null check
			if (completionPopup != null) completionPopup.remove(); // 重建时丢掉旧弹窗，它绑的是旧编辑区
			completionPopup = new CompletionPopup(area);
			topGroup.addChild(completionPopup); // Add it to the Tester's window
		} else {
			Log.warn("Tester: 'area' or 'ui' is null during completionPopup initialization. Completion will not work.");
		}

		Table _cont = new Table();
		if (!Vars.mobile) textarea.addListener(new EscapeAndAxesClearListener(area));

		Runnable areaInvalidate = () -> {
			Element focused = Core.scene.getKeyboardFocus();
			// Log.info("Before layout: " + focused);
			textarea.getArea().invalidateHierarchy();
			textarea.layout();
			// Log.info("After layout: " + Core.scene.getKeyboardFocus());
			if (focused != null) Core.scene.setKeyboardFocus(focused); // 恢复焦点
		};
		ui.maximized(_ -> Time.runTask(0, areaInvalidate));
		ui.sclListener.listener = areaInvalidate;

		addListenerToArea(textarea);

		_cont.add(textarea).grow();
		_cont.row();
		ui.cont.update(() -> ((JSSyntax) textarea.syntax).js_prop = js_prop.enabled());

		Table center = _cont.table(t -> {
			 t.defaults().padRight(4f).size(42);
			 // t.addListener(new KeepFocusListener(area));

			 ImageButton move = t.button(Icon.move, HopeStyles.hope_flati, IntVars.EMPTY_RUN).get();
			 move.clicked(() -> {
				 IntUI.showSelectTable(move, (p, hide, _) -> {
					 p.defaults().size(42);
					 // 9*9
					 p.add(); // 占位
					 p.button(Icon.upOpenSmall, HopeStyles.clearNonei, area::fireUp);
					 p.add();

					 p.row();

					 p.button(Icon.leftOpenSmall, HopeStyles.clearNonei, area::fireLeft);
					 p.add();
					 p.button(Icon.rightOpenSmall, HopeStyles.clearNonei, area::fireRight);

					 p.row();

					 p.add();
					 p.button(Icon.downOpenSmall, HopeStyles.clearNonei, area::fireDown);
					 p.add();
				 }, false, Align.center);
			 });
			 t.button("@ok", HopeStyles.flatBordert, () -> {
				 error = false;
				 // area.setText(getMessage().replaceAll("\\r", "\\n"));
				 compileAndExec();
			 }).width(64).disabled(_ -> !finished);

			 t.button(Icon.copySmall, HopeStyles.clearNonei, area::copy);
			 t.button(Icon.pasteSmall, HopeStyles.clearNonei, () ->
				area.paste(Core.app.getClipboardText(), true)
			 );
			 t.button(Icon.eyeSmall, HopeStyles.clearNonei, () -> {
				 compileScript();
				 Script script = this.script;
				 setContextToThread();
				 JSFunc.watch().watch(textarea.getText(), () -> {
					 return CAST.unwrap(script.exec(cx, customScope));
				 });
			 });
		 }).growX().minWidth(WIDTH)
		 .touchable(Touchable.enabled).get();
		_cont.row();
		Cell<?> infoCell = _cont.table(Tex.sliderBack, t -> {
			t.pane(p -> {
				p.left().top();
				buildLog(p);
				p.image(Icon.leftOpenSmall).color(Color.gray).size(24).top().left();
				logLabel = new ClearValueLabel<>(Object.class, () -> res, () -> logLabel.setVal(ValueLabel.unset));
				logLabelCell = BindCell.ofConst(p.add(logLabel)
				 .wrap().style(HopeStyles.defaultLabel)
				 .labelAlign(Align.left).growX());
			}).grow().with(p -> p.setScrollingDisabled(true, false));
		}).growX();

		ScrollPane pane = new ScrollPane(_cont);
		pane.setScrollingDisabled(true, true);
		int areaSpace = 20;
		Floatc invalidate = height -> {
			float maxHeight = _cont.getHeight() - _cont.getChildren().sumf(
			 elem -> elem == textarea ? areaSpace : elem == infoCell.get() ? 0 : elem.getHeight()
			);
			infoCell.height(Mathf.clamp(height, 32, maxHeight) / Scl.scl());
			_cont.invalidate();
		};
		pane.update(() -> {
			if (pane.needsLayout()) {
				invalidate.get(infoCell.get().getHeight());
			}
		});
		barListener = new SclListener(center, 0, 0) {
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				return super.touchDown(event, x, y, pointer, button);
			}
			public boolean valid(float x, float y) {
				if (overElement != center) return false;
				top = Math.abs(y - bind.getHeight()) < offset;
				return top && !isDisabled();
			}
			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				super.touchDragged(event, x, y, pointer);
				float delta = bind.getHeight() - bind.getPrefHeight();
				defY = bind.y;
				bind.pack();
				invalidate.get(infoCell.get().getHeight() + delta);
			}
		};
		barListener.offset = center.getPrefHeight();
		Time.runTask(1, () -> {
			InputEvent event = EventHelper.obtainEvent(InputEventType.touchDown, 0, 0, 0, KeyCode.mouseLeft);
			if (barListener.touchDown(event, 0, 0, 0, KeyCode.mouseLeft)) {
				barListener.touchUp(event, 0, 0, 0, KeyCode.mouseLeft);
			}
			Pools.free(event);
		});

		infoCell.height(64f).padLeft(8f);

		table.add(pane).grow();
		table.row();

		bottomBar(table, textarea);
	}
	boolean stopEvent = false;
	private void addListenerToArea(TextAreaTab textarea) {
		ComplementListener complementListener = new ComplementListener();
		textarea.keyDownBlock = (_, keycode) -> {
			stopEvent = false;
			// 手动补全要排在别的快捷键前面：Ctrl+Space 的 space 不能落进文本
			if (completeKeyCode.isPressThen(() -> complementListener.complement(true))
			    || rollAndExec() || detailsListener(keycode)) {
				stopEvent = true;
				return true;
			}
			return false;
		};
		textarea.keyTypedBlock = (_, _) -> stopEvent;
		textarea.keyUpBlock = (_, _) -> stopEvent;
		area.addCaptureListener(complementListener);
	}
	static boolean hasFunctionKey() {
		return Core.input.shift() || Core.input.ctrl() || Core.input.alt();
	}

	private void buildLog(Table p) {
		p.table(lg -> lg.left().update(() -> {
			if (logs.isResolved()) return;
			lg.clearChildren();
			logs.each(item -> {
				lg.add("[" + item.charAt(0) + "]")
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
				textarea.enableHighlight = !textarea.enableHighlight;
			}), () -> textarea.enableHighlight, "@tester.highlighting", "@tester.nothighlighting");

			ImageButton details = IntUI.addDetailsButton(p, () -> res, null);
			details.resizeImage(isize);

			p.button(HopeIcons.history, istyle, isize, history::show)
			 .with(IntUI.makeTipListener("tester.rollHistory"))
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
		folder.fireCheck(true);
	}

	private void showDetails() {
		if (res instanceof Class) { INFO_DIALOG.showInfo((Class<?>) res); } else INFO_DIALOG.showInfo(res);
	}

	public HKeyCode detailKeyCode = keyCodeData().dynamicKeyCode("detail", () -> new HKeyCode(KeyCode.d).ctrl().shift());
	public HKeyCode viewKeyCode   = keyCodeData().dynamicKeyCode("view", () -> new HKeyCode(KeyCode.v).alt());
	/** 手动唤出补全。自动补全关掉时，这是唯一入口 */
	public HKeyCode completeKeyCode = keyCodeData().dynamicKeyCode("complete", () -> new HKeyCode(KeyCode.space).ctrl());
	public boolean detailsListener(KeyCode keycode) {
		if (detailKeyCode.isPress()) {
			showDetails();
			return true;
		}
		if (viewKeyCode.isPress()) {
			switch (res) {
				case Element o -> INFO_DIALOG.dialog(o, true);
				case String o -> INFO_DIALOG.dialogText(o, true);
				case TextureRegion o -> INFO_DIALOG.dialog(o, true);
				case Texture o -> INFO_DIALOG.dialog(o, true);
				case Drawable o -> INFO_DIALOG.dialog(o, true);
				case Color o -> INFO_DIALOG.dialog(o, true);
				default -> { }
			}
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

	public HKeyCode exec_keyCode          = keyCodeData().dynamicKeyCode("exec", () -> new HKeyCode(KeyCode.enter).ctrl().shift());
	public HKeyCode rollback_history_up   = keyCodeData().dynamicKeyCode("rollback_history_up", () -> new HKeyCode(KeyCode.up).ctrl().shift().alt());
	public HKeyCode rollback_history_down = keyCodeData().dynamicKeyCode("rollback_history_down", () -> new HKeyCode(KeyCode.down).ctrl().shift().alt());

	/** true 就停止事件 */
	private boolean rollAndExec() {
		return exec_keyCode.isPressThen(this::compileAndExec)
		       || (rollback_history_up.isPress() && rollHistory(true))
		       || (rollback_history_down.isPress() && rollHistory(false));
	}

	// roll history：回滚历史
	private static final Vec2 tmpV = new Vec2();
	private boolean rollHistory(boolean forward) {
		if (max_history_size.getInt() == 0) return false;
		if (historyIndex == -1) originalText = area.getText();
		historyIndex += Mathf.sign(forward);
		Vec2 pos         = tmpV.set(ui.x, ui.y + 20);
		int  historySize = Math.min(history.list.size(), max_history_size.getInt());
		if (historyIndex == -1 || (rollback_history.enabled() && historyIndex >= historySize)) {
			if (historyIndex != -1) showRollback(pos);
			historyIndex = -1;
			area.setText(originalText);
			log = "";
			IntUI.showInfoFade("[accent]0[]/[lightgray]" + historySize, pos, FADE_ALIGN);
			return true;
		}
		if (rollback_history.enabled()) {
			historyIndex = Math.max(historyIndex, -1);
			int last = historyIndex;
			historyIndex = (historyIndex + historySize) % historySize;
			if (last != historyIndex) {
				showRollback(pos);
			}
		} else if (historyIndex < -1 || historyIndex >= historySize) {
			historyIndex = forward ? history.list.size() - 1 : -1;
			return false;
		}
		IntUI.showInfoFade(historyIndex + 1 + "/[lightgray]" + historySize, pos, FADE_ALIGN);
		switchHistory(history.list.get(historyIndex));
		return true;
	}
	public void switchHistory(Fi dir) {
		area.setText(readFiOrEmpty(dir.child("message.txt")));
		log = readFiOrEmpty(dir.child("log.txt"));
	}
	private static void showRollback(Vec2 pos) {
		IntUI.showInfoFade("@tester.rollback", pos.add(0, 90), FADE_ALIGN);
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
		} else { ui.show(); }
	}
	void setup() {
		ui.cont.table(this::build).grow();
	}

	public boolean finished = true;

	public void compileAndExec() {
		compileAndExec(IntVars.EMPTY_RUN);
	}
	public void compileAndExec(Runnable callback) {
		setContextToThread();

		logs.clear();
		compileScript();
		execScript();

		historyIndex = -1;
		originalText = area.getText();
		if (max_history_size.getInt() <= 0) {
			lastDir = null;
			return;
		}
		// 保存历史记录
		lastDir = history.file.child(String.valueOf(Time.millis()));
		lastDir.child("message.txt").writeString(getMessage());

		history.addItem(lastDir);
		int max = history.list.size() - 1;
		/* 判断是否大于边际（maxHistorySize），大于就删除 */
		for (int i = max, maxHistorySize = max_history_size.getInt(); i >= maxHistorySize; i--) {
			history.removeItemAt(i);
		}
		callback.run();
	}
	final boolean USE_JSHELL = false;
	// final JShell  jShell     = USE_JSHELL ? JShell.builder().executionEngine("local").build() : null;
	public void compileScript() {
		/* if (USE_JSHELL) {
			List<SnippetEvent> eval = jShell.eval(getMessage());
			SnippetEvent       last = eval.getLast();
			JShellException    ex   = last.exception();
			if (ex != null) handleError(ex);
			res = last.value();
			finished = true;
			return;
		} */
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
		if (!ignore_popup_error.enabled()) { IntUI.showException(Core.bundle.get("error_in_execution"), ex); }
		log = fromExecutor && ex instanceof RhinoException
		 ? ex.getMessage() + "\n" + ((RhinoException) ex).getScriptStackTrace()
		 : Strings.neatError(ex);
		errorLabel.get().setText(log);
		logLabelCell.replace(errorLabel.get());
	}

	public boolean killScript;
	public long    startTime;
	// 执行脚本
	public void execScript() {
		if (USE_JSHELL) return;

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
			 setLogger(logHandler, () -> script.exec(cx, customScope))
			 : script.exec(cx, customScope);
			o = CAST.unwrap(o);
			if (o instanceof NativeArray na) o = na.toArray();
			if (finished) return;
			res = o;
			ScriptableObject.putProperty(customScope, "$0", res);

			Core.app.post(() -> {
				logLabelCell.replace(logLabel);
				// logLabel.clearMouseEvents();
				// logLabel.clearExpand();
				logLabel.flushVal();
				log = String.valueOf(logLabel.getText());
				if (output_to_log.enabled()) {
					Log.info("[[TESTER]: " + log);
				}

				if (lastDir != null) lastDir.child("log.txt").writeString(log);
				lastDir = null;
			});
		} catch (Throwable e) {
			Core.app.post(() -> handleError(e));
		} finally {
			finished = true;
		}
	}
	private static void setContextToThread() {
		if (Context.getCurrentContext() != cx) { VMBridge.setContext(VMBridge.getThreadContextHelper(), cx); }
	}
	public void handleError(Throwable ex) {
		makeError(ex, true);
		finished = true;
	}

	public void _load() {
		ui = new IconWindow(0, 100, true);
		// 补全弹窗挂在 topGroup 上，不会随窗口一起隐藏，得手动收
		ui.hidden(() -> { if (completionPopup != null) completionPopup.hide(); });
		// JSFunc.watch("times", () -> ui.times);

		/*ui.update(() -> {
			ui.setZIndex(frag.getZIndex() - 1);
		});*/
		history = new ListDialog("history", IntVars.dataDirectory.child("historical record"),
		 f -> f.child("message.txt"), this::switchHistory, (f, p) -> {
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

		killTask = TaskManager.newTaskc(task -> {
			if ((!multiThread || executor.get().getActiveCount() > 0) && stopIfOvertime) {
				killScript = true;
				task.cancel();
			}
		});

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
				 : val instanceof Content ? "Vars.mods.mainLoader().loadClass('" + clName(val) + "')"
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
		ForRhino.load();

		Core.app.post(this::addJSInternalProperty);
	}
	void addJSInternalProperty() {
		setContextToThread();
		try {
			Object obj1 = new JSFuncClass(customScope);
			ScriptableObject.putProperty(customScope, "IntFunc", obj1);
			ScriptableObject.putProperty(customScope, "$", obj1);
			ScriptableObject.putProperty(customScope, "unsafe", unsafe);
			ScriptableObject.putProperty(topScope, "modName", "<?>");
			ScriptableObject.putProperty(topScope, "scriptName", "console.js");

			NativeJavaPackage pkg = (NativeJavaPackage) ScriptableObject.getProperty(topScope, "Packages");
			ScriptableObject.putProperty(customScope, "$p", pkg);
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
					() -> cx.evaluateString(customScope, readFiOrEmpty(f), f.name(), 1))
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
		if (ForRhino.factory.getApplicationClassLoader() != null) {
			try {
				// 判断是否能加载到mod的类
				ForRhino.factory.getApplicationClassLoader().loadClass(ModTools.class.getName());
			} catch (Throwable _) {
				loader = OS.isAndroid ? AndroidLoader.loader(loader) : loader;
				try {
					ForRhino.factory.initApplicationClassLoader(loader);
				} catch (Throwable _) {
					Reflect.set(ContextFactory.class, ForRhino.factory
					 , "applicationClassLoader", loader);
				}
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
		Contents.settings_ui.addSection(localizedName(), icon, t -> {
			ISettings.buildAll("tester", t, Settings.class);
		});
	}

	public String getMessage() {
		return area.getText();
	}

	public static Object wrap(Object val) {
		try {
			return switch (val) {
				case Class<?> aClass -> cx.getWrapFactory().wrapJavaClass(cx, topScope, aClass);
				case Method method -> new NativeJavaMethod(method, method.getName());
				case MethodHandle handle -> new NativeJavaHandle(customScope, handle);
				case null, default -> Context.javaToJS(val, topScope);
			};
		} catch (Throwable e) {
			return val;
		}
	}

	public static void quietPut0(String name, Object val) {
		if (wrap_ref.enabled()) {
			val = wrap(val);
			//			else if (val instanceof Field) val = new NativeJavaObject(scope, val, Field.class);
		}
		ScriptableObject.putProperty(topScope, name, val);
		JSSyntax.varSet.add(name);
	}
	public static final String prefix = "tmp";
	public static String quietPut(Object val) {
		int i = 0;
		// 从0开始直到找到没有被定义的变量
		while (ScriptableObject.hasProperty(topScope, prefix + i)) i++;
		String key = prefix + i;
		quietPut0(key, val);
		return key;
	}
	public static void put(Element element, Object val) {
		put(ElementUtils.getAbsolutePos(element), val);
	}
	/** put之后，会弹窗提示 */
	public static void put(Vec2 vec2, Object val) {
		IntUI.showInfoFade(Core.bundle.format("jsfunc.saved", quietPut(val)), vec2);
	}

	@SettingsInit
	public enum Settings implements ISettings {
		ignore_popup_error, catch_outsize_error, wrap_ref,
		rollback_history, multi_windows, output_to_log,
		capture_logger,
		js_prop,
		@Switch(dependency = "js_prop")
		auto_complement,
		/** @see ISettings#$(int, int, int, int) */
		max_history_size(int.class, it -> it.$(40/* def */, 0/* min */, 100/* max */));

		Settings() { }
		Settings(Class<?> type, Cons<ISettings> builder) { }
		static {
			wrap_ref.defTrue();
			capture_logger.defTrue();
		}
	}

	public class ComplementListener extends InputListener {
		/** @see TextField#BACKSPACE */
		@CopyConstValue
		static final char BACKSPACE = 8;
		/** @see TextField#DELETE */
		@CopyConstValue
		static final char DELETE    = 127;
		/** @see TextField#TAB */
		@CopyConstValue
		static final char TAB       = '\t';

		JSSyntax syntax = (JSSyntax) textarea.syntax; // Ensure syntax is initialized with textarea

		@Override
		public boolean keyDown(InputEvent event, KeyCode keycode) {
			if (completionPopup == null || !completionPopup.isShown()) return false;

			if (completionPopup.handleKeyDown(keycode)) {
				event.stop();
				stopEvent = true;
				return true;
			}

			// 删字符后候选要跟着变：等编辑区把字符真正删掉，下一帧再重新过滤
			if (keycode == KeyCode.backspace || keycode == KeyCode.del) {
				postCompletion();
				return false; // 让编辑区照常处理删除
			}
			if (keycode == KeyCode.escape) {
				hideCompletion();
				event.stop();
				stopEvent = true;
				return true;
			}

			return false;
		}


		private void complement() { complement(false); }
		/** @param manual 手动触发：不受 auto_complement 限制，且允许空前缀（光标紧跟 '.' 时列出全部成员） */
		void complement(boolean manual) {
			if (completionPopup == null || syntax == null) return; // Ensure dependencies are ready

			try {
				String text   = area.getText();
				int    cursor = Math.min(area.getCursorPosition(), text.length());
				Completion.Context ctx = Completion.contextAt(text, cursor);
				// 字符串、注释里的内容不是代码；空前缀（把词删光了）也不弹，否则会列出一整屏全局名
				if (!ctx.triggersCompletion(manual)) { hideCompletion(); return; }

				int     start  = ctx.start;
				String  prefix = ctx.prefix;
				boolean member = ctx.member;

				// 高亮按 '.' 截止：Vars.ma 里 cursorObj 要停在 Vars，不能解析到 Vars.map 上
				completionPopup.cursorBeforeDot = member ? ctx.dot : cursor;

				Scriptable obj = member ? syntax.cursorObj : customScope;
				if (obj == null || obj == Undefined.SCRIPTABLE_UNDEFINED) {
					if (member || prefix.isEmpty()) { hideCompletion(); return; }
					obj = customScope; // 没有基对象时退化成当前作用域的名字补全
				}

				Vec2 pos = caretPosition(start);
				// 同一个基对象上继续敲：过滤已有候选就够，重新向 Rhino 取 id 在大类上是纯浪费
				if (!manual && completionPopup.isSession(obj, member)) {
					completionPopup.narrow(prefix, pos);
					return;
				}

				keys.clear();
				addIds(keys, obj);
				if (!member) {
					JSSyntax.varSet.each(keys::add);
					JSSyntax.constantSet.each(keys::add);
					syntax.eachLocalName(keys::add);
					// 类名本身还能补出 javaClass 这类属性
					if (obj instanceof NativeJavaClass || (obj instanceof NativeJavaObject njo && njo.unwrap() instanceof Class)) {
						keys.add(RHINO.javaClassPropertyName);
					}
				}

				Completion.collect(complements, prefix, keys);
				if (complements.isEmpty()) { hideCompletion(); return; }
				completionPopup.show(complements, prefix, obj, member, this::apply, pos);
			} catch (Throwable err) {
				Log.err("Completion error", err);
				hideCompletion();
			}
		}

		/** 选中候选：替换光标所在的词。位置按当前文本重算，不依赖弹窗打开时的快照 */
		private void apply(String selected) {
			String text   = area.getText();
			int    cursor = Math.min(area.getCursorPosition(), text.length());
			int    start  = Completion.prefixStart(text, cursor);

			lookupKey.setLength(0);
			lookupKey.append(text, 0, start).append(selected).append(text, cursor, text.length());
			area.setText(lookupKey.toString());
			area.setCursorPosition(start + selected.length());
			area.clearSelection();
		}

		private final Vec2 caretPos = new Vec2();
		/** 弹窗锚点：光标位置，坐标相对 topGroup。y 是光标行的下沿（TextArea 画光标用的同一个值） */
		private Vec2 caretPosition(int index) {
			// getRelativeX/Y 里已经带了 area 自己在父容器中的位置，而
			// localToAscendantCoordinates 是从「本地坐标」起步的，会再加一次 area.x/y
			return area.localToAscendantCoordinates(topGroup,
			 caretPos.set(area.getRelativeX(index) - area.x, area.getRelativeY(index) - area.y));
		}

		private final Seq<String>     complements = new Seq<>();
		private final Seq<String>     keys        = new Seq<>();
		private final StringBuilder   lookupKey   = new StringBuilder();

		/** 只收字符串 id：数组之类的会给出整数 id，列出来没有意义 */
		private void addIds(Seq<String> out, Scriptable obj) {
			Object[] ids = obj instanceof ScriptableObject
			 ? ScriptableObject.getPropertyIds(obj) : obj.getIds();
			if (ids == null) return;
			for (Object id : ids) if (id instanceof String s) out.add(s);
		}

		final Task completionTask = TaskManager.newTask(this::complement);
		private void postCompletion() {
			TaskManager.reset(completionTask, 0.05f);
		}
		private void hideCompletion() {
			if (completionPopup != null) {
				completionPopup.hide();
				completionTask.cancel();
			}
		}
		@Override
		public boolean keyTyped(InputEvent event, char character) {
			if (stopEvent) {
				event.cancel();
				return false;
			}
			if (completionPopup == null) return false;
			// 组合键不算输入，别让它触发补全
			if (!completionPopup.isShown() && (Core.input.alt() || Core.input.ctrl())) return false;

			if (completionPopup.isShown() && (character == TAB || character == '\n')) {
				completionPopup.handleKeyDown(character == TAB ? KeyCode.tab : KeyCode.enter);
				event.cancel();
				return true;
			}

			boolean completable = area.isWordCharacter(character) || character == '.';
			if (!event.stopped && completable && auto_complement.enabled()) {
				postCompletion();
			} else if (completionPopup.isShown() && !completable
			           // 退格/删除不算「词结束」：keyDown 已经安排重新过滤，这里收起只会闪一下
			           && character != BACKSPACE && character != DELETE) {
				// 空格、';'、'(' 这类字符一敲，当前这个词就结束了
				hideCompletion();
			}
			return false;
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
			logs.add(FormatHelper.stripColor(s));
		}
	}

}