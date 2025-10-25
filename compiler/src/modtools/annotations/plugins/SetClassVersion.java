// Save as SetClassVersion.java
package modtools.annotations.plugins;

import jdk.internal.org.objectweb.asm.*;
import modtools.annotations.HopeReflect;

import java.io.*;
import java.nio.file.*;
import java.util.jar.*;

/**
 * 一个用于修改JAR文件中所有.class文件字节码版本的工具。
 */
public class SetClassVersion {

	/**
	 * 设置 class 文件的字节码版本。
	 * Java 8  -> 52 (Opcodes.V1_8)
	 * Java 11 -> 55 (Opcodes.V11)
	 * Java 17 -> 61 (Opcodes.V17)
	 */
	private static final int TARGET_CLASS_VERSION = Opcodes.V17; // 目标设为 Java 8 (version 52)
	// 如果需要版本 55, 请使用 Opcodes.V11

	/**
	 * 程序入口。
	 * 接收一个或多个JAR文件路径作为命令行参数。
	 * @param args JAR文件的路径列表。
	 */
	public static void main(String[] args) {
		if (args.length == 0) {
			System.out.println("用法: java SetClassVersion <path-to-jar-1> [path-to-jar-2] ...");
			System.out.println("错误: 请提供至少一个JAR文件路径作为参数。");
			return;
		}

		HopeReflect.load();
		// 移除了对 HopeReflect.load() 的调用，因为它未定义且对于此任务不是必需的。

		for (String jarPath : args) {
			try {
				System.out.println("正在处理: " + jarPath);
				JarPatcher.processJar(new File(jarPath), TARGET_CLASS_VERSION);
				System.out.println("成功更新 " + jarPath + " 的字节码版本。");
			} catch (IOException e) {
				System.err.println("处理 " + jarPath + " 时发生错误: " + e.getMessage());
				e.printStackTrace();
			}
		}
	}
}

/**
 * 负责修改JAR文件内容的工具类。
 */
class JarPatcher {

	/**
	 * 读取指定的JAR文件，修改其中所有.class文件的版本，然后写回。
	 * @param sourceJarFile   源JAR文件。
	 * @param newClassVersion 新的类版本，使用 Opcodes.V_XX 常量。
	 * @throws IOException 如果发生I/O错误。
	 */
	public static void processJar(File sourceJarFile, int newClassVersion) throws IOException {
		if (!sourceJarFile.exists()) {
			throw new FileNotFoundException("文件未找到: " + sourceJarFile.getAbsolutePath());
		}

		// 创建一个临时文件用于写入修改后的内容，避免在读取时修改源文件。
		File tempJarFile = File.createTempFile(sourceJarFile.getName(), ".tmp");

		try (JarInputStream jis = new JarInputStream(new BufferedInputStream(new FileInputStream(sourceJarFile)));
		     JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(tempJarFile)))) {

			JarEntry entry;
			// 遍历JAR文件中的每一个条目
			while ((entry = jis.getNextJarEntry()) != null) {
				// 将条目写入新的JAR文件
				jos.putNextEntry(new JarEntry(entry.getName()));

				if (entry.getName().endsWith(".class")) {
					// 如果是class文件, 读取字节码并进行转换
					byte[] originalBytes = readAllBytes(jis);
					byte[] modifiedBytes = ClassVersionUpdater.update(originalBytes, newClassVersion);
					jos.write(modifiedBytes);
				} else {
					// 如果不是class文件 (如资源文件, MANIFEST.MF), 直接复制内容
					copyStream(jis, jos);
				}
				jos.closeEntry();
			}
		}

		// 操作成功后，用修改后的临时文件替换原始文件
		Files.move(tempJarFile.toPath(), sourceJarFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * 从输入流中读取所有字节。
	 */
	private static byte[] readAllBytes(InputStream is) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[]                data   = new byte[4096];
		int                   nRead;
		while ((nRead = is.read(data, 0, data.length)) != -1) {
			buffer.write(data, 0, nRead);
		}
		return buffer.toByteArray();
	}

	/**
	 * 将输入流的内容复制到输出流。
	 */
	private static void copyStream(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[4096];
		int    bytesRead;
		while ((bytesRead = in.read(buffer)) != -1) {
			out.write(buffer, 0, bytesRead);
		}
	}

	/**
	 * 使用 ASM 库来修改字节码版本的核心逻辑。
	 */
	static class ClassVersionUpdater {

		/**
		 * 接收一个class文件的字节数组，返回一个修改了版本号的新字节数组。
		 * @param classBytes 原始的class字节码。
		 * @param newVersion 新的目标版本 (例如 Opcodes.V1_8)。
		 * @return 修改版本后的class字节码。
		 */
		public static byte[] update(byte[] classBytes, final int newVersion) {
			ClassReader cr = new ClassReader(classBytes);
			ClassWriter cw = new ClassWriter(cr, 0);

			ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
				@Override
				public void visit(int version, int access, String name, String signature, String superName,
				                  String[] interfaces) {
					// 调用父类方法，但传入新的版本号
					super.visit(newVersion, access, name, signature, superName, interfaces);
				}
			};

			cr.accept(cv, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

			return cw.toByteArray();
		}
	}
}

