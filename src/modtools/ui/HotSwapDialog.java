package modtools.ui;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.util.*;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.ui.comp.Window;
import nipx.HotSwapAgent;

/**
 * 热重载控制台
 * 集成了日志监控、手动触发和内存监视
 */
public class HotSwapDialog extends Window {
	public static HotSwapDialog instance = new HotSwapDialog();

	public static void staticShow() {
		instance.show();
	}

	// 日志存储，限制长度以防内存溢出
	private final StringBuilder logBuilder = new StringBuilder();
	private final Label         logLabel;
	private final ScrollPane    logPane;

	// 原始 Logger，用于窗口关闭时恢复
	private final HotSwapAgent.Logger originalLogger;

	public HotSwapDialog() {
		super("HotSwap Control", 400, 300, true);

		// 1. 劫持 Logger (Agent -> UI 的桥梁)
		this.originalLogger = HotSwapAgent.logger;
		setupAgentLogger();

		logLabel = new Label("");
		logLabel.setAlignment(Align.topLeft);
		logLabel.setWrap(true);
		// 2. 构建 UI
		// 顶部工具栏
		cont.table(t -> {
			t.defaults().size(40);

			// 手动触发检测 (对应 processChanges)
			t.button(Icon.refresh, Styles.cleari, () -> {
				log("[lightgray]Triggering manual hotswap...[]");
				// 在后台执行，防止卡顿 UI
				Threads.daemon(HotSwapAgent::triggerHotswap);
			}).tooltip("Check for changes & Hotswap");

			// 强制全量重扫 (对应 transformLoaded)
			t.button(Icon.box, Styles.cleari, () -> {
				log("[accent]Force re-scanning all loaded classes...[]");
				// 这里需要反射或者修改 Agent 公开 transformLoaded
				// 假设你在 Agent 里把 transformLoaded 改为了 public static
				// Threads.daemon(HotSwapAgent::transformLoaded);
				log("[yellow](Method needs to be public in Agent)[]");
			}).tooltip("Force Retransform Loaded Classes");

			// 垃圾回收
			t.button(Icon.eraser, Styles.cleari, () -> {
				System.gc();
				log("[lightgray]GC invoked. Heap: " + Core.app.getJavaHeap() / 1024 / 1024 + "MB[]");
			}).tooltip("Force GC");

			t.add().growX();

			// 清空日志
			t.button(Icon.trash, Styles.cleari, () -> {
				logBuilder.setLength(0);
				logLabel.setText("");
			}).tooltip("Clear Log");

		}).growX().row();


		// 使用等宽字体如果不喜欢可以去掉，但代码看起来舒服点
		// logLabel.setStyle(Styles.outlineLabel);

		Table logTable = new Table();
		logTable.top().left();
		logTable.add(logLabel).growX().top().left();

		logPane = new ScrollPane(logTable, Styles.defaultPane);
		logPane.setFadeScrollBars(false);

		// 黑色背景板，增加对比度
		Table paneContainer = new Table();
		paneContainer.background(Styles.black6);
		paneContainer.add(logPane).grow().margin(4);

		cont.add(paneContainer).grow().row();

		// 底部状态栏
		cont.table(t -> {
			t.label(() -> "Heap: " + (Core.app.getJavaHeap() / 1024 / 1024) + "MB").color(Color.gray).fontScale(0.8f);
			t.add().growX();
			t.label(() -> "Watch: " + (HotSwapAgent.ENABLE_HOTSWAP_EVENT ? "Active" : "Passive")).color(Color.gray).fontScale(0.8f);
		}).growX().pad(2);

		// 3. 窗口关闭时的资源回收 (否定之否定：恢复原状)
		this.hidden(() -> HotSwapAgent.logger = originalLogger);
	}

	/**
	 * 核心黑科技：代理模式 (Proxy Pattern)
	 * 既保留控制台输出，又转发到 UI，同时解决线程安全问题
	 */
	private void setupAgentLogger() {
		HotSwapAgent.logger = new HotSwapAgent.Logger() {
			@Override
			public void log(String msg) {
				originalLogger.log(msg); // 转发给原控制台
				appendUI("[lightgray]" + msg + "[]");
			}

			@Override
			public void info(String msg) {
				originalLogger.info(msg);
				appendUI("[white]" + msg + "[]");
			}

			@Override
			public void error(String msg) {
				originalLogger.error(msg);
				appendUI("[red]" + msg + "[]");
			}

			@Override
			public void error(String msg, Throwable t) {
				originalLogger.error(msg, t);
				appendUI("[red]" + msg + "\n" + Strings.getStackTrace(t) + "[]");
			}
		};
	}

	private void appendUI(String text) {
		// 矛盾解决：Agent 在后台线程，UI 在主线程
		// 使用 Core.app.post 调度到 UI 线程执行
		Core.app.post(() -> {
			if (logBuilder.length() > 20000) {
				logBuilder.delete(0, 5000); // 截断旧日志
			}
			logBuilder.append(text).append("\n");
			logLabel.setText(logBuilder);

			// 自动滚动到底部
			Core.app.post(() -> {
				logPane.validate();
				logPane.setScrollY(logPane.getMaxY());
			});
		});
	}

	// 简单的辅助日志方法
	private void log(String msg) {
		HotSwapAgent.info(msg);
	}
}