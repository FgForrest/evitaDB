/*
 * Vendored subset of RoaringBitmap (Apache-2.0), reshaped for evitaDB.
 * See the module NOTICE file for attribution and the synced upstream commit.
 */

/**
 * evitaDB's vendored RoaringBitmap core.
 *
 * Third-party code derived from the RoaringBitmap project
 * (https://github.com/RoaringBitmap/RoaringBitmap), Apache License 2.0, under evitaDB
 * stewardship. Synced from upstream v1.6.12 (fork github.com/novoj/RoaringBitmap,
 * commit {@code f27cd538}). See the module {@code LICENSE}, {@code AUTHORS} and
 * {@code NOTICE} files.
 *
 * The public entry point is {@link io.evitadb.roaringbitmap.PersistentRoaringBitmap} - a single,
 * mutable bitmap that uses structural sharing in binary operations and copy-on-write for
 * mutations (originally prototyped as {@code CopyOnWriteRoaringBitmapV2}, now folded in).
 */
package io.evitadb.roaringbitmap;
