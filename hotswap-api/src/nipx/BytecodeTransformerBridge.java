package nipx;

/**
 * 字节码重织入桥接接口（用于 Mixin、AspectJ、自定义字节码增强器在热重载时重新织入）。
 *
 * <p>在类对齐完成后、进入布局安全门 (LayoutGate) 与重定义 (redefineClasses) 之前介入，
 * 将源码级编译产物转换为带有增强钩子（如 Mixin @Inject/@Unique）的运行时字节码。</p>
 */
@FunctionalInterface
public interface BytecodeTransformerBridge {

	/**
	 * 对新编译/对齐后的字节码执行重新织入。
	 *
	 * @param loader       目标类的 ClassLoader
	 * @param dotClassName 点分隔类名（如 "com.example.Foo"）
	 * @param targetClass  已加载的目标 Class 对象（如果是全新类则为 null）
	 * @param bytecode     进入此桥接器前的字节码
	 * @return 重新织入后的字节码；若未作修改可直接返回传入的 bytecode
	 */
	byte[] transform(ClassLoader loader, String dotClassName, Class<?> targetClass, byte[] bytecode);
}
