package com.frak89.cloudyscanner.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** Quad-dominant surface mesh. Triangles are derived by splitting each quad in two. */
/** A named part of a mesh (one object in Blender): a run of faces and of n-gons. */
class MeshGroup(val name: String, val faceStart: Int, val faceEnd: Int, val ngonStart: Int, val ngonEnd: Int)

class MeshData(
    val pos: FloatArray,
    val nrm: FloatArray,
    val col: IntArray,
    val quads: IntArray,
    val cell: Float,
    /** Flat faces with more than four sides (the end caps of round solids), as vertex loops. */
    val ngons: List<IntArray> = emptyList(),
    val groups: List<MeshGroup> = emptyList(),
) {
    /** Faces stored in [quads]; a face whose last two indices are equal is a lone triangle. */
    val faceCount get() = quads.size / 4
    val vertexCount get() = pos.size / 3
    /** True four-sided faces. */
    val quadCount: Int
    /** Triangles after splitting every quad in two, plus the lone triangles. */
    val triangleCount: Int

    init {
        var q = 0
        for (f in 0 until quads.size / 4) if (quads[f * 4 + 2] != quads[f * 4 + 3]) q++
        quadCount = q
        var t = q * 2 + (quads.size / 4 - q)
        for (g in ngons) t += g.size - 2
        triangleCount = t
    }

    val ngonCount get() = ngons.size

    fun triangleIndices(): IntArray {
        val t = IntArray(triangleCount * 3)
        var o = 0
        for (q in 0 until faceCount) {
            val a = quads[q * 4]; val b = quads[q * 4 + 1]; val c = quads[q * 4 + 2]; val d = quads[q * 4 + 3]
            t[o] = a; t[o + 1] = b; t[o + 2] = c; o += 3
            if (c != d) { t[o] = a; t[o + 1] = c; t[o + 2] = d; o += 3 }
        }
        for (g in ngons) { val tri = earClip(g); System.arraycopy(tri, 0, t, o, tri.size); o += tri.size }
        return t
    }

    /**
     * Triangulates one flat n-gon, concave ones included (ear clipping in the polygon's own
     * plane). Keeps the n-gon's winding, so the triangles face the same way.
     */
    fun earClip(g: IntArray): IntArray {
        val n = g.size
        val out = IntArray((n - 2) * 3)
        if (n == 3) { out[0] = g[0]; out[1] = g[1]; out[2] = g[2]; return out }
        // Newell normal, then drop its largest axis to work in 2D.
        var nx = 0f; var ny = 0f; var nz = 0f
        for (i in 0 until n) {
            val a = g[i] * 3; val b = g[(i + 1) % n] * 3
            nx += (pos[a + 1] - pos[b + 1]) * (pos[a + 2] + pos[b + 2])
            ny += (pos[a + 2] - pos[b + 2]) * (pos[a] + pos[b])
            nz += (pos[a] - pos[b]) * (pos[a + 1] + pos[b + 1])
        }
        val ax = kotlin.math.abs(nx); val ay = kotlin.math.abs(ny); val az = kotlin.math.abs(nz)
        val ia: Int; val ib: Int; val sgn: Float
        if (ax >= ay && ax >= az) { ia = 1; ib = 2; sgn = if (nx >= 0) 1f else -1f }
        else if (ay >= az) { ia = 2; ib = 0; sgn = if (ny >= 0) 1f else -1f }
        else { ia = 0; ib = 1; sgn = if (nz >= 0) 1f else -1f }
        val px = FloatArray(n) { pos[g[it] * 3 + ia] }; val py = FloatArray(n) { pos[g[it] * 3 + ib] * sgn }
        val idx = ArrayList<Int>(n); for (i in 0 until n) idx.add(i)
        var o = 0
        fun cross(a: Int, b: Int, c: Int) = (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])
        var guard = 0
        while (idx.size > 3 && guard++ < n * n) {
            var clipped = false
            for (k in idx.indices) {
                val a = idx[(k + idx.size - 1) % idx.size]; val b = idx[k]; val c = idx[(k + 1) % idx.size]
                if (cross(a, b, c) <= 1e-12f) continue                       // reflex corner
                var inside = false
                for (q in idx) {
                    if (q == a || q == b || q == c) continue
                    if (cross(a, b, q) >= 0f && cross(b, c, q) >= 0f && cross(c, a, q) >= 0f) { inside = true; break }
                }
                if (inside) continue
                out[o] = g[a]; out[o + 1] = g[b]; out[o + 2] = g[c]; o += 3
                idx.removeAt(k); clipped = true; break
            }
            if (!clipped) break                                                // degenerate: fan the rest
        }
        for (k in 1 until idx.size - 1) { if (o + 3 > out.size) break; out[o] = g[idx[0]]; out[o + 1] = g[idx[k]]; out[o + 2] = g[idx[k + 1]]; o += 3 }
        return out
    }

    /** Line list of face outlines; with [diagonals] the triangle split edge is added too. */
    fun wireIndices(diagonals: Boolean): IntArray {
        val per = if (diagonals) 10 else 8
        val t = IntArray(faceCount * per)
        for (q in 0 until faceCount) {
            val a = quads[q * 4]; val b = quads[q * 4 + 1]; val c = quads[q * 4 + 2]; val d = quads[q * 4 + 3]
            val o = q * per
            t[o] = a; t[o + 1] = b; t[o + 2] = b; t[o + 3] = c; t[o + 4] = c; t[o + 5] = d; t[o + 6] = d; t[o + 7] = a
            if (diagonals) { t[o + 8] = a; t[o + 9] = c }
        }
        if (ngons.isEmpty()) return t
        // N-gon outlines (plus the fan edges when triangles are shown) follow the regular faces.
        val extra = ArrayList<Int>()
        for (g in ngons) {
            for (k in g.indices) { extra.add(g[k]); extra.add(g[(k + 1) % g.size]) }
            if (diagonals) for (k in 2 until g.size - 1) { extra.add(g[0]); extra.add(g[k]) }
        }
        return t + extra.toIntArray()
    }
}

