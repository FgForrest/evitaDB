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
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.spi.store.catalog.persistence.storageParts.RecordWithCompressedId;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * Filter index container stores index for single {@link AttributeSchema} of the single
 * {@link EntitySchema}. This container object serves only as a storage carrier for
 * {@link io.evitadb.index.attribute.SortIndex} which is a live memory representation of the data stored in this
 * container.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@ToString(of = "attributeIndexKey")
public class SortIndexStoragePart implements AttributeIndexStoragePart, RecordWithCompressedId<AttributeIndexKey> {
	@Serial private static final long serialVersionUID = 5192847362910473829L;

	/**
	 * Unique id that identifies {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final Integer entityIndexPrimaryKey;
	/**
	 * Contains name and locale of the indexed attribute.
	 */
	@Getter private final AttributeIndexKey attributeIndexKey;
	/**
	 * Contains type of the attribute and comparison properties.
	 */
	@Getter private final ComparatorSource[] comparatorBase;
	/**
	 * Contains record ids sorted by assigned values. The array is divided in so called record ids block that respects
	 * order in {@link #sortedRecordsValues}. Record ids within the same block are sorted naturally by their integer id.
	 */
	@Getter private final int[] sortedRecords;
	/**
	 * Contains comparable values sorted naturally by their {@link Comparable} characteristics.
	 */
	@Getter private final Serializable[] sortedRecordsValues;
	/**
	 * Map contains only values with cardinalities greater than one. It is expected that records will have scarce values
	 * with low cardinality so this should save a lot of memory.
	 */
	@Getter private final Map<Serializable, Integer> valueCardinalities;
	/**
	 * The `indexedDecimalPlaces` scale frozen at index-creation time and persisted with the index. `BigDecimal` sort
	 * values are stored as order-preserving scaled `int`s at this scale; it is `0` for every non-`BigDecimal` attribute
	 * (and for compound sort attributes, which are not scaled). The value is frozen into the index (rather than
	 * re-derived from the schema at load) so the on-disk scaled values are always interpreted at the scale they were
	 * written with; a later schema change to `indexedDecimalPlaces` is detected as drift on the next modification instead
	 * of silently reinterpreting the persisted values.
	 */
	@Getter private final int indexedDecimalPlaces;
	/**
	 * Id used for lookups in persistent data storage for this particular container.
	 */
	@Nullable @Getter @Setter private Long storagePartPK;

	/**
	 * Creates a fresh sort index part whose storage part PK is not yet assigned (computed before persistence) with a `0`
	 * decimal-places scale. Retained for the backward-compatible serializers of pre-freeze formats, which never carried a
	 * persisted scale.
	 */
	public SortIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull ComparatorSource[] comparatorBase,
		@Nonnull int[] sortedRecords,
		@Nonnull Serializable[] sortedRecordsValues,
		@Nonnull Map<Serializable, Integer> valueCardinalities
	) {
		this(
			entityIndexPrimaryKey, attributeIndexKey, comparatorBase,
			sortedRecords, sortedRecordsValues, valueCardinalities, 0, null
		);
	}

	/**
	 * Constructor carrying the already-assigned storage part PK with a `0` decimal-places scale. Retained for the
	 * backward-compatible serializers of pre-freeze formats, which never carried a persisted scale.
	 */
	public SortIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull ComparatorSource[] comparatorBase,
		@Nonnull int[] sortedRecords,
		@Nonnull Serializable[] sortedRecordsValues,
		@Nonnull Map<Serializable, Integer> valueCardinalities,
		@Nullable Long storagePartPK
	) {
		this(
			entityIndexPrimaryKey, attributeIndexKey, comparatorBase,
			sortedRecords, sortedRecordsValues, valueCardinalities, 0, storagePartPK
		);
	}

	/**
	 * Canonical constructor carrying every field, including the frozen `indexedDecimalPlaces` scale and the
	 * already-assigned storage part PK.
	 */
	public SortIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull ComparatorSource[] comparatorBase,
		@Nonnull int[] sortedRecords,
		@Nonnull Serializable[] sortedRecordsValues,
		@Nonnull Map<Serializable, Integer> valueCardinalities,
		int indexedDecimalPlaces,
		@Nullable Long storagePartPK
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.attributeIndexKey = attributeIndexKey;
		this.comparatorBase = comparatorBase;
		this.sortedRecords = sortedRecords;
		this.sortedRecordsValues = sortedRecordsValues;
		this.valueCardinalities = valueCardinalities;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.storagePartPK = storagePartPK;
	}

	@Nonnull
	@Override
	public AttributeIndexType getIndexType() {
		return AttributeIndexType.SORT;
	}

	@Override
	public AttributeIndexKey getStoragePartSourceKey() {
		return this.attributeIndexKey;
	}

}
