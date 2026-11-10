/*
 * Ported from DistortionWavesKit's Metal shaders (https://github.com/Jakubantalik/Libraries.dev),
 * Copyright (c) 2026 Jakub Antalik, MIT License. The full licence ships in assets/licenses/distortion-waves.txt.
 */
package com.chalaiden.app.ui.wave

/**
 * The wave's three AGSL shaders (Android 13+). Everything is read from one signed distance to the
 * front, so the swell, the colour and the blur can never drift apart:
 *
 *   d > 0  the wave has not got here yet
 *   d = 0  the front
 *   d < 0  the wave has passed
 *
 * The shaders work in dp, as the kit's do in points: pixels are divided by `dens` on the way in, and
 * sampling multiplies back. The field arrives as four float4s:
 *
 *   f0  originX, originY, spine, radius
 *   f1  squash, time, energy, swell
 *   f2  width, height, gravity, glow
 *   f3  clearRadius, rim, launched, hairClearRadius
 */
internal object WaveShaders {
    private const val FIELD = """
        uniform float4 f0;
        uniform float4 f1;
        uniform float4 f2;
        uniform float4 f3;
        uniform float dens;

        const float coverZone = 60.0;

        // The front: a pill (the button's outline) that opens into an arc as its spine shrinks and its
        // radius grows. n points away from the source; q is the position relative to the origin.
        float fieldAt(float2 p, out float2 n, out float2 q, out float g) {
            q = p - f0.xy;
            float spine = f0.z;
            float radius = f0.w;
            float squash = f1.x;
            float time = f1.y;
            float swell = f1.w;
            // A narrower x metric bows the front, so it crosses a tall screen as a curve.
            float mx = q.x * squash;
            float2 v = float2(max(abs(mx) - spine, 0.0), q.y);
            float l = max(length(v), 0.001);
            float2 grad = float2(v.x * sign(q.x) * squash, v.y) / l;
            g = max(length(grad), 0.001);
            // A slow bend along the front, so it never looks drawn with a compass.
            float s = mx * 0.0058;
            float bend = 0.62 * sin(s + time * 1.1) + 0.38 * sin(s * 2.1 - time * 1.5 + 1.7);
            n = grad / g;
            return (l - radius) / g + swell * bend;
        }
    """

    /**
     * One side of the transition, with its blur already in it. side +1 is the screen the wave leaves
     * behind (shown ahead of the front), -1 the one it reveals (shown behind it). Content rises once as
     * the swell passes; the colours part along the way a pixel moved, in proportion to how far, so the
     * split shows exactly where the screen is distorted and nowhere else.
     */
    const val LENS = FIELD + """
        uniform shader content;
        uniform float amplitude;
        uniform float lensWidth;
        uniform float aberration;
        uniform float side;

        half4 at(float2 s) {
            return content.eval(clamp(s, float2(0.5), f2.xy - 0.5) * dens);
        }

        half4 main(float2 fc) {
            float2 p = fc / dens;
            float2 n; float2 q; float g;
            float d = fieldAt(p, n, q, g);
            float energy = f1.z;
            float gravity = f2.z;

            // Ahead of the crossfade zone the outgoing side covers the incoming one completely.
            float cover = 1.0;
            if (side > 0.0) {
                if (f3.z > 0.5) cover = smoothstep(-coverZone, coverZone, d);
                if (cover <= 0.0) return half4(0.0);
            } else if (d > coverZone + 6.0) {
                return half4(0.0);
            }

            // Steeper ahead, where it compresses what it is about to reach; long behind.
            float u = d / (lensWidth * (d > 0.0 ? 0.85 : 1.5));
            float lift = exp(-u * u);
            float r = max(length(q), 1.0);
            float2 swollen = n * energy * amplitude * lift;
            float2 inward = q / r;
            float2 s = p - swollen + inward * gravity * exp(-r / 900.0);
            float2 split = (swollen - inward * gravity * exp(-r / 240.0)) * aberration;

            // Under a quarter of a dp the colours can't be seen apart.
            if (dot(split, split) < 0.0625) return at(s) * half(cover);

            // Five samples across the spectrum: a smooth split, not three ghost images.
            half4 c0 = at(s - split);
            half4 c1 = at(s - split * 0.5);
            half4 c2 = at(s);
            half4 c3 = at(s + split * 0.5);
            half4 c4 = at(s + split);
            half3 rgb = c0.rgb * half3(0.571, 0.0, 0.0)
                + c1.rgb * half3(0.343, 0.206, 0.0)
                + c2.rgb * half3(0.086, 0.588, 0.086)
                + c3.rgb * half3(0.0, 0.206, 0.343)
                + c4.rgb * half3(0.0, 0.0, 0.571);
            half a = (c0.a + c1.a + c2.a + c3.a + c4.a) / 5.0;
            return half4(rgb, a) * half(cover);
        }
    """

