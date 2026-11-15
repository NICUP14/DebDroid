package io.github.nicup14.debdroid

// The image the app may install for one architecture, as rootfs/manifest
// records it. "size" is the uncompressed size in bytes.
data class PinnedImage(val sha256: String, val size: Long)
