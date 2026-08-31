#extension GL_OES_EGL_image_external : require

precision mediump float;
uniform samplerExternalOES sTexture;
varying vec2 texCoord;
uniform vec2 texSize;
uniform float sharpening;
uniform float denoise;

float edgeWeight(vec4 sampleColor, vec4 centerColor) {
	vec3 difference = sampleColor.rgb - centerColor.rgb;
	/* A rational bilateral weight is considerably cheaper than exp() on older mobile
	 * GPUs. Similar colours are smoothed while strong thermal boundaries stay sharp. */
	return 1.0 / (1.0 + 16.0 * dot(difference, difference));
}

void main(void) { /* Awesome page: https://setosa.io/ev/image-kernels/ */
	vec4 px = texture2D(sTexture, texCoord);
	if (sharpening > 0.0 || denoise > 0.0) {
		vec4 center = px;
		float mul = 2.0;
		vec2 ts = 1.0 / texSize;
		vec4 a = texture2D(sTexture, texCoord + vec2(0.0, -ts.y));
		vec4 b = texture2D(sTexture, texCoord + vec2(0.0, ts.y));
		vec4 c = texture2D(sTexture, texCoord + vec2(-ts.x, 0));
		vec4 d = texture2D(sTexture, texCoord + vec2(ts.x, 0));
		if (sharpening > 0.0) {
			vec4 spx = px * (1.0 + 4.0 * mul) - (a + b + c + d) * mul;
			px = mix(px, spx, sharpening);
		}
		if (denoise > 0.0) {
			float wa = edgeWeight(a, center);
			float wb = edgeWeight(b, center);
			float wc = edgeWeight(c, center);
			float wd = edgeWeight(d, center);
			/* Double centre weight keeps the five-tap cross filter light and avoids
			 * washing out small objects even at the maximum setting. */
			vec4 smooth = (center * 2.0 + a * wa + b * wb + c * wc + d * wd) /
					(2.0 + wa + wb + wc + wd);
			px = mix(px, smooth, denoise);
		}
	}
	gl_FragColor = px;
}
