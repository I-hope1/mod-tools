package modtools.annotations;

import com.google.auto.service.AutoService;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Names;
import sun.misc.Unsafe;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import javax.tools.StandardLocation;
import java.io.*;
import java.lang.Module;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

@SuppressWarnings({"unchecked"})
@AutoService(Processor.class)
public class LightCompiler extends AbstractProcessor{
  private static final long fieldFilterOffset = 112L;

  private static final Field opensField;
  private static final Field exportField;

  private static final Method exportNative;

  final HashMap<TypeElement, ArrayList<JCTree.JCClassDecl>> processedMap = new HashMap<>();

  JavacTrees trees;
  TreeMaker maker;
  Names names;
  Types types;
  ParserFactory parsers;
  Symtab symtab;

  Elements elements;
  Filer filer;

  Messager messager;

  static final Unsafe unsafe;

  private static final String[] opensModule = {
      "com.sun.tools.javac.api",
      "com.sun.tools.javac.code",
      "com.sun.tools.javac.parser",
      "com.sun.tools.javac.processing",
      "com.sun.tools.javac.tree",
      "com.sun.tools.javac.util",
      "com.sun.tools.javac.comp"
  };

  //使用此模块可替代jabel，使程序进行降级编译（编译到JDK8）
  static{
    try{
      Constructor<Unsafe> cstr = Unsafe.class.getDeclaredConstructor();
      cstr.setAccessible(true);
      unsafe = cstr.newInstance();

      Class<?> clazz = Class.forName("jdk.internal.reflect.Reflection");
      Map<Class<?>, Set<String>> map = (Map<Class<?>, Set<String>>) unsafe.getObject(clazz, fieldFilterOffset);
      map.clear();

      opensField = Module.class.getDeclaredField("openPackages");
      exportField = Module.class.getDeclaredField("exportedPackages");

      makeModuleOpen(Module.class.getModule(), "java.lang", LightCompiler.class.getModule());

      exportNative = Module.class.getDeclaredMethod("addExportsToAll0", Module.class, String.class);
      exportNative.setAccessible(true);
      exportNative.invoke(null, Module.class.getModule(), "java.lang");

      for (String pack : opensModule) {
        makeModuleOpen(Tree.class.getModule(), pack, LightCompiler.class.getModule());
      }

      Field minLevel = Source.Feature.class.getDeclaredField("minLevel");
      long off = unsafe.objectFieldOffset(minLevel);

      for (Source.Feature feature : Source.Feature.values()) {
        if (!feature.allowedInSource(Source.JDK8)) {
          unsafe.putObject(feature, off, Source.JDK8);
        }
      }
    } catch (NoSuchFieldException | ClassNotFoundException | InvocationTargetException | NoSuchMethodException |
             InstantiationException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static void makeModuleOpen(Module from, String pac, Module to){
    try {
      if (exportNative != null) exportNative.invoke(null, from, pac);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }

    Map<String, Set<Module>> opensMap = (Map<String, Set<Module>>) unsafe.getObjectVolatile(from, unsafe.objectFieldOffset(opensField));
    if(opensMap == null){
      opensMap = new HashMap<>();
      unsafe.putObjectVolatile(from, unsafe.objectFieldOffset(opensField), opensMap);
    }

    Map<String, Set<Module>> exportsMap = (Map<String, Set<Module>>) unsafe.getObjectVolatile(from, unsafe.objectFieldOffset(exportField));
    if(exportsMap == null){
      exportsMap = new HashMap<>();
      unsafe.putObjectVolatile(from, unsafe.objectFieldOffset(exportField), exportsMap);
    }

    Set<Module> opens = opensMap.computeIfAbsent(pac, e -> new HashSet<>());
    Set<Module> exports = exportsMap.computeIfAbsent(pac, e -> new HashSet<>());

    try{
      opens.add(to);
    }catch(UnsupportedOperationException e){
      ArrayList<Module> lis = new ArrayList<>(opens);
      lis.add(to);
      opensMap.put(pac, new HashSet<>(lis));
    }

    try{
      exports.add(to);
    }catch(UnsupportedOperationException e){
      ArrayList<Module> lis = new ArrayList<>(exports);
      lis.add(to);
      exportsMap.put(pac, new HashSet<>(lis));
    }
  }

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv){
    super.init(processingEnv);
    elements = processingEnv.getElementUtils();
    filer = processingEnv.getFiler();
    messager = processingEnv.getMessager();

    Context context = ((JavacProcessingEnvironment)processingEnv).getContext();
    trees = JavacTrees.instance(context);
    maker = TreeMaker.instance(context);
    names = Names.instance(context);
    types = Types.instance(context);
    parsers = ParserFactory.instance(context);
    symtab = Symtab.instance(context);
  }

  @Override
  public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment){
    PrintStream writer = null;
    try{
      String pac = getClass().getSimpleName() + ".log";

      String p = filer.getResource(StandardLocation.SOURCE_OUTPUT, "", "null.java").toUri().toURL().getFile();
      File logDir = new File(p.replace("/build/generated/sources/annotationProcessor/java/main/null.java", "/processorLog/").replace("/", File.separator));
      if(logDir.exists()) logDir.delete();
      logDir.mkdirs();

      File file = new File(logDir, pac);

      writer = new PrintStream(file);
      writer.print("processor: ");
      writer.print(getClass().getSimpleName());
      writer.println(" (full class name: " + getClass().getCanonicalName() + ")");

      writer.print("time: ");
      writer.println(new Date());
      writer.println();

      for(Map.Entry<TypeElement, ArrayList<JCTree.JCClassDecl>> entry: processedMap.entrySet()){
        writer.println("-----------------------------------------");
        writer.print("annotation: ");
        writer.println(entry.getKey().getQualifiedName());
        writer.println("-----------------------------------------\n");
        for(JCTree.JCClassDecl type: entry.getValue()){

          writer.println("> class: " + type.sym.getQualifiedName());

          String javaStr = type.toString();
          int blankPad = (int) Math.log10(javaStr.split("\n").length) + 1;
          BufferedReader reader = new BufferedReader(new StringReader(javaStr));
          String line;
          int count = 0;
          while((line = reader.readLine()) != null){
            count++;
            int blank = blankPad - ((int)Math.log10(count) + 1);
            for(int i = 0; i < blank; i++){
              writer.print(" ");
            }
            writer.print(count);
            writer.print("| ");
            writer.println(line);
          }

          writer.println();
          writer.flush();
        }
      }
    }catch(IOException e){
      e.printStackTrace();
    }finally{
      if(writer != null) writer.close();
    }

    return false;
  }

