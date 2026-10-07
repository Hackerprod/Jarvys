package com.jarvys.agent.linux

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream

interface RootfsTarFileOps {
    fun openNewRegular(file: File): OutputStream
    fun symlink(target: String, link: File)
    fun hardlink(source: File, link: File)
    fun chmod(file: File, mode: Int)
}

internal object AndroidRootfsTarFileOps : RootfsTarFileOps {
    override fun openNewRegular(file: File): OutputStream {
        val descriptor: FileDescriptor = Os.open(file.absolutePath,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_NOFOLLOW,
            0x180 /* 0600 */)
        return FileOutputStream(descriptor)
    }
    override fun symlink(target: String, link: File) = Os.symlink(target, link.absolutePath)
    override fun hardlink(source: File, link: File) = Os.link(source.absolutePath, link.absolutePath)
    override fun chmod(file: File, mode: Int) = Os.chmod(file.absolutePath, mode)
}
