package modtools.graphics;

import arc.Core;
import arc.files.Fi;
import arc.graphics.gl.Shader;
import arc.math.Mat;
import arc.math.geom.Vec2;
import modtools.ModTools;


public class MyShaders {
	public static Shader specl, baseShader;
	public static Shader blur;

	public static       Fi      shaderFi = ModTools.root.child("shaders");
	public static final float[] kernel   = {
			0.02f, 0.125f, 0.0625f,
			0.125f, 0.25f, 0.125f,
			0.0625f, 0.125f, 0.105f
	};
	public static void load() {
		blur = new Shader(shaderFi.child("screenspace.vert"), shaderFi.child("高斯模糊.frag")) {
			public void apply() {
				setUniform1fv("u_kernel", kernel, 0, kernel.length);
				float width  = Core.camera.width;
				float height = Core.camera.height;
				setUniformf("u_invsize", 1f / width, 1f / height);
			}
		};
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

		baseShader = new Shader(Core.files.internal("shaders/screenspace.vert"), shaderFi.child("dist_base.frag"));

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
}
