package modtools.unsupported;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

public class HotSwapWatcher implements Runnable {

    private final Path        rootWatchDir;
    private final Set<String> changedClasses = Collections.synchronizedSet(new HashSet<>());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "HotSwap-Scheduler");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> scheduledTask;

    private HotSwapWatcher(Path rootWatchDir) {
        this.rootWatchDir = rootWatchDir;
    }

    public static void start() {
        try {
            String classesDirPath = HotSwapManager.findClassesDirectory();
            Path dir = Paths.get(classesDirPath);
            if (!Files.exists(dir)) {
                 System.out.println("[HotSwapWatcher] Classes directory does not exist at startup, will try to watch later: " + dir);
            }

            HotSwapWatcher watcher = new HotSwapWatcher(dir);
            Thread watcherThread = new Thread(watcher, "HotSwap-FileWatcher");
            watcherThread.setDaemon(true);

            watcherThread.start();
        } catch (IllegalStateException | URISyntaxException e) {
            System.out.println("[HotSwapWatcher] Not in a watchable development environment. Auto hot-swap is disabled.");
        }
    }


    @Override
    public void run() {
        System.out.println("[HotSwapWatcher] Watcher thread started for: " + rootWatchDir);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 等待根目录出现
                waitForRootDirectory();
                if (Thread.currentThread().isInterrupted()) break;

                Thread.sleep(200);
                // 启动并运行监控服务
                monitorDirectory();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 捕获所有其他意外异常，打印并重启监控循环
                System.err.println("[HotSwapWatcher] An unexpected error occurred. Restarting watch cycle.");
                e.printStackTrace();
                try { TimeUnit.SECONDS.sleep(2); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        System.out.println("[HotSwapWatcher] Watcher thread terminated.");
    }

    private void waitForRootDirectory() throws InterruptedException {
        while (!Files.exists(rootWatchDir)) {
            System.out.println("[HotSwapWatcher] Root directory not found. Retrying in 2 seconds...");
            TimeUnit.SECONDS.sleep(2);
        }
    }

    private void monitorDirectory() throws IOException, InterruptedException {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            System.out.println("[HotSwapWatcher] Root directory detected. Registering directories for watching...");

            // 使用更安全的方式注册目录
            registerAll(watchService, rootWatchDir);

            System.out.println("[HotSwapWatcher] Monitoring started.");

            while (Files.exists(rootWatchDir)) {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) continue;

                Path triggeredDir = (Path) key.watchable();

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

                    Path context = (Path) event.context();
                    Path fullPath = triggeredDir.resolve(context);

                    // 如果新创建的是一个目录，递归地为它和它的子目录注册监控
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath)) {
                        Thread.sleep(200);
                        registerAll(watchService, fullPath);
                    }

                    if (context.toString().endsWith(".class")) {
                        handleClassChange(triggeredDir, context);
                    }
                }

                if (!key.reset()) {
                    System.out.println("[HotSwapWatcher] WatchKey for " + triggeredDir + " is no longer valid. Re-initializing...");
                    break; // 退出内部循环，外部循环将处理目录重建
                }
            }
        } finally {
             System.out.println("[HotSwapWatcher] Monitoring stopped. Root directory may have been deleted.");
        }
    }

    /**
     * 安全地递归注册目录。如果遍历时有目录被删除，会忽略错误。
     */
    private void registerAll(WatchService service, Path startDir) {
        try (Stream<Path> stream = Files.walk(startDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                try {
                    dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                } catch (IOException e) {
                    // 在并发删除时，这里可能会抛出异常，我们可以安全地忽略它
                    // 因为外部循环会检测到根目录删除并重启整个监控
                    // System.err.println("[HotSwapWatcher] Could not register " + dir + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
             // 如果 walk 本身失败（例如 startDir 在 walk 开始时就被删除），也忽略
             // System.err.println("[HotSwapWatcher] Failed to walk directory " + startDir + ": " + e.getMessage());
        }
    }
    // 辅助方法，用于注册目录，并包含日志
    private void register(WatchService service, Path dir) {
        try {
            dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            System.err.println("[HotSwapWatcher] Failed to register directory: " + dir + " (" + e.getMessage() + ")");
        }
    }

    private void handleClassChange(Path dir, Path file) {
        Path relativePath = rootWatchDir.relativize(dir.resolve(file));
        String className = relativePath.toString()
                .replace(FileSystems.getDefault().getSeparator(), ".")
                .replace(".class", "");

        changedClasses.add(className);

        if (scheduledTask != null && !scheduledTask.isDone()) {
            scheduledTask.cancel(false);
        }
        scheduledTask = scheduler.schedule(this::triggerHotswap, 1000, TimeUnit.MILLISECONDS);
    }

    private void triggerHotswap() {
        Set<String> classesToUpdate;
        synchronized (changedClasses) {
            if (changedClasses.isEmpty()) return;
            classesToUpdate = new HashSet<>(changedClasses);
            changedClasses.clear();
        }
        HotSwapManager.hotswap(classesToUpdate);
    }
}