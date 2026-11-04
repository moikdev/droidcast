package dev.tinycast.android.extensions

import java.lang.ref.WeakReference

/** Bounded diagnostic labels for equal render values, without retaining them. */
internal class WeakValueTokens<T : Any>(private val capacity: Int = 128,
    private val reference: (T) -> WeakReference<T> = { WeakReference(it) }) {
    init { require(capacity in 1..128) }
    private val values = mutableListOf<Pair<WeakReference<T>, Int>>()
    private var next = 0
    @Volatile var evictions = 0
        private set

    @Synchronized fun token(value: T): Int {
        values.removeAll { it.first.get() == null }
        values.firstOrNull { it.first.get() === value }?.let { return it.second }
        val token = values.firstOrNull { it.first.get() == value }?.second ?: ++next
        // Register each live equal wrapper. Retiring an older wrapper must
        // not discard the replacement's label while it is being drawn.
        values += reference(value) to token
        if (values.size > capacity) { values.removeAt(0); evictions++ }
        return token
    }
}