/**
 * Surface reconstruction from oriented points.
 *
 * Each point defines a tangent plane; a signed distance field is blended on a regular grid
 * (positive on the side the camera saw) and the zero surface is extracted with Surface Nets,
 * which yields one vertex per crossed cell and one quad per crossed grid edge.
 */
object Mesher {

    private class Grow(cap: Int) {
        var d = FloatArray(cap); var w = FloatArray(cap)
        var r = FloatArray(cap); var g = FloatArray(cap); var b = FloatArray(cap)
        var key = LongArray(cap)
        var n = 0
        fun add(k: Long): Int {
            if (n == key.size) {
                val c = n + n / 2 + 16
                d = d.copyOf(c); w = w.copyOf(c); r = r.copyOf(c); g = g.copyOf(c); b = b.copyOf(c); key = key.copyOf(c)
            }
            key[n] = k
            return n++
        }
    }

    /**
     * @param n number of input points
     * @param h grid cell size in metres (bigger = fewer polygons)
     * @param cancel polled periodically; return true to abort (result is then null)
     */
    fun build(
        n: Int, pos: FloatArray, nrm: FloatArray, col: IntArray, h: Float,
        minWeight: Float = 0.5f,
        cancel: (() -> Boolean)? = null,
        progress: ((Float) -> Unit)? = null,
    ): MeshData? {
        if (n == 0) return MeshData(FloatArray(0), FloatArray(0), IntArray(0), IntArray(0), h)

        // --- 0. Thin the input to roughly two samples per cell so cost follows the output size.
        var pn = n; var pp = pos; var pnr = nrm; var pc = col
        run {
            val b = h * 0.5f
            val ib = 1f / b
            val m = LongIntMap(n / 2 + 16)
            val g = Grow(n / 4 + 16)
            val sx = ArrayList<FloatArray>(1)
            var acc = FloatArray((n / 4 + 16) * 6)
            var cnt = IntArray(n / 4 + 16)
            for (i in 0 until n) {
                val x = pos[i * 3]; val y = pos[i * 3 + 1]; val z = pos[i * 3 + 2]
                val k = LongIntMap.pack(floor(x * ib).toInt(), floor(y * ib).toInt(), floor(z * ib).toInt())
                var j = m.get(k)
                if (j < 0) {
                    j = g.add(k); m.put(k, j)
                    if (j * 6 + 6 > acc.size) { acc = acc.copyOf(acc.size + acc.size / 2 + 6); cnt = cnt.copyOf(acc.size / 6) }
                }
                val o = j * 6
                acc[o] += x; acc[o + 1] += y; acc[o + 2] += z
                acc[o + 3] += nrm[i * 3]; acc[o + 4] += nrm[i * 3 + 1]; acc[o + 5] += nrm[i * 3 + 2]
                val c = col[i]
                g.r[j] += ((c shr 16) and 255).toFloat(); g.g[j] += ((c shr 8) and 255).toFloat(); g.b[j] += (c and 255).toFloat()
                cnt[j]++
            }
            sx.clear()
            pn = g.n
            pp = FloatArray(pn * 3); pnr = FloatArray(pn * 3); pc = IntArray(pn)
            for (j in 0 until pn) {
                val o = j * 6; val ic = 1f / cnt[j]
                pp[j * 3] = acc[o] * ic; pp[j * 3 + 1] = acc[o + 1] * ic; pp[j * 3 + 2] = acc[o + 2] * ic
                pnr[j * 3] = acc[o + 3]; pnr[j * 3 + 1] = acc[o + 4]; pnr[j * 3 + 2] = acc[o + 5]
                pc[j] = (0xFF shl 24) or ((g.r[j] * ic).toInt() shl 16) or ((g.g[j] * ic).toInt() shl 8) or (g.b[j] * ic).toInt()
            }
        }
        if (cancel?.invoke() == true) return null
        progress?.invoke(0.1f)

        // --- 1. Blend signed plane distances onto grid corners within two cells of each point.
        val ih = 1f / h
        val reach = 2f * h
        val corners = LongIntMap(pn * 16)
        val f = Grow(pn * 24 + 64)
        for (i in 0 until pn) {
            if ((i and 4095) == 0) {
                if (cancel?.invoke() == true) return null
                progress?.invoke(0.1f + 0.5f * i / pn)
            }
            val x = pp[i * 3]; val y = pp[i * 3 + 1]; val z = pp[i * 3 + 2]
            var nx = pnr[i * 3]; var ny = pnr[i * 3 + 1]; var nz = pnr[i * 3 + 2]
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl < 1e-6f) continue
            nx /= nl; ny /= nl; nz /= nl
            val c = pc[i]
            val cr = ((c shr 16) and 255).toFloat(); val cg = ((c shr 8) and 255).toFloat(); val cb = (c and 255).toFloat()
            val gx = floor(x * ih).toInt(); val gy = floor(y * ih).toInt(); val gz = floor(z * ih).toInt()
            for (dz in -1..2) {
                val cz = (gz + dz) * h - z
                for (dy in -1..2) {
                    val cy = (gy + dy) * h - y
                    for (dx in -1..2) {
                        val cx = (gx + dx) * h - x
                        val dist = sqrt(cx * cx + cy * cy + cz * cz)
                        if (dist >= reach) continue
                        val q = dist / reach
                        val w = 1f - q * q
                        val k = LongIntMap.pack(gx + dx, gy + dy, gz + dz)
                        var j = corners.get(k)
                        if (j < 0) { j = f.add(k); corners.put(k, j) }
                        f.d[j] += w * (nx * cx + ny * cy + nz * cz)
                        f.w[j] += w
                        f.r[j] += w * cr; f.g[j] += w * cg; f.b[j] += w * cb
                    }
                }
            }
        }
        val nc = f.n
        for (j in 0 until nc) {
            if (f.w[j] >= minWeight) {
                val iw = 1f / f.w[j]
                f.d[j] *= iw; f.r[j] *= iw; f.g[j] *= iw; f.b[j] *= iw
            } else f.w[j] = 0f
        }
        if (cancel?.invoke() == true) return null
        progress?.invoke(0.65f)

