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
}
