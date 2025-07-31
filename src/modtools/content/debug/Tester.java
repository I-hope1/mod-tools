
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
import jdk.jshell.*;
import mindustry.Vars;
import mindustry.android.AndroidRhinoContext.AndroidContextFactory;
import mindustry.game.EventType;
import mindustry.game.EventType.Trigger;
import mindustry.gen.*;
import mindustry.mod.Scripts;
import modtools.*;
import modtools.annotations.asm.CopyConstValue;
import modtools.annotations.settings.Switch;
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
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import static ihope_lib.MyReflect.unsafe;
import static modtools.content.debug.Tester.Settings.*;
import static modtools.utils.Tools.*;

public class Tester extends Content {
	private static final int    FADE_ALIGN            = Align.bottomLeft;
	/**
	 * @see NativeJavaClass#javaClassPropertyName
	 */
	@CopyConstValue
	public static final  String javaClassPropertyName = "";

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
					 cx.evaluateString(customScope, source, STR."<\{taskName}>\{entry.key}", 1);
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
			completionPopup = new CompletionPopup(area);
			ui.addChild(completionPopup); // Add it to the Tester's window
		} else {
			Log.warn("Tester: 'area' or 'ui' is null during completionPopup initialization. Completion will not work.");
		}

		Table _cont = new Table();
		if (!Vars.mobile) textarea.addListener(new EscapeAndAxesClearListener(area));

		Runnable areaInvalidate = () -> {
			textarea.getArea().invalidateHierarchy();
			textarea.layout();
		};
		ui.maximized(_ -> Time.runTask(0, areaInvalidate));
		ui.sclListener.listener = areaInvalidate;

		addListenerToArea(textarea);

		_cont.add(textarea).grow();
		_cont.row();
		ui.cont.update(() -> ((JSSyntax) textarea.syntax).js_prop = js_prop.enabled());

		Table center = _cont.table(t -> {
			 t.defaults().padRight(4f).size(42);
			 t.addListener(new KeepFocusListener(area));

			 ImageButton move = t.button(Icon.move, HopeStyles.hope_flati, () -> { }).get();
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
				logLabel = new ClearValueLabel<>(Object.class, () -> res, null);
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
	private void addListenerToArea(TextAreaTab textarea) {
		boolean[] stopEvent = {false};
		textarea.keyDownB = (_, keycode) -> {
			stopEvent[0] = false;
			if (rollAndExec() || detailsListener(keycode)) {
				stopEvent[0] = true;
				return true;
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
		IntUI.showInfoFade(STR."\{historyIndex + 1}/[lightgray]\{historySize}", pos, FADE_ALIGN);
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
	final JShell  jShell     = USE_JSHELL ? JShell.builder().executionEngine("local").build() : null;
	public void compileScript() {
		if (USE_JSHELL) {
			List<SnippetEvent> eval = jShell.eval(getMessage());
			SnippetEvent       last = eval.getLast();
			JShellException    ex   = last.exception();
			if (ex != null) handleError(ex);
			res = last.value();
			finished = true;
			return;
		}
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
		 ? STR."\{ex.getMessage()}\n\{((RhinoException) ex).getScriptStackTrace()}"
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

			logLabelCell.replace(logLabel);
			logLabel.flushVal();
			log = String.valueOf(logLabel.getText());
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
		if (Context.getCurrentContext() != cx) { VMBridge.setContext(VMBridge.getThreadContextHelper(), cx); }
	}
	public void handleError(Throwable ex) {
		makeError(ex, true);
		finished = true;
	}

	public void _load() {
		ui = new IconWindow(0, 100, true);
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
		try {
			ForRhino.factory.getApplicationClassLoader().loadClass(ModTools.class.getName());
		} catch (Throwable _) {
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
		boolean  stoppedKeyTyped;

		@Override
		public boolean keyDown(InputEvent event, KeyCode keycode) {
			stoppedKeyTyped = false;
			if (completionPopup != null && completionPopup.isShown()) {
				if (completionPopup.handleKeyDown(keycode)) {
					event.stop();
					stoppedKeyTyped = true;
					return true;
				}

				// If the popup is visible, and the user presses backspace or delete,
				// we want to update the completion list on the next frame, *after*
				// the character has been deleted from the text area.
				if (keycode == KeyCode.backspace || keycode == KeyCode.del) {
					Core.app.post(this::complement);
					// Return false to allow the key press to be processed by the text area.
					return false;
				}
			}

			if (keycode == KeyCode.escape) {
				if (completionPopup != null && completionPopup.isShown()) {
					completionPopup.hide();
					event.stop();
					return true;
				}
			}

			return false;
		}


		private void complement() {
			if (completionPopup == null || syntax == null) return; // Ensure dependencies are ready

			int    originalCursor = area.getCursorPosition();
			String textContent    = area.getText();

			// Determine prefix

			area.selectForward();
			int    start         = area.getSelectionStart();
			String currentPrefix = area.getSelection();
			area.clearSelection();

			int i = start;
			while (i > 0 && !area.isCharCheck(i, '.')) i--;
			completionPopup.cursorBeforeDot = i;

			try {
				complement0(currentPrefix, originalCursor, area.isCharCheck(i, '.'), start);
			} catch (Throwable err) {
				Log.err("Completion error", err);
				completionPopup.hide();
				// area.setCursorPosition(originalCursor); // Cursor might have moved
			}
		}

		private final Seq<String> complements = new Seq<>();
		private final Seq<Object> keys        = new Seq<>();

		private void complement0(String prefix, int originalCursor, boolean afterDot, int actualPrefixStart) {
			if (completionPopup == null || syntax == null) return;

			Scriptable obj;
			if (afterDot) {
				obj = syntax.cursorObj; // cursorObj should be updated by JSSyntax when '.' is typed
			} else {
				obj = customScope; // Or a more specific scope if available
			}

			if (obj == null || obj == Undefined.SCRIPTABLE_UNDEFINED) {
				// If after a dot and obj is null, no completions.
				// If not after a dot (word completion) and obj is null, might still try globals if prefix is short.
				if (afterDot || prefix.length() == 0) {
					completionPopup.hide();
					return;
				}
				obj = customScope; // Fallback to custom scope for word completion
			}


			keys.clear().addAll(getAllIds(prefix, obj));
			// For non-dot completion, also add global/scope vars
			if (!afterDot) {
				if (obj == customScope) { // Only add these if we're in the global-like scope
					JSSyntax.varSet.each(s -> { if (s.toLowerCase().startsWith(prefix.toLowerCase())) keys.addUnique(s); });
					JSSyntax.constantSet.each(s -> { if (s.toLowerCase().startsWith(prefix.toLowerCase())) keys.addUnique(s); });
				}
				if (obj instanceof NativeJavaClass) { // if completing on a class name itself
					if (javaClassPropertyName.toLowerCase().startsWith(prefix.toLowerCase())) {
						keys.addUnique(javaClassPropertyName);
					}
				}
			}


			complements.clear();
			keys.each((o) -> {
				String key = String.valueOf(o);
				if (key.toLowerCase().startsWith(prefix.toLowerCase()) && !key.equals(prefix)) {
					complements.add(key);
				}
			});

			if (complements.isEmpty()) {
				completionPopup.hide();
				return;
			}

			// Calculate position for the popup
			// This calculates Y position of the line *below* the cursor line, relative to area's bottom.
			// For popup above, use Y of current line. For popup below, use Y of line below.

			float cursorLineVisualY = area.getRelativeY(actualPrefixStart);
			Vec2  uiCoords          = area.localToAscendantCoordinates(ui, Tmp.v1.set(area.getRelativeX(actualPrefixStart), cursorLineVisualY));


			completionPopup.show(complements, prefix, selectedSuggestion -> {
				String text = area.getText();

				String before = text.substring(0, actualPrefixStart);
				String after  = text.substring(originalCursor);

				String newText = before + selectedSuggestion + after;
				area.setText(newText);
				area.setCursorPosition(actualPrefixStart + selectedSuggestion.length());
				area.clearSelection();
			}, uiCoords.x, uiCoords.y);
		}

		private Object[] getAllIds(String searchingKey, Scriptable obj) {
			// For NativeJavaObject/Class, getIds() is appropriate. For general ScriptableObject, getPropertyIds might be better.
			if (obj instanceof ScriptableObject) return ScriptableObject.getPropertyIds(obj);
			return obj.getIds();
		}

		Task completionTask = new Task() {
			@Override
			public void run() {
				complement();
			}
		};
		private boolean postCompletion() {
			return TaskManager.trySchedule(0.05f, completionTask);
		}
		@Override
		public boolean keyTyped(InputEvent event, char character) {
			if (stoppedKeyTyped) {
				event.cancel();
				return false;
			}
			if (completionPopup != null && !completionPopup.isShown() && (Core.input.alt() || Core.input.ctrl())) {
				return false;
			}

			if (completionPopup != null && completionPopup.isShown() && (character == TAB || character == '\n')) {
				completionPopup.handleKeyDown(character == TAB ? KeyCode.tab : KeyCode.enter);
				event.cancel();
				return true;
			}

			// --- The faulty logic for DELETE/BACKSPACE has been REMOVED from here. ---

			boolean isCompletableChar       = area.isWordCharacter(character) || character == '.';
			boolean shouldTriggerCompletion = auto_complement.enabled() && isCompletableChar;

			if (!event.stopped && shouldTriggerCompletion) {
				Timer.schedule(this::complement, 0.05f);
			} else if (completionPopup != null && completionPopup.isShown() && !isCompletableChar) {
				// Hide the popup if a non-word character (like space, ';', '(') is typed.
				completionPopup.hide();
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


	/* The animal world is full of wonder. But what's it like to be right in the heart of the action?
	To find out, our spy creatures join the animal families.
	They not only look like the animals they film, they behave like them, too.
	 Armed with the latest camera technology, they are heading across the globe,
	 from the heat of the tropics to the lands that lie in the north,
	  from the islands of the southern seas to the snow and ice of the frozen poles.
	   Our spies reveal the astonishing variety of life that thrives there,
	   from inside their world.

 */
}