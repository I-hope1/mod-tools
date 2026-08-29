package hope.magic.compiler;

import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import org.objectweb.asm.Opcodes;

public class TypeUtils {

	public static int returnOpcode(Type returnType) {
		return switch (returnType.getKind()) {
			case BOOLEAN, INT, CHAR, BYTE, SHORT -> Opcodes.IRETURN;
			case FLOAT -> Opcodes.FRETURN;
			case LONG -> Opcodes.LRETURN;
			case DOUBLE -> Opcodes.DRETURN;
			case VOID -> Opcodes.RETURN;
			default -> Opcodes.ARETURN;
		};
	}

	public static int loadOpcode(TypeSymbol type) {
		return switch (type.getQualifiedName().toString()) {
			case "boolean", "int", "char", "byte", "short" -> Opcodes.ILOAD;
			case "float" -> Opcodes.FLOAD;
			case "long" -> Opcodes.LLOAD;
			case "double" -> Opcodes.DLOAD;
			default -> Opcodes.ALOAD;
		};
	}

	public static String dotToSlash(Type type) {
		if (type instanceof ArrayType arrayType) {
			return typeToDescriptor(arrayType);
		}
		String s = type.tsym.flatName().toString();
		return switch (s) {
			case "boolean" -> "Z";
			case "byte" -> "B";
			case "char" -> "C";
			case "short" -> "S";
			case "int" -> "I";
			case "long" -> "J";
			case "float" -> "F";
			case "double" -> "D";
			case "void" -> "V";
			default -> s.replace('.', '/');
		};
	}

	public static String typeToDescriptor(Type type) {
		if (type instanceof ArrayType arrayType) {
			int depth = 1;
			while (arrayType.elemtype instanceof ArrayType) {
				arrayType = (ArrayType) arrayType.elemtype;
				depth++;
			}
			return "[".repeat(depth) + typeToDescriptor(arrayType.elemtype);
		}
		String s = type.tsym.flatName().toString();
		return switch (s) {
			case "boolean" -> "Z";
			case "byte" -> "B";
			case "char" -> "C";
			case "short" -> "S";
			case "int" -> "I";
			case "long" -> "J";
			case "float" -> "F";
			case "double" -> "D";
			case "void" -> "V";
			default -> "L" + s.replace('.', '/') + ";";
		};
	}

	public static short typeSize(TypeSymbol typeSymbol, Symtab symtab) {
		Type type = typeSymbol.type;
		if (type == symtab.voidType) return 0;
		if (type == symtab.longType || type == symtab.doubleType) return 2;
		return 1;
	}

	public static String unsafeGetterMethodName(Type type) {
		return switch (type.getKind()) {
			case BOOLEAN -> "getBoolean";
			case BYTE -> "getByte";
			case CHAR -> "getChar";
			case SHORT -> "getShort";
			case INT -> "getInt";
			case LONG -> "getLong";
			case FLOAT -> "getFloat";
			case DOUBLE -> "getDouble";
			default -> "getObject";
		};
	}

	public static String unsafeGetterMethodDesc(Type type) {
		return switch (type.getKind()) {
			case BOOLEAN -> "(Ljava/lang/Object;J)Z";
			case BYTE -> "(Ljava/lang/Object;J)B";
			case CHAR -> "(Ljava/lang/Object;J)C";
			case SHORT -> "(Ljava/lang/Object;J)S";
			case INT -> "(Ljava/lang/Object;J)I";
			case LONG -> "(Ljava/lang/Object;J)J";
			case FLOAT -> "(Ljava/lang/Object;J)F";
			case DOUBLE -> "(Ljava/lang/Object;J)D";
			default -> "(Ljava/lang/Object;J)Ljava/lang/Object;";
		};
	}

	public static String unsafeSetterMethodName(Type type) {
		return switch (type.getKind()) {
			case BOOLEAN -> "putBoolean";
			case BYTE -> "putByte";
			case CHAR -> "putChar";
			case SHORT -> "putShort";
			case INT -> "putInt";
			case LONG -> "putLong";
			case FLOAT -> "putFloat";
			case DOUBLE -> "putDouble";
			default -> "putObject";
		};
	}

	public static String unsafeSetterMethodDesc(Type type) {
		return switch (type.getKind()) {
			case BOOLEAN -> "(Ljava/lang/Object;JZ)V";
			case BYTE -> "(Ljava/lang/Object;JB)V";
			case CHAR -> "(Ljava/lang/Object;JC)V";
			case SHORT -> "(Ljava/lang/Object;JS)V";
			case INT -> "(Ljava/lang/Object;JI)V";
			case LONG -> "(Ljava/lang/Object;JJ)V";
			case FLOAT -> "(Ljava/lang/Object;JF)V";
			case DOUBLE -> "(Ljava/lang/Object;JD)V";
			default -> "(Ljava/lang/Object;JLjava/lang/Object;)V";
		};
	}
}
