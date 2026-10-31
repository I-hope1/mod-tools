import modtools.ui.effect.EBBlur;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure tests for the EBBlur convolution data; no OpenGL context is required.
 * <p>
 * Run with: gradlew test --tests EBBlurConvolutionTest
 */
class EBBlurConvolutionTest {
	@Test
	@DisplayName("every scheme has an odd number of weights and no runaway gain")
	void schemesAreUsableConvolutions() {
		for (EBBlur.DEF def : EBBlur.DEF.values()) {
			assertEquals(1, def.floats.length % 2, def + " weight count must be odd");

			float sum = 0f;
			for (float weight : def.floats) {
				assertTrue(weight > 0f, def + " weights must be positive");
				sum += weight;
			}
			// Upstream ships G at 1.10 and E at 1.01, so only guard against typos
			// that would badly over- or under-expose the blur.
			assertEquals(1f, sum, 0.11f, def + " weights must stay close to unit gain");
		}
	}

	@Test
	@DisplayName("genShader rejects an even number of weights")
	void genShaderRejectsEvenLength() {
		assertThrows(IllegalArgumentException.class, () -> EBBlur.genShader(1f, 2f));
	}
}
