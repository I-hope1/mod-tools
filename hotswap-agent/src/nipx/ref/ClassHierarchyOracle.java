package nipx.ref;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.util.List;
import java.util.Optional;

/**
 * 离线类层级与元数据查询契约（{@code docs/initfix/01-safety-gate.md} §5）。
 *
 * <p>供 {@link InitFix} 在补丁分析与切片提取期使用，全程基于字节码抽象查询，<b>严禁触发类加载或 {@link Class#forName}</b>。
 * 所有类名均使用 JVM 内部名（Internal form，如 {@code java/lang/String}）。</p>
 *
 * <h2>核心能力与失败语义契约</h2>
 * <ul>
 *   <li><b>层级与接口查询</b>（{@link #isAssignableFrom}、{@link #isInterface}）：无法解析时返回 {@code false}。</li>
 *   <li><b>类修饰符查询</b>（{@link #getClassModifiers}）：元数据不可用时抛出 {@link IllegalArgumentException}。</li>
 *   <li><b>成员修饰符与解析</b>（{@link #getFieldModifiers}、{@link #getMethodModifiers}、{@link #resolveMember}）：
 *       未找到或解析失败时返回 {@link Optional#empty()}，调用方按"无法证明"保守处理（拒绝放行）。</li>
 *   <li><b>Nest 成员与写入证明</b>（{@link #getNestMembers}、{@link #getNestFieldWrites}）：
 *       若任一声明的 Nest 成员字节码无法读取，抛出 {@link IllegalStateException}，由调用方捕获并记 warning，
 *       进而拒绝全 Nest 不可变性写入证明（条件 B）。</li>
 * </ul>
 *
 * @see HierarchyTreeOracle
 * @see InitFix
 */
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
