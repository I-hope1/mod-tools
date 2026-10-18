package modtools.annotations;

import javax.lang.model.element.Element;
import java.util.Set;

/** 只为拿到 {@link BaseProcessor#initConst} 初始化出的 javac 静态上下文 */
final class ProbeProcessor extends BaseProcessor<Element> {
	@Override
	public void dealElement(Element element) { }

	@Override
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of();
	}
}
