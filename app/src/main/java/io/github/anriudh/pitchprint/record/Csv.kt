package io.github.anriudh.pitchprint.record

// Numbers go through toString, which ignores the locale and round-trips exactly, so nothing is
// rounded and a comma-decimal phone still writes valid CSV. Null leaves the field empty.

internal fun StringBuilder.col(v: Long): StringBuilder = append(',').append(v)

internal fun StringBuilder.col(v: Int): StringBuilder = append(',').append(v)

internal fun StringBuilder.col(v: Float): StringBuilder = append(',').append(v)

internal fun StringBuilder.col(v: Double): StringBuilder = append(',').append(v)

internal fun StringBuilder.col(v: Any?): StringBuilder {
    append(',')
    if (v != null) append(v)
    return this
}

internal fun bit(b: Boolean): Int = if (b) 1 else 0
