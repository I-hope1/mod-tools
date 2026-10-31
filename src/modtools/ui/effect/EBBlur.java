package modtools.ui.effect;

import arc.Core;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import modtools.events.*;
import modtools.ui.effect.MyDraw.DrawEffect;

/**
 * from EB-wilson
 * <p>
 * 模糊走 Dual Kawase 金字塔：逐级 2x 降采样到 {@link #pyramidDepth} 层，再逐级 2x 升采样回到 1/2 分辨率，
 * 最后只把窗口矩形那块按 UV 裁出来贴回屏幕。
 * <p>
 * 用金字塔而不是"单次降采样 + 一次大核卷积"，是因为后者在大降采样倍率下会把低清纹理直接放大 s 倍上屏：
 * 双线性放大的梯度逐 texel 是常数，看起来就是方块/网格，窗口移动时还会闪。升采样链每一级都是 8 抽头 tent 滤波，
 * 相当于在低清空间里就把过渡铺平了，最后只剩一次 2x 放大。
 * @author EB-wilson
 */
public class EBBlur implements DrawEffect {
	/**
	 * GLSL 只要求换行可识别，不跟随平台；用常量而不是 {@code IntVars.NL}，是为了让本类能被 headless 单测加载
	 * （{@code IntVars} 的静态初始化需要游戏运行时）。
	 */
	private static final String NL = "\n";

	/** 金字塔最多下探到 1/32；再深只剩一团雾，没有毛玻璃质感。 */
	public static final int MAX_DEPTH = 5;

	/**
	 * 可分离一维高斯核的权重表。
	 * <p>
	 * {@link #genShader(float...)} 保留给需要普通可分离高斯模糊的调用方；{@link EBBlur} 自身的渲染路径走 Kawase，
	 * 不再读取这些权重。
	 */
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

	Shader      downShader, upShader;
	/** 全分辨率屏幕拷贝：金字塔的输入，也是唯一需要全屏尺寸的缓冲。 */
	FrameBuffer screen;
	/** 1/2、1/4、1/8、1/16、1/32；降采样链写入，升采样链原地回填，最终结果在 {@code levels[0]}。 */
	final FrameBuffer[] levels = new FrameBuffer[MAX_DEPTH];

	/** 本次金字塔层数：2 表示走到 1/4，输出仍停在 1/2。 */
	public int   pyramidDepth;
	/** 每级采样偏移，单位是该级 texel；越大越糊，超过 2 会开始出现振铃。 */
	public float sampleOffset;

	boolean pending;
	float   rectX, rectY, rectW, rectH, rectAlpha;

	final Color         lastColor = new Color();
	final TextureRegion composite = new TextureRegion();
	final float[]       cropUV    = new float[4];

	public EBBlur() {
		downShader = new Shader(SAMPLE_VERTEX, downSampleFragment());
		upShader = new Shader(SAMPLE_VERTEX, upSampleFragment());

		screen = new FrameBuffer();
		for (int i = 0; i < MAX_DEPTH; i++) levels[i] = new FrameBuffer();

		initShader(downShader);
		initShader(upShader);

		pyramidDepth = depthFor(4);
		sampleOffset = offsetFor(4);
	}

	private static void initShader(Shader shader) {
		shader.bind();
		shader.setUniformi("u_texture", 0);
	}

	/** 层数随 {@code scale_level} 单调上升：1~2 级 2 层，3~4 级 3 层，之后每两级再深一层。 */
	static int depthFor(int level) {
		return Math.max(2, Math.min(MAX_DEPTH, 1 + level / 2));
	}

	/**
	 * 偏移随等级线性微调。下限 1.4 是为了让最低档也有明确的毛玻璃感：层数 d 的金字塔有效半径约为
	 * {@code 2^(d+1) * offset} 个屏幕像素，2 层 / 1.4 对应 1080p 下 10px 量级；上限压在 2 以内，
	 * 再大 tent 核会在高频处振铃。
	 */
	static float offsetFor(int level) {
		return 1.4f + (level - 1) * 0.03f;
	}

	private static final String SAMPLE_VERTEX =
	 """
		attribute vec4 a_position;
		attribute vec2 a_texCoord0;
		
		varying vec2 v_texCoords;
		
		void main(){
		  v_texCoords = a_texCoord0;
		  gl_Position = a_position;
		}
		""";

