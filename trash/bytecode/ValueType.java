package modtools.ui.windows.bytecode;

public enum ValueType {
	_object(Object.class, '\0'),
	_int(int.class, 'I'),
	_float(float.class, 'F'),
	_double(double.class, 'D'),
	_short(short.class, 'S'),
	_long(long.class, 'J'),
	_byte(byte.class, 'B'),
	_boolean(boolean.class, 'Z');
	public final Class<?> type;
	public final char byteName;
	ValueType(Class<?> type, char byteName) {
		this.type = type;
		this.byteName = byteName;
	}
	public static ValueType getByClass(Class<?> cl) {
		if (cl == null || !cl.isPrimitive()) return _object;
		return switch (cl.getName().charAt(0)) {
			case 'i' -> _int;
			case 'f' -> _float;
			case 'd' -> _double;
			case 's' -> _short;
			case 'b' -> cl.getName().length() == 4 ? _byte : _boolean;
			case 'l' -> _long;
			default -> throw new IllegalStateException("Unexpected value: " + cl.getName().charAt(0));
		};
	}
}
