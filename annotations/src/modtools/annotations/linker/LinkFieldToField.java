package modtools.annotations.linker;

import java.lang.annotation.*;

/**
 * <p>Indicates that accessing this field should be redirected to another field specified by a {@code @see} javadoc tag.</p>
 * <p>Behavior:</p>
 * <ul>
 *   <li>If the target field is in a superclass and accessible: direct access.</li>
 *   <li>If the target field is in a different class: it must be static and accessible for direct access.</li>
 *   <li>If the target field is private or otherwise not directly accessible: uses reflection (e.g., HopeReflect) to access it.</li>
 * </ul>
 *
 * <p>Example:</p>
 * <pre>{@code
 *  class Parent {
 *     public int publicField;
 *     private int privateField;
 *  }
 *
 *  class Util {
 *		public static int staticField;
 *  }
 *
 *  class Child extends Parent {
 *     /** @see Parent#publicField *\/
 *     @LinkFieldToField
 *     int linkedPublicField; // Accesses this.publicField or super.publicField
 *
 *     /** @see Parent#privateField *\/
 *     @LinkFieldToField
 *     int linkedPrivateField; // Accesses Parent.privateField via reflection
 *
 *     /** @see Util#staticField *\/
 *     @LinkFieldToField
 *     int linkedStaticField; // Accesses Util.staticField
 * }}</pre>
 * @see modtools.annotations.processors.LinkProcessor
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface LinkFieldToField {
}