	/** 5 抽头降采样：中心权重 4、四角各 1，采样点落在相邻 texel 中心，近似一次 2x2 盒式平均。 */
	private static String downSampleFragment() {
		return """
		 uniform lowp sampler2D u_texture;
		 uniform vec2 u_halfpixel;
		 uniform float u_offset;
		 
		 varying vec2 v_texCoords;
		 
		 void main(){
		   vec2 d = u_halfpixel * u_offset;
		   vec3 sum = texture2D(u_texture, v_texCoords).rgb * 4.0;
		   sum += texture2D(u_texture, v_texCoords - d).rgb;
		   sum += texture2D(u_texture, v_texCoords + d).rgb;
		   sum += texture2D(u_texture, v_texCoords + vec2(d.x, -d.y)).rgb;
		   sum += texture2D(u_texture, v_texCoords - vec2(d.x, -d.y)).rgb;
		   gl_FragColor = vec4(sum * 0.125, 1.0);
		 }
		 """;
	}

	/** 8 抽头升采样：轴向权重 1、对角权重 2 的 tent 核，把上一级低清结果铺平成连续过渡。 */
	private static String upSampleFragment() {
		return """
		 uniform lowp sampler2D u_texture;
		 uniform vec2 u_halfpixel;
		 uniform float u_offset;
		 
		 varying vec2 v_texCoords;
		 
		 void main(){
		   vec2 d = u_halfpixel * u_offset;
		   vec3 sum = texture2D(u_texture, v_texCoords + vec2(-d.x * 2.0, 0.0)).rgb;
		   sum += texture2D(u_texture, v_texCoords + vec2(-d.x, d.y)).rgb * 2.0;
		   sum += texture2D(u_texture, v_texCoords + vec2(0.0, d.y * 2.0)).rgb;
		   sum += texture2D(u_texture, v_texCoords + d).rgb * 2.0;
		   sum += texture2D(u_texture, v_texCoords + vec2(d.x * 2.0, 0.0)).rgb;
		   sum += texture2D(u_texture, v_texCoords + vec2(d.x, -d.y)).rgb * 2.0;
		   sum += texture2D(u_texture, v_texCoords + vec2(0.0, -d.y * 2.0)).rgb;
		   sum += texture2D(u_texture, v_texCoords + vec2(-d.x, -d.y)).rgb * 2.0;
		   gl_FragColor = vec4(sum / 12.0, 1.0);
		 }
		 """;
	}

	/** 降采样链的顶点/片元源码；暴露出来是为了在没有 GL 上下文时也能断言着色器文本。 */
	public static String[] downSampleSources() {
		return new String[]{SAMPLE_VERTEX, downSampleFragment()};
	}

	/** 升采样链的顶点/片元源码；暴露出来是为了在没有 GL 上下文时也能断言着色器文本。 */
	public static String[] upSampleSources() {
		return new String[]{SAMPLE_VERTEX, upSampleFragment()};
	}

	/** Only the sampled screen copy needs full screen size; every pyramid level is a fraction of it. */
	public void resize(float width, float height) {
		int level = Math.max(1, Math.min(16, E_Blur.scale_level.getInt()));
		pyramidDepth = depthFor(level);
		sampleOffset = offsetFor(level);

		int screenW = Math.max(2, Core.graphics.getWidth());
		int screenH = Math.max(2, Core.graphics.getHeight());
		screen.resize(screenW, screenH);

		for (int i = 0; i < MAX_DEPTH; i++) {
			int divisor = 1 << (i + 1);
			levels[i].resize(Math.max(2, screenW / divisor), Math.max(2, screenH / divisor));
		}
	}

	/**
	 * Records the rect to composite; the blur itself runs in {@link #render()}, where the screen is guaranteed to be complete.
	 */
	public void capture(float x, float y, float w, float h, float alpha) {
		rectX = x;
		rectY = y;
		rectW = w;
		rectH = h;
		rectAlpha = alpha;
		pending = true;
	}

	public void render() {
		if (!pending) return;
		pending = false;

		// Pending vertices must reach the default framebuffer first, otherwise the blur samples a stale screen.
		Draw.flush();

		Gl.disable(Gl.blend);
		Gl.disable(Gl.depthTest);
		Gl.depthMask(false);
		try {
			ScreenSampler.instance.getToBuffer(screen, true);

			FrameBuffer source = screen;
			for (int i = 0; i < pyramidDepth; i++) {
				pass(downShader, levels[i], source);
				source = levels[i];
			}
			// 升采样读上一级、写本级，两个缓冲永远不是同一张，可以原地回填。
			for (int i = pyramidDepth - 2; i >= 0; i--) {
				pass(upShader, levels[i], levels[i + 1]);
			}
		} finally {
			Gl.depthMask(true);
			Gl.enable(Gl.blend);
			Gl.blendFunc(Gl.srcAlpha, Gl.oneMinusSrcAlpha);
		}

		composite();
	}

