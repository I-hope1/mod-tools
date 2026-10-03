import nipx.MethodFingerprinter;
import org.objectweb.asm.tree.MethodNode;

/** 复用产品自己的 MethodFingerprinter 算方法体指纹。 */
public class MethodFingerprinterProbe {
	public long hash(String className, MethodNode mn) {
		MethodFingerprinter fp = new MethodFingerprinter();
		fp.reset();
		fp.setContext(className);
		mn.accept(fp);
		return fp.getHash();
	}
}
