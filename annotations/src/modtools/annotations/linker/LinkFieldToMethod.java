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
 * {@snippet src="LinkSample.java" region="hello"}
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