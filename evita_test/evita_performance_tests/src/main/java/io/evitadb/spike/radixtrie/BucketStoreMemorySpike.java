/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.spike.radixtrie;

import io.evitadb.index.bPlusTree.TransactionalLongBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import org.openjdk.jol.info.GraphLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Memory spike for the **inverted-index bucket store** — the value→records map that
 * {@link io.evitadb.index.invertedIndex.InvertedIndex} keeps in a
 * {@link TransactionalObjectBPlusTree} of {@link ValueToRecord} buckets. It measures, on the *real* evitaDB
 * demo-catalog `Product` data, how much heap three independent optimizations reclaim versus the current
 * production representation:
 *
 * - **① primitive key tree** (temporal attributes) — store the normalized {@link OffsetDateTime} as an
 *   epoch key in the existing {@link TransactionalLongBPlusTree} instead of a boxed {@link Instant} in an
 *   object tree.
 * - **② front-coded value column** (string attributes) — store the sorted distinct values prefix-compressed
 *   in a contiguous `byte[]` block (the trie's prefix-sharing idea, but pointer-free and cache-local).
 * - **③ ValueToRecord decomposition** — replace the array-of-{@link ValueToRecord}-objects with a
 *   structure-of-arrays: a primitive `int[]` record column (the lone record id for the single-record long
 *   tail) plus a compact bitmap overflow for the few multi-record buckets, with the value held only once
 *   (as the key column, never duplicated into a per-bucket wrapper).
 *
 * The baseline is built from the **real engine classes** ({@link ValueToRecordPrimitive} for single-record
 * buckets, {@link ValueToRecordBitmap} for multi-record buckets) so the comparison is faithful. Footprint is
 * the JOL deep-retained size measured as a delta against the empty structure of the same type (cancelling each
 * structure's constant framework graph). The candidate columnar stores hold the *same* {@link TransactionalBitmap}
 * objects for multi-record buckets, so the only thing the comparison varies is the bucket *structure*, not the
 * record-bitmap payload.
 *
 * Input: the `*.buckets.tsv` files (one `value TAB csvPks` line per distinct value) produced from the GraphQL
 * download. Run: {@code java … io.evitadb.spike.radixtrie.BucketStoreMemorySpike <dir>}.
 *
 * @author Claude (inverted-index bucket-store memory spike), FG Forrest a.s. (c) 2026
 */
public class BucketStoreMemorySpike {
	// mirror InvertedIndex's exact B+ tree geometry so the baseline footprint is faithful
	private static final int VALUE_BLOCK_SIZE = 256;
	private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;
	private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);
	/** Restart interval for front-coding: every Rth key stores its full bytes for random access. */
	private static final int FRONT_CODE_RESTART = 16;
	/** Same wrapper the real {@link io.evitadb.index.invertedIndex.InvertedIndex} uses to host buckets in the tree. */
	private static final Function<Object, ValueToRecord> VALUE_TO_RECORD_WRAPPER = ValueToRecord.class::cast;

	/** One inverted-index bucket: a distinct value and the ordered record ids that carry it. */
	private record Bucket(@Nonnull String raw, @Nonnull int[] pks) {
		boolean single() {
			return this.pks.length == 1;
		}
	}

	public static void main(String[] args) {
		final String dir = args.length > 0 ? args[0] : "/tmp/radixtrie_data";
		System.out.printf("Inverted-index bucket-store memory spike — REAL evitaDB demo Product data from %s%n%n", dir);

		// strings (PROD + ③ + ②③ front-coding) across the cardinality spectrum
		measureStringAttr("code (String, 100% single)", loadBuckets(Path.of(dir, "code.buckets.tsv")));
		measureStringAttr("url_en (String loc, 100% single)", loadBuckets(Path.of(dir, "url_en.buckets.tsv")));
		measureStringAttr("ean (String, 100% single)", loadBuckets(Path.of(dir, "ean.buckets.tsv")));
		measureStringAttr("name (String loc, 41% single)", loadBuckets(Path.of(dir, "name.buckets.tsv")));
		measureStringAttr("status (String, 2 buckets, 0% single)", loadBuckets(Path.of(dir, "status.buckets.tsv")));
		// temporal (PROD + ① + ①③ + ①③FOR)
		measureTemporalAttr("published (OffsetDateTime, 100% single)", loadBuckets(Path.of(dir, "published.buckets.tsv")));
		measureTemporalAttr("changed (OffsetDateTime, 25% single)", loadBuckets(Path.of(dir, "changed.buckets.tsv")));
		// numeric (PROD + ③ + primitive value column)
		measureBoxedAttr("stockItemPrimaryKey (Int, 100% single)", loadBuckets(Path.of(dir, "stockpk.buckets.tsv")), ValType.INT);
		measureBoxedAttr("weight (BigDecimal, 25% single)", loadBuckets(Path.of(dir, "weight.buckets.tsv")), ValType.BIGDECIMAL);
		measureBoxedAttr("displaySize (BigDecimal, 0% single)", loadBuckets(Path.of(dir, "displaySize.buckets.tsv")), ValType.BIGDECIMAL);

		System.out.println();
		System.out.println("Footprint = JOL deep retained size, delta vs empty structure of same type. " +
			"Ratio is vs the PROD baseline (lower = smaller). B/bucket = footprint / bucket count.");
		System.out.println("① = primitive key tree · ② = front-coded value column · ③ = ValueToRecord → plain arrays.");
	}

	/* ============================================================================================ */

	/** Measures a String attribute: PROD object-tree, then ③ alone, then ②+③ combined. */
	private static void measureStringAttr(@Nonnull String label, @Nonnull List<Bucket> buckets) {
		final int n = buckets.size();
		final int multi = countMulti(buckets);
		System.out.printf("== %s — %,d buckets (%,d multi-record) ==%n", label, n, multi);

		// PROD: TransactionalObjectBPlusTree<String, ValueToRecord>
		final long prod = footprint(
			buildObjectTree(buckets, false), emptyObjectTree(String.class, naturalStringComparator()));
		report("PROD object-tree<String> + ValueToRecord", prod, prod, n);

		// ③ only: structure-of-arrays, value still a boxed String in an Object[] column
		final long soa = footprint(buildColumnarObject(buckets), new ColumnarObjectStore(new Object[0], new int[0], new TransactionalBitmap[0]));
		report("③ SoA: Object[] value col + int[] rec col", soa, prod, n);

		// ②+③: front-coded value column + int[] record column (verified order-preserving before trusting)
		final FrontCodedStore fcStore = buildFrontCoded(buckets);
		verifyFrontCoded(label, buckets, fcStore);
		final long fc = footprint(fcStore, emptyFrontCoded());
		report("②+③ SoA: front-coded value col + int[] rec", fc, prod, n);
		System.out.println();
	}

	/** Measures a temporal attribute: PROD object-tree(Instant), then ① alone, then ①+③, then ①+③+FOR. */
	private static void measureTemporalAttr(@Nonnull String label, @Nonnull List<Bucket> buckets) {
		final int n = buckets.size();
		final int multi = countMulti(buckets);
		System.out.printf("== %s — %,d buckets (%,d multi-record) ==%n", label, n, multi);

		// PROD: TransactionalObjectBPlusTree<Instant, ValueToRecord> (boxed Instant key + boxed Instant in bucket)
		final long prod = footprint(
			buildInstantTree(buckets), emptyObjectTree(Instant.class, null));
		report("PROD object-tree<Instant> + ValueToRecord", prod, prod, n);

		// ① only: long-keyed tree, ValueToRecord payload unchanged (still boxes the Instant value inside)
		final long one = footprint(buildLongTree(buckets), emptyLongTree());
		report("① long-tree(epochNanos) + ValueToRecord", one, prod, n);

		// ①+③: SoA long[] value column + int[] record column (Instant object eliminated entirely)
		final long oneThree = footprint(buildColumnarLong(buckets),
			new ColumnarLongStore(new long[0], new int[0], new TransactionalBitmap[0]));
		report("①+③ SoA: long[] value col + int[] rec col", oneThree, prod, n);

		// ①+③+FOR: frame-of-reference delta in millis (lossless for this data) packed into int[]
		final ForDeltaLongStore forStore = buildForDelta(buckets);
		if (forStore != null) {
			final long forPacked = footprint(forStore,
				new ForDeltaLongStore(0L, new int[0], new int[0], new TransactionalBitmap[0]));
			report("①+③+FOR SoA: base+int[] delta(ms) + int[] rec", forPacked, prod, n);
		} else {
			System.out.println("  ①+③+FOR SoA: skipped (millisecond precision would lose distinct values)");
		}
		System.out.println();
	}

	/** Boxed-value datatypes whose value column has a primitive alternative. */
	private enum ValType {INT, BIGDECIMAL}

	/**
	 * Measures a non-string, non-temporal datatype: PROD object-tree, ③ (decompose, value stays boxed), and a
	 * type-specific primitive value column (`int[]` for Int; unscaled `long[]` + `byte[]` scale for BigDecimal).
	 * Demonstrates that ③ is **datatype-agnostic** while the value-column lever generalizes beyond String/temporal.
	 */
	private static void measureBoxedAttr(@Nonnull String label, @Nonnull List<Bucket> buckets, @Nonnull ValType type) {
		final int n = buckets.size();
		final int multi = countMulti(buckets);
		System.out.printf("== %s — %,d buckets (%,d multi-record) ==%n", label, n, multi);

		final Class<?> keyClass = type == ValType.INT ? Integer.class : BigDecimal.class;
		final long prod = footprint(buildBoxedTree(buckets, type, keyClass), emptyBoxedTree(keyClass));
		report("PROD object-tree + ValueToRecord", prod, prod, n);

		final long soa = footprint(buildColumnarBoxed(buckets, type),
			new ColumnarObjectStore(new Object[0], new int[0], new TransactionalBitmap[0]));
		report("③ SoA: boxed value col + int[] rec col", soa, prod, n);

		if (type == ValType.INT) {
			final long primitive = footprint(buildColumnarInt(buckets),
				new ColumnarIntStore(new int[0], new int[0], new TransactionalBitmap[0]));
			report("①+③ SoA: int[] value col + int[] rec col", primitive, prod, n);
		} else {
			final DecimalColumnStore dec = buildDecimalColumn(buckets);
			if (dec != null) {
				final long primitive = footprint(dec,
					new DecimalColumnStore(new long[0], new byte[0], new int[0], new TransactionalBitmap[0]));
				report("①+③ SoA: long[] unscaled + byte[] scale + rec", primitive, prod, n);
			} else {
				System.out.println("  ①+③ decimal column: skipped (unscaled value exceeds a long)");
			}
		}
		System.out.println();
	}

	/* ===================================== PROD baselines ======================================== */

	@Nonnull
	private static TransactionalObjectBPlusTree<String, ValueToRecord> buildObjectTree(
		@Nonnull List<Bucket> buckets, boolean ignored
	) {
		final TransactionalObjectBPlusTree<String, ValueToRecord> tree =
			emptyObjectTree(String.class, naturalStringComparator());
		for (int i = 0; i < buckets.size(); i++) {
			final Bucket b = buckets.get(i);
			tree.insert(b.raw(), toValueToRecord(b.raw(), b.pks()));
		}
		return tree;
	}

	@Nonnull
	private static TransactionalObjectBPlusTree<Instant, ValueToRecord> buildInstantTree(@Nonnull List<Bucket> buckets) {
		final TransactionalObjectBPlusTree<Instant, ValueToRecord> tree = emptyObjectTree(Instant.class, null);
		for (int i = 0; i < buckets.size(); i++) {
			final Bucket b = buckets.get(i);
			final Instant key = OffsetDateTime.parse(b.raw()).toInstant();
			tree.insert(key, toValueToRecord(key, b.pks()));
		}
		return tree;
	}

	@Nonnull
	private static TransactionalLongBPlusTree<ValueToRecord> buildLongTree(@Nonnull List<Bucket> buckets) {
		final TransactionalLongBPlusTree<ValueToRecord> tree = emptyLongTree();
		for (int i = 0; i < buckets.size(); i++) {
			final Bucket b = buckets.get(i);
			final Instant key = OffsetDateTime.parse(b.raw()).toInstant();
			tree.insert(epochNanos(key), toValueToRecord(key, b.pks()));
		}
		return tree;
	}

	/** Builds the real bucket object the inverted index would store: primitive for single, bitmap for multi. */
	@Nonnull
	private static ValueToRecord toValueToRecord(@Nonnull Serializable value, @Nonnull int[] pks) {
		return pks.length == 1
			? new ValueToRecordPrimitive(value, pks[0])
			: new ValueToRecordBitmap(value, pks);
	}

	@Nonnull
	private static TransactionalObjectBPlusTree<? extends Comparable<?>, ValueToRecord> buildBoxedTree(
		@Nonnull List<Bucket> buckets, @Nonnull ValType type, @Nonnull Class<?> keyClass
	) {
		@SuppressWarnings({"unchecked", "rawtypes"})
		final TransactionalObjectBPlusTree tree = emptyBoxedTree(keyClass);
		for (int i = 0; i < buckets.size(); i++) {
			final Bucket b = buckets.get(i);
			final Comparable<?> key = parseValue(b.raw(), type);
			//noinspection unchecked
			tree.insert(key, toValueToRecord((Serializable) key, b.pks()));
		}
		return tree;
	}

	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static TransactionalObjectBPlusTree emptyBoxedTree(@Nonnull Class<?> keyClass) {
		return new TransactionalObjectBPlusTree(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			keyClass, ValueToRecord.class, VALUE_TO_RECORD_WRAPPER, null);
	}

	/** ③ for a boxed datatype: value column keeps the boxed Integer/BigDecimal, records decomposed. */
	@Nonnull
	private static ColumnarObjectStore buildColumnarBoxed(@Nonnull List<Bucket> buckets, @Nonnull ValType type) {
		final int n = buckets.size();
		final Object[] values = new Object[n];
		final int[] records = new int[n];
		final TransactionalBitmap[] overflow = new TransactionalBitmap[countMulti(buckets)];
		int oi = 0;
		for (int i = 0; i < n; i++) {
			final Bucket b = buckets.get(i);
			values[i] = parseValue(b.raw(), type);
			if (b.single()) {
				records[i] = b.pks()[0];
			} else {
				overflow[oi] = new TransactionalBitmap(b.pks());
				records[i] = ~oi++;
			}
		}
		return new ColumnarObjectStore(values, records, overflow);
	}

	/** ①+③ for Int: a primitive `int[]` value column (4 B/key) + record column. */
	@Nonnull
	private static ColumnarIntStore buildColumnarInt(@Nonnull List<Bucket> buckets) {
		final int n = buckets.size();
		final int[] values = new int[n];
		final int[] records = new int[n];
		final TransactionalBitmap[] overflow = new TransactionalBitmap[countMulti(buckets)];
		int oi = 0;
		for (int i = 0; i < n; i++) {
			final Bucket b = buckets.get(i);
			values[i] = Integer.parseInt(b.raw());
			if (b.single()) {
				records[i] = b.pks()[0];
			} else {
				overflow[oi] = new TransactionalBitmap(b.pks());
				records[i] = ~oi++;
			}
		}
		return new ColumnarIntStore(values, records, overflow);
	}

	/**
	 * ①+③ for BigDecimal: store the unscaled value as a `long` column + a `byte` scale column (so the boxed
	 * BigDecimal disappears). Returns `null` if any value's unscaled magnitude does not fit a `long`.
	 */
	@Nullable
	private static DecimalColumnStore buildDecimalColumn(@Nonnull List<Bucket> buckets) {
		final int n = buckets.size();
		final long[] unscaled = new long[n];
		final byte[] scale = new byte[n];
		final int[] records = new int[n];
		final TransactionalBitmap[] overflow = new TransactionalBitmap[countMulti(buckets)];
		int oi = 0;
		for (int i = 0; i < n; i++) {
			final Bucket b = buckets.get(i);
			final BigDecimal bd = new BigDecimal(b.raw());
			try {
				unscaled[i] = bd.unscaledValue().longValueExact();
			} catch (ArithmeticException e) {
				return null;
			}
			if (bd.scale() < Byte.MIN_VALUE || bd.scale() > Byte.MAX_VALUE) {
				return null;
			}
			scale[i] = (byte) bd.scale();
			if (b.single()) {
				records[i] = b.pks()[0];
			} else {
				overflow[oi] = new TransactionalBitmap(b.pks());
				records[i] = ~oi++;
			}
		}
		return new DecimalColumnStore(unscaled, scale, records, overflow);
	}

	@Nonnull
	private static Comparable<?> parseValue(@Nonnull String raw, @Nonnull ValType type) {
		return type == ValType.INT ? Integer.valueOf(raw) : new BigDecimal(raw);
	}

	/* ===================================== candidate stores ====================================== */

	/** ③ for strings: value column stays boxed Strings, records become a primitive column + bitmap overflow. */
	@Nonnull
	private static ColumnarObjectStore buildColumnarObject(@Nonnull List<Bucket> buckets) {
		final int n = buckets.size();
		final Object[] values = new Object[n];
		final int[] records = new int[n];
		final TransactionalBitmap[] overflow = new TransactionalBitmap[countMulti(buckets)];
		int oi = 0;
		for (int i = 0; i < n; i++) {
			final Bucket b = buckets.get(i);
			values[i] = b.raw();
			if (b.single()) {
				records[i] = b.pks()[0];
			} else {
				overflow[oi] = new TransactionalBitmap(b.pks());
				records[i] = ~oi++;
			}
		}
		return new ColumnarObjectStore(values, records, overflow);
	}

	/** ①+③ for temporal: value column is a primitive long[] (no Instant object at all) + record column. */
	@Nonnull
	private static ColumnarLongStore buildColumnarLong(@Nonnull List<Bucket> buckets) {
		final int n = buckets.size();
		final long[] values = new long[n];
		final int[] records = new int[n];
		final TransactionalBitmap[] overflow = new TransactionalBitmap[countMulti(buckets)];
		int oi = 0;
		for (int i = 0; i < n; i++) {
			final Bucket b = buckets.get(i);
			values[i] = epochNanos(OffsetDateTime.parse(b.raw()).toInstant());
			if (b.single()) {
				records[i] = b.pks()[0];
			} else {
				overflow[oi] = new TransactionalBitmap(b.pks());
				records[i] = ~oi++;
			}
		}
		return new ColumnarLongStore(values, records, overflow);
	}

	/**
	 * ①+③+FOR for temporal: a block base plus per-key deltas in **milliseconds** packed into an `int[]`. Returns
	 * `null` when millisecond precision would collapse distinct values (so the optimization is honestly skipped).
	 */
	@Nullable
	private static ForDeltaLongStore buildForDelta(@Nonnull List<Bucket> buckets) {
		final int n = buckets.size();
		final long[] millis = new long[n];
		for (int i = 0; i < n; i++) {
			millis[i] = OffsetDateTime.parse(buckets.get(i).raw()).toInstant().toEpochMilli();
		}
		// verify lossless: distinct at milli precision and deltas fit a signed int
		final long base = millis[0];
		for (int i = 1; i < n; i++) {
			if (millis[i] <= millis[i - 1]) {
				return null; // collision / non-monotone at milli precision → not lossless
			}
			if (millis[i] - base > Integer.MAX_VALUE) {
				return null; // span too wide for an int delta
			}
		}
		final int[] deltas = new int[n];
		final int[] records = new int[n];
		final TransactionalBitmap[] overflow = new TransactionalBitmap[countMulti(buckets)];
		int oi = 0;
		for (int i = 0; i < n; i++) {
			final Bucket b = buckets.get(i);
			deltas[i] = (int) (millis[i] - base);
			if (b.single()) {
				records[i] = b.pks()[0];
			} else {
				overflow[oi] = new TransactionalBitmap(b.pks());
				records[i] = ~oi++;
			}
		}
		return new ForDeltaLongStore(base, deltas, records, overflow);
	}

	/**
	 * ②+③ for strings: the sorted distinct values prefix-compressed into one contiguous `byte[]` (every
	 * {@link #FRONT_CODE_RESTART}th key stored in full for random access), plus a primitive record column.
	 */
	@Nonnull
	private static FrontCodedStore buildFrontCoded(@Nonnull List<Bucket> buckets) {
		final int n = buckets.size();
		final ByteArrayOutputStream data = new ByteArrayOutputStream(n * 8);
		final int[] restartOffsets = new int[(n + FRONT_CODE_RESTART - 1) / FRONT_CODE_RESTART];
		final int[] records = new int[n];
		final TransactionalBitmap[] overflow = new TransactionalBitmap[countMulti(buckets)];
		byte[] prev = new byte[0];
		int oi = 0;
		for (int i = 0; i < n; i++) {
			final Bucket b = buckets.get(i);
			final byte[] key = b.raw().getBytes(StandardCharsets.UTF_8);
			final int shared;
			if (i % FRONT_CODE_RESTART == 0) {
				restartOffsets[i / FRONT_CODE_RESTART] = data.size();
				shared = 0; // restart point: store the full key
			} else {
				shared = commonPrefix(prev, key);
			}
			final int suffixLen = key.length - shared;
			if (shared > 0xFF || suffixLen > 0xFF) {
				throw new IllegalStateException("Front-coding spike assumes value bytes < 256 / prefix < 256");
			}
			data.write(shared);                       // 1 byte shared-prefix length
			data.write(suffixLen);                    // 1 byte suffix length
			data.write(key, shared, suffixLen);       // the divergent suffix bytes
			prev = key;
			if (b.single()) {
				records[i] = b.pks()[0];
			} else {
				overflow[oi] = new TransactionalBitmap(b.pks());
				records[i] = ~oi++;
			}
		}
		return new FrontCodedStore(data.toByteArray(), restartOffsets, FRONT_CODE_RESTART, n, records, overflow);
	}

	/* ===================================== store record types ==================================== */

	/** ③ value column kept as boxed objects (Strings), records decomposed into a primitive column + overflow. */
	private record ColumnarObjectStore(@Nonnull Object[] values, @Nonnull int[] records,
	                                    @Nonnull TransactionalBitmap[] overflow) {
	}

	/** ①+③ value column as primitive epoch-nanos longs, records decomposed. */
	private record ColumnarLongStore(@Nonnull long[] values, @Nonnull int[] records,
	                                  @Nonnull TransactionalBitmap[] overflow) {
	}

	/** ①+③ value column as a primitive int[] (Int datatype), records decomposed. */
	private record ColumnarIntStore(@Nonnull int[] values, @Nonnull int[] records,
	                                 @Nonnull TransactionalBitmap[] overflow) {
	}

	/** ①+③ value column as unscaled long[] + byte[] scale (BigDecimal datatype), records decomposed. */
	private record DecimalColumnStore(@Nonnull long[] unscaled, @Nonnull byte[] scale, @Nonnull int[] records,
	                                  @Nonnull TransactionalBitmap[] overflow) {
	}

	/** ①+③+FOR value column as base + int delta(ms), records decomposed. */
	private record ForDeltaLongStore(long base, @Nonnull int[] deltas, @Nonnull int[] records,
	                                 @Nonnull TransactionalBitmap[] overflow) {
	}

	/** ②+③ prefix-compressed value blob + restart index, records decomposed. */
	private record FrontCodedStore(@Nonnull byte[] data, @Nonnull int[] restartOffsets, int restartInterval,
	                               int count, @Nonnull int[] records, @Nonnull TransactionalBitmap[] overflow) {
	}

	/**
	 * Decodes every key back out of the front-coded blob and asserts it equals the original (sorted) bucket
	 * value, so the ~11× footprint number describes a *correct, losslessly reconstructable* store and not a
	 * buggy encoding that merely happens to be small.
	 */
	private static void verifyFrontCoded(
		@Nonnull String label, @Nonnull List<Bucket> buckets, @Nonnull FrontCodedStore store
	) {
		final byte[] data = store.data();
		for (int i = 0; i < store.count(); i++) {
			final int restartBase = (i / store.restartInterval()) * store.restartInterval();
			int pos = store.restartOffsets()[i / store.restartInterval()];
			byte[] key = new byte[0];
			for (int j = restartBase; j <= i; j++) {
				final int shared = data[pos++] & 0xFF;
				final int suffixLen = data[pos++] & 0xFF;
				final byte[] next = new byte[shared + suffixLen];
				System.arraycopy(key, 0, next, 0, shared);
				System.arraycopy(data, pos, next, shared, suffixLen);
				pos += suffixLen;
				key = next;
			}
			final String decoded = new String(key, StandardCharsets.UTF_8);
			if (!decoded.equals(buckets.get(i).raw())) {
				throw new IllegalStateException(label + ": front-coding round-trip FAILED at " + i +
					" — decoded '" + decoded + "' != '" + buckets.get(i).raw() + "'");
			}
		}
	}

	@Nonnull
	private static FrontCodedStore emptyFrontCoded() {
		return new FrontCodedStore(new byte[0], new int[0], FRONT_CODE_RESTART, 0, new int[0], new TransactionalBitmap[0]);
	}

	/* ========================================= helpers ========================================== */

	@Nonnull
	private static <K extends Comparable<K>> TransactionalObjectBPlusTree<K, ValueToRecord> emptyObjectTree(
		@Nonnull Class<K> keyType, @Nullable Comparator<K> comparator
	) {
		return new TransactionalObjectBPlusTree<>(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			keyType, ValueToRecord.class, VALUE_TO_RECORD_WRAPPER, comparator);
	}

	@Nonnull
	private static TransactionalLongBPlusTree<ValueToRecord> emptyLongTree() {
		return new TransactionalLongBPlusTree<>(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			ValueToRecord.class, VALUE_TO_RECORD_WRAPPER);
	}

	private static long footprint(@Nonnull Object live, @Nonnull Object empty) {
		return GraphLayout.parseInstance(live).totalSize() - GraphLayout.parseInstance(empty).totalSize();
	}

	private static void report(@Nonnull String label, long bytes, long prodBytes, int buckets) {
		System.out.printf("  %-44s : %,11d B  | %6.1f B/bucket | %5.2f×%n",
			label, bytes, (double) bytes / buckets, (double) bytes / prodBytes);
	}

	private static int countMulti(@Nonnull List<Bucket> buckets) {
		int m = 0;
		for (int i = 0; i < buckets.size(); i++) {
			if (!buckets.get(i).single()) {
				m++;
			}
		}
		return m;
	}

	private static long epochNanos(@Nonnull Instant instant) {
		return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
	}

	private static int commonPrefix(@Nonnull byte[] a, @Nonnull byte[] b) {
		final int max = Math.min(a.length, b.length);
		int i = 0;
		while (i < max && a[i] == b[i]) {
			i++;
		}
		return i;
	}

	@Nonnull
	private static Comparator<String> naturalStringComparator() {
		return Comparator.naturalOrder();
	}

	/* ========================================= loading ========================================== */

	@Nonnull
	private static List<Bucket> loadBuckets(@Nonnull Path path) {
		try {
			final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
			final List<Bucket> out = new ArrayList<>(lines.size());
			for (int i = 0; i < lines.size(); i++) {
				final String line = lines.get(i);
				if (line.isEmpty()) {
					continue;
				}
				final int tab = line.indexOf('\t');
				final String value = line.substring(0, tab);
				final String[] pkStrings = line.substring(tab + 1).split(",");
				final int[] pks = new int[pkStrings.length];
				for (int j = 0; j < pkStrings.length; j++) {
					pks[j] = Integer.parseInt(pkStrings[j]);
				}
				out.add(new Bucket(value, pks));
			}
			return out;
		} catch (IOException e) {
			throw new UncheckedIOException("Missing/unreadable bucket file: " + path, e);
		}
	}
}
