package modtools.unsupported;

import arc.Core;
import arc.util.Log;

import java.net.ServerSocket;
import java.util.concurrent.locks.LockSupport;

/** 端口：35791 */
public class HotSwapController {
	public static final     int     SOCKET_PORT = 35791;
	// 记录主线程（游戏线程）的引用
	private static          Thread  gameThread;
	// 标记是否正在热重载
	private static volatile boolean isReloading = false;

	// 在 Mod 初始化阶段调用一次 (比如 FMLClientSetupEvent)
	public static void init() {
		gameThread = Thread.currentThread();

		// 启动 Socket 监听器
		startSocketListener();
	}


	// 外部线程（Socket）调用此方法暂停
	public static void requestPause() {
		isReloading = true;
		Log.info("[NIPX] Pausing Game Thread...");
		Core.app.post(() -> {
			// 2. 检查暂停状态（必须在循环中检查，防止虚假唤醒）
			while (isReloading) {
				Log.info("[NIPX] Game Paused. Waiting...");
				// 挂起当前线程
				LockSupport.park();
			}
		});
	}

	// 外部线程（Socket）调用此方法恢复
	public static void requestResume() {
		isReloading = false;
		// 【核心】唤醒主线程
		if (gameThread != null) {
			LockSupport.unpark(gameThread);
		}
		Log.info("[NIPX] Game Thread Resumed!");
	}

	// ========== Socket 监听部分 ==========
	private static void startSocketListener() {
		Thread t = new Thread(() -> {
			try (var ss = new ServerSocket(SOCKET_PORT)) {
				while (true) {
					try (var s = ss.accept()) {
						int cmd = s.getInputStream().read();
						if (cmd == 1) {
							requestPause();       // Gradle: doFirst
						} else if (cmd == 0) requestResume(); // Gradle: doLast / Agent
					}
				}
			} catch (Exception e) {
				Log.err(e);
			}
		}, "HotSwap-Listener");
		t.setDaemon(true);
		t.start();
	}
}