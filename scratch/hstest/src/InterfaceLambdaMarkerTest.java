import nipx.AnnotationTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.Arrays;

public class InterfaceLambdaMarkerTest {
	interface LambdaFixture {
		default Runnable make() {
			return () -> consume(this);
		}

		static void consume(LambdaFixture value) {
		}
	}

	static final class ByteLoader extends ClassLoader {
		ByteLoader(ClassLoader parent) {
			super(parent);
		}

		Class<?> define(byte[] bytes) {
			return defineClass(null, bytes, 0, bytes.length);
		}
	}

	public static void main(String[] args) throws Exception {
		String slashName = LambdaFixture.class.getName().replace('.', '/');
		byte[] original;
		try (java.io.InputStream in = LambdaFixture.class.getResourceAsStream("/" + slashName + ".class")) {
			if (in == null) throw new AssertionError("fixture class bytes not found");
			original = in.readAllBytes();
		}

		byte[] transformed = AnnotationTransformer.forceStaticLambdas(
			original, slashName, InterfaceLambdaMarkerTest.class.getClassLoader());
		if (Arrays.equals(original, transformed)) {
			throw new AssertionError("fixture was not transformed");
		}

		ClassNode node = new ClassNode();
		new ClassReader(transformed).accept(node, 0);
		int markerAccess = -1;
		for (FieldNode field : node.fields) {
			if ("$nipx$lambdasForced".equals(field.name)) {
				markerAccess = field.access;
				break;
			}
		}
		int required = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
		if (markerAccess != required) {
			throw new AssertionError("expected interface marker flags 0x1019, got 0x"
				+ Integer.toHexString(markerAccess));
		}

		byte[] transformedAgain = AnnotationTransformer.forceStaticLambdas(
			transformed, slashName, InterfaceLambdaMarkerTest.class.getClassLoader());
		if (!Arrays.equals(transformed, transformedAgain)) {
			throw new AssertionError("transformation is not idempotent");
		}

		Class<?> defined = new ByteLoader(InterfaceLambdaMarkerTest.class.getClassLoader()).define(transformed);
		if (!defined.isInterface()) throw new AssertionError("defined class is not an interface");
		System.out.println("interface marker flags, class loading, and idempotence OK");
	}
}
