import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/** 确认"重排方法表"确实生效。 */
public class ShufCheck {

	static ClassNode parse(byte[] b) {
		ClassNode c = new ClassNode();
		new ClassReader(b).accept(c, 0);
		return c;
	}

	static byte[] shuffle(byte[] b, long seed) {
		ClassNode cn = parse(b);
		if (seed == -1) Collections.reverse(cn.methods);
		else if (seed > 0) Collections.shuffle(cn.methods, new Random(seed));
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
	}

	static String order(byte[] b) {
		List<String> l = new ArrayList<>();
		for (MethodNode mn : parse(b).methods) if (mn.name.startsWith("lambda$")) l.add(mn.name);
		return l.toString();
	}

	public static void main(String[] a) throws Exception {
		byte[] f = Files.readAllBytes(Paths.get(a[0]));
		System.out.println("原序  : " + order(f));
		System.out.println("倒序  : " + order(shuffle(f, -1)));
		System.out.println("seed7 : " + order(shuffle(f, 7)));
		System.out.println("seed11: " + order(shuffle(f, 11)));
		byte[] s = shuffle(f, 7);
		System.out.println("seed7 写出再读回: " + order(s));
	}
}
