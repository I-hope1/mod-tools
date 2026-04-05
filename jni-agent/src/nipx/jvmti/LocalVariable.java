package nipx.jvmti;

/**
 * A single local variable (or parameter) captured from a stack frame.
 * Primitive values are boxed; reference values are the live Java objects.
 * {@code value} is {@code null} when the slot is out of scope, the frame is
 * compiled without debug info, or the read otherwise failed.
 */
public record LocalVariable(
        String name,
        /* JVM type descriptor, e.g. "I", "J", "Ljava/lang/String;", "[B" */
        String typeSignature,
        int    slot,
        Object value
) {
    /** Returns {@code true} for object/array types (L or [). */
    public boolean isReference() {
        if (typeSignature == null || typeSignature.isEmpty()) return false;
        char c = typeSignature.charAt(0);
        return c == 'L' || c == '[';
    }

    /** Returns a human-readable type name. */
    public String typeName() {
        if (typeSignature == null) return "?";
        return switch (typeSignature) {
            case "Z" -> "boolean";
            case "B" -> "byte";
            case "C" -> "char";
            case "S" -> "short";
            case "I" -> "int";
            case "J" -> "long";
            case "F" -> "float";
            case "D" -> "double";
            default  -> {
                if (typeSignature.startsWith("L") && typeSignature.endsWith(";"))
                    yield typeSignature.substring(1, typeSignature.length() - 1).replace('/', '.');
                yield typeSignature;
            }
        };
    }

    @Override
    public String toString() {
        return typeName() + " " + name + " = " + value + "  [slot=" + slot + "]";
    }
}