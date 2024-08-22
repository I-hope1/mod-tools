package modtools.files;

import arc.Files.FileType;
import arc.files.Fi;

import java.io.*;
/**
 * HFi类扩展了Fi类，用于处理类路径中的资源
 * 它通过ClassLoader来定位和加载资源，不支持写操作
 */
public class HFi extends Fi {
    // 加载类路径资源的ClassLoader
    public final ClassLoader loader;

    /**
     * 构造函数，使用指定的ClassLoader创建一个HFi实例
     * @param loader 用于加载资源的ClassLoader，不能为空
     */
    public HFi(ClassLoader loader) {
        this("", loader);
    }

    /**
     * 私有构造函数，从路径字符串创建HFi实例
     * @param path 资源路径
     * @param loader 用于加载资源的ClassLoader，不能为空
     */
    private HFi(String path, ClassLoader loader) {
        this(new File(path), loader);
    }

    /**
     * 私有构造函数，从File对象创建HFi实例
     * @param file 指定资源的File对象
     * @param loader 用于加载资源的ClassLoader，不能为空
     */
    private HFi(File file, ClassLoader loader) {
        super(file, FileType.classpath);
        if (loader == null) throw new IllegalArgumentException("loader cannot be null.");
        this.loader = loader;
    }

    /**
     * 检查资源是否存在
     * @return 如果资源存在返回true，否则返回false
     */
    public boolean exists() {
        return loader.getResource(path()) != null;
    }

    /**
     * 获取资源路径，去除开头的'/'
     * @return 资源的路径
     */
    public String path() {
        return super.path().substring(1);
    }

    /**
     * 读取资源的输入流
     * @return 资源的输入流
     * @throws UnsupportedOperationException 如果尝试读取根路径则抛出异常
     */
    public InputStream read() {
        if (file.getPath().isEmpty()) throw new UnsupportedOperationException("Cannot read the root.");
        return loader.getResourceAsStream(path());
    }

    /**
     * 抛出异常，表示不支持写操作
     * @return OutputStream对象，但永远不会被达到
     * @throws UnsupportedOperationException 始终抛出不支持操作异常
     */
    public OutputStream write() {
        throw new UnsupportedOperationException("HFi cannot write anything.");
    }

    /**
     * 获取当前资源的父资源
     * @return 当前资源的父资源的Fi实例
     */
    public Fi parent() {
        return new HFi(file.getParent(), loader);
    }

    /**
     * 创建或获取当前资源的子资源
     * @param name 子资源的名称
     * @return 当前资源的子资源的Fi实例
     */
    public Fi child(String name) {
        return new HFi(new File(file, name), loader);
    }
}