	/** Renders one pyramid pass; source and target must differ, a texture cannot be sampled while bound for writing. */
	private void pass(Shader shader, FrameBuffer target, FrameBuffer source) {
		target.begin();
		try {
			shader.bind();
			shader.setUniformf("u_halfpixel", 0.5f / source.getWidth(), 0.5f / source.getHeight());
			shader.setUniformf("u_offset", sampleOffset);
			source.getTexture().bind(0);
			Draw.blit(shader);
		} finally {
			target.end();
		}
	}

	/** Draws the cropped 1/2-resolution result over the requested rect, so only the covered pixels are shaded. */
	private void composite() {
		if (rectW <= 0f || rectH <= 0f || rectAlpha <= 0f) return;

		float sw = Core.graphics.getWidth(), sh = Core.graphics.getHeight();
		if (sw <= 0f || sh <= 0f) return;

		stageRectToUV(cropUV, rectX, rectY, rectW, rectH, sw, sh);
		composite.set(levels[0].getTexture());
		composite.set(cropUV[0], cropUV[1], cropUV[2], cropUV[3]);

		Blending lastBlend = Draw.getBlend();
		lastColor.set(Draw.getColor());
		Draw.blend(Blending.normal);
		Draw.color(1f, 1f, 1f, rectAlpha);
		Draw.rect(composite, rectX + rectW / 2f, rectY + rectH / 2f, rectW, rectH);
		Draw.color(lastColor);
		Draw.blend(lastBlend);
	}

	/**
	 * Converts a stage-space rect into the (u, v, u2, v2) that {@link TextureRegion#set(float, float, float, float)} expects.
	 * <p>
	 * v and v2 are swapped on purpose: an FBO texture has v=0 at the bottom row, while
	 * {@code SpriteBatch.constructVertices} writes {@code region.v} to the top edge and {@code region.v2} to the bottom one.
	 * Passing the natural (bottom, top) order renders the blurred image upside down.
	 */
	static void stageRectToUV(float[] out, float x, float y, float w, float h, float sw, float sh) {
		out[0] = x / sw;
		out[1] = (y + h) / sh;
		out[2] = (x + w) / sw;
		out[3] = y / sh;
	}

	/**
	 * Builds the vertex/fragment sources for a separable 1D gaussian convolution.
	 * <p>
	 * Kept separate from {@link #genShader(float...)} so the generated text can be asserted without a GL context.
	 */
	public static String[] genShaderSources(float... convolutions) {
		if (convolutions.length % 2 != 1) {
			throw new IllegalArgumentException("convolution numbers length must be odd number!");
		}

		int convLen = convolutions.length;
		int half    = convLen / 2;

		StringBuilder varyings  = new StringBuilder();
		StringBuilder assignVar = new StringBuilder();
		StringBuilder sum       = new StringBuilder();

		for (int c = 0; c < convLen; c++) {
			varyings.append("varying vec2 v_texCoords").append(c).append(';').append(NL);

			assignVar.append("v_texCoords").append(c).append(" = a_texCoord0");
			if (c != half) {
				assignVar.append(c > half ? "+" : "-").append(Math.abs(c - half)).append(".0*len");
			}
			assignVar.append(';').append(NL).append("  ");

			if (c > 0) sum.append("        + ");
			sum.append(convolutions[c]).append("*texture2D(u_texture, v_texCoords").append(c).append(").rgb").append(NL);
		}
		sum.append(';');

		String vertexShader =
		 """
			attribute vec4 a_position;
			attribute vec2 a_texCoord0;
			
			uniform vec2 dir;
			uniform vec2 size;
			
			varying vec2 v_texCoords;
			%s
			void main(){
			  vec2 len = dir/size;
			
			  v_texCoords = a_texCoord0;
				%s
			  gl_Position = a_position;
			}
			""".formatted(varyings, assignVar);
		// 合成不走这个 shader：卷积结果始终写成不透明色，透明度由画矩形时的 Draw.color 决定。
		String fragmentShader =
		 """
			uniform lowp sampler2D u_texture;
			
			varying vec2 v_texCoords;
			%s
			void main(){
			  vec3 blurColor = %s
			
			  gl_FragColor = vec4(blurColor, 1.0);
			}
			""".formatted(varyings, sum);

		return new String[]{vertexShader, fragmentShader};
	}

	public static Shader genShader(float... convolutions) {
		String[] sources = genShaderSources(convolutions);
		return new Shader(sources[0], sources[1]);
	}

	public void dispose() {
		if (downShader != null) downShader.dispose();
		if (upShader != null) upShader.dispose();
		downShader = upShader = null;

		if (screen != null) screen.dispose();
		for (int i = 0; i < MAX_DEPTH; i++) {
			if (levels[i] != null) levels[i].dispose();
		}
	}
}
