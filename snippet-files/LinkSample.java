class FieldUtils {
    public static Object get(Class<?> clazz, String fieldName, Object obj) {
        // reflection logic
        return null;
    }
    public static void set(Class<?> clazz, String fieldName, Object obj, Object value) {
        // reflection logic
    }
}
class MyClass {
    /**
     * {@link Reflect#get(Class, String, Object) GETTER}
     * {@link Reflect#set(Class, String, Object, Object) SETTER}
     */
    @LinkFieldToMethod
    int linkedValue; // Accesses Reflect.get/set(MyClass.class, "linkedValue", this, ...)

    /**
     * {@link Reflect#get(Class, String, Object) GETTER}
     * {@link Reflect#set(Class, String, Object, Object) SETTER}
     */
    @LinkFieldToMethod(clazz = AnotherClass.class, fieldName = "specificField")
    int anotherLinkedValue; // Accesses Reflect.get/set(AnotherClass.class, "specificField", this, ...)

    /**
     * {@link Reflect#get(Class, String, Object) GETTER}
     * {@link Reflect#set(Class, String, Object, Object) SETTER}
     */
    @LinkFieldToMethod
    static int staticLinkedValue; // Accesses Reflect.get/set(MyClass.class, "staticLinkedValue", null, ...)
}