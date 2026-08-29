package hope.magic.runtime;

/**
 * 编译期占位基类与运行期 MagicAccessorImpl 的直接子类。
 * <p>在运行期，{@link Magic#install()} 会通过 Unsafe 将实际继承自
 * {@code jdk.internal.reflect.MagicAccessorImpl_PUBLIC} 的字节码注入定义为此类，
 * 从而赋予其子类绕过 JVM 访问控制检查的特权。</p>
 */
public class MAGICIMPL {
}
