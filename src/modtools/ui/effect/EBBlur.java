package modtools.ui.effect;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import modtools.IntVars;
import modtools.ui.effect.MyDraw.DrawEffect;

import static modtools.utils.MySettings.D_BLUR;

public class EBBlur implements DrawEffect {
	public enum DEF {
		A(
				0.0086973240159f, 0.0359949776755f, 0.1093610049784f,
				0.2129658870149f, 0.2659615230194f, 0.2129658870149f,
				0.1093610049784f, 0.0359949776755f, 0.0086973240159f
		), B(
				0.0444086447005f, 0.0779944219933f, 0.1159966211046f,
				0.1673080561213f, 0.1885769121606f, 0.1673080561213f,
				0.1159966211046f, 0.0779944219933f, 0.0444086447005f
		), C(
				0.0045418484119f, 0.0539998665132f, 0.2419867245191f,
				0.3989431211116f,
				0.2419867245191f, 0.0539998665132f, 0.0045418484119f
		), D(
				0.0245418484119f, 0.0639998665132f, 0.2519867245191f,
				0.3189431211116f,
				0.2519867245191f, 0.0639998665132f, 0.0245418484119f
		), E(
				0.019615710072f, 0.2054255182127f,
				0.5599175434306f,
				0.2054255182127f, 0.019615710072f
		), F(
				0.0702702703f, 0.3162162162f,
				0.2270270270f,
				0.3162162162f, 0.0702702703f
		), G(
				0.2079819330264f,
				0.6840361339472f,
				0.2079819330264f
		), H(
				0.2561736558128f,
				0.4876526883744f,
				0.2561736558128f
		);
		DEF(float... floats) {
			this.floats = floats;
		}
		public final float[] floats;
	}

	Shader      blurShader;
	FrameBuffer buffer, pingpong;

	boolean capturing;

	public int   blurScl   = 4;
	public float blurSpace = 2.26f;

	public EBBlur() {
		this(DEF.C.floats);
	}

	public EBBlur(float... convolutions) {
		blurShader = genShader(convolutions);

		buffer = new FrameBuffer();
		pingpong = new FrameBuffer();

		blurShader.bind();
		blurShader.setUniformi("u_texture0", 0);
		blurShader.setUniformi("u_texture1", 1);
	}

	public static Shader genShader(float... convolutions) {
		if (convolutions.length % 2 != 1)
			throw new IllegalArgumentException("convolution numbers length must be odd number!");

		int convLen = convolutions.length;

		StringBuilder varyings    = new StringBuilder();
		StringBuilder assignVar   = new StringBuilder();
		StringBuilder convolution = new StringBuilder();

		int c    = 0;
		int half = convLen / 2;
		for (float v : convolutions) {
			varyings.append("varying vec2 v_texCoords")
					.append(c)
					.append(";")
					.append(IntVars.NL);

			assignVar.append("v_texCoords")
					.append(c)
					.append(" = ")
					.append("a_texCoord0");
			if (c - half != 0) {
				assignVar.append(c - half > 0 ? "+" : "-")
						.append(Math.abs((float) c - half))
						.append("*len");
			}
			assignVar.append(";")
					.append(IntVars.NL).append("  ");

			if (c > 0) convolution.append("        + ");
			convolution.append(v)
					.append("*texture2D(u_texture1, v_texCoords")
					.append(c)
					.append(")")
					.append(".rgb")
					.append(IntVars.NL);

			c++;
		}
		convolution.append(";");

		String vertexShader = ("attribute vec4 a_position;\n" +
		                       "attribute vec2 a_texCoord0;\n" +
		                       "\n" +
		                       "uniform vec2 dir;\n" +
		                       "uniform vec2 size;\n" +
		                       "\n" +
		                       "varying vec2 v_texCoords;\n" +
		                       "%varying%\n" +
		                       "void main(){\n" +
		                       "  vec2 len = dir/size;\n" +
		                       "\n" +
		                       "  v_texCoords = a_texCoord0;\n" +
		                       "  %assignVar%\n" +
		                       "  gl_Position = a_position;\n" +
		                       "}\n").replace("%varying%", varyings).replace("%assignVar%", assignVar);
		String fragmentShader = ("uniform lowp sampler2D u_texture0;\n" +
		                         "uniform lowp sampler2D u_texture1;\n" +
		                         "\n" +
		                         "uniform lowp float def_alpha;\n" +
		                         "\n" +
		                         "varying vec2 v_texCoords;\n" +
		                         "%varying%\n" +
		                         "void main(){\n" +
		                         "  vec4 blur = texture2D(u_texture0, v_texCoords);\n" +
		                         "  vec3 color = texture2D(u_texture1, v_texCoords).rgb;\n" +
		                         "\n" +
		                         "  if(blur.a > 0.0){\n" +
		                         "    vec3 blurColor =\n" +
		                         "        %convolution%\n" +
		                         "\n" +
		                         "    gl_FragColor.rgb = blurColor;\n" +
		                         "    gl_FragColor.a = blur.a;\n" +
		                         "  }\n" +
		                         "  else{\n" +
		                         "    gl_FragColor.rgb = color;\n" +
		                         "    gl_FragColor.a = def_alpha;\n" +
		                         "  }\n" +
		                         "}\n").replace("%varying%", varyings).replace("%convolution%", convolution);

		/* if (Sgl.config.loadInfo && Sgl.config.debugMode) {
			Log.info("[DEBUG] [Singularity] blur shader generate, shader content:" + Sgl.NL + "=vertexShader=" + Sgl.NL + vertexShader + Sgl.NL + "=fragShader=" + Sgl.NL + fragmentShader);
		} */

		return new Shader(vertexShader, fragmentShader);
		// return null;
	}

	public void resize(int width, int height) {
		blurScl = D_BLUR.getInt("缩放级别", 4);
		width /= blurScl;
		height /= blurScl;

		buffer.resize(width, height);
		pingpong.resize(width, height);

		blurShader.bind();
		blurShader.setUniformf("size", width, height);
	}
	public void capture(float x, float y, float w, float h) {
		capture();
		Draw.reset();
		Fill.crect(x, y, w, h);
	}

	public void capture() {
		if (!capturing) {
			buffer.begin(Color.clear);

			capturing = true;
		}
	}

	public void render() {
		if (!capturing) return;
		Draw.reset();
		capturing = false;
		buffer.end();

		Gl.disable(Gl.blend);
		Gl.disable(Gl.depthTest);
		Gl.depthMask(false);

		ScreenSampler.pause();
		pingpong.begin();
		blurShader.bind();
		blurShader.setUniformf("dir", blurSpace, 0f);
		blurShader.setUniformi("def_alpha", 1);
		ScreenSampler.getSampler().bind(1);
		buffer.blit(blurShader);
		pingpong.end();

		blurShader.bind();
		blurShader.setUniformf("dir", 0f, blurSpace);
		blurShader.setUniformf("def_alpha", 0);
		pingpong.getTexture().bind(1);

		Gl.enable(Gl.blend);
		Gl.blendFunc(Gl.srcAlpha, Gl.oneMinusSrcAlpha);
		ScreenSampler.contiune();
		buffer.blit(blurShader);
	}
}