  @Override
  public SourceVersion getSupportedSourceVersion(){
    return SourceVersion.latest();
  }

  protected boolean isAssignable(Type a, Type b){
    if(a instanceof Type.ClassType && b instanceof Type.ClassType){
      return b.tsym.isSubClass(a.tsym, types);
    }
    else if(a instanceof Type.JCPrimitiveType){
      return a.equals(b);
    }
    return false;
  }

  protected boolean equalOrSub(Symbol.MethodSymbol m1, Symbol.MethodSymbol m2){
    if(!m1.getQualifiedName().equals(m2.getQualifiedName())) return false;

    Symbol.VarSymbol[] param1 = m1.params().toArray(new Symbol.VarSymbol[0]), param2 = m2.params().toArray(new Symbol.VarSymbol[0]);
    if(param1.length != param2.length) return false;

    for(int i = 0; i < param1.length; i++){
      if(!param1[i].type.tsym.getQualifiedName().equals(param2[i].type.tsym.getQualifiedName())) return false;
    }
    return m2.getReturnType().getKind() == m1.getReturnType().getKind()
        && (m1.getReturnType().getKind() == TypeKind.VOID
        || (isAssignable(m1.getReturnType(), m2.getReturnType()) || isAssignable(m2.getReturnType(), m1.getReturnType())));
  }

  protected boolean containMethod(HashMap<String, HashSet<Symbol.MethodSymbol>> map, Symbol.MethodSymbol symbol){
    HashSet<Symbol.MethodSymbol> set = map.get(symbol.getSimpleName().toString());

    if (set == null) return false;

    for (Symbol.MethodSymbol methodSymbol : set) {
      if (equalOrSub(symbol, methodSymbol)) return true;
    }

    return false;
  }

  protected boolean addMethod(HashMap<String, HashSet<Symbol.MethodSymbol>> map, Symbol.MethodSymbol symbol){
    return addMethod(map, symbol, false);
  }

