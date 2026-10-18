package modtools.annotations;

import modtools.annotations.unsafe.ProcessingEnvContext;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProcessingEnvContext} 是处理器启动的第一步：挖不到 Context 后面全废。
 * 这里覆盖它要应付的几种包装形态，以及挖不到时的报错。
 */
class ProcessingEnvContextTest {
	@Test
	void unwrapsJavacProcessingEnvironmentDirectly() throws Exception {
		try (JavacFixture fx = JavacFixture.parseOnly("P", "class P {}")) {
			assertSame(fx.context, ProcessingEnvContext.of(fx.processingEnv));
		}
	}

	/** 模拟 IDEA：动态代理，真正的 ProcessingEnvironment 挂在 handler 的某个字段上 */
	@Test
	void unwrapsDynamicProxy() throws Exception {
		try (JavacFixture fx = JavacFixture.parseOnly("P", "class P {}")) {
			assertSame(fx.context, ProcessingEnvContext.of(proxy(new Holder(fx.processingEnv))));
		}
	}

	/** 模拟自带 delegate 字段的包装类 */
	@Test
	void unwrapsDelegateWrapper() throws Exception {
		try (JavacFixture fx = JavacFixture.parseOnly("P", "class P {}")) {
			assertSame(fx.context, ProcessingEnvContext.of(new DelegateWrapper(fx.processingEnv)));
		}
	}

	@Test
	void reportsWhatItCouldNotUnwrap() {
		ProcessingEnvironment proxy = proxy(new Holder("not a ProcessingEnvironment"));

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
		 () -> ProcessingEnvContext.of(proxy));

		assertTrue(e.getMessage().contains("Holder"), "报错里应该带出代理背后的 handler: " + e.getMessage());
	}

	/** handler 指回代理自己：必须靠深度上限停下来，不能把栈打爆 */
	@Test
	void stopsOnSelfReferentialHandler() {
		Holder holder = new Holder(null);
		ProcessingEnvironment proxy = proxy(holder);
		holder.value = proxy;

		assertThrows(IllegalArgumentException.class, () -> ProcessingEnvContext.of(proxy));
	}

	private static ProcessingEnvironment proxy(InvocationHandler handler) {
		return (ProcessingEnvironment) Proxy.newProxyInstance(
		 ProcessingEnvContextTest.class.getClassLoader(),
		 new Class<?>[]{ProcessingEnvironment.class}, handler);
	}

	/** 代理背后的 handler：只有一个字段，指向真正的对象 */
	static final class Holder implements InvocationHandler {
		Object value;

		Holder(Object value) { this.value = value; }

		public Object invoke(Object proxy, Method method, Object[] args) {
			throw new UnsupportedOperationException(method.getName());
		}
	}

	/** 一个自带 delegate 字段的 ProcessingEnvironment 包装类 */
	static final class DelegateWrapper implements ProcessingEnvironment {
		private final ProcessingEnvironment delegate;

		DelegateWrapper(ProcessingEnvironment delegate) { this.delegate = delegate; }

		public Map<String, String> getOptions() { return delegate.getOptions(); }
		public Messager getMessager() { return delegate.getMessager(); }
		public Filer getFiler() { return delegate.getFiler(); }
		public Elements getElementUtils() { return delegate.getElementUtils(); }
		public Types getTypeUtils() { return delegate.getTypeUtils(); }
		public SourceVersion getSourceVersion() { return delegate.getSourceVersion(); }
		public Locale getLocale() { return delegate.getLocale(); }
	}
}
