// temp_file_9e2936cb-88f5-40a8-92be-c17210cda96e_pasted_text.txt
package nipx;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger; // <--- [FIX] 引入 AtomicInteger
import java.util.stream.Stream;

public class Main {
    // ... (DEBUG 和日志方法保持不变) ...

	/**
	 * 存储当前已加载类的字节码哈希值，用于检测变更。
	 * Key: 类的全限定名
	 * Value: 字节码的 MD5 哈希 (byte[])
	 */
	private static final Map<String, byte[]> classHashes = new ConcurrentHashMap<>();

	public static void agentmain(String agentArgs, Instrumentation inst) {
		info("Agent loaded dynamically.");
		if (DEBUG) {
			info("Debug mode is enabled.");
		}
        // ... (参数检查代码不变) ...

		Path classesDir = Paths.get(agentArgs);
		if (!Files.isDirectory(classesDir)) {
			error("Provided path is not a directory: " + agentArgs);
			return;
		}

		info("Target classes directory: " + classesDir);
		redefineClasses(classesDir, inst);
	}

	private static void redefineClasses(Path classesDir, Instrumentation inst) {
		Map<String, Class<?>> loadedClassesMap = new HashMap<>();
		for (Class<?> loadedClass : inst.getAllLoadedClasses()) {
			loadedClassesMap.put(loadedClass.getName(), loadedClass);
		}

		List<ClassDefinition> definitions = new ArrayList<>();
        // [FIX] 使用 AtomicInteger 来正确地在 lambda 中计数
		final AtomicInteger unchangedCount = new AtomicInteger(0);

		try (Stream<Path> stream = Files.walk(classesDir)) {
			stream.filter(path -> path.toString().endsWith(".class"))
				.forEach(path -> {
					try {
						String className = getClassName(classesDir, path);
						Class<?> targetClass = loadedClassesMap.get(className);

						if (targetClass != null) {
							byte[] newBytecode = Files.readAllBytes(path);
							byte[] newHash = calculateHash(newBytecode);
							byte[] oldHash = classHashes.get(className);

							// 比较哈希值
							if (oldHash != null && Arrays.equals(oldHash, newHash)) {
								// 哈希值相同，说明字节码未改变
								log("Bytecode for " + className + " is unchanged, skipping.");
                                // [FIX] 正确地增加计数器
								unchangedCount.incrementAndGet();
							} else {
								// 哈希值不同或首次加载，需要重定义
								definitions.add(new ClassDefinition(targetClass, newBytecode));
								// 成功后会更新哈希
								log("Staged for redefine: " + className);
							}
						} else {
							log("Class not loaded, skipping: " + className);
						}
					} catch (IOException | NoSuchAlgorithmException e) {
						error("Failed to process class file: " + path, e);
					}
				});
		} catch (IOException e) {
			error("Error walking classes directory.", e);
			return;
		}

		if (definitions.isEmpty()) {
            // [IMPROVEMENT] 提供更详细的日志信息
			info("No modified classes to redefine. " + unchangedCount.get() + " classes were checked and found unchanged.");
			return;
		}

		try {
			inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));

			// 重定义成功后，更新这些类的哈希值
			for (ClassDefinition def : definitions) {
				String className = def.getDefinitionClass().getName();
				try {
					// [IMPROVEMENT] 直接使用传入的字节码来计算哈希，避免重复读取或依赖内部 API
					byte[] newHash = calculateHash(def.getDefinitionClassFile());
					classHashes.put(className, newHash);
				} catch (NoSuchAlgorithmException e) {
					// This should not happen as MD5 is a standard algorithm
					error("Failed to calculate hash after redefine for " + className, e);
				}
			}

			info("Successfully redefined " + definitions.size() + " classes.");
		} catch (ClassNotFoundException | UnmodifiableClassException e) {
			error("Error redefining " + definitions.size() + " classes.", e);
		} catch (Throwable t) {
			error("An unexpected error occurred during redefinition.", t);
		}

        // [IMPROVEMENT] 提供更完整的最终摘要信息
		info("Hot-swap summary: " + definitions.size() + " redefined, " + unchangedCount.get() + " unchanged.");
	}

	/**
	 * 计算字节码的 MD5 哈希值
	 */
	private static byte[] calculateHash(byte[] bytecode) throws NoSuchAlgorithmException {
		MessageDigest md = MessageDigest.getInstance("MD5");
		return md.digest(bytecode);
	}

    // ... (getClassName 和日志方法保持不变) ...

    // ================== 日志辅助方法 (保持不变) ==================
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("nipx.agent.debug", "false"));
    private static void log(String message) { if (DEBUG) System.out.println("[HotSwapAgent] " + message); }
	private static void info(String message) { System.out.println("[HotSwapAgent] " + message); }
	private static void error(String message) { System.err.println("[HotSwapAgent] " + message); }
	private static void error(String message, Throwable t) { System.err.println("[HotSwapAgent] " + message); t.printStackTrace(System.err); }
    private static String getClassName(Path rootDir, Path classFile) {
		Path relativePath = rootDir.relativize(classFile);
		String pathStr = relativePath.toString();
		return pathStr.substring(0, pathStr.length() - ".class".length()).replace(File.separatorChar, '.');
	}
}