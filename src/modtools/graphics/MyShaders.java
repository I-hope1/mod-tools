package modtools.graphics;

import arc.Core;
import arc.files.Fi;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.Shader;
import arc.math.Mat;
import arc.math.geom.*;
import arc.util.*;
import modtools.*;


public class MyShaders {
	public static Shader specl, baseShader;
	/** 将任何纹理中有颜色的替换成{@code color} */
	public static MixScreen  mixScreen;
	public static MaskShader maskShader;
	// public static FrontShader frontShader;

	// public static Batch maskBatch;

	public static Fi shaderFi = IntVars.root.child("shaders");
	public static void load() {
		/* specl = new Shader(shaderFi.child("screenspace.vert"), shaderFi.child("毛玻璃.frag")) {
			public void apply() {
				setUniformf("u_time", Time.time / Scl.scl(1f));
				float width  = Core.camera.width;
				float height = Core.camera.height;
				setUniformf("u_offset",
						Core.camera.position.x - width / 2,
						Core.camera.position.y - height / 2);
				setUniformf("u_texsize", width, height);
				setUniformf("u_invsize", 1f / width, 1f / height);
			}
		}; */
		baseShader = new Shader(
		 Core.files.internal("shaders/screenspace.vert"),
		 shaderFi.child("dist_base.frag"));
		mixScreen = new MixScreen();
		// maskShader = new MaskShader();
		// maskBatch = Core.batch = new SpriteBatch(10, maskShader);
		// frontShader = new FrontShader();

		// blur = new BlurShader();

		// FrameBuffer buffer = new FrameBuffer();
		// Shaders.shield = shader;
		/* Events.run(Trigger.draw, () -> {
			// Draw.alpha(0.7f);
			// Fill.rect(0, 0, 1000, 1000);
			// Draw.shader(shader);
			// Draw.blit(shader);
			buffer.resize(graphics.getWidth(), graphics.getHeight());
			// shader.bind();
			Draw.drawRange(Layer.shields, 1f, () -> buffer.begin(Color.clear), () -> {
				buffer.end();
				buffer.blit(shader);
				// shader.apply();
			});
		}); */
	}

	public static class MaskShader extends Shader {
		private final Color mashColor = new Color();

		public MaskShader() {
			super("attribute vec4 a_position;\n" +
						"attribute vec4 a_color;\n" +
						"attribute vec2 a_texCoord0;\n" +
						"attribute vec4 a_mix_color;\n" +
						"uniform mat4 u_projTrans;\n" +
						"varying vec4 v_color;\n" +
						"varying vec4 v_mix_color;\n" +
						"varying vec4 v_mask;\n" +
						"varying vec2 v_texCoords;\n" +

						"void main(){\n" +
						"   v_color = a_color;\n" +
						"   v_color.a = v_color.a * (255.0/254.0);\n" +
						"   v_mix_color = a_mix_color;\n" +
						"   v_mix_color.a *= (255.0/254.0);\n" +
						"   v_texCoords = a_texCoord0;\n" +
						"   gl_Position = u_projTrans * a_position;\n" +
						"}",

			 "\n" +
			 "varying lowp vec4 v_color;\n" +
			 "varying lowp vec4 v_mix_color;\n" +
			 "varying highp vec2 v_texCoords;\n" +
			 "uniform highp sampler2D u_texture;\n" +
			 "uniform vec4 u_mask;\n" +
			 "void main(){\n" +
			 "  vec4 c = texture2D(u_texture, v_texCoords);\n" +
			 "  gl_FragColor = v_color" +
			 " * mix(c, vec4(v_mix_color.rgb, c.a), v_mix_color.a)" +
			 " * (vec4(1) - u_mask);" +
			 "}");
			apply();
		}
		public void setMashColor(Color color) {
			mashColor.set(color);
			apply();
		}
		public void bind() {
			apply();
			super.bind();
		}
		public void apply() {
			setUniformf("u_mask", mashColor);
		}
	}

	public static class BlurShader extends Shader {
		private final Mat  convMat = new Mat();
		private final Vec2 size    = new Vec2();

		public BlurShader() {
			super(Core.files.internal("bloomshaders/blurspace.vert"),
			 shaderFi.child("gaussian_blur.frag"));
		}

		/* public void setConvMat(float... conv) {
			convMat.set(conv);
		}

		public void setBlurSize(float width, float height) {
			size.set(width, height);
		} */

		@Override
		public void apply() {
			setUniformMatrix("conv", convMat);
			setUniformf("size", size);
		}
	}
	public static class MixScreen extends Shader {
		public MixScreen() {
			super(Core.files.internal("shaders/screenspace.vert"),
			 shaderFi.child("mix.frag"));
		}

		public Color color;
		public void apply() {
			setUniformf("color", color.r, color.g, color.b, color.a);
			setUniformi("u_texture0", 0);
			setUniformi("u_texture1", 1);
		}
	}
	public static class FrontShader extends Shader {
		public FrontShader() {
			super(Core.files.internal("shaders/screenspace.vert"), shaderFi.child("frontOnly.frag"));
		}
		public void apply() {
			setUniformi("u_texture0", 0);
			setUniformi("u_texture1", 1);
		}
	}
}
