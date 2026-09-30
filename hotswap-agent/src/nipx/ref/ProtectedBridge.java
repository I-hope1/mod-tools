package nipx.ref;

import nipx.Reflect;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.*;

/**
 * 让补丁类通过 {@code invokedynamic} 以宿主视角访问字段/方法，绕过 hidden class
 * 不是宿主子类导致的 protected receiver check（JVMS §5.4.4）。
 *
 * <p>BSM 通过 {@code privateLookupIn(host, patchLookup)} 拿到宿主的特权 Lookup。
 * <b>host 由 {@code bsmArgs} 显式传入</b>，不能用 {@code patchLookup.lookupClass().getNestHost()}——
 * 当宿主是嵌套类时，{@code getNestHost()} 返回的是最外层类，而非真正继承目标父类的那个类。
 * 例如 {@code OuterService.InnerWorker extends BaseWorker}：
 * {@code getNestHost()} 返回 {@code OuterService}，用它的 Lookup 去
 * {@code findVirtual(BaseWorker, "doWork", ...)} 会因 {@code OuterService} 不是
 * {@code BaseWorker} 的子类而抛 {@code IllegalAccessException}。显式传入 host 才能锁定
 * 真正持有继承权的类。</p>
 *
 * <p><b>字段类型从 {@code indyType} 派生</b>，不作为 {@code bsmArgs} 传递。
 * 基本类型（如 {@code int}）在 class 文件常量池里没有字面量表示，ASM 会把
 * {@code Type.INT_TYPE} 编码为 {@code CONSTANT_Class_info "I"}，JVM 链接 indy 时会尝试
 * 加载名为 {@code I} 的类并抛 {@code NoClassDefFoundError}。{@code MethodType}
 * 原生支持基本类型，没有任何解析风险。</p>
 */
public final class ProtectedBridge {

	private ProtectedBridge() { }

	/**
	 * @param patchLookup hidden class 的 Lookup
	 * @param name        成员名
	 * @param indyType    调用点类型
	 * @param opcode      ASM 操作码：GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC/
	 *                    INVOKEVIRTUAL/INVOKEINTERFACE/INVOKESTATIC
	 * @param owner       成员声明类（或接收者的静态类型）
	 * @param host        宿主类；经 {@code privateLookupIn} 拿到特权 Lookup
	 */
	public static CallSite bootstrap(MethodHandles.Lookup patchLookup,
	                                 String name,
	                                 MethodType indyType,
	                                 int opcode,
	                                 Class<?> owner,
	                                 Class<?> host) throws Throwable {

		// host 是真正的目标类（可能是嵌套类），而不是 getNestHost() 返回的最外层类。
		// hidden class 与 host 在同一 nest，privateLookupIn 可以成功。
		MethodHandles.Lookup hostLookup = MethodHandles.privateLookupIn(host, Reflect.IMPL_LOOKUP);

		MethodHandle target = switch (opcode) {
			case Opcodes.GETFIELD -> {
				// indyType = (Lowner;)LfieldType;
				Class<?> fieldType = indyType.returnType();
				yield hostLookup.findGetter(owner, name, fieldType);
			}
			case Opcodes.PUTFIELD -> {
				// indyType = (Lowner;LfieldType;)V
				Class<?> fieldType = indyType.parameterType(1);
				yield hostLookup.findSetter(owner, name, fieldType);
			}
			case Opcodes.GETSTATIC -> {
				// indyType = ()LfieldType;
				Class<?> fieldType = indyType.returnType();
				yield hostLookup.findStaticGetter(owner, name, fieldType);
			}
			case Opcodes.PUTSTATIC -> {
				// indyType = (LfieldType;)V
				Class<?> fieldType = indyType.parameterType(0);
				yield hostLookup.findStaticSetter(owner, name, fieldType);
			}

			// indyType = (Lowner;args...)ret；findVirtual 需要 (args...)ret
			case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> {
				MethodType mt = indyType.dropParameterTypes(0, 1);
				yield hostLookup.findVirtual(owner, name, mt);
			}

			// 静态方法：indyType 就是 (args...)ret
			case Opcodes.INVOKESTATIC -> hostLookup.findStatic(owner, name, indyType);

			default -> throw new BootstrapMethodError(
			 "ProtectedBridge: unsupported opcode " + opcode);
		};

		return new ConstantCallSite(target);
	}
}
