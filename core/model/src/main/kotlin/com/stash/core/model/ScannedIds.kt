package com.stash.core.model

/** Ids at or below this are All Songs scans. Radio/search hash ids are Int-ranged, so they never reach it. */
const val SCANNED_ID_BASE = -4294967296L

val Track.isScannedSong: Boolean get() = id <= SCANNED_ID_BASE
