/*
 * Vendored subset of RoaringBitmap (Apache-2.0), reshaped for evitaDB.
 * See the module NOTICE file for attribution and the synced upstream commit.
 */

/**
 * Internal 64-bit support machinery for the vendored RoaringBitmap (ART-backed high/low
 * container store: {@code HighLowContainer}, {@code LongUtils}, {@code IntegerUtil}, etc.).
 * Third-party code derived from the RoaringBitmap project (Apache License 2.0); see the
 * module {@code NOTICE}.
 *
 * This package is NOT exported by the module - it is an implementation detail. The public
 * 64-bit entry point {@link io.evitadb.roaringbitmap.PersistentLongRoaringBitmap} (originally
 * {@code Roaring64Bitmap}) lives in the exported {@code io.evitadb.roaringbitmap} package
 * alongside the 32-bit bitmap.
 */
package io.evitadb.roaringbitmap.longlong;
