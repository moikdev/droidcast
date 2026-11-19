package dev.tinycast.android.extensions

/** JPEG decoders may round each sampled dimension up to the next pixel. */
internal fun rasterSampleSize(longestEdge: Int, targetEdge: Int): Int {
    var sample = 1
    while (longestEdge > targetEdge.toLong() * sample) sample *= 2
    return sample
}
