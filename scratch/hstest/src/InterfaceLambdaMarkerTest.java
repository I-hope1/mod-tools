import nipx.AnnotationTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import java.util.Arrays;

public class InterfaceLambdaMarkerTest {
	public interface LambdaFixture {
		default Runnable capturing() {
			return () -> consume(this);
		}

		default Runnable nonCapturing() {
			return () -> consume(null);
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

	private static void check(boolean condition, String message) {
		System.out.println((condition ? "   PASS  " : "   FAIL  ") + message);
		if (!condition) throw new AssertionError(message);
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
		check(!Arrays.equals(original, transformed), "interface lambda fixture transformed");

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
		check(markerAccess == required, "interface marker flags are exactly 0x1019 (actual 0x"
			+ Integer.toHexString(markerAccess) + ")");

		boolean staticLambdaHasExplicitReceiver = false;
		for (MethodNode method : node.methods) {
			if (!method.name.startsWith("lambda$nonCapturing$") || (method.access & Opcodes.ACC_STATIC) == 0) continue;
			org.objectweb.asm.Type[] arguments = org.objectweb.asm.Type.getArgumentTypes(method.desc);
			staticLambdaHasExplicitReceiver = arguments.length > 0
				&& arguments[0].getDescriptor().equals("L" + slashName + ";");
			if (staticLambdaHasExplicitReceiver) break;
		}
		check(staticLambdaHasExplicitReceiver,
			"non-capturing lambda was rewritten static with interface receiver parameter");

		byte[] transformedAgain = AnnotationTransformer.forceStaticLambdas(
			transformed, slashName, InterfaceLambdaMarkerTest.class.getClassLoader());
		check(Arrays.equals(transformed, transformedAgain), "transformation is byte-for-byte idempotent");

		ByteLoader loader = new ByteLoader(InterfaceLambdaMarkerTest.class.getClassLoader());
		Class<?> defined = loader.define(transformed);
		Class.forName(defined.getName(), true, loader);
		check(defined.isInterface(), "transformed interface verifies, links, and initializes");

		Object instance = Proxy.newProxyInstance(loader, new Class<?>[]{defined},
			(proxy, method, arguments) -> InvocationHandler.invokeDefault(proxy, method, arguments));
		for (String methodName : new String[]{"capturing", "nonCapturing"}) {
			Runnable lambda = (Runnable) defined.getMethod(methodName).invoke(instance);
			lambda.run();
		}
		check(true, "capturing and non-capturing lambdas link and execute");
	}
}
