package nipx;

import nipx.util.CRC64;
import org.objectweb.asm.*;

import java.util.IdentityHashMap;
import java.util.Map;

public final class MethodFingerprinter extends MethodVisitor {

    /* ================= MARKERS ================= */

    private static final int MARK_LDC              = 0x7F000010;
    private static final int MARK_IINC             = 0x7F000011;
    private static final int MARK_TABLESWITCH      = 0x7F000012;
    private static final int MARK_LOOKUPSWITCH     = 0x7F000013;
    private static final int MARK_TRY_CATCH        = 0x7F000014;
    private static final int MARK_INVOKEDYNAMIC    = 0x7F000015;
    private static final int MARK_MULTIANEWARRAY   = 0x7F000016;
    private static final int MARK_LABEL            = 0x7F000017;
    private static final int MARK_JUMP             = 0x7F000018;
    private static final int MARK_PARAM_ANNOT_CNT  = 0x7F000019;
    private static final int MARK_ANNOT_DEFAULT    = 0x7F00001A;

    /* ================= Label ID ================= */

    private final Map<Label, Integer> labelIds = new IdentityHashMap<>();
    private int nextLabelId = 0;

    private int getLabelId(Label l) {
        return labelIds.computeIfAbsent(l, _ -> nextLabelId++);
    }

    /* ================= CRC ================= */

    private long crc = CRC64.init();

    public MethodFingerprinter() {
        super(Opcodes.ASM9);
    }

    public long getHash() {
        return CRC64.finish(crc);
    }

    /* ================= Helpers ================= */

    private void updateInt(int v) {
        crc = CRC64.updateInt(crc, v);
    }

    private void updateLong(long v) {
        crc = CRC64.updateLong(crc, v);
    }

    private void updateString(String s) {
        if (s == null) {
            updateInt(0);
        } else {
            crc = CRC64.updateStringUTF16(crc, s);
        }
    }

    private void updateHandle(Handle h) {
        updateInt(h.getTag());
        updateString(h.getOwner());
        updateString(h.getName());
        updateString(h.getDesc());
        updateInt(h.isInterface() ? 1 : 0);
    }

    /* ================= Instructions ================= */

    @Override
    public void visitInsn(int opcode) {
        updateInt(opcode);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        updateInt(opcode);
        updateInt(operand);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        updateInt(opcode);
        updateInt(var);
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        updateInt(opcode);
        updateString(type);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        updateInt(opcode);
        updateString(owner);
        updateString(name);
        updateString(desc);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name,
                                String desc, boolean isInterface) {
        updateInt(opcode);
        updateString(owner);
        updateString(name);
        updateString(desc);
        updateInt(isInterface ? 1 : 0);
    }

    @Override
    public void visitInvokeDynamicInsn(String name,
                                       String desc,
                                       Handle bsm,
                                       Object... bsmArgs) {

        updateInt(MARK_INVOKEDYNAMIC);

        updateString(name);
        updateString(desc);
        updateHandle(bsm);

        if (bsmArgs == null) {
            updateInt(0);
        } else {
            updateInt(bsmArgs.length);
            for (Object o : bsmArgs) {
                updateConstant(o);
            }
        }
    }

    @Override
    public void visitLdcInsn(Object value) {
        updateInt(MARK_LDC);
        updateConstant(value);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        updateInt(MARK_IINC);
        updateInt(var);
        updateInt(increment);
    }

    @Override
    public void visitTableSwitchInsn(int min, int max,
                                     Label dflt, Label... labels) {
        updateInt(MARK_TABLESWITCH);
        updateInt(min);
        updateInt(max);
        updateInt(getLabelId(dflt));
        for (Label l : labels) {
            updateInt(getLabelId(l));
        }
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt,
                                      int[] keys,
                                      Label... labels) {

        updateInt(MARK_LOOKUPSWITCH);
        updateInt(getLabelId(dflt));
        for (int i = 0; i < keys.length; i++) {
            updateInt(keys[i]);
            updateInt(getLabelId(labels[i]));
        }
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        updateInt(opcode);
        updateInt(MARK_JUMP);
        updateInt(getLabelId(label));
    }

    @Override
    public void visitLabel(Label label) {
        updateInt(MARK_LABEL);
        updateInt(getLabelId(label));
    }

    @Override
    public void visitTryCatchBlock(Label start,
                                   Label end,
                                   Label handler,
                                   String type) {

        updateInt(MARK_TRY_CATCH);
        updateInt(getLabelId(start));
        updateInt(getLabelId(end));
        updateInt(getLabelId(handler));
        updateString(type);
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
        updateInt(MARK_MULTIANEWARRAY);
        updateString(desc);
        updateInt(dims);
    }

    /* ================= Annotation Coverage ================= */

    @Override
    public AnnotationVisitor visitAnnotationDefault() {
        updateInt(MARK_ANNOT_DEFAULT);
        return new FingerprintAnnotationVisitor();
    }

    @Override
    public void visitAnnotableParameterCount(int parameterCount,
                                             boolean visible) {
        updateInt(MARK_PARAM_ANNOT_CNT);
        updateInt(parameterCount);
        updateInt(visible ? 1 : 0);
    }

    /* ================= Constant Handling ================= */

    private void updateConstant(Object cst) {
        switch (cst) {
            case null -> updateInt(0);
            case Integer i -> {
                updateInt(1);
                updateInt(i);
            }
            case Long l -> {
                updateInt(2);
                updateLong(l);
            }
            case Float f -> {
                updateInt(3);
                updateInt(Float.floatToRawIntBits(f));
            }
            case Double d -> {
                updateInt(4);
                updateLong(Double.doubleToRawLongBits(d));
            }
            case String s -> {
                updateInt(5);
                updateString(s);
            }
            case Type t -> {
                updateInt(6);
                updateString(t.getDescriptor());
            }
            case Handle h -> {
                updateInt(7);
                updateHandle(h);
            }
            case ConstantDynamic cd -> {
                updateInt(8);
                updateString(cd.getName());
                updateString(cd.getDescriptor());
                updateHandle(cd.getBootstrapMethod());
                int count = cd.getBootstrapMethodArgumentCount();
                updateInt(count);
                for (int i = 0; i < count; i++) {
                    updateConstant(cd.getBootstrapMethodArgument(i));
                }
            }
            default -> throw new IllegalStateException(
                    "Unhandled constant type: " + cst.getClass()
            );
        }
    }

    /* ================= Annotation Visitor ================= */

    private final class FingerprintAnnotationVisitor extends AnnotationVisitor {

        FingerprintAnnotationVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(String name, Object value) {
            updateString(name);
            updateConstant(value);
        }

        @Override
        public void visitEnum(String name, String desc, String value) {
            updateString(name);
            updateString(desc);
            updateString(value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String desc) {
            updateString(name);
            updateString(desc);
            return this;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            updateString(name);
            return this;
        }
    }

    /* ================= Ignore Debug ================= */

    @Override
    public void visitLineNumber(int line, Label start) { }

    @Override
    public void visitLocalVariable(String name, String desc,
                                   String sig, Label start,
                                   Label end, int index) { }
}
