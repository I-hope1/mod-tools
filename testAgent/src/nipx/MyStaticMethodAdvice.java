package nipx;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;

import java.lang.reflect.Method;

// MyStaticMethodAdvice.java (定义新的方法逻辑，与之前相同)
// 这个类也需要打包到 YourAgent.jar 中
public class MyStaticMethodAdvice {
    @Advice.OnMethodEnter
    public static Object onEnter(@Advice.Origin Method method) {
        System.out.println(">>> MODIFIED STATIC METHOD: " + method.getName() + " from " + method.getDeclaringClass().getTypeName() + " by MOD! <<<");
        // 返回 null 表示原方法继续执行 (如果它不是 void，并且你没有在 OnExit 拦截返回值)
        // 返回 Advice.OnNonDefaultValue.TRUE; 可以跳过原方法，但需要根据原方法返回类型提供兼容值
        return null;
    }
}