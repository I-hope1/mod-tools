import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** 打印 align 内部算出的 (shape, upDepth)，用于诊断 A 趟为何未生效。 */
public class DepthProbe {
	public static void main(String[] a) throws Exception {
		byte[] r = Files.readAllBytes(Paths.get(a[0]));
		String slash = new ClassReader(r).getClassName();
		AnnotationTransformer.HierarchyTree.register(r);
		byte[] v1 = AnnotationTransformer.forceStaticLambdas(r, slash, DepthProbe.class.getClassLoader());
		byte[] r2 = Files.readAllBytes(Paths.get(a[1]));
		AnnotationTransformer.HierarchyTree.register(r2);
		byte[] v2 = AnnotationTransformer.forceStaticLambdas(r2, new ClassReader(r2).getClassName(),
			DepthProbe.class.getClassLoader());

		// align 一次让内部算好；随后从 CONTEXT 里读 SyntheticInfo
		LambdaAligner.align(v1, v2);
		Object ctx = LambdaAligner.CONTEXT.get();
		for (String side : new String[]{"oldGroups", "newGroups"}) {
			Field gf = ctx.getClass().getDeclaredField(side);
			gf.setAccessible(true);
			Object groups = gf.get(ctx);
			Method nextEntry = groups.getClass().getMethod("nextEntry", long.class);
			Method valueAt = groups.getClass().getMethod("valueAt", int.class);
			System.out.println("== " + side + " ==");
			for (int idx = (int) nextEntry.invoke(groups, -1L); idx != -1;
			     idx = (int) nextEntry.invoke(groups, (long) idx)) {
				for (Object info : (List<?>) valueAt.invoke(groups, idx)) {
					Field nf = info.getClass().getDeclaredField("name");
					Field sf = info.getClass().getDeclaredField("shape");
					Field df = info.getClass().getDeclaredField("upDepth");
					Field cf = info.getClass().getDeclaredField("children");
					nf.setAccessible(true); sf.setAccessible(true); df.setAccessible(true); cf.setAccessible(true);
					System.out.println("   " + nf.get(info) + " shape=" + sf.get(info)
						+ " upDepth=" + df.get(info) + " children=" + cf.get(info));
				}
			}
		}
	}
}
