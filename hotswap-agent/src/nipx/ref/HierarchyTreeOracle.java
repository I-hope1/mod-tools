package nipx.ref;

import nipx.AnnotationTransformer;
import nipx.HotSwapAgent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.*;

/** ClassHierarchyOracle backed exclusively by bytecode metadata and the agent cache. */
public final class HierarchyTreeOracle implements ClassHierarchyOracle {
	private final WeakReference<ClassLoader> loader;
	private final ClassNode                  preferredClass;
	private final Map<String, ClassNode>     resolved     = new HashMap<>();
	private final Map<String, ClassNode>     nestResolved = new HashMap<>();

	public HierarchyTreeOracle(ClassLoader loader) {
		this(loader, null);
	}

	public HierarchyTreeOracle(ClassLoader loader, ClassNode preferredClass) {
		this.loader = new WeakReference<>(loader);
		this.preferredClass = preferredClass;
	}

	@Override
	public boolean isAssignableFrom(String superType, String subType) {
		return AnnotationTransformer.HierarchyTree.isAssignableFrom(superType, subType, loader.get());
	}

	@Override
	public boolean isInterface(String className) {
		if (preferredClass != null && preferredClass.name.equals(className)) {
			return (preferredClass.access & Opcodes.ACC_INTERFACE) != 0;
		}
		return AnnotationTransformer.HierarchyTree.isInterface(className, loader.get());
	}

	@Override
	public int getClassModifiers(String className) {
		ClassNode node = resolve(className);
		if (node == null) throw new IllegalArgumentException("class metadata unavailable: " + className);
		return node.access;
	}

	@Override
	public Optional<Integer> getFieldModifiers(String className, String fieldName, String desc) {
		return findField(className, className, fieldName, desc, new HashSet<>()).map(member -> member.access);
	}

	@Override
	public Optional<Integer> getMethodModifiers(String className, String methodName, String desc) {
		return resolveMember(className, methodName, desc, false).map(member -> member.access);
	}

	@Override
	public Optional<MemberRef> resolveMember(String className, String name, String desc, boolean isField) {
		if (isField) return findField(className, className, name, desc, new HashSet<>());
		if ("<init>".equals(name)) return declaredMethod(className, name, desc);

		Set<String> classChain = new LinkedHashSet<>();
		String      current    = className;
		while (current != null && classChain.add(current)) {
			ClassNode node = resolve(current);
			if (node == null) return Optional.empty();
			Optional<MemberRef> declared = declaredMethod(current, name, desc);
			if (declared.isPresent() && visibleFromLookup(className, current, declared.get().access)) return declared;
			current = node.superName;
		}

		Set<String> visitedInterfaces = new HashSet<>();
		for (String classNameInChain : classChain) {
			ClassNode node = resolve(classNameInChain);
			if (node == null || node.interfaces == null) continue;
			for (String itf : node.interfaces) {
				Optional<MemberRef> found = findInterfaceMethod(className, itf, name, desc, visitedInterfaces);
				if (found.isPresent()) return found;
			}
		}
		return Optional.empty();
	}

	private Optional<MemberRef> findField(String lookupType, String currentType, String name, String desc,
	                                      Set<String> visited) {
		if (!visited.add(currentType)) return Optional.empty();
		ClassNode node = resolve(currentType);
		if (node == null) return Optional.empty();
		for (FieldNode field : node.fields) {
			if (field.name.equals(name) && field.desc.equals(desc)
			    && visibleFromLookup(lookupType, currentType, field.access)) {
				return Optional.of(new MemberRef(currentType, field.access));
			}
		}
		if (node.interfaces != null) {
			for (String itf : node.interfaces) {
				Optional<MemberRef> found = findField(lookupType, itf, name, desc, visited);
				if (found.isPresent()) return found;
			}
		}
		return node.superName == null
		 ? Optional.empty()
		 : findField(lookupType, node.superName, name, desc, visited);
	}

	private Optional<MemberRef> findInterfaceMethod(String lookupType, String interfaceType, String name, String desc,
	                                                Set<String> visited) {
		if (!visited.add(interfaceType)) return Optional.empty();
		ClassNode node = resolve(interfaceType);
		if (node == null) return Optional.empty();
		Optional<MemberRef> declared = declaredMethod(interfaceType, name, desc);
		if (declared.isPresent() && visibleFromLookup(lookupType, interfaceType, declared.get().access)) return declared;
		if (node.interfaces != null) {
			for (String parent : node.interfaces) {
				Optional<MemberRef> found = findInterfaceMethod(lookupType, parent, name, desc, visited);
				if (found.isPresent()) return found;
			}
		}
		return Optional.empty();
	}

