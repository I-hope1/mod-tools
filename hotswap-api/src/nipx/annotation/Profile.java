package nipx.annotation;

import java.lang.annotation.*;

/**
 * 方法性能分析追踪注解。
 *
 * <p>标记在方法上时，{@link nipx.AnnotationTransformer} 会在类加载或转换阶段对目标方法进行字节码插桩，
 * 注入时间探测点，统计方法调用耗时及执行指标，供 profiler 诊断工具使用。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Profile {}