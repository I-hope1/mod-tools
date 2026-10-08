import nipx.AnnotationTransformer;
import nipx.ref.ClassHierarchyOracle;
import nipx.ref.HierarchyTreeOracle;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

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

	private static byte[] classBytes(Class<?> type) throws Exception {
		String resource = "/" + type.getName().replace('.', '/') + ".class";
		try (InputStream in = type.getResourceAsStream(resource)) {
			if (in == null) throw new AssertionError("class bytes unavailable: " + resource);
			return in.readAllBytes();
		}
	}

	@Test
	void resolvesHierarchyFromBytecode() throws Exception {
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

		assertTrue(oracle.isAssignableFrom(parentName, childName), "subclass assignability resolved from bytecode");
		assertTrue(oracle.isAssignableFrom(markerName, childName) && oracle.isInterface(markerName),
			"implemented interface and interface modifier resolved");
		assertTrue((oracle.getClassModifiers(childName) & Opcodes.ACC_SUPER) != 0,
			"class access flags resolved");

		String intDesc = "I";
		Optional<Integer> inheritedField = oracle.getFieldModifiers(childName, "inheritedField", intDesc);
		Optional<ClassHierarchyOracle.MemberRef> inheritedFieldRef =
			oracle.resolveMember(childName, "inheritedField", intDesc, true);
		assertTrue(inheritedField.isPresent() && (inheritedField.get() & Opcodes.ACC_PROTECTED) != 0
			&& inheritedFieldRef.isPresent() && inheritedFieldRef.get().declaringClass.equals(grandParentName),
			"inherited field modifiers and declaring class resolved by descriptor");
		assertTrue(oracle.getFieldModifiers(childName, "ownField", "Ljava/lang/String;").isPresent(),
			"declared field modifiers resolved");
		assertTrue(oracle.getFieldModifiers(childName, "ownField", "I").isEmpty(),
			"field lookup requires an exact descriptor");
		Optional<ClassHierarchyOracle.MemberRef> inheritedMethod =
			oracle.resolveMember(childName, "inheritedMethod", "()V", false);
		assertTrue(inheritedMethod.isPresent() && inheritedMethod.get().declaringClass.equals(grandParentName),
			"inherited method resolution preserves its declaring class");
		Optional<ClassHierarchyOracle.MemberRef> clone =
			oracle.resolveMember(childName, "clone", "()Ljava/lang/Object;", false);
		assertTrue(clone.isPresent() && clone.get().declaringClass.equals("java/lang/Object")
			&& (clone.get().access & Opcodes.ACC_PROTECTED) != 0,
			"inherited protected Object.clone retains cross-package declaring class");
		ClassNode childNode = new ClassNode();
		new ClassReader(childBytes).accept(childNode, 0);
		MethodInsnNode thisCall = null;
		for (MethodNode method : childNode.methods) {
			if (!method.name.equals("callViaThis")) continue;
			for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) thisCall = call;
			}
		}
		assertTrue(thisCall != null && thisCall.owner.equals(childName)
			&& oracle.getMethodModifiers(thisCall.owner, thisCall.name, thisCall.desc).isPresent(),
			"this.superProtectedMethod() resolves package-private parent method via Child owner");
		assertTrue(oracle.getMethodModifiers(childName, "hiddenMethod", "()V").isEmpty(),
			"private superclass method is not inherited");
		Optional<Integer> classWins = oracle.getMethodModifiers(childName, "classWins", "()V");
		assertTrue(classWins.isPresent() && (classWins.get() & Opcodes.ACC_FINAL) != 0,
			"superclass method takes precedence over interface default");
		Optional<Integer> fieldOrder = oracle.getFieldModifiers(childName, "resolutionOrder", "I");
		assertTrue(fieldOrder.isPresent() && (fieldOrder.get() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
			== (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL),
			"direct interface field is searched before superclass field");
		assertTrue(oracle.getMethodModifiers(childName, "<init>", "()V").isPresent(),
			"constructor modifiers resolved without inheritance");

		List<org.objectweb.asm.tree.ClassNode> nest = oracle.getNestMembers(childName);
		assertTrue(nest.stream().anyMatch(node -> node.name.equals(childName))
			&& nest.stream().anyMatch(node -> node.name.equals(parentName))
			&& nest.stream().allMatch(node -> node.methods.stream()
				.allMatch(method -> method.instructions.size() == 0)),
			"complete nest headers resolved without retaining method bodies");
		assertTrue(oracle.getNestFieldWrites(childName).stream().anyMatch(write ->
			write.className.equals(grandParentName) && write.instruction.name.equals("inheritedField")),
			"compact nest write index retains field-write evidence");
	}
}
