import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 逐项追踪 MethodFingerprinter 对外层 lambda 的 CRC 更新，
 * 找出 v1/v2 之间到底是哪一项在变。
 * 复刻 MethodFingerprinter 的 update 逻辑（同一套 CRC64 算法）。
 */
public class FpProbe extends MethodVisitor {

	static final int MARK_INVOKEDYNAMIC = 0x7F000015;
	static final int MARK_LDC           = 0x7F000010;

	final List<String> trace = new ArrayList<>();
	long  crc;
	String currentClassName;

	public FpProbe(String className) {
		super(Opcodes.ASM9);
		this.currentClassName = className;
		this.crc = nipx.util.CRC64.init();
	}

	private void upd(int v) { crc = nipx.util.CRC64.updateInt(crc, v); }
	private void upd(long v) { crc = nipx.util.CRC64.updateLong(crc, v); }
	private void upd(String s) {
		if (s == null) { upd(-1); return; }
		upd(s.length());
		crc = nipx.util.CRC64.updateStringUTF16(crc, s);
	}

	private boolean isSelfSynthetic(String owner, String name) {
		return owner != null && owner.equals(currentClassName)
		       && name != null && (name.contains("lambda$") || name.contains("$lambda")
		                           || name.contains("$anonfun$") || name.contains("access$"))
		       && !name.startsWith("access$");
	}

	private void handle(Handle h) {
		String shown;
		upd(h.getTag());
		upd(h.getOwner());
		if (isSelfSynthetic(h.getOwner(), h.getName())) { shown = "#SYNTHETIC_METHOD#"; }
		else shown = h.getName();
		upd(shown);
		upd(h.getDesc());
		upd(h.isInterface() ? 1 : 0);
		trace.add("  handle tag=" + h.getTag() + " owner=" + h.getOwner()
			+ " name=" + h.getName() + " -> 计入[" + shown + "] desc=" + h.getDesc());
	}

	private void constant(Object c) {
		if (c instanceof Handle h) { upd(7); handle(h); }
		else if (c instanceof Type t) { upd(6); upd(t.getDescriptor()); }
		else if (c instanceof String s) { upd(5); upd(s); }
		else if (c instanceof Integer i) { upd(1); upd(i); }
		else if (c == null) upd(0);
		else { upd(99); upd(String.valueOf(c)); }
	}

	@Override public void visitInsn(int opcode) { upd(opcode); trace.add("  insn " + opcode); }
	@Override public void visitVarInsn(int opcode, int var) { upd(opcode); upd(var); trace.add("  var " + opcode + " " + var); }
	@Override public void visitTypeInsn(int opcode, String type) { upd(opcode); upd(type); trace.add("  type " + opcode + " " + type); }
	@Override public void visitFieldInsn(int o, String ow, String n, String d) { upd(o); upd(ow); upd(n); upd(d); trace.add("  field " + ow + "." + n); }
	@Override public void visitMethodInsn(int o, String ow, String n, String d, boolean itf) {
		upd(o); upd(ow); upd(isSelfSynthetic(ow, n) ? "#SYNTHETIC_METHOD#" : n); upd(d); upd(itf ? 1 : 0);
		trace.add("  method " + ow + "." + n + " -> 计入[" + (isSelfSynthetic(ow, n) ? "#SYNTHETIC_METHOD#" : n) + "]");
	}
	@Override public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
		upd(MARK_INVOKEDYNAMIC);
		upd(name); upd(desc);
		trace.add("  INDY name=" + name + " desc=" + desc);
		handle(bsm);
		if (bsmArgs == null) upd(0);
		else {
			upd(bsmArgs.length);
			for (Object o : bsmArgs) {
				trace.add("    bsmArg: " + describe(o));
				constant(o);
			}
		}
	}
	@Override public void visitLdcInsn(Object v) { upd(MARK_LDC); constant(v); }
	@Override public void visitJumpInsn(int op, Label l) { upd(op); upd(0x7F000018); upd(0); }
	@Override public void visitLabel(Label l) { }

	static String describe(Object o) {
		if (o instanceof Handle h) return "Handle " + h.getOwner() + "." + h.getName() + h.getDesc();
		if (o instanceof Type t) return "Type " + t.getDescriptor();
		if (o instanceof String s) return "String \"" + s + "\"";
		return String.valueOf(o);
	}

	static void dump(String tag, byte[] bytes, String wantName) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		for (MethodNode mn : cn.methods) {
			if (!mn.name.equals(wantName)) continue;
			FpProbe p = new FpProbe(cn.name);
			mn.accept(p);
			System.out.println("=== " + tag + " : " + mn.name + mn.desc
				+ "  hash=" + Long.toHexString(nipx.util.CRC64.finish(p.crc)) + " ===");
			for (String s : p.trace) System.out.println(s);
			System.out.println();
		}
	}

	public static void main(String[] args) throws Exception {
		byte[] v1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] v2 = Files.readAllBytes(Paths.get(args[1]));
		String m1 = args.length > 2 ? args[2] : "lambda$build$0";
		String m2 = args.length > 3 ? args[3] : m1;
		dump("v1", v1, m1);
		dump("v2", v2, m2);
	}
}
