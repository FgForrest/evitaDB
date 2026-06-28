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
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
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
	 * Sparse value column holding only the distinct values whose cardinality is greater than one (positionally aligned
	 * with {@link #cardinalities}); values absent here have an implied cardinality of `1`. Records are expected to carry
	 * scarce values with low cardinality, so this sparse representation saves a lot of memory. When produced by the live
	 * index (the persistence path) these values are in ascending order, aligned with {@link #sortedRecordsValues} — the
	 * serializer's per-value block-length computation relies on that; a part reconstructed from disk carries them in
	 * stored order and is only used to reseed the index (never re-serialized directly).
	 */
	@Getter private final Serializable[] cardinalityValues;
	/**
	 * The cardinalities (each `> 1`) positionally aligned with {@link #cardinalityValues}.
	 */
	@Getter private final int[] cardinalities;
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
	 * Map-based canonical constructor carrying every field, including the frozen `indexedDecimalPlaces` scale and the
	 * already-assigned storage part PK. The sparse `valueCardinalities` map (only values with cardinality `> 1`) is
	 * folded into the ascending sparse {@link #cardinalityValues} / {@link #cardinalities} columns by iterating the
	 * already-ascending `sortedRecordsValues`, so the stored columns keep the order the serializer relies on. Retained so
	 * the load / migration / backward-compatible paths (which carry a map) construct the part unchanged.
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
		this(
			entityIndexPrimaryKey, attributeIndexKey, comparatorBase,
			sortedRecords, sortedRecordsValues,
			sparseCardinalityValues(sortedRecordsValues, valueCardinalities),
			sparseCardinalities(sortedRecordsValues, valueCardinalities),
			indexedDecimalPlaces, storagePartPK
		);
	}

	/**
	 * Array-based canonical constructor carrying every field. The sparse `cardinalityValues` / `cardinalities` columns
	 * must be positionally aligned and, when produced by the live index for persistence, in ascending value order (a
	 * subset of `sortedRecordsValues`). This is the allocation-free path used by the live index, which produces the
	 * columns directly from its value tree without materializing an intermediate map.
	 */
	public SortIndexStoragePart(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull ComparatorSource[] comparatorBase,
		@Nonnull int[] sortedRecords,
		@Nonnull Serializable[] sortedRecordsValues,
		@Nonnull Serializable[] cardinalityValues,
		@Nonnull int[] cardinalities,
		int indexedDecimalPlaces,
		@Nullable Long storagePartPK
	) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.attributeIndexKey = attributeIndexKey;
		this.comparatorBase = comparatorBase;
		this.sortedRecords = sortedRecords;
		this.sortedRecordsValues = sortedRecordsValues;
		this.cardinalityValues = cardinalityValues;
		this.cardinalities = cardinalities;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.storagePartPK = storagePartPK;
	}

	/**
	 * Folds the sparse cardinality map into an ascending value column by walking the already-ascending
	 * `sortedRecordsValues` and keeping only the values whose mapped cardinality is `> 1`.
	 */
	@Nonnull
	private static Serializable[] sparseCardinalityValues(
		@Nonnull Serializable[] sortedRecordsValues,
		@Nonnull Map<Serializable, Integer> valueCardinalities
	) {
		if (valueCardinalities.isEmpty()) {
			return new Serializable[0];
		}
		final Serializable[] result = new Serializable[valueCardinalities.size()];
		int n = 0;
		for (final Serializable value : sortedRecordsValues) {
			final Integer cardinality = valueCardinalities.get(value);
			if (cardinality != null && cardinality > 1) {
				result[n++] = value;
			}
		}
		return n == result.length ? result : Arrays.copyOf(result, n);
	}

	/**
	 * The cardinalities (each `> 1`) positionally aligned with {@link #sparseCardinalityValues}, in the same ascending
	 * `sortedRecordsValues` order.
	 */
	@Nonnull
	private static int[] sparseCardinalities(
		@Nonnull Serializable[] sortedRecordsValues,
		@Nonnull Map<Serializable, Integer> valueCardinalities
	) {
		if (valueCardinalities.isEmpty()) {
			return new int[0];
		}
		final int[] result = new int[valueCardinalities.size()];
		int n = 0;
		for (final Serializable value : sortedRecordsValues) {
			final Integer cardinality = valueCardinalities.get(value);
			if (cardinality != null && cardinality > 1) {
				result[n++] = cardinality;
			}
		}
		return n == result.length ? result : Arrays.copyOf(result, n);
	}

	/**
	 * Rebuilds the sparse `value → cardinality` map from the {@link #cardinalityValues} / {@link #cardinalities} columns.
	 * Used only by the rare load / migration / backward-compatible paths that consume a map; the persistence (write) path
	 * reads the columns directly, so no map is allocated on the commit/flush hot path.
	 *
	 * @return the sparse map of values whose cardinality is `> 1`
	 */
	@Nonnull
	public Map<Serializable, Integer> getValueCardinalities() {
		final Map<Serializable, Integer> result =
			CollectionUtils.createHashMap(this.cardinalityValues.length);
		for (int i = 0; i < this.cardinalityValues.length; i++) {
			result.put(this.cardinalityValues[i], this.cardinalities[i]);
		}
		return result;
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
