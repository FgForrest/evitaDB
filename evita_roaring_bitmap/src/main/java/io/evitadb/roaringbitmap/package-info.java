/*
 * Vendored subset of RoaringBitmap (Apache-2.0), reshaped for evitaDB.
 * See the module NOTICE file for attribution and the synced upstream commit.
 */

/**
 * evitaDB's vendored RoaringBitmap core - compressed bitmaps of unsigned 32-bit and 64-bit
 * integers that serve as the building block for evitaDB's in-memory lookup and search indexes.
 *
 * Third-party code derived from the RoaringBitmap project
 * (https://github.com/RoaringBitmap/RoaringBitmap), Apache License 2.0, under evitaDB
 * stewardship. Synced from upstream v1.6.12 (fork github.com/novoj/RoaringBitmap,
 * commit {@code f27cd538}). See the module {@code LICENSE}, {@code AUTHORS} and
 * {@code NOTICE} files.
 *
 * This is the sole package exported by the module - the logical public API surface. The two
 * public entry points are {@link io.evitadb.roaringbitmap.PersistentRoaringBitmap} (32-bit) and
 * {@link io.evitadb.roaringbitmap.PersistentLongRoaringBitmap} (64-bit, originally
 * {@code Roaring64Bitmap}). The 32-bit bitmap is a single, mutable bitmap that uses structural
 * sharing in binary operations and copy-on-write for mutations (originally prototyped as
 * {@code CopyOnWriteRoaringBitmapV2}, now folded in).
 *
 * Implementation-detail classes that must remain {@code public} for cross-package use within the
 * module (the container hierarchy, {@code Util}, etc.) are intentionally kept here but are not part
 * of the supported API; truly internal helpers are package-private, and the {@code longlong} and
 * {@code art} packages are not exported.
 */
package io.evitadb.roaringbitmap;
