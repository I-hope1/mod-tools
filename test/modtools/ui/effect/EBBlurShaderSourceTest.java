package modtools.ui.effect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-level checks for {@link EBBlur}'s generated shaders and its stage-to-UV mapping.
 * Nothing here compiles a shader or allocates a texture, so no OpenGL context is required.
 */
class EBBlurShaderSourceTest {
	@Test
	@DisplayName("every scheme emits one texture fetch per weight")
	void oneFetchPerWeight() {
		for (EBBlur.DEF def : EBBlur.DEF.values()) {
			String[] sources = EBBlur.genShaderSources(def.floats);

			assertEquals(def.floats.length, countOf(sources[1], "texture2D("),
			 def + " must fetch once per weight");
			for (float weight : def.floats) {
				assertTrue(sources[1].contains(String.valueOf(weight)),
				 def + " must emit weight " + weight + " into the fragment shader");
			}
		}
	}

	@Test
	@DisplayName("the blur shader never composites: no mask texture, no alpha uniforms")
	void blurShaderIsMaskFree() {
		String fragment = EBBlur.genShaderSources(EBBlur.DEF.D.floats)[1];

		for (String banned : new String[]{"u_texture0", "u_texture1", "def_alpha", "u_alpha"}) {
			assertFalse(fragment.contains(banned),
			 "the blur pass must not reference '" + banned + "'; masking happens when the rect is drawn");
		}
		assertTrue(fragment.contains("gl_FragColor = vec4(blurColor, 1.0);"),
		 "the blur pass must write opaque pixels so the rect alpha can be applied at composite time");
	}

	@Test
	@DisplayName("offsets are expressed in low-resolution texels of the blur target")
	void offsetsUseTargetSize() {
		String vertex = EBBlur.genShaderSources(EBBlur.DEF.D.floats)[0];

		assertTrue(vertex.contains("uniform vec2 size;"),
		 "the vertex stage needs the target size to convert texels to UV");
		assertTrue(vertex.contains("vec2 len = dir/size;"),
		 "offsets must be scaled by the blur target, not by the screen");
		// A 9-tap kernel has one varying per tap and one offset per non-centre tap.
		assertEquals(EBBlur.DEF.D.floats.length - 1, countOf(vertex, "*len"),
		 "every non-centre tap must carry its own offset");
	}

	@Test
	@DisplayName("stage rect maps to UV with v and v2 swapped for the bottom-up FBO layout")
	void stageRectToUVFlipsVertically() {
		float[] uv = new float[4];

		EBBlur.stageRectToUV(uv, 0f, 0f, 100f, 50f, 100f, 100f);
		assertArrayEquals(new float[]{0f, 0.5f, 1f, 0f}, uv, 1e-6f,
		 "region.v must carry the top edge (y+h) and region.v2 the bottom edge (y)");

		EBBlur.stageRectToUV(uv, 0f, 0f, 100f, 100f, 100f, 100f);
		assertArrayEquals(new float[]{0f, 1f, 1f, 0f}, uv, 1e-6f,
		 "a full-screen rect must sample the whole texture");
	}

	@Test
	@DisplayName("an even number of weights is rejected before any GL work")
	void rejectsEvenLength() {
		assertThrows(IllegalArgumentException.class, () -> EBBlur.genShaderSources(1f, 2f));
	}

	@Test
	@DisplayName("pyramid shaders use the documented tap counts and a single sampler")
	void pyramidTapCounts() {
		String[] down = EBBlur.downSampleSources();
		String[] up = EBBlur.upSampleSources();

		assertEquals(5, countOf(down[1], "texture2D("), "downsample is a 5-tap dual filter");
		assertEquals(8, countOf(up[1], "texture2D("), "upsample is an 8-tap tent");

		for (String fragment : new String[]{down[1], up[1]}) {
			assertTrue(fragment.contains("uniform vec2 u_halfpixel;"),
			 "offsets must be expressed in source texels, otherwise the pyramid blurs by the wrong amount");
			assertTrue(fragment.contains("uniform float u_offset;"),
			 "the offset has to be tunable per level");
			assertFalse(fragment.contains("u_texture0"), "one sampler is enough");
			assertTrue(fragment.contains("gl_FragColor = vec4(sum"), "the pass must write the accumulated sum");
			assertTrue(fragment.contains(", 1.0);"), "the pass must write an opaque result");
		}
	}

	@Test
	@DisplayName("scale_level maps to a monotonic depth that never collapses to a single level")
	void depthMappingIsMonotonic() {
		assertEquals(2, EBBlur.depthFor(1),
		 "level 1 must still traverse 1/2 -> 1/4 -> 1/2, otherwise it barely blurs at native resolution");
		assertEquals(3, EBBlur.depthFor(4), "the default level must keep a strong blur");
		assertEquals(EBBlur.MAX_DEPTH, EBBlur.depthFor(16));

		int previous = 0;
		for (int level = 1; level <= 16; level++) {
			int depth = EBBlur.depthFor(level);
			assertTrue(depth >= 2 && depth <= EBBlur.MAX_DEPTH, "depth out of range at level " + level);
			assertTrue(depth >= previous, "depth must not decrease as the level grows");
			previous = depth;
		}
	}

	@Test
	@DisplayName("sample offset stays wide enough at the bottom and free of ringing at the top")
	void offsetStaysInRange() {
		assertTrue(EBBlur.offsetFor(1) >= 1.2f, "level 1 needs a wide kernel to read as frosted glass");
		assertTrue(EBBlur.offsetFor(16) <= 2f, "a tent offset above 2 texels starts to ring");
		assertTrue(EBBlur.offsetFor(16) > EBBlur.offsetFor(1), "offset must grow with the level");
	}

	private static int countOf(String haystack, String needle) {
		int count = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
			count++;
		}
		return count;
	}
}
