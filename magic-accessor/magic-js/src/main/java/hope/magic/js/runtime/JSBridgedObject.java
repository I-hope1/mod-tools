package hope.magic.js.runtime;

/**
 * 桥接对象标记接口。
 * 由动态生成的 Java 子类实现，使其既是标准的 Java 父类实例，又内部持有对应的 JSObject 状态。
 */
public interface JSBridgedObject {
	JSObject getJSObject();
}