	private Optional<MemberRef> declaredMethod(String className, String name, String desc) {
		ClassNode node = resolve(className);
		if (node == null) return Optional.empty();
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) {
				return Optional.of(new MemberRef(className, method.access));
			}
		}
		return Optional.empty();
	}

	private boolean visibleFromLookup(String lookupType, String declaringType, int access) {
		if (lookupType.equals(declaringType)) return true;
		if ((access & Opcodes.ACC_PRIVATE) != 0) return false;
		if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) return true;
		return packageName(lookupType).equals(packageName(declaringType));
	}

	private static String packageName(String internalName) {
		int separator = internalName.lastIndexOf('/');
		return separator < 0 ? "" : internalName.substring(0, separator);
	}

	@Override
	public List<ClassNode> getNestMembers(String className) {
		ClassNode member = resolveNestNode(className);
		if (member == null) throw new IllegalStateException("nest class metadata unavailable: " + className);
		String    hostName = member.nestHostClass == null ? member.name : member.nestHostClass;
		ClassNode host     = resolveNestNode(hostName);
		if (host == null) throw new IllegalStateException("nest host metadata unavailable: " + hostName);

		Map<String, ClassNode> members = new LinkedHashMap<>();
		members.put(host.name, host);
		if (host.nestMembers != null) {
			for (String nestMember : host.nestMembers) {
				ClassNode node = resolveNestNode(nestMember);
				if (node == null) {
					throw new IllegalStateException("nest member metadata unavailable: " + nestMember);
				}
				members.put(nestMember, node);
			}
		}
		if (!members.containsKey(member.name)) members.put(member.name, member);
		return new ArrayList<>(members.values());
	}

	@Override
	public List<NestFieldWrite> getNestFieldWrites(String className) {
		List<ClassNode>      members = getNestMembers(className);
		List<NestFieldWrite> writes  = new ArrayList<>();
		for (ClassNode member : members) {
			if (member == preferredClass) {
				for (MethodNode method : member.methods) {
					for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
						if (insn instanceof FieldInsnNode field && isPut(field.getOpcode())) {
							writes.add(new NestFieldWrite(member.name, method.name, method.desc, field));
						}
					}
				}
			} else {
				byte[] bytes = readClassBytes(member.name);
				if (bytes == null) throw new IllegalStateException("nest member bytecode unavailable: " + member.name);
				try {
					new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
						@Override
						public MethodVisitor visitMethod(int access, String methodName, String methodDesc,
						                                 String signature, String[] exceptions) {
							return new MethodVisitor(Opcodes.ASM9) {
								@Override
								public void visitFieldInsn(int opcode, String owner, String name, String desc) {
									if (isPut(opcode)) {
										writes.add(new NestFieldWrite(member.name, methodName, methodDesc,
										 new FieldInsnNode(opcode, owner, name, desc)));
									}
								}
							};
						}
					}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				} catch (Throwable failure) {
					throw new IllegalStateException("cannot scan nest member bytecode: " + member.name, failure);
				}
			}
		}
		return writes;
	}

	private static boolean isPut(int opcode) {
		return opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
	}

	private ClassNode resolveNestNode(String internalName) {
		if (preferredClass != null && preferredClass.name.equals(internalName)) return preferredClass;
		ClassNode known = nestResolved.get(internalName);
		if (known != null) return known;
		byte[] bytes = readClassBytes(internalName);
		if (bytes == null) return null;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node,
			 ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			nestResolved.put(internalName, node);
			return node;
		} catch (Throwable ignored) {
			return null;
		}
	}

	private ClassNode resolve(String internalName) {
		if (preferredClass != null && preferredClass.name.equals(internalName)) return preferredClass;
		ClassNode known = resolved.get(internalName);
		if (known != null) return known;
		byte[] bytes = readClassBytes(internalName);
		if (bytes == null) return null;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node,
			 ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			resolved.put(internalName, node);
			return node;
		} catch (Throwable ignored) {
			return null;
		}
	}

	private byte[] readClassBytes(String internalName) {
		byte[] cached = HotSwapAgent.bytecodeCache.get(internalName.replace('/', '.'));
		if (cached != null) return cached;
		ClassLoader targetLoader = loader.get();
		ClassLoader sourceLoader = targetLoader == null ? ClassLoader.getSystemClassLoader() : targetLoader;
		if (sourceLoader == null) return null;
		try (InputStream in = sourceLoader.getResourceAsStream(internalName + ".class")) {
			return in == null ? null : in.readAllBytes();
		} catch (Throwable ignored) {
			return null;
		}
	}
}
