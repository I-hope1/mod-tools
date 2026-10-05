import nipx.AnnotationTransformer;
import nipx.ref.ClassHierarchyOracle;
import nipx.ref.HierarchyTreeOracle;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

public class ClassHierarchyOracleTest {
	interface Marker {
	}

	interface DefaultMethod {
		default void classWins() {
		}
	}

	interface FieldCarrier {
		int resolutionOrder = 1;
	}

	static class GrandParent {
		protected int inheritedField;
		protected int resolutionOrder;

		GrandParent() {
			inheritedField = 1;
		}

		protected void inheritedMethod() {
		}

		void superProtectedMethod() {
		}

		private void hiddenMethod() {
		}

		public final void classWins() {
		}
	}

	static class Parent extends GrandParent {
	}

	static class Child extends Parent implements Marker, DefaultMethod, FieldCarrier {
		private String ownField;

		void ownMethod() {
		}

		void callViaThis() {
			this.superProtectedMethod();
		}
	}

	private static void check(boolean condition, String message) {
		System.out.println((condition ? "   PASS  " : "   FAIL  ") + message);
		if (!condition) throw new AssertionError(message);
	}

	private static byte[] classBytes(Class<?> type) throws Exception {
		String resource = "/" + type.getName().replace('.', '/') + ".class";
		try (InputStream in = type.getResourceAsStream(resource)) {
			if (in == null) throw new AssertionError("class bytes unavailable: " + resource);
			return in.readAllBytes();
		}
	}

	public static void main(String[] args) throws Exception {
		byte[] childBytes = classBytes(Child.class);
		byte[] parentBytes = classBytes(Parent.class);
		byte[] grandParentBytes = classBytes(GrandParent.class);
		byte[] markerBytes = classBytes(Marker.class);
		byte[] defaultMethodBytes = classBytes(DefaultMethod.class);
		byte[] fieldCarrierBytes = classBytes(FieldCarrier.class);
		AnnotationTransformer.HierarchyTree.register(childBytes);
		AnnotationTransformer.HierarchyTree.register(parentBytes);
		AnnotationTransformer.HierarchyTree.register(grandParentBytes);
		AnnotationTransformer.HierarchyTree.register(markerBytes);
		AnnotationTransformer.HierarchyTree.register(defaultMethodBytes);
		AnnotationTransformer.HierarchyTree.register(fieldCarrierBytes);
		String childName = Child.class.getName().replace('.', '/');
		String parentName = Parent.class.getName().replace('.', '/');
		String markerName = Marker.class.getName().replace('.', '/');
		String grandParentName = GrandParent.class.getName().replace('.', '/');
		ClassHierarchyOracle oracle = new HierarchyTreeOracle(ClassHierarchyOracleTest.class.getClassLoader());

		check(oracle.isAssignableFrom(parentName, childName), "subclass assignability resolved from bytecode");
		check(oracle.isAssignableFrom(markerName, childName) && oracle.isInterface(markerName),
			"implemented interface and interface modifier resolved");
		check((oracle.getClassModifiers(childName) & Opcodes.ACC_SUPER) != 0,
			"class access flags resolved");

		String intDesc = "I";
		Optional<Integer> inheritedField = oracle.getFieldModifiers(childName, "inheritedField", intDesc);
		check(inheritedField.isPresent() && (inheritedField.get() & Opcodes.ACC_PROTECTED) != 0,
			"inherited field modifiers resolved by descriptor");
		check(oracle.getFieldModifiers(childName, "ownField", "Ljava/lang/String;").isPresent(),
			"declared field modifiers resolved");
		check(oracle.getFieldModifiers(childName, "ownField", "I").isEmpty(),
			"field lookup requires an exact descriptor");
		check(oracle.getMethodModifiers(childName, "inheritedMethod", "()V").isPresent(),
			"inherited method modifiers resolved");
		ClassNode childNode = new ClassNode();
		new ClassReader(childBytes).accept(childNode, 0);
		MethodInsnNode thisCall = null;
		for (MethodNode method : childNode.methods) {
			if (!method.name.equals("callViaThis")) continue;
			for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) thisCall = call;
			}
		}
		check(thisCall != null && thisCall.owner.equals(childName)
			&& oracle.getMethodModifiers(thisCall.owner, thisCall.name, thisCall.desc).isPresent(),
			"this.superProtectedMethod() resolves package-private parent method via Child owner");
		check(oracle.getMethodModifiers(childName, "hiddenMethod", "()V").isEmpty(),
			"private superclass method is not inherited");
		Optional<Integer> classWins = oracle.getMethodModifiers(childName, "classWins", "()V");
		check(classWins.isPresent() && (classWins.get() & Opcodes.ACC_FINAL) != 0,
			"superclass method takes precedence over interface default");
		Optional<Integer> fieldOrder = oracle.getFieldModifiers(childName, "resolutionOrder", "I");
		check(fieldOrder.isPresent() && (fieldOrder.get() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
			== (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL),
			"direct interface field is searched before superclass field");
		check(oracle.getMethodModifiers(childName, "<init>", "()V").isPresent(),
			"constructor modifiers resolved without inheritance");

		List<org.objectweb.asm.tree.ClassNode> nest = oracle.getNestMembers(childName);
		check(nest.stream().anyMatch(node -> node.name.equals(childName))
			&& nest.stream().anyMatch(node -> node.name.equals(parentName))
			&& nest.stream().allMatch(node -> node.methods.stream()
				.allMatch(method -> method.instructions.size() == 0)),
			"complete nest headers resolved without retaining method bodies");
		check(oracle.getNestFieldWrites(childName).stream().anyMatch(write ->
			write.className.equals(grandParentName) && write.instruction.name.equals("inheritedField")),
			"compact nest write index retains field-write evidence");
	}
}
