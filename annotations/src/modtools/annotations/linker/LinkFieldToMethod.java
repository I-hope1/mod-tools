package modtools.annotations.linker;

import java.lang.annotation.*;

/**
 * <p>Indicates that accessing this field (read/write) should be redirected to calls to specific getter and setter methods.</p>
 * <p>The getter and setter methods are specified by {@code {@link ... GETTER}} and {@code {@link ... SETTER}} javadoc tags within the field's doc comment.</p>
 * <p>The target getter/setter utility methods must be static.</p>
 * <p>Signature for getter: {@code public static TargetFieldType get(Class<?> targetClass, String targetFieldName, Object instance)}</p>
 * <p>Signature for setter: {@code public static void set(Class<?> targetClass, String targetFieldName, Object instance, SourceFieldType value)}</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * class FieldUtils {
 *     public static Object get(Class<?> clazz, String fieldName, Object obj) {
 *         // reflection logic
 *         return null;
 *     }
 *     public static void set(Class<?> clazz, String fieldName, Object obj, Object value) {
 *         // reflection logic
 *     }
 * }
 *
 * class MyClass {
 *     /**
 *      * {\@link Reflect#get(Class, String, Object) GETTER}
 *      * {\@link Reflect#set(Class, String, Object, Object) SETTER}
 *      *@/
 *     \@LinkFieldToMethod
 *     int linkedValue; // Accesses Reflect.get/set(MyClass.class, "linkedValue", this, ...)
 *
 *     /**
 *      * {\@link Reflect#get(Class, String, Object) GETTER}
 *      * {\@link Reflect#set(Class, String, Object, Object) SETTER}
 *      *\/
 *     \@LinkFieldToMethod(clazz = AnotherClass.class, fieldName = "specificField")
 *     int anotherLinkedValue; // Accesses Reflect.get/set(AnotherClass.class, "specificField", this, ...)
 *
 *     /**
 *      * {\@link Reflect#get(Class, String, Object) GETTER}
 *      * {\@link Reflect#set(Class, String, Object, Object) SETTER}
 *      *\/
 *     \@LinkFieldToMethod
 *     static int staticLinkedValue; // Accesses Reflect.get/set(MyClass.class, "staticLinkedValue", null, ...)
 * }
 * }</pre>
 * @see modtools.annotations.processors.LinkProcessor
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface LinkFieldToMethod {
    /**
     * The class to be passed as the first argument to the getter/setter utility methods.
     * Defaults to the class containing the annotated field.
     */
    Class<?> clazz() default void.class;

    /**
     * The field name to be passed as the second argument to the getter/setter utility methods.
     * Defaults to the name of the annotated field.
     */
    String fieldName() default "";
}