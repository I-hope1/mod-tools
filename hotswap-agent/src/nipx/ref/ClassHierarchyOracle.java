package nipx.ref;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.util.List;
import java.util.Optional;

/** Offline class metadata queries used by InitFix; names use JVM internal form. */
public interface ClassHierarchyOracle {
	boolean isAssignableFrom(String superType, String subType);

	boolean isInterface(String className);

	int getClassModifiers(String className);

	Optional<Integer> getFieldModifiers(String className, String fieldName, String desc);

	Optional<Integer> getMethodModifiers(String className, String methodName, String desc);

	Optional<MemberRef> resolveMember(String className, String name, String desc, boolean isField);

	final class MemberRef {
		public final String declaringClass;
		public final int    access;

		public MemberRef(String declaringClass, int access) {
			this.declaringClass = declaringClass;
			this.access = access;
		}
	}

	/** Returns the complete nest, or throws when any declared nest member is unavailable. */
	List<ClassNode> getNestMembers(String className);

	/** Returns a compact index of PUTFIELD/PUTSTATIC instructions across the complete nest. */
	List<NestFieldWrite> getNestFieldWrites(String className);

	final class NestFieldWrite {
		public final String        className;
		public final String        methodName;
		public final String        methodDesc;
		public final FieldInsnNode instruction;

		public NestFieldWrite(String className, String methodName, String methodDesc, FieldInsnNode instruction) {
			this.className = className;
			this.methodName = methodName;
			this.methodDesc = methodDesc;
			this.instruction = instruction;
		}
	}
}
