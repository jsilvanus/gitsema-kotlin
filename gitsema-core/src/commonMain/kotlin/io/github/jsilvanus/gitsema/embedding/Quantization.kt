package io.github.jsilvanus.gitsema.embedding

import kotlin.math.round

/**
 * A quantized vector: per-vector asymmetric min/max scaling, matching
 * gitsema-TS's `quantize.ts` exactly (kotlin-port.md §3.4) — not a global or
 * per-model scale, so [min]/[scale] must travel with [data], never be
 * factored out or assumed constant across vectors.
 */
data class QuantizedVector(val min: Float, val scale: Float, val data: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is QuantizedVector && min == other.min && scale == other.scale && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * (31 * min.hashCode() + scale.hashCode()) + data.contentHashCode()
}

/**
 * int8 quantization, default (not opt-in) per kotlin-port.md §9.2/Decision C
 * #9 — a phone doesn't have the "disk/RAM are cheap" luxury gitsema-TS's
 * `--quantize` flag assumes. Exactly 4x smaller than the raw Float32Array on
 * the wire (1 byte/dim vs. 4); the accuracy floor to re-validate against real
 * on-device models is gitsema-TS's own test bar: round-trip cosine similarity
 * should stay above ~0.98 (kotlin-port.md §3.4 — the "~1% recall loss" figure
 * in gitsema-TS's docs is an optimistic rounding of that looser 2% bound, not
 * a sourced measurement; treat it the same way here).
 */
fun quantizeVector(vector: FloatArray): QuantizedVector {
    var minV = Float.POSITIVE_INFINITY
    var maxV = Float.NEGATIVE_INFINITY
    for (v in vector) {
        if (v < minV) minV = v
        if (v > maxV) maxV = v
    }
    val range = (maxV - minV).let { if (it == 0f) 1f else it }
    val scale = range / 255f
    val data = ByteArray(vector.size)
    for (i in vector.indices) {
        val q = round((vector[i] - minV) / scale).toInt() - 128
        data[i] = q.coerceIn(-128, 127).toByte()
    }
    return QuantizedVector(minV, scale, data)
}

/** Inverse of [quantizeVector] — `(data[i] + 128) * scale + min`. */
fun dequantizeVector(quantized: QuantizedVector): FloatArray {
    val out = FloatArray(quantized.data.size)
    for (i in out.indices) {
        out[i] = (quantized.data[i].toInt() + 128) * quantized.scale + quantized.min
    }
    return out
}

/**
 * Cosine similarity between a raw query vector and a quantized candidate,
 * dequantizing element-by-element without allocating an intermediate
 * [FloatArray] — this is the per-candidate hot path in [io.github.jsilvanus.gitsema.storage.VectorStore.search]
 * (kotlin-port.md §9.2 point 3: score directly off the mapped buffer, never
 * materialize the whole candidate pool).
 */
fun cosineSimilarityQuantized(query: FloatArray, candidateData: ByteArray, candidateMin: Float, candidateScale: Float): Double {
    var dot = 0.0
    var queryNormSq = 0.0
    var candNormSq = 0.0
    for (i in query.indices) {
        val candVal = (candidateData[i].toInt() + 128) * candidateScale + candidateMin
        dot += query[i] * candVal
        queryNormSq += query[i].toDouble() * query[i].toDouble()
        candNormSq += candVal * candVal
    }
    val denom = kotlin.math.sqrt(queryNormSq) * kotlin.math.sqrt(candNormSq)
    return if (denom == 0.0) 0.0 else dot / denom
}
