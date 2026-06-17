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
	 * Histogram is the main data structure that holds the information about value to record ids relation.
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
	 * Canonical constructor carrying every field, including the frozen `indexedDecimalPlaces` scale and the
	 * already-assigned storage part PK.
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
		// the type-less 2024.5 format is unsupported: a null attributeType must fail fast at construction
		this.attributeType = Objects.requireNonNull(attributeType);
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.attributeIndexKey = attributeIndexKey;
		this.histogramPoints = histogramPoints;
		this.rangeIndex = rangeIndex;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
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
