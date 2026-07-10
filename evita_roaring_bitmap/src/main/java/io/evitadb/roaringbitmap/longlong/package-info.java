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
 * Each 64-bit value is split into a 48-bit high key and a 16-bit low position. The high key is held
 * as a 6-byte big-endian array in an adaptive radix tree ({@code Art}), whose leaves point at 16-bit
 * Roaring {@code Container}s that store the low bits of the values sharing that key. Keeping the key
 * big-endian makes the tree's byte-dictionary ordering coincide with the unsigned ordering of the
 * original longs. The bit/byte plumbing for this split lives in {@code LongUtils} (value halves and
 * keys) and {@code IntegerUtil} (the 4-byte partial keys inside ART nodes); {@code RoaringIntPacking}
 * is the older, coarser 32/32 packing helper.
 *
 * This package is NOT exported by the module - it is an implementation detail. The public
 * 64-bit entry point {@link io.evitadb.roaringbitmap.PersistentLongRoaringBitmap} (originally
 * {@code Roaring64Bitmap}) lives in the exported {@code io.evitadb.roaringbitmap} package
 * alongside the 32-bit bitmap.
 */
package io.evitadb.roaringbitmap.longlong;
