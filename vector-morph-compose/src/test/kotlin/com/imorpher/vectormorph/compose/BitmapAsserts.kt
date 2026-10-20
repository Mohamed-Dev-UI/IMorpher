import android.graphics.Bitmap

/** Shared pixel-comparison helpers for the Robolectric render tests. */
internal object BitmapAsserts {

    /** 0 = identical pixels, 1 = maximally different. */
    fun meanAbsDifference(a: Bitmap, b: Bitmap): Float {
        require(a.width == b.width && a.height == b.height)
        var sum = 0L
        val total = a.width * a.height
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                sum += kotlin.math.abs((pa ushr 24 and 0xFF) - (pb ushr 24 and 0xFF)) +
                    kotlin.math.abs((pa ushr 16 and 0xFF) - (pb ushr 16 and 0xFF)) +
                    kotlin.math.abs((pa ushr 8 and 0xFF) - (pb ushr 8 and 0xFF)) +
                    kotlin.math.abs(pa and 0xFF - pb and 0xFF)
            }
        }
        return sum / (255f * 4f * total)
    }

    fun paintedCount(bitmap: Bitmap): Int {
        var painted = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != 0) painted++
            }
        }
        return painted
    }

    fun meanAlpha(bitmap: Bitmap): Float {
        var sum = 0L
        val total = bitmap.width * bitmap.height
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                sum += (bitmap.getPixel(x, y) ushr 24) and 0xFF
            }
        }
        return sum / (255f * total)
    }
}
