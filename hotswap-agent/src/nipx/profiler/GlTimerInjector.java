package nipx.profiler;

import arc.graphics.g2d.SpriteBatch;
import mindustry.core.Logic;
import nipx.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.*;
import java.security.ProtectionDomain;

/**
 * 把 GL Timer Query 注入点插入到 {@code SpriteBatch.flush()} 和帧循环入口。
 *
 * <ul>
 *   <li>{@code flush()} 入口 → {@link GlTimerProfiler#onFlushEnter(String)}，
 *       传入当前插桩栈顶的方法 key（若无则传空串）</li>
 *   <li>{@code flush()} 出口 → {@link GlTimerProfiler#onFlushExit()}</li>
 *   <li>帧循环入口（{@code Logic.update}）→ {@link GlTimerProfiler#drainResults()}</li>
 * </ul>
 */
public class GlTimerInjector implements ClassFileTransformer {

	public static volatile String batchClass  = "arc/graphics/g2d/SpriteBatch";
	public static volatile String batchMethod = "flush";
	public static volatile String frameClass  = "mindustry/core/Logic";
	public static volatile String frameMethod = "update";

	private static final String PROFILER = "nipx/profiler/GlTimerProfiler";

	@Override
	public byte[] transform(ClassLoader loader, String className,
	                        Class<?> classBeingRedefined,
	                        ProtectionDomain domain, byte[] classfileBuffer) {
		return inject(loader, className, classfileBuffer);
	}
	private static byte[] inject(ClassLoader loader, String className, byte[] classfileBuffer) {
		boolean isBatch = batchClass.equals(className);
		boolean isFrame = frameClass.equals(className);
		if (!isBatch && !isFrame) return null;

		ClassReader cr = new ClassReader(classfileBuffer);
		ClassWriter cw = new AnnotationTransformer.MyClassWriter(cr, loader);

		cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

				if (isBatch && batchMethod.equals(name)) {
					return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
						@Override
						protected void onMethodEnter() {
							// if (this.flushing) → 跳过外层协调调用
							loadThis();
							visitFieldInsn(GETFIELD,
							 "arc/graphics/g2d/SpriteBatch", "idx", "I");
							Label skip = new Label();
							visitJumpInsn(IFEQ, skip);   // idx==0 → 没有 GPU 工作，跳过）

							// 读当前插桩栈顶的 key 作为 flush 归属
							// ProfilerData.currentFlushKey() 是下面新增的静态工具方法
							visitMethodInsn(INVOKESTATIC,
							 "nipx/profiler/ProfilerData", "currentFlushKey",
							 "()Ljava/lang/String;", false);
							visitMethodInsn(INVOKESTATIC,
							 PROFILER, "onFlushEnter",
							 "(Ljava/lang/String;)V", false);

							visitLabel(skip);
						}
						@Override
						protected void onMethodExit(int opcode) {
							if (opcode == ATHROW) return;

							// 对称地只在内层退出时关 query
							// loadThis();
							// visitFieldInsn(GETFIELD,
							//  "arc/graphics/g2d/SpriteBatch", "idx", "I");
							// Label skip = new Label();
							// visitJumpInsn(IFEQ, skip);   // idx==0 → 没有 GPU 工作，跳过

							visitMethodInsn(INVOKESTATIC, PROFILER, "onFlushExit", "()V", false);

							// visitLabel(skip);
						}
					};
				}

				if (isFrame && frameMethod.equals(name)) {
					return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
						@Override
						protected void onMethodEnter() {
							visitMethodInsn(INVOKESTATIC, PROFILER, "drainResults", "()V", false);
						}
					};
				}
				return mv;
			}
		}, ClassReader.EXPAND_FRAMES);

		return cw.toByteArray();
	}

	public void load() {
		HotSwapAgent.info("[GlTimerInjector] Retransforming " + batchClass + " and " + frameClass);
		Injector.redefineOneClass(SpriteBatch.class, inject(SpriteBatch.class.getClassLoader(), batchClass, HotSwapAgent.fetchOriginalBytecode(SpriteBatch.class)));
		Injector.redefineOneClass(Logic.class, inject(Logic.class.getClassLoader(), frameClass, HotSwapAgent.fetchOriginalBytecode(Logic.class)));
	}
}