    /**
     * Progressive blur: how much of one blurred copy shows here. Copies stacked over the sharp original,
     * each taking over where the last ends, give a true blur at every radius. Blur gathers ahead of the
     * front, peaks on it and clears slowly behind, so what the wave reveals comes into focus.
     */
    const val BLUR_MASK = FIELD + """
        uniform shader content;
        uniform float blur;
        uniform float ahead;
        uniform float behind;
        uniform float fromR;
        uniform float toR;

        half4 main(float2 fc) {
            float2 n; float2 q; float g;
            float d = fieldAt(fc / dens, n, q, g);
            float u = d / (d > 0.0 ? ahead : behind);
            float radius = f1.z * blur * exp(-u * u);
            float t = clamp((radius - fromR) / max(toR - fromR, 0.001), 0.0, 1.0);
            return content.eval(fc) * half(t * t * (3.0 - 2.0 * t));
        }
    """

    /**
     * The colour. The band rides the front: the palette flowing along it, a warm fringe ahead, a cool one
     * behind. The rim is what it leaves on the screen's edges, uneven lumps with a hairline on the glass,
     * until a second, later front wipes it away. pal holds OKLab triplets, the last two the fringes.
     */
    const val BAND = FIELD + """
        uniform float3 pal[9];
        uniform float3 lumps[7];
        uniform float lead;
        uniform float tail;
        uniform float strength;
        uniform float rimWidth;
        uniform float rimSoftness;
        uniform float cornerRadius;
        uniform float unevenness;
        uniform float pulse;
        uniform float hairWidth;
        uniform float hairOpacity;
        uniform float hairWhite;

        const float PI = 3.14159265;

        float sq(float x) { return x * x; }

        float3 oklabToSRGB(float3 c) {
            float l = c.x + 0.3963377774 * c.y + 0.2158037573 * c.z;
            float m = c.x - 0.1055613458 * c.y - 0.0638541728 * c.z;
            float s = c.x - 0.0894841775 * c.y - 1.2914855480 * c.z;
            l = l * l * l; m = m * m * m; s = s * s * s;
            float3 lin = clamp(float3(
                4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
            ), 0.0, 1.0);
            return mix(12.92 * lin, 1.055 * pow(lin, float3(1.0 / 2.4)) - 0.055, step(float3(0.0031308), lin));
        }

        // Signed distance to a rounded rect with continuous corners.
        float roundedRectSDF(float2 p, float2 halfSize, float radius) {
            float a = min(radius * 1.528665, min(halfSize.x, halfSize.y));
            float2 q = abs(p) - (halfSize - a);
            if (q.x <= 0.0 || q.y <= 0.0) {
                return max(abs(p.x) - halfSize.x, abs(p.y) - halfSize.y);
            }
            float n = 3.26;
            float2 u = q / a;
            float norm = pow(pow(u.x, n) + pow(u.y, n), 1.0 / n);
            float2 g = pow(u / max(norm, 0.0001), float2(n - 1.0));
            return (norm - 1.0) * a / max(length(g), 0.001);
        }

        // Where a point is along the rounded-rect edge, clockwise from the top centre; .y the perimeter.
        float2 perimeterAt(float2 p, float2 halfSize, float r) {
            float ex = max(halfSize.x - r, 0.0);
            float ey = max(halfSize.y - r, 0.0);
            float arc = 0.5 * PI * r;
            float P = 4.0 * (ex + ey + arc);
            float ax = abs(p.x);
            float ay = abs(p.y);
            if (ax > ex && ay > ey) {
                float2 v = p - float2(p.x >= 0.0 ? ex : -ex, p.y >= 0.0 ? ey : -ey);
                float a;
                float base;
                if (p.x >= 0.0 && p.y < 0.0) { a = atan(v.x, -v.y); base = ex; }
                else if (p.x >= 0.0) { a = atan(v.y, v.x); base = ex + arc + 2.0 * ey; }
                else if (p.y >= 0.0) { a = atan(-v.x, v.y); base = 3.0 * ex + 2.0 * arc + 2.0 * ey; }
                else { a = atan(-v.y, -v.x); base = 3.0 * ex + 3.0 * arc + 4.0 * ey; }
                return float2(base + r * clamp(a, 0.0, 0.5 * PI), P);
            }
            bool onSide = ax > ex ? true : (ay > ey ? false : (halfSize.x - ax) < (halfSize.y - ay));
            if (onSide) {
                return float2(p.x >= 0.0 ? ex + arc + (p.y + ey) : 3.0 * ex + 3.0 * arc + 2.0 * ey + (ey - p.y), P);
            }
            return float2(p.y < 0.0 ? (p.x >= 0.0 ? p.x : P + p.x) : ex + 2.0 * arc + 2.0 * ey + (ex - p.x), P);
        }

        // How thick the colour on the edges is here, as a share of rimWidth: lumps with thin stretches.
        float rimThickness(float s, float P) {
            float t = 0.08;
            for (int i = 0; i < 7; i++) {
                float3 lump = lumps[i];
                float ds = s - lump.x;
                ds -= P * floor(ds / P + 0.5);
                t += lump.z * exp(-sq(ds / lump.y));
            }
            return t / 1.0498;
        }

        // Interleaved gradient noise: enough to hide banding, not enough to see.
        float dither(float2 p) {
            return fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715)))) - 0.5;
        }

        float3 colorAt(int k) {
            float3 c = pal[0];
            for (int i = 1; i < 7; i++) {
                if (i == k) c = pal[i];
            }
            return c;
        }

        // One colour from the ring of seven, mixed so neighbours never pass through grey.
        float3 paletteAt(float u) {
            float x = fract(u) * 7.0;
            int i = int(min(floor(x), 6.0));
            int j = i == 6 ? 0 : i + 1;
            float m = fract(x);
            m = m * m * (3.0 - 2.0 * m);
            return mix(colorAt(i), colorAt(j), m);
        }

        half4 main(float2 fc) {
            float2 p = fc / dens;
            float glow = f2.w;
            float rim = f3.y;
            float2 size = f2.xy;
            float2 c = p - size * 0.5;
            float inset = max(-roundedRectSDF(c, size * 0.5, cornerRadius), 0.0);
            bool nearEdge = rim > 0.0 && inset < max(rimWidth * 2.2 * (1.0 + 1.9 * unevenness), hairWidth * 0.5 + 0.75);
            if (glow <= 0.0 && !nearEdge) return half4(0.0);

            float2 n; float2 q; float g;
            float d = fieldAt(p, n, q, g);
            float time = f1.y;

            // The tail swells and thins along the front, and is lighter in the middle of the screen.
            float along = q.x * 0.0068;
            float lobes = 0.5 + 0.5 * sin(along * 1.3 + time * 1.4) * cos(along * 0.6 - time * 0.7 + 1.2);
            float bandA = 0.0;
            if (glow > 0.0 && d < lead) {
                if (d >= 0.0) {
                    float k = 1.0 - d / lead;
                    bandA = k * k * k * (k * (k * 6.0 - 15.0) + 10.0);
                } else {
                    bandA = pow(1.0 - clamp(-d / (tail * mix(0.6, 1.3, lobes)), 0.0, 1.0), 2.3);
                }
                bandA *= glow * strength * mix(0.6, 1.0, exp(-inset / 140.0));
            }

            float around = atan(c.y / size.y, c.x / size.x);
            float rimA = 0.0;
            float hairA = 0.0;
            float shimmer = 0.5;
            if (nearEdge) {
                float2 edge = perimeterAt(c, size * 0.5, cornerRadius);
                float thickness = 1.0;
                if (unevenness > 0.0) {
                    thickness = max(1.0 + (rimThickness(edge.x, edge.y) - 1.0) * unevenness, 0.04);
                }
                float hug = exp(-sq(inset / (rimWidth * thickness)));
                float reached = 1.0 - smoothstep(-lead * 0.7, lead * 0.5, d);
                float kept = smoothstep(-rimSoftness, rimSoftness, d + (f0.w - f3.x) / g);
                shimmer = 0.5 + 0.5 * sin(time * 3.4 - edge.x / edge.y * 4.0 * PI);
                rimA = hug * reached * kept * rim * mix(1.0, 0.7 + 0.3 * shimmer, pulse);

                if (hairWidth > 0.0) {
                    float hl = 1.0 - smoothstep(hairWidth * 0.5, hairWidth * 0.5 + 0.75, inset);
                    // Wiped by a later, slower front of its own, so the line outlasts its colour.
                    float hairKept = smoothstep(-rimSoftness, rimSoftness, d + (f0.w - f3.w) / g);
                    hairA = hl * reached * hairKept * rim * hairOpacity
                        * mix(0.5, 1.0, smoothstep(0.1, 1.5, thickness))
                        * mix(1.0, 0.55 + 0.45 * shimmer, pulse);
                }
            }

            float a = 1.0 - (1.0 - bandA) * (1.0 - rimA);
            float total = 1.0 - (1.0 - a) * (1.0 - hairA);
            if (total <= 0.0005) return half4(0.0);

            float flow = 0.62 + along * 0.2 + time * 0.13 + d * 0.0015 + 0.05 * sin(q.y * 0.009 + time);
            float3 lab = paletteAt(flow);
            lab = mix(lab, pal[7], 0.5 * exp(-sq((d - 30.0) / 50.0)));
            lab = mix(lab, pal[8], 0.5 * exp(-sq((d + 84.0) / 72.0)));
            lab.yz *= 1.22;
            lab = mix(lab, float3(0.99, 0.0, 0.0), 0.7 * exp(-sq((d + 8.0) / 26.0)));

            float3 rimLab = paletteAt(around / (2.0 * PI) + time * (0.12 + 0.25 * pulse) + inset * 0.0012);
            rimLab.yz *= 1.1;
            lab = mix(lab, rimLab, rimA / (rimA + bandA + 0.0001));

            // The hairline over the glow: the edge's own colour, whitening where the pulse's light passes.
            float3 rgb = oklabToSRGB(lab);
            if (hairA > 0.0) {
                float3 hair = mix(oklabToSRGB(rimLab), float3(1.0), hairWhite * (0.5 + 0.5 * shimmer));
                rgb = (rgb * a * (1.0 - hairA) + hair * hairA) / total;
            }

            float noise = dither(fc) * 2.0 / 255.0;
            return half4(half3(clamp(rgb * total + noise, 0.0, 1.0)), half(clamp(total + noise, 0.0, 1.0)));
        }
    """
}