        // --- 2. One vertex per cell whose 8 corners are known and straddle the surface.
        var vp = FloatArray(1024 * 3); var vn = FloatArray(1024 * 3); var vc = IntArray(1024)
        var vf = FloatArray(1024 * 4); var vcell = LongArray(1024)
        var nv = 0
        val cellVert = LongIntMap(nc / 4 + 16)
        val ci = IntArray(8)
        val cv = FloatArray(8)
        for (j in 0 until nc) {
            if ((j and 8191) == 0 && cancel?.invoke() == true) return null
            if (f.w[j] == 0f) continue
            val k = f.key[j]
            val ix = LongIntMap.ux(k); val iy = LongIntMap.uy(k); val iz = LongIntMap.uz(k)
            ci[0] = j
            var ok = true
            for (c in 1 until 8) {
                val q = corners.get(LongIntMap.pack(ix + (c and 1), iy + ((c shr 1) and 1), iz + ((c shr 2) and 1)))
                if (q < 0 || f.w[q] == 0f) { ok = false; break }
                ci[c] = q
            }
            if (!ok) continue
            var neg = 0
            for (c in 0 until 8) { cv[c] = f.d[ci[c]]; if (cv[c] < 0f) neg++ }
            if (neg == 0 || neg == 8) continue

            var sx = 0f; var sy = 0f; var sz = 0f; var cnt = 0
            for (e in 0 until 12) {
                val a = EDGE_A[e]; val b = EDGE_B[e]
                val fa = cv[a]; val fb = cv[b]
                if ((fa < 0f) == (fb < 0f)) continue
                val t = fa / (fa - fb)
                sx += (a and 1) + t * ((b and 1) - (a and 1))
                sy += ((a shr 1) and 1) + t * (((b shr 1) and 1) - ((a shr 1) and 1))
                sz += ((a shr 2) and 1) + t * (((b shr 2) and 1) - ((a shr 2) and 1))
                cnt++
            }
            if (nv * 3 + 3 > vp.size) {
                val c = nv + nv / 2
                vp = vp.copyOf(c * 3); vn = vn.copyOf(c * 3); vc = vc.copyOf(c); vf = vf.copyOf(c * 4); vcell = vcell.copyOf(c)
            }
            val inv = 1f / cnt
            vp[nv * 3] = (ix + sx * inv) * h; vp[nv * 3 + 1] = (iy + sy * inv) * h; vp[nv * 3 + 2] = (iz + sz * inv) * h
            var gx = (cv[1] + cv[3] + cv[5] + cv[7]) - (cv[0] + cv[2] + cv[4] + cv[6])
            var gy = (cv[2] + cv[3] + cv[6] + cv[7]) - (cv[0] + cv[1] + cv[4] + cv[5])
            var gz = (cv[4] + cv[5] + cv[6] + cv[7]) - (cv[0] + cv[1] + cv[2] + cv[3])
            val gl = max(1e-9f, sqrt(gx * gx + gy * gy + gz * gz))
            gx /= gl; gy /= gl; gz /= gl
            vn[nv * 3] = gx; vn[nv * 3 + 1] = gy; vn[nv * 3 + 2] = gz
            var r = 0f; var g = 0f; var b = 0f
            for (c in 0 until 8) { r += f.r[ci[c]]; g += f.g[ci[c]]; b += f.b[ci[c]] }
            vc[nv] = (0xFF shl 24) or ((r / 8f).toInt().coerceIn(0, 255) shl 16) or
                ((g / 8f).toInt().coerceIn(0, 255) shl 8) or (b / 8f).toInt().coerceIn(0, 255)
            vf[nv * 4] = cv[0]; vf[nv * 4 + 1] = cv[1]; vf[nv * 4 + 2] = cv[2]; vf[nv * 4 + 3] = cv[4]
            vcell[nv] = k
            cellVert.put(k, nv)
            nv++
        }
        if (cancel?.invoke() == true) return null
        progress?.invoke(0.85f)

