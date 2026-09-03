/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.Locale;

/**
 * Root storage part for a single histogram index entry (one histogram name + locale pair). Carries the bucketed filter
 * data ({@link ValueToRecordBitmap} histogram points with optional {@link RangeIndex}) of the histogram's embedded
 * {@code OwnerFilterIndex}. The cardinality tracking ({@link io.evitadb.index.cardinality.AttributeCardinalityIndex}) is
 * NOT carried here — it is evicted to a sibling {@link HistogramCardinalityStoragePart} so it can be rewritten
 * independently of the histogram buckets/range.
 *
 * Like {@link FilterIndexStoragePart}, the bucket and range data each have TWO shapes selected by an independent
 * `PAGED`/`SINGLE` discriminator: a `SINGLE`-shaped axis carries its data inline (the whole bucket tree / range tree
 * fits one record — the common, small-index case); a `PAGED`-shaped axis persists the tree as individual leaf pages
 * ({@link HistogramIndexLeafPagePart} / {@link HistogramRangeIndexLeafPagePart}) and the root carries only the
 * high-water `pageSequence` and the ordered live leaf-page list.
 *
 * Shares its identity — the owning entity index primary key plus the (histogramName, locale) pair, packed via
 * {@link HistogramIndexKey} in the key compressor — with its cardinality sibling through the common
 * {@link AbstractHistogramStoragePart} base; the two differ on disk only by their record type.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
@ToString(callSuper = true)
public class HistogramIndexStoragePart extends AbstractHistogramStoragePart {

	// bumped from 5083172946028471653L when DateTimeRange moved to millisecond comparison granularity: the byte layout
	// is unchanged, but a histogram over a DateTimeRange attribute now persists epoch-MILLISECOND range thresholds
	// where the previous shape persisted epoch-seconds. This record has never shipped in a release, so there is
	// nothing to be backward-compatible WITH and no reader is registered for the old uid - a stale unreleased-dev
	// catalog therefore fails loud (and is regenerated) rather than having its thresholds read at the wrong scale,
	// which is the same reason the previous bump on this record exists.
	@Serial private static final long serialVersionUID = 5083172946028471654L;

	/**
	 * Empty leaf-page list shared by every `SINGLE`-shaped axis (no paged leaves).
	 */
	private static final int[] NO_LEAF_PAGES = ArrayUtils.EMPTY_INT_ARRAY;
	/**
	 * Empty inline-bucket array shared by every bucket-`PAGED`-shaped part (its buckets live in leaf pages).
	 */
	private static final ValueToRecordBitmap[] EMPTY_HISTOGRAM = new ValueToRecordBitmap[0];

	/**
	 * The plain numeric type of the attribute values stored in this histogram.
	 */
	@Getter @Nonnull private final Class<?> valueType;

	/**
	 * Bucketed histogram data mapping attribute values to owner entity primary keys. For a `SINGLE`-shaped bucket axis
	 * this holds every bucket inline; for a bucket-`PAGED` part the buckets live in {@link HistogramIndexLeafPagePart}
	 * leaf pages instead and this array is empty.
	 */
	@Getter @Nonnull private final ValueToRecordBitmap[] histogramPoints;

	/**
	 * Optional range index for range-type attributes. Inline for a `SINGLE`/non-range-`PAGED` axis; `null` when
	 * {@link #rangePaged} (the range leaves live in {@link HistogramRangeIndexLeafPagePart} pages) or when there is no
	 * range companion at all.
	 */
	@Getter @Nullable private final RangeIndex rangeIndex;

	/**
	 * The `indexedDecimalPlaces` scale frozen at histogram-creation time and persisted with the index. `BigDecimal`
	 * source values are stored as order-preserving scaled `int`s at this scale; it is `0` for every non-`BigDecimal`
	 * source type. The value is frozen into the index (rather than re-derived from the source attribute's schema at load)
	 * so the on-disk scaled keys are always interpreted at the scale they were written with; a later schema change to
	 * `indexedDecimalPlaces` is detected as drift on the next modification instead of silently reinterpreting them.
	 */
	@Getter private final int indexedDecimalPlaces;

	/**
	 * The `PAGED`/`SINGLE` discriminator for the BUCKET axis. When `true` the bucket tree is persisted as individual
	 * {@link HistogramIndexLeafPagePart} leaf pages keyed by `pack(streamId, pageSequence)` and {@link #histogramPoints}
	 * is empty; when `false` every bucket lives inline. The page stream id is NOT persisted here — it is the
	 * {@link HistogramLeafStreamKey}'s compressed id, recomputed at load from the sub-index identity.
	 */
	@Getter private final boolean paged;
	/**
	 * The high-water `pageSequence` of the bucket stream (the maximum `pageSequence` ever allocated) for a
	 * bucket-`PAGED` part; `-1` otherwise. Persisted explicitly rather than derived as `max(pageSequence)` over live
	 * pages, so a freed max page cannot let a reused id be handed out while an older catalog version still references it.
	 */
	@Getter private final int highWaterPageSequence;
	/**
	 * The bucket leaf pages of a bucket-`PAGED` part, in ascending key order — the order the load path reads them back
	 * and reassembles the spine (the spine is NOT persisted). Empty for a bucket-`SINGLE` axis.
	 */
	@Getter @Nonnull private final int[] leafPageSequences;
	/**
	 * The `PAGED`/`SINGLE` discriminator for the RANGE axis, independent of the bucket axis. When `true` the range tree
	 * is persisted as individual {@link HistogramRangeIndexLeafPagePart} leaf pages and {@link #rangeIndex} is `null`.
	 */
	@Getter private final boolean rangePaged;
	/**
	 * The high-water `pageSequence` of the range stream for a range-`PAGED` part; `-1` otherwise.
	 */
	@Getter private final int rangeHighWaterPageSequence;
	/**
	 * The range leaf pages of a range-`PAGED` part, in ascending threshold order. Empty unless {@link #rangePaged}.
	 */
	@Getter @Nonnull private final int[] rangeLeafPageSequences;

	/**
	 * Creates a fresh `SINGLE`-shaped histogram root part whose storage part PK is not yet assigned (computed before
	 * persistence). Both axes carry their data inline.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param histogramName         name of the histogram definition
	 * @param locale                locale for localized histograms, or `null`
	 * @param valueType             plain numeric type of the stored histogram values
	 * @param histogramPoints       bucketed histogram data (inline)
	 * @param rangeIndex            optional range index for range-type attributes, or `null` (inline)
	 * @param indexedDecimalPlaces  frozen decimal-places scale (0 for non-`BigDecimal` source types)
	 */
	public HistogramIndexStoragePart(
		int entityIndexPrimaryKey,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Class<?> valueType,
		@Nonnull ValueToRecordBitmap[] histogramPoints,
		@Nullable RangeIndex rangeIndex,
		int indexedDecimalPlaces
	) {
		this(
			entityIndexPrimaryKey, histogramName, locale, valueType, histogramPoints, rangeIndex, indexedDecimalPlaces,
			false, -1, NO_LEAF_PAGES, false, -1, NO_LEAF_PAGES, null
		);
	}

	/**
	 * Shared empty-bucket sentinel for a bucket-`PAGED` axis (its buckets live in leaf pages, so the inline array is
	 * empty). Exposed for the emit path, which passes it for `histogramPoints` when the bucket axis is paged.
	 *
	 * @return the shared empty {@link ValueToRecordBitmap} array
	 */
	@Nonnull
	public static ValueToRecordBitmap[] emptyHistogram() {
		return EMPTY_HISTOGRAM;
	}

	/**
	 * Shared empty leaf-page list for a `SINGLE`-shaped axis (no paged leaves).
	 *
	 * @return the shared empty `int[]`
	 */
	@Nonnull
	public static int[] noLeafPages() {
		return NO_LEAF_PAGES;
	}

	/**
	 * Canonical constructor carrying every field, including both independent page-stream axes — the bucket axis
	 * (`paged`/`highWaterPageSequence`/`leafPageSequences`) and the range axis
	 * (`rangePaged`/`rangeHighWaterPageSequence`/`rangeLeafPageSequences`) — and the already-assigned storage part PK.
	 * When a bucket axis is `paged` the `histogramPoints` must be empty; when the range axis is `rangePaged` the
	 * `rangeIndex` must be `null`.
	 *
	 * @param entityIndexPrimaryKey      primary key of the owning entity index
	 * @param histogramName              name of the histogram definition
	 * @param locale                     locale for localized histograms, or `null`
	 * @param valueType                  plain numeric type of the stored histogram values
	 * @param histogramPoints            inline bucketed histogram data (empty when bucket-paged)
	 * @param rangeIndex                 inline range index (null when range-paged or absent)
	 * @param indexedDecimalPlaces       frozen decimal-places scale
	 * @param paged                      whether the bucket axis is paged
	 * @param highWaterPageSequence      the maximum bucket `pageSequence` ever allocated; `-1` when not bucket-paged
	 * @param leafPageSequences          the bucket leaf pages in ascending key order; empty when not bucket-paged
	 * @param rangePaged                 whether the range axis is paged
	 * @param rangeHighWaterPageSequence the maximum range `pageSequence` ever allocated; `-1` when not range-paged
	 * @param rangeLeafPageSequences     the range leaf pages in ascending threshold order; empty when not range-paged
	 * @param storagePartPK              the already-assigned storage part PK, or `null`
	 */
	public HistogramIndexStoragePart(
		int entityIndexPrimaryKey,
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Class<?> valueType,
		@Nonnull ValueToRecordBitmap[] histogramPoints,
		@Nullable RangeIndex rangeIndex,
		int indexedDecimalPlaces,
		boolean paged,
		int highWaterPageSequence,
		@Nonnull int[] leafPageSequences,
		boolean rangePaged,
		int rangeHighWaterPageSequence,
		@Nonnull int[] rangeLeafPageSequences,
		@Nullable Long storagePartPK
	) {
		super(entityIndexPrimaryKey, histogramName, locale, storagePartPK);
		this.valueType = valueType;
		this.histogramPoints = histogramPoints;
		this.rangeIndex = rangeIndex;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.paged = paged;
		this.highWaterPageSequence = highWaterPageSequence;
		this.leafPageSequences = leafPageSequences;
		this.rangePaged = rangePaged;
		this.rangeHighWaterPageSequence = rangeHighWaterPageSequence;
		this.rangeLeafPageSequences = rangeLeafPageSequences;
	}

}
