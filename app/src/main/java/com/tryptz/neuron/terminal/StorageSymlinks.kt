package com.tryptz.neuron.terminal

import android.os.Environment
import android.system.Os
import java.io.File

/**
 * What `termux-setup-storage` does, without needing Termux's activity: after
 * the storage permission is granted, link the shared folders into ~/storage.
 */
object StorageSymlinks {
    fun create(paths: TermuxPaths) {
        val storage = File(paths.home, "storage")
        storage.deleteRecursively()
        storage.mkdirs()
        val links = mapOf(
            "shared" to Environment.getExternalStorageDirectory(),
            "documents" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "downloads" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "dcim" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "pictures" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "music" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "movies" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        )
        links.forEach { (name, target) -> Os.symlink(target.absolutePath, File(storage, name).absolutePath) }
    }

    fun isSetUp(paths: TermuxPaths): Boolean = File(paths.home, "storage/shared").exists()
}
