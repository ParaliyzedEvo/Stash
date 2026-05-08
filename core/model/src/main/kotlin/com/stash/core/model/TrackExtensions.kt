package com.stash.core.model

/**
 * v0.9.18: convenience accessor for "is this a lossless file?". Used
 * by the Now Playing "Find in FLAC" dialog to hide the upgrade option
 * when the current track is already FLAC.
 *
 * Case-insensitive comparison guards against any future tagger that
 * writes "FLAC" instead of "flac" — the file extension is canonical
 * lowercase per [com.stash.data.download.files.FileOrganizer], but
 * a defensive comparison is essentially free here.
 */
val Track.isFlac: Boolean
    get() = fileFormat.equals("flac", ignoreCase = true)
