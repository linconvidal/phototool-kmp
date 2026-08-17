package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.domain.Photo
import java.nio.file.Path

/** A bounded set of reentrant JVM locks shared by every editorial store. */
internal object EditorialPhotoLocks {
    private const val STRIPE_COUNT = 4096
    private val locks = Array(STRIPE_COUNT) { Any() }

    internal val capacity: Int get() = locks.size

    internal fun stripeIdentity(realRoot: Path, photo: Photo): Any =
        locks[Math.floorMod("$realRoot\u0000${photo.id}".hashCode(), locks.size)]

    fun <T> withLock(realRoot: Path, photo: Photo, block: () -> T): T =
        synchronized(stripeIdentity(realRoot, photo), block)
}
