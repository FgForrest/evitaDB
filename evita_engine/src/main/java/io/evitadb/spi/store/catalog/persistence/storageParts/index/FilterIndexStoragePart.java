/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Range;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.RecordWithCompressedId;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Objects;

/**
 * Filter index container stores index for single {@link AttributeSchema} of the single
 * {@link EntitySchema}. This container object serves only as a storage carrier for
 * {@link io.evitadb.index.attribute.FilterIndex} which is a live memory representation of the data stored in this
 * container.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ToString(of = {"attributeIndexKey", "entityIndexPrimaryKey"})
public class FilterIndexStoragePart implements AttributeIndexStoragePart, RecordWithCompressedId<AttributeIndexKey> {
	@Serial private static final long serialVersionUID = 3847290165472938104L;

	/**
	 * Unique id that identifies {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final Integer entityIndexPrimaryKey;
	/**
	 * Contains name and locale of the indexed attribute.
	 */
	@Getter private final AttributeIndexKey attributeIndexKey;
	/**
	 * Contains the type of the objects kept as values in this particular filter index. Always present: the only format
	 * that ever stored a `null` type was the 2024.5 filter serializer, whose support has been dropped.
	 */
	@Nonnull @Getter private final Class<?> attributeType;
	/**
	 * Empty leaf-page list shared by every `SINGLE`-shaped part (no paged leaves).
	 */
	private static final int[] NO_LEAF_PAGES = ArrayUtils.EMPTY_INT_ARRAY;
	/**
	 * Empty inline-bucket array shared by every `PAGED`-shaped part (its buckets live in leaf pages).
	 */
	private static final ValueToRecordBitmap[] EMPTY_HISTOGRAM = new ValueToRecordBitmap[0];
	/**
	 * Histogram is the main data structure that holds the information about value to record ids relation. For a
	 * `SINGLE`-shaped part (the whole bucket tree fits one record — the common, small-index case) this holds every
	 * bucket inline. For a `PAGED`-shaped part the buckets live in individual {@link
	 * FilterIndexLeafPagePart} leaf pages instead, and this array is empty.
	 */
	@Nonnull @Getter private final ValueToRecordBitmap[] histogramPoints;
	/**
	 * Range index is used only for attribute types that are assignable to {@link Range} and can answer questions like:
	 * <p>
	 * - what records are valid at precise moment
	 * - what records are valid until certain moment
	 * - what records are valid after certain moment
	 */
	@Nullable @Getter private final RangeIndex rangeIndex;
	/**
	 * The `indexedDecimalPlaces` scale frozen at index-creation time and persisted with the index. `BigDecimal` filter
	 * keys are stored as order-preserving scaled `int`s at this scale; it is `0` for every non-`BigDecimal` attribute.
	 * The value is frozen into the index (rather than re-derived from the schema at load) so the on-disk scaled keys are
	 * always interpreted at the scale they were written with; a later schema change to `indexedDecimalPlaces` is detected
	 * as drift on the next modification instead of silently reinterpreting the persisted keys.
	 */
	@Getter private final int indexedDecimalPlaces;
	/**
	 * The `PAGED`/`SINGLE` discriminator. When `true` the bucket tree is persisted as individual
	 * {@link FilterIndexLeafPagePart} leaf pages keyed by `join(streamId, pageSequence)` and {@link #histogramPoints} is
	 * empty; when `false` every bucket lives inline in {@link #histogramPoints}. The page stream id is deliberately NOT
	 * persisted here: it is the {@link LeafStreamKey}'s compressed id (see {@link LeafStreamKey}), recomputed at load
	 * from the sub-index identity via the catalog's read-only {@code KeyCompressor} — the engine that builds this part
	 * has no compressor and never needs one (the leaf-page primary keys are resolved store-side at write time).
	 */
	@Getter private final boolean paged;
	/**
	 * The high-water `pageSequence` of the stream (the maximum `pageSequence` ever allocated) for a `PAGED`-shaped part; `-1` for
	 * a `SINGLE`-shaped part. Persisted explicitly rather than derived as `max(pageSequence)` over live pages, so a freed max
	 * page cannot let a reused id be handed out while an older catalog version still references it.
	 */
	@Getter private final int highWaterPageSequence;
	/**
	 * The leaf pages of a `PAGED`-shaped part, listed in ascending key order — exactly the order in which the load path
	 * reads them back and hands them to `assembleFromLeaves` to rebuild the spine (the spine is NOT persisted; it is
	 * reconstructed at load). Empty for a `SINGLE`-shaped part.
	 */
	@Nonnull @Getter private final int[] leafPageSequences;
	/**
	 * The `PAGED`/`SINGLE` discriminator for the RANGE companion, independent of the bucket-tree
	 * {@link #paged} axis. When `true` the range tree is persisted as individual {@link RangeIndexLeafPagePart} leaf
	 * pages keyed by `join(streamId, pageSequence)` (with the range stream id) and {@link #rangeIndex} is `null`; when `false`
	 * the whole {@link #rangeIndex} is carried inline (or there is no range companion at all). The range stream id is
	 * deliberately NOT persisted here — it is the {@link LeafStreamKey}'s compressed id resolved at load with
	 * {@link LeafStreamKey.StreamKind#RANGE}, mirroring the bucket stream.
	 */
	@Getter private final boolean rangePaged;
	/**
	 * The high-water `pageSequence` of the range stream for a range-`PAGED` part; `-1` otherwise. Persisted explicitly rather
	 * than derived as `max(pageSequence)` over live pages, so a freed max page cannot let a reused id be handed out while an
	 * older catalog version still references it.
	 */
	@Getter private final int rangeHighWaterPageSequence;
	/**
	 * The range leaf pages of a range-`PAGED` part, in ascending threshold order — the order the load path reads them
	 * back and reassembles the range spine. Empty unless {@link #rangePaged}.
	 */
	@Nonnull @Getter private final int[] rangeLeafPageSequences;
	/**
	 * Id used for lookups in file offset index for this particular container.
	 */
	@Nullable @Getter @Setter private Long storagePartPK;

	/**
	 * Creates a fresh filter index part whose storage part PK is not yet assigned (computed before persistence) with a
	 * `0` decimal-places scale. Retained for the backward-compatible serializers of pre-freeze formats, which never
	 * carried a persisted scale.
	 */
	public FilterIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<?> attributeType,
		@Nonnull ValueToRecordBitmap[] histogramPoints,
		@Nullable RangeIndex rangeIndex
	) {
		this(entityIndexPrimaryKey, attributeIndexKey, attributeType, histogramPoints, rangeIndex, 0, null);
	}

	/**
	 * Constructor carrying the already-assigned storage part PK with a `0` decimal-places scale. Retained for the
	 * backward-compatible serializers of pre-freeze formats, which never carried a persisted scale.
	 */
	public FilterIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<?> attributeType,
		@Nonnull ValueToRecordBitmap[] histogramPoints,
		@Nullable RangeIndex rangeIndex,
		@Nullable Long storagePartPK
	) {
		this(entityIndexPrimaryKey, attributeIndexKey, attributeType, histogramPoints, rangeIndex, 0, storagePartPK);
	}

	/**
	 * Constructor for a `SINGLE`-shaped part carrying every legacy field including the frozen `indexedDecimalPlaces`
	 * scale. Delegates to the canonical constructor with the `PAGED` metadata absent.
	 */
	public FilterIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<?> attributeType,
		@Nonnull ValueToRecordBitmap[] histogramPoints,
		@Nullable RangeIndex rangeIndex,
		int indexedDecimalPlaces,
		@Nullable Long storagePartPK
	) {
		this(
			entityIndexPrimaryKey, attributeIndexKey, attributeType, histogramPoints, rangeIndex,
			indexedDecimalPlaces, false, -1, NO_LEAF_PAGES, storagePartPK
		);
	}

	/**
	 * Builds a `PAGED`-shaped part: the buckets live in {@link FilterIndexLeafPagePart} leaf pages, so
	 * the root carries the explicit high-water `pageSequence`, the ordered leaf-page list (ascending key order) and the
	 * inline {@link RangeIndex} (RangeIndex pagination is a later step), but NO inline buckets and NO page-stream id (it
	 * is recomputed at load from the sub-index identity — see {@link #paged}).
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param attributeIndexKey     the attribute key
	 * @param attributeType         the indexed value type
	 * @param rangeIndex            the inline range index, or `null`
	 * @param indexedDecimalPlaces  the frozen scale (0 for non-BigDecimal)
	 * @param highWaterPageSequence      the maximum `pageSequence` ever allocated for the stream
	 * @param leafPageSequences          the leaf pages in ascending key order
	 * @param storagePartPK         the already-assigned storage part PK, or `null`
	 * @return the paged filter index storage part
	 */
	@Nonnull
	public static FilterIndexStoragePart paged(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<?> attributeType,
		@Nullable RangeIndex rangeIndex,
		int indexedDecimalPlaces,
		int highWaterPageSequence,
		@Nonnull int[] leafPageSequences,
		@Nullable Long storagePartPK
	) {
		return new FilterIndexStoragePart(
			entityIndexPrimaryKey, attributeIndexKey, attributeType, EMPTY_HISTOGRAM, rangeIndex,
			indexedDecimalPlaces, true, highWaterPageSequence, leafPageSequences, storagePartPK
		);
	}

	/**
	 * Canonical constructor carrying every field, including the frozen `indexedDecimalPlaces` scale, the `PAGED` page-
	 * stream metadata (`paged`/`highWaterPageSequence`/`leafPageSequences`; `paged == false` ⇔ `SINGLE` shape) and the
	 * already-assigned storage part PK.
	 */
	public FilterIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<?> attributeType,
		@Nonnull ValueToRecordBitmap[] histogramPoints,
		@Nullable RangeIndex rangeIndex,
		int indexedDecimalPlaces,
		boolean paged,
		int highWaterPageSequence,
		@Nonnull int[] leafPageSequences,
		@Nullable Long storagePartPK
	) {
		this(
			entityIndexPrimaryKey, attributeIndexKey, attributeType, histogramPoints, rangeIndex, indexedDecimalPlaces,
			paged, highWaterPageSequence, leafPageSequences, false, -1, NO_LEAF_PAGES, storagePartPK
		);
	}

	/**
	 * Canonical constructor carrying every field, including the frozen `indexedDecimalPlaces` scale, BOTH independent
	 * page-stream axes — the bucket axis (`paged`/`highWaterPageSequence`/`leafPageSequences`) and the range axis
	 * (`rangePaged`/`rangeHighWaterPageSequence`/`rangeLeafPageSequences`) — and the already-assigned storage part PK. When
	 * `rangePaged` is `true` the `rangeIndex` must be `null` (its leaves live in {@link RangeIndexLeafPagePart} pages);
	 * when `false` the `rangeIndex` is carried inline (or is `null` when there is no range companion).
	 */
	public FilterIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull Class<?> attributeType,
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
		// the type-less 2024.5 format is unsupported: a null attributeType must fail fast at construction
		this.attributeType = Objects.requireNonNull(attributeType);
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.attributeIndexKey = attributeIndexKey;
		this.histogramPoints = histogramPoints;
		this.rangeIndex = rangeIndex;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.paged = paged;
		this.highWaterPageSequence = highWaterPageSequence;
		this.leafPageSequences = leafPageSequences;
		this.rangePaged = rangePaged;
		this.rangeHighWaterPageSequence = rangeHighWaterPageSequence;
		this.rangeLeafPageSequences = rangeLeafPageSequences;
		this.storagePartPK = storagePartPK;
	}

	@Nonnull
	@Override
	public AttributeIndexType getIndexType() {
		return AttributeIndexType.FILTER;
	}

	@Override
	public AttributeIndexKey getStoragePartSourceKey() {
		return this.attributeIndexKey;
	}

}