  protected boolean addMethod(HashMap<String, HashSet<Symbol.MethodSymbol>> map, Symbol.MethodSymbol symbol, boolean override){
    HashSet<Symbol.MethodSymbol> set = map.computeIfAbsent(symbol.getQualifiedName().toString(), e -> new HashSet<>());
    for(Symbol.MethodSymbol m : set){
      if(equalOrSub(m, symbol)){
        if(override){
          set.remove(m);
          set.add(symbol);
        }
        return false;
      }
    }
    set.add(symbol);
    return true;
  }

  protected <A extends Annotation> AnnotationMirrors getAnnotationParams(Symbol sym, Class<A> annoType){
    AnnotationMirrors mirrors = new AnnotationMirrors();
    boolean existed = false;

    for(Attribute.Compound mirror: sym.getAnnotationMirrors()){
      if(((TypeElement)mirror.getAnnotationType().asElement()).getQualifiedName().equals(names.fromString(annoType.getCanonicalName()))){
        existed = true;
        elements.getElementValuesWithDefaults(mirror).forEach((e, v) -> {
          mirrors.attributes.put(e.getSimpleName().toString(), (Attribute) v);
        });
      }
    }

    return existed? mirrors: null;
  }

  protected void genLog(TypeElement annotationType, JCTree.JCClassDecl type){
    processedMap.computeIfAbsent(annotationType, e -> new ArrayList<>()).add(type);
  }

  @SuppressWarnings("unchecked")
  public static class AnnotationMirrors{
    private final HashMap<String, Attribute> attributes = new HashMap<>();

    public <T> T get(String key){
      return (T) attributes.get(key).getValue();
    }

    public Object getConstant(String key){
      Attribute attr = attributes.get(key);
      if(!(attr instanceof Attribute.Constant)) throw new UnsupportedOperationException("type of key: " + key + " is not a constant");
      return attr.getValue();
    }

    public char getChar(String key){
      Object obj = getConstant(key);
      if(!(obj instanceof Character)) throw new UnsupportedOperationException("type of key: " + key + " is not a string");
      return (char) obj;
    }

    public String getString(String key){
      Object obj = getConstant(key);
      if(!(obj instanceof String)) throw new UnsupportedOperationException("type of key: " + key + " is not a string");
      return (String) obj;
    }

    public boolean getBoolean(String key){
      Object obj = getConstant(key);
      if(!(obj instanceof Boolean)) throw new UnsupportedOperationException("type of key: " + key + " is not a boolean value");
      return (boolean) obj;
    }

    public Number getNumber(String key){
      Object obj = getConstant(key);
      if(!(obj instanceof Number)) throw new UnsupportedOperationException("type of key: " + key + " is not a number");
      return (Number) obj;
    }

    public int getInt(String key){
      return getNumber(key).intValue();
    }

    public float getFloat(String key){
      return getNumber(key).floatValue();
    }

    public byte getByte(String key){
      return getNumber(key).byteValue();
    }

    public short getShort(String key){
      return getNumber(key).shortValue();
    }

    public long getLong(String key){
      return getNumber(key).longValue();
    }

    public double getDouble(String key){
      return getNumber(key).doubleValue();
    }

    public Type getType(String key){
      Attribute attr = attributes.get(key);
      if(!(attr instanceof Attribute.Class)) throw new UnsupportedOperationException("type of key: " + key + " is not a class");
      return ((Attribute.Class) attr).getValue();
    }

    public <E extends Enum<?>> E getEnum(String key){
      Attribute attr = attributes.get(key);
      if(!(attr instanceof Attribute.Enum)) throw new UnsupportedOperationException("type of key: " + key + " is not an Enum");
      return (E) ((Attribute.Enum) attr).getValue().getConstantValue();
    }

    public Attribute[] getArr(String key){
      Attribute attr = attributes.get(key);
      if(!(attr instanceof Attribute.Array)) throw new UnsupportedOperationException("type of key: " + key + " is not an array");
      return ((Attribute.Array) attr).getValue().toArray(new Attribute[0]);
    }

    public <T> T[] getArr(String key, T[] typeArr){
      Attribute[] attr = getArr(key);
      typeArr = Arrays.copyOf(typeArr, attr.length);

      for(int i = 0; i < attr.length; i++){
        typeArr[i] = (T) attr[i].getValue();
      }

      return typeArr;
    }
  }
  public Set<String> getSupportedAnnotationTypes() {
    Set<String> set = new HashSet<>();
    set.add("asjsao");
    return set;
  }
}