        // --- 3. One quad per crossed grid edge, joining the four cells around it.
        var quads = IntArray(nv * 4 + 16)
        var nq = 0
        for (v in 0 until nv) {
            val k = vcell[v]
            val ix = LongIntMap.ux(k); val iy = LongIntMap.uy(k); val iz = LongIntMap.uz(k)
            val f0 = vf[v * 4]
            for (a in 0 until 3) {
                val fa = vf[v * 4 + 1 + a]
                if ((f0 < 0f) == (fa < 0f)) continue
                val u = (a + 1) % 3; val w = (a + 2) % 3
                val ux = if (u == 0) 1 else 0; val uy = if (u == 1) 1 else 0; val uz = if (u == 2) 1 else 0
                val wx = if (w == 0) 1 else 0; val wy = if (w == 1) 1 else 0; val wz = if (w == 2) 1 else 0
                val b = cellVert.get(LongIntMap.pack(ix - ux, iy - uy, iz - uz))
                val c = cellVert.get(LongIntMap.pack(ix - ux - wx, iy - uy - wy, iz - uz - wz))
                val d = cellVert.get(LongIntMap.pack(ix - wx, iy - wy, iz - wz))
                if (b < 0 || c < 0 || d < 0) continue
                // Face normal along axis a must point from negative (inside) to positive (outside).
                val e1x = vp[c * 3] - vp[v * 3]; val e1y = vp[c * 3 + 1] - vp[v * 3 + 1]; val e1z = vp[c * 3 + 2] - vp[v * 3 + 2]
                val e2x = vp[d * 3] - vp[b * 3]; val e2y = vp[d * 3 + 1] - vp[b * 3 + 1]; val e2z = vp[d * 3 + 2] - vp[b * 3 + 2]
                val nA = when (a) {
                    0 -> e1y * e2z - e1z * e2y
                    1 -> e1z * e2x - e1x * e2z
                    else -> e1x * e2y - e1y * e2x
                }
                val wantPositive = f0 < 0f
                if (nq * 4 + 4 > quads.size) quads = quads.copyOf(quads.size + quads.size / 2)
                val o = nq * 4
                if ((nA > 0f) == wantPositive) {
                    quads[o] = v; quads[o + 1] = b; quads[o + 2] = c; quads[o + 3] = d
                } else {
                    quads[o] = v; quads[o + 1] = d; quads[o + 2] = c; quads[o + 3] = b
                }
                nq++
            }
        }
        progress?.invoke(1f)
        return weld(vp, vn, vc, nv, quads, nq, h)
    }

    /**
     * Where the surface hugs a grid plane, Surface Nets emits paper-thin sliver faces between
     * near-coincident vertices. Merge those vertices and drop the collapsed faces.
     */
    private fun weld(vp: FloatArray, vn: FloatArray, vc: IntArray, nv: Int, quads: IntArray, nq: Int, h: Float): MeshData {
        val parent = IntArray(nv) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var y = x
            while (parent[y] != r) { val t = parent[y]; parent[y] = r; y = t }
            return r
        }
        val lim = (0.3f * h) * (0.3f * h)
        for (q in 0 until nq) for (j in 0 until 4) {
            val a = quads[q * 4 + j]; val b = quads[q * 4 + (j + 1) % 4]
            val dx = vp[a * 3] - vp[b * 3]; val dy = vp[a * 3 + 1] - vp[b * 3 + 1]; val dz = vp[a * 3 + 2] - vp[b * 3 + 2]
            if (dx * dx + dy * dy + dz * dz < lim) { val ra = find(a); val rb = find(b); if (ra != rb) parent[rb] = ra }
        }
        val acc = FloatArray(nv * 6); val cnt = IntArray(nv)
        for (v in 0 until nv) {
            val r = find(v)
            for (c in 0 until 3) { acc[r * 6 + c] += vp[v * 3 + c]; acc[r * 6 + 3 + c] += vn[v * 3 + c] }
            cnt[r]++
        }
        for (v in 0 until nv) if (cnt[v] > 1) {
            val ic = 1f / cnt[v]
            for (c in 0 until 3) { vp[v * 3 + c] = acc[v * 6 + c] * ic; vn[v * 3 + c] = acc[v * 6 + 3 + c] * ic }
            val l = max(1e-9f, sqrt(vn[v * 3] * vn[v * 3] + vn[v * 3 + 1] * vn[v * 3 + 1] + vn[v * 3 + 2] * vn[v * 3 + 2]))
            vn[v * 3] /= l; vn[v * 3 + 1] /= l; vn[v * 3 + 2] /= l
        }
        val out = IntArray(nq * 4)
        var k = 0
        val u = IntArray(4)
        for (q in 0 until nq) {
            var m = 0
            for (j in 0 until 4) {
                val r = find(quads[q * 4 + j])
                if (m == 0 || (u[m - 1] != r && !(j == 3 && u[0] == r))) {
                    var dup = false
                    for (t in 0 until m) if (u[t] == r) dup = true
                    if (dup) { m = 0; break }
                    u[m++] = r
                }
            }
            if (m < 3) continue
            out[k * 4] = u[0]; out[k * 4 + 1] = u[1]; out[k * 4 + 2] = u[2]; out[k * 4 + 3] = if (m == 4) u[3] else u[2]
            k++
        }
        return compact(vp, vn, vc, nv, out, k, h)
    }

    /** Drops vertices not referenced by any quad. */
    private fun compact(vp: FloatArray, vn: FloatArray, vc: IntArray, nv: Int, quads: IntArray, nq: Int, h: Float): MeshData {
        val remap = IntArray(nv) { -1 }
        var m = 0
        for (i in 0 until nq * 4) { val v = quads[i]; if (remap[v] < 0) remap[v] = m++ }
        val p = FloatArray(m * 3); val nn = FloatArray(m * 3); val c = IntArray(m)
        for (v in 0 until nv) {
            val r = remap[v]; if (r < 0) continue
            System.arraycopy(vp, v * 3, p, r * 3, 3); System.arraycopy(vn, v * 3, nn, r * 3, 3); c[r] = vc[v]
        }
        val q = IntArray(nq * 4) { remap[quads[it]] }
        return MeshData(p, nn, c, q, h)
    }

    /** Removes connected pieces with fewer than [minQuads] quads. */
    fun removeIslands(m: MeshData, minQuads: Int): MeshData {
        val nv = m.vertexCount; val nq = m.faceCount
        if (nq == 0) return m
        val parent = IntArray(nv) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var y = x; while (parent[y] != r) { val t = parent[y]; parent[y] = r; y = t }; return r }
        for (q in 0 until nq) {
            val a = find(m.quads[q * 4])
            for (j in 1 until 4) { val b = find(m.quads[q * 4 + j]); if (b != a) parent[b] = a }
        }
        val count = IntArray(nv)
        for (q in 0 until nq) count[find(m.quads[q * 4])]++
        var keep = IntArray(nq * 4); var k = 0
        for (q in 0 until nq) if (count[find(m.quads[q * 4])] >= minQuads) {
            System.arraycopy(m.quads, q * 4, keep, k * 4, 4); k++
        }
        if (k == nq) return m
        keep = keep.copyOf(k * 4)
        return compact(m.pos, m.nrm, m.col, nv, keep, k, m.cell)
    }

    /** Laplacian smoothing along quad edges; keeps topology and counts unchanged. */
    fun smooth(m: MeshData, iterations: Int, strength: Float = 0.5f): MeshData {
        val nv = m.vertexCount
        var p = m.pos.copyOf()
        val acc = FloatArray(nv * 3); val deg = IntArray(nv)
        repeat(iterations) {
            acc.fill(0f); deg.fill(0)
            for (q in 0 until m.faceCount) for (j in 0 until 4) {
                val a = m.quads[q * 4 + j]; val b = m.quads[q * 4 + (j + 1) % 4]
                if (a == b) continue
                acc[a * 3] += p[b * 3]; acc[a * 3 + 1] += p[b * 3 + 1]; acc[a * 3 + 2] += p[b * 3 + 2]; deg[a]++
                acc[b * 3] += p[a * 3]; acc[b * 3 + 1] += p[a * 3 + 1]; acc[b * 3 + 2] += p[a * 3 + 2]; deg[b]++
            }
            val np = p.copyOf()
            for (v in 0 until nv) if (deg[v] > 0) for (c in 0 until 3) {
                np[v * 3 + c] += (acc[v * 3 + c] / deg[v] - p[v * 3 + c]) * strength
            }
            p = np
        }
        // Recompute normals from the smoothed faces.
        val n = FloatArray(nv * 3)
        for (q in 0 until m.faceCount) {
            val a = m.quads[q * 4]; val b = m.quads[q * 4 + 1]; val c = m.quads[q * 4 + 2]; val d = m.quads[q * 4 + 3]
            val e1x = p[c * 3] - p[a * 3]; val e1y = p[c * 3 + 1] - p[a * 3 + 1]; val e1z = p[c * 3 + 2] - p[a * 3 + 2]
            val e2x = p[d * 3] - p[b * 3]; val e2y = p[d * 3 + 1] - p[b * 3 + 1]; val e2z = p[d * 3 + 2] - p[b * 3 + 2]
            val x = e1y * e2z - e1z * e2y; val y = e1z * e2x - e1x * e2z; val z = e1x * e2y - e1y * e2x
            for (v in intArrayOf(a, b, c, d)) { n[v * 3] += x; n[v * 3 + 1] += y; n[v * 3 + 2] += z }
        }
        for (v in 0 until nv) {
            val l = max(1e-12f, sqrt(n[v * 3] * n[v * 3] + n[v * 3 + 1] * n[v * 3 + 1] + n[v * 3 + 2] * n[v * 3 + 2]))
            n[v * 3] /= l; n[v * 3 + 1] /= l; n[v * 3 + 2] /= l
        }
        return MeshData(p, n, m.col, m.quads, m.cell)
    }

    // Cube corner c has offsets (c&1, (c>>1)&1, (c>>2)&1).
    private val EDGE_A = intArrayOf(0, 2, 4, 6, 0, 1, 4, 5, 0, 1, 2, 3)
    private val EDGE_B = intArrayOf(1, 3, 5, 7, 2, 3, 6, 7, 4, 5, 6, 7)
}
