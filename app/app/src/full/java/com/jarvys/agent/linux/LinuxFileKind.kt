package com.jarvys.agent.linux

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

enum class LinuxFileKind { DIRECTORY, SYMLINK, OTHER, MISSING, UNKNOWN }

/** Production classifier: lstat reports the entry itself and never follows a symlink. */
fun interface LinuxFileKindReader {
    fun kind(file: File): LinuxFileKind
}

object AndroidLinuxFileKindReader : LinuxFileKindReader {
    override fun kind(file: File): LinuxFileKind = try {
        when (Os.lstat(file.absolutePath).st_mode.toInt() and OsConstants.S_IFMT) {
            OsConstants.S_IFDIR -> LinuxFileKind.DIRECTORY
            OsConstants.S_IFLNK -> LinuxFileKind.SYMLINK
            else -> LinuxFileKind.OTHER
        }
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT || failure.errno == OsConstants.ENOTDIR) LinuxFileKind.MISSING
        else LinuxFileKind.UNKNOWN
    } catch (_: RuntimeException) {
        LinuxFileKind.UNKNOWN
    }
}
