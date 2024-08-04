package modtools.annotations.plugins;

import com.google.auto.service.AutoService;
import com.sun.source.util.*;
import jdk.internal.org.objectweb.asm.*;
import modtools.annotations.HopeReflect;

import java.io.*;
import java.nio.file.Files;

@AutoService(Plugin.class)
public class MainPlugin implements Plugin {
	public String getName() {
		return "ModTools-Plugin";
	}
	public void init(JavacTask task, String... args) {
	}
	public boolean autoStart() {
		return true;
	}

	/** 设置字节码版本  */
	public static void main(String[] args) throws IOException {
		HopeReflect.load();
		V.load();
	}
}
class V {
	static void load() throws IOException {
		File file = new File("D:/core-v146/mindustry");
		// 将jar中所有class文件majorVersion设置为55
		Files.walk(file.toPath(), Integer.MAX_VALUE)
		 .filter(p -> p.toString().endsWith(".class"))
		 .forEach(p -> {
			 try {
				 updateClassVersion(p.toString(), Opcodes.V1_8);
			 } catch (IOException e) {
				 e.printStackTrace();
			 }
		 });
	}
	public static void updateClassVersion(String filename, int newVersion) throws IOException {
		ClassReader cr = new ClassReader(new FileInputStream(filename));
		ClassWriter cw = new ClassWriter(cr, 0);

		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				super.visit(newVersion, access, name, signature, superName, interfaces);
			}
		};

		cr.accept(cv, 0);

		try (FileOutputStream fos = new FileOutputStream(filename)) {
			fos.write(cw.toByteArray());
		}
	}
}
