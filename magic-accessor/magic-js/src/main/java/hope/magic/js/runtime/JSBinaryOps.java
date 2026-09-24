package hope.magic.js.runtime;

import java.lang.invoke.*;

@SuppressWarnings({"unused", "RedundantSuppression"})
public class JSBinaryOps {

	public static CallSite bootstrapBinaryOp(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String op
	) {
		MethodHandle mh = findSpecializedBinaryOp(op, type);
		if (mh != null) {
			return new ConstantCallSite(mh.asType(type));
		}
		mh = switch (op) {
			case "+" -> OpMH.ADD;
			case "-" -> OpMH.SUB;
			case "*" -> OpMH.MUL;
			case "/" -> OpMH.DIV;
			case "%" -> OpMH.MOD;
			case "==" -> OpMH.EQ;
			case "===" -> OpMH.STRICT_EQ;
			case "!=" -> OpMH.NE;
			case "!==" -> OpMH.STRICT_NE;
			case "<" -> OpMH.LT;
			case "<=" -> OpMH.LTE;
			case ">" -> OpMH.GT;
			case ">=" -> OpMH.GTE;
			case "&&" -> OpMH.AND;
			case "||" -> OpMH.OR;
			case "&" -> OpMH.BIT_AND;
			case "|" -> OpMH.BIT_OR;
			case "^" -> OpMH.BIT_XOR;
			case "<<" -> OpMH.SHL;
			case ">>" -> OpMH.SHR;
			case ">>>" -> OpMH.USHR;
			default -> throw new IllegalArgumentException("Unknown binary operator: " + op);
		};
		return new ConstantCallSite(mh.asType(type));
	}

	public static MethodHandle findSpecializedBinaryOp(String op, MethodType type) {
		if (type.parameterCount() != 2) return null;
		Class<?> p0  = type.parameterType(0);
		Class<?> p1  = type.parameterType(1);
		Class<?> ret = type.returnType();

		if ("+".equals(op)) {
			if (p0 == double.class && p1 == double.class && ret == double.class) return OpMH.ADD_DD_D;
			if (p0 == int.class && p1 == int.class && ret == int.class) return OpMH.ADD_II_I;
			if (p0 == int.class && p1 == double.class && ret == double.class) return OpMH.ADD_ID_D;
			if (p0 == double.class && p1 == int.class && ret == double.class) return OpMH.ADD_DI_D;
			if (p0 == Object.class && p1 == double.class) return OpMH.ADD_OD_O;
			if (p0 == double.class && p1 == Object.class) return OpMH.ADD_DO_O;
			if (p0 == Object.class && p1 == int.class) return OpMH.ADD_OI_O;
			if (p0 == int.class && p1 == Object.class) return OpMH.ADD_IO_O;
			if (p0 == String.class && p1 == String.class) return OpMH.ADD_SS_S;
			if (p0 == String.class && p1 == Object.class) return OpMH.ADD_SO_S;
			if (p0 == Object.class && p1 == String.class) return OpMH.ADD_OS_S;
		} else if ("-".equals(op)) {
			if (p0 == double.class && p1 == double.class && ret == double.class) return OpMH.SUB_DD_D;
			if (p0 == int.class && p1 == int.class && ret == int.class) return OpMH.SUB_II_I;
			if (p0 == int.class && p1 == double.class && ret == double.class) return OpMH.SUB_ID_D;
			if (p0 == double.class && p1 == int.class && ret == double.class) return OpMH.SUB_DI_D;
			if (p0 == Object.class && p1 == double.class) return OpMH.SUB_OD_D;
			if (p0 == double.class && p1 == Object.class) return OpMH.SUB_DO_D;
		} else if ("*".equals(op)) {
			if (p0 == double.class && p1 == double.class && ret == double.class) return OpMH.MUL_DD_D;
			if (p0 == int.class && p1 == int.class && ret == int.class) return OpMH.MUL_II_I;
			if (p0 == int.class && p1 == double.class && ret == double.class) return OpMH.MUL_ID_D;
			if (p0 == double.class && p1 == int.class && ret == double.class) return OpMH.MUL_DI_D;
			if (p0 == Object.class && p1 == double.class) return OpMH.MUL_OD_D;
			if (p0 == double.class && p1 == Object.class) return OpMH.MUL_DO_D;
		} else if ("/".equals(op)) {
			if (p0 == double.class && p1 == double.class && ret == double.class) return OpMH.DIV_DD_D;
			if (p0 == int.class && p1 == int.class) return OpMH.DIV_II_D;
			if (p0 == int.class && p1 == double.class && ret == double.class) return OpMH.DIV_ID_D;
			if (p0 == double.class && p1 == int.class && ret == double.class) return OpMH.DIV_DI_D;
			if (p0 == Object.class && p1 == double.class) return OpMH.DIV_OD_D;
			if (p0 == double.class && p1 == Object.class) return OpMH.DIV_DO_D;
		} else if ("%".equals(op)) {
			if (p0 == double.class && p1 == double.class && ret == double.class) return OpMH.MOD_DD_D;
			if (p0 == int.class && p1 == int.class && ret == int.class) return OpMH.MOD_II_I;
			if (p0 == int.class && p1 == double.class && ret == double.class) return OpMH.MOD_ID_D;
			if (p0 == double.class && p1 == int.class && ret == double.class) return OpMH.MOD_DI_D;
			if (p0 == Object.class && p1 == double.class) return OpMH.MOD_OD_D;
			if (p0 == double.class && p1 == Object.class) return OpMH.MOD_DO_D;
		} else if ("==".equals(op)) {
			if (p0 == Object.class && p1 == int.class) return OpMH.EQ_OI_Z;
			if (p0 == Object.class && p1 == double.class) return OpMH.EQ_OD_Z;
			if (p0 == Object.class && p1 == boolean.class) return OpMH.EQ_OB_Z;
			if (p0 == Object.class && p1 == String.class) return OpMH.EQ_OS_Z;
		} else if ("===".equals(op)) {
			if (p0 == Object.class && p1 == int.class) return OpMH.STRICT_EQ_OI_Z;
			if (p0 == Object.class && p1 == double.class) return OpMH.STRICT_EQ_OD_Z;
			if (p0 == Object.class && p1 == boolean.class) return OpMH.STRICT_EQ_OB_Z;
			if (p0 == Object.class && p1 == String.class) return OpMH.STRICT_EQ_OS_Z;
		}
		return null;
	}
}
