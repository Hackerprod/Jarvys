package com.jarvys.agent.linux

/** Pinned official Ubuntu Base release. The image is downloaded only for Full flavor. */
object LinuxCatalog {
    const val VERSION = "24.04.5"
    const val ARCH = "arm64"
    const val URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
    const val SHA256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
    const val DOWNLOAD_BYTES = 29_936_675L
    /** Allocated size from du -s after extracting this exact archive on the build host. */
    const val EXTRACTED_BYTES = 110_321_664L
    val release = LinuxRelease(VERSION, ARCH, URL, SHA256, DOWNLOAD_BYTES, EXTRACTED_BYTES)
}

data class LinuxRelease(
    val version: String,
    val arch: String,
    val url: String,
    val sha256: String,
    val downloadBytes: Long,
    val extractedBytes: Long,
)
