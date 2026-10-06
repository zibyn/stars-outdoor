package com.starsdom.trail.track

// Not yet on iOS: these files fail to import as unreadable ones do.

// TODO(iOS): inflate with zlib (platform.zlib), keeping the same cap on what's inflated.
internal actual fun unzip(bytes: ByteArray, limit: Long): List<Pair<String, ByteArray>> = error("zip not read on iOS yet")

// TODO(iOS): find out whether Garmin ships a FIT SDK iOS can use (Swift / Objective-C), or decode the records by hand.
internal actual fun parseFit(bytes: ByteArray): TrackFile = error("FIT not read on iOS yet")

// TODO(iOS): NSString with CFStringConvertIANACharSetNameToEncoding.
internal actual fun decode(bytes: ByteArray, charset: String): String = error("$charset text not read on iOS yet")
