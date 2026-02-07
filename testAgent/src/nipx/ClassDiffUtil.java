package nipx;

import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.Type;

import java.lang.reflect.*;
import java.util.*;

final class ClassDiffUtil {

	private ClassDiffUtil() {}

	/* =========================
	 * 对外 API
	 * ========================= */

	static ClassDiff diff(Class<?> loadedClass, byte[] newBytecode) {
		ClassStructure oldStruct = fromLoadedClass(loadedClass);
		ClassStructure newStruct = fromBytecode(newBytecode);
		return diff(oldStruct, newStruct);
	}

	/* =========================
	 * 数据结构
	 * ========================= */

	static final class ClassStructure {
		String className;
		Set<FieldSig> fields = new HashSet<>();
		Set<MethodSig> methods = new HashSet<>();
		Set<MethodSig> constructors = new HashSet<>();
	}

	static final class ClassDiff {
		Set<FieldSig> addedFields = new HashSet<>();
		Set<FieldSig> removedFields = new HashSet<>();

		Set<MethodSig> addedMethods = new HashSet<>();
		Set<MethodSig> removedMethods = new HashSet<>();

		Set<MethodSig> addedConstructors = new HashSet<>();
		Set<MethodSig> removedConstructors = new HashSet<>();

		boolean hasStructuralChange() {
			return !addedFields.isEmpty()
				|| !removedFields.isEmpty()
				|| !addedMethods.isEmpty()
				|| !removedMethods.isEmpty()
				|| !addedConstructors.isEmpty()
				|| !removedConstructors.isEmpty();
		}
	}

	static final class FieldSig {
		private final String name;
		private final String desc;
		private final int    access;
		FieldSig(String name, String desc, int access) {
			this.name = name;
			this.desc = desc;
			this.access = access;
		}
		public String name() { return name; }
		public String desc() { return desc; }
		public int access() { return access; }
		@Override
		public boolean equals(Object obj) {
			if (obj == this) return true;
			if (obj == null || obj.getClass() != this.getClass()) return false;
			var that = (FieldSig) obj;
			return Objects.equals(this.name, that.name) &&
			       Objects.equals(this.desc, that.desc) &&
			       this.access == that.access;
		}
		@Override
		public int hashCode() {
			return Objects.hash(name, desc, access);
		}
		@Override
		public String toString() {
			return "FieldSig[" +
			       "name=" + name + ", " +
			       "desc=" + desc + ", " +
			       "access=" + access + ']';
		}
	}

	static final class MethodSig {
		private final String name;
		private final String desc;
		private final int    access;
		MethodSig(String name, String desc, int access) {
			this.name = name;
			this.desc = desc;
			this.access = access;
		}
		public String name() { return name; }
		public String desc() { return desc; }
		public int access() { return access; }
		@Override
		public boolean equals(Object obj) {
			if (obj == this) return true;
			if (obj == null || obj.getClass() != this.getClass()) return false;
			var that = (MethodSig) obj;
			return Objects.equals(this.name, that.name) &&
			       Objects.equals(this.desc, that.desc) &&
			       this.access == that.access;
		}
		@Override
		public int hashCode() {
			return Objects.hash(name, desc, access);
		}
		@Override
		public String toString() {
			return "MethodSig[" +
			       "name=" + name + ", " +
			       "desc=" + desc + ", " +
			       "access=" + access + ']';
		}
	}

	/* =========================
	 * Loaded Class -> Structure
	 * ========================= */

	private static ClassStructure fromLoadedClass(Class<?> c) {
		ClassStructure cs = new ClassStructure();
		cs.className = c.getName();

		for (Field f : c.getDeclaredFields()) {
			cs.fields.add(new FieldSig(
				f.getName(),
				Type.getDescriptor(f.getType()),
				f.getModifiers()
			));
		}

		for (Method m : c.getDeclaredMethods()) {
			cs.methods.add(new MethodSig(
				m.getName(),
				Type.getMethodDescriptor(m),
				m.getModifiers()
			));
		}

		for (Constructor<?> ctor : c.getDeclaredConstructors()) {
			cs.constructors.add(new MethodSig(
				"<init>",
				Type.getConstructorDescriptor(ctor),
				ctor.getModifiers()
			));
		}

		return cs;
	}

	/* =========================
	 * Bytecode -> Structure
	 * ========================= */

	private static ClassStructure fromBytecode(byte[] bytes) {
		ClassStructure cs = new ClassStructure();

		ClassReader cr = new ClassReader(bytes);
		cr.accept(new ClassVisitor(Opcodes.ASM9) {

			@Override
			public void visit(int version, int access, String name,
			                  String signature, String superName, String[] interfaces) {
				cs.className = name.replace('/', '.');
			}

			@Override
			public FieldVisitor visitField(int access, String name,
			                               String desc, String signature, Object value) {
				cs.fields.add(new FieldSig(name, desc, access));
				return null;
			}

			@Override
			public MethodVisitor visitMethod(int access, String name,
			                                 String desc, String signature, String[] exceptions) {
				MethodSig sig = new MethodSig(name, desc, access);
				if ("<init>".equals(name)) {
					cs.constructors.add(sig);
				} else {
					cs.methods.add(sig);
				}
				return null;
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

		return cs;
	}

	/* =========================
	 * Diff
	 * ========================= */

	private static ClassDiff diff(ClassStructure oldC, ClassStructure newC) {
		ClassDiff d = new ClassDiff();

		diffSet(oldC.fields, newC.fields, d.addedFields, d.removedFields);
		diffSet(oldC.methods, newC.methods, d.addedMethods, d.removedMethods);
		diffSet(oldC.constructors, newC.constructors, d.addedConstructors, d.removedConstructors);

		return d;
	}

	private static <T> void diffSet(Set<T> oldSet, Set<T> newSet,
	                                Set<T> added, Set<T> removed) {
		for (T n : newSet) {
			if (!oldSet.contains(n)) {
				added.add(n);
			}
		}
		for (T o : oldSet) {
			if (!newSet.contains(o)) {
				removed.add(o);
			}
		}
	}

	/* =========================
	 * 可选：日志友好输出
	 * ========================= */

	static void logDiff(String className, ClassDiff diff) {
		if (!diff.hasStructuralChange()) return;

		System.out.println("[NIPX] [DIFF] " + className);

		diff.addedFields.forEach(f ->
			System.out.println("  + field: " + f.name() + " " + f.desc())
		);
		diff.removedFields.forEach(f ->
			System.out.println("  - field: " + f.name() + " " + f.desc())
		);

		diff.addedMethods.forEach(m ->
			System.out.println("  + method: " + m.name() + m.desc())
		);
		diff.removedMethods.forEach(m ->
			System.out.println("  - method: " + m.name() + m.desc())
		);

		diff.addedConstructors.forEach(c ->
			System.out.println("  + ctor: " + c.desc())
		);
		diff.removedConstructors.forEach(c ->
			System.out.println("  - ctor: " + c.desc())
		);
	}
}
