/*
 * Vendored subset of RoaringBitmap (Apache-2.0), reshaped for evitaDB.
 * See the module NOTICE file for attribution and the synced upstream commit.
 */

/**
 * evitaDB's vendored Adaptive Radix Tree (ART), the ordered index backing the 64-bit
 * {@link io.evitadb.roaringbitmap.PersistentLongRoaringBitmap} through
 * {@link io.evitadb.roaringbitmap.longlong.HighLowContainer}.
 *
 * A 64-bit value is split into its high 48 bits and low 16 bits.
 * {@link io.evitadb.roaringbitmap.art.Art} indexes the high 48 bits (a 6-byte key) and maps each to
 * the index of a {@link io.evitadb.roaringbitmap.Container} — held in
 * {@link io.evitadb.roaringbitmap.art.Containers} — that stores the matching low 16 bits. The tree is
 * a byte-wise radix tree whose inner nodes ({@code Node4}/{@code Node16}/{@code Node48}/
 * {@code Node256}) adapt their fan-out to the number of children and path-compress shared key bytes,
 * keeping it CPU-cache friendly. Keys are ordered by unsigned-byte comparison, so the iterators walk
 * them in ascending numeric order.
 *
 * Third-party code derived from the RoaringBitmap project (Apache License 2.0); see the module
 * {@code NOTICE}.
 */
package io.evitadb.roaringbitmap.art;
