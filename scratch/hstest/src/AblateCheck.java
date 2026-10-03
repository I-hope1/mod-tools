import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;

/** 消融：v1 -> v2（插入无关 lambda）-> v3（再改 B 叶子体）。看外/中层是否保住基线的名字。 */
public class AblateCheck {
	static ClassNode parse(byte[] b){ClassNode c=new ClassNode();new ClassReader(b).accept(c,0);return c;}
	static boolean isGhost(MethodNode mn){for(AbstractInsnNode n:mn.instructions)if(n instanceof MethodInsnNode m&&m.owner.equals("nipx/LambdaAligner")&&m.name.equals("onOrphanInvoked"))return true;return false;}
	static String sem(ClassNode cn,MethodNode mn,int d){
		if(d>6)return "..."; if(isGhost(mn))return "GHOST";
		Map<String,MethodNode> by=new HashMap<>(); for(MethodNode m:cn.methods)by.put(m.name,m);
		List<String> p=new ArrayList<>();
		for(AbstractInsnNode n:mn.instructions){
			if(n instanceof MethodInsnNode m&&m.owner.equals(cn.name)&&!m.name.startsWith("lambda$"))p.add(m.name);
			else if(n instanceof InvokeDynamicInsnNode i&&i.bsmArgs!=null&&i.bsmArgs.length>1&&i.bsmArgs[1] instanceof Handle h&&cn.name.equals(h.getOwner())){
				MethodNode c=by.get(h.getName()); if(c!=null)p.add(sem(cn,c,d+1));}}
		Collections.sort(p); return p.toString();}
	static Map<String,String> semAll(byte[] b){ClassNode cn=parse(b);Map<String,String> m=new TreeMap<>();
		for(MethodNode mn:cn.methods)if(mn.name.startsWith("lambda$"))m.put(mn.name,sem(cn,mn,0));return m;}
	static byte[] force(String p,ClassLoader cl)throws Exception{byte[] r=Files.readAllBytes(Paths.get(p));String s=new ClassReader(r).getClassName();AnnotationTransformer.HierarchyTree.register(r);return AnnotationTransformer.forceStaticLambdas(r,s,cl);}
	public static void main(String[] a)throws Exception{
		ClassLoader cl=AblateCheck.class.getClassLoader();
		byte[] v1=force(a[0],cl),v2=force(a[1],cl),v3=force(a[2],cl);
		byte[] b2=LambdaAligner.align(v1,v2);
		byte[] b3=LambdaAligner.align(b2,v3);
		System.out.println("基线（v1->v2 后）:");
		semAll(b2).forEach((k,v)->System.out.println("   "+k+" -> "+v));
		System.out.println("最终（再 ->v3，B 叶子改体）:");
		semAll(b3).forEach((k,v)->System.out.println("   "+k+" -> "+v));
		System.out.println();
		Map<String,String> base=semAll(b2), fin=semAll(b3);
		System.out.println("基线里承载 [[[doB]]] / [[doB]] / [doB] 的名字，在最终类里的归宿:");
		for(var e:base.entrySet()){
			if(!e.getValue().contains("doB"))continue;
			System.out.println("   "+e.getKey()+" 旧="+e.getValue()+"  现="+fin.get(e.getKey()));
		}
	}
}
