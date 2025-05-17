package modtools.annotations.linker;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that this method's body should be replaced by a call
 * to another method specified by a {@code @see} or {@code @link} Javadoc tag
 * with the key "METHOD".
 * <p>
 * Example:
 * <pre>{@code
 * class SourceClass {
 *     /**
 *      * Links to {@link TargetClass#actualMethod(int, String) METHOD}
 *      * /
 *     @LinkMethod
 *     public native String linkedMethod(int a, String b); // Body will be replaced
 * }
 *
 * class TargetClass {
 *     public static String actualMethod(int x, String y) {
 *         return y + x;
 *     }
 * }
 * }</pre>
 * The {@code linkedMethod} will effectively become:
 * <pre>{@code
 * public String linkedMethod(int a, String b) {
 *     return TargetClass.actualMethod(a, b);
 * }
 * }</pre>
 * <p>
 * Constraints:
 * <ul>
 *     <li>The Javadoc tag must be of the form {@code {@link package.Class#method(paramTypes) METHOD}} or {@code @see package.Class#method(paramTypes) METHOD}.</li>
 *     <li>The target method must be public.</li>
 *     <li>The source method's signature (parameter types and return type) must strictly match the target method's signature.</li>
 *     <li>If the target method is non-static:
 *         <ul>
 *             <li>The source method must also be non-static.</li>
 *             <li>The source method's enclosing class must be the same as or a subclass/implementor of the target method's enclosing class (for 'this' to be a valid instance).</li>
 *         </ul>
 *     </li>
 *     <li>If the target method is static, the source method can be static or non-static.</li>
 *     <li>The source method's body will be entirely replaced. If it was 'native' or 'abstract', these modifiers will be removed.</li>
 * </ul>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface LinkMethod {
	class MTemp {
		public static final String METHOD = "METHOD";
		public static Class<?> declaring;
		public static Object   obj;
		public static String methodName;
		public static Class<?> params[] = new Class<?>[0];
		public static Object args[] = new Object[0];
	}
}