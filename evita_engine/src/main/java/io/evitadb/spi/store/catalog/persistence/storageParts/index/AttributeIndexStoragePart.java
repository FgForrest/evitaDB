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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.index.EntityIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This class aggregates similar functionality for all types of attribute indexes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface AttributeIndexStoragePart extends StoragePart {

	/**
	 * Computes the unique storage part primary key for an attribute index by bit-joining the entity index primary key
	 * (high 32 bits) with the compressed integer id for the `(attributeKey, indexType)` pair (low 32 bits). The
	 * compressed id is obtained from `keyCompressor`, which may register a new mapping if the combination has not
	 * been seen before.
	 *
	 * @param entityIndexPrimaryKey integer primary key of the owning {@link io.evitadb.index.EntityIndex}
	 * @param indexType             the kind of attribute index (filter, sort, unique, …) — needed because the same
	 *                              attribute may have several independent index structures
	 * @param attributeKey          the attribute key identifying the attribute and its locale (if localized)
	 * @param keyCompressor         the key compressor used to translate the compound key into a compact integer id
	 * @return a 64-bit storage part primary key that is unique among all {@link AttributeIndexStoragePart} instances
	 *         within the same entity collection
	 */
	static long computeUniquePartId(
		@Nonnull Integer entityIndexPrimaryKey,
		@Nonnull AttributeIndexType indexType,
		@Nonnull AttributeIndexKey attributeKey,
		@Nonnull KeyCompressor keyCompressor
	) {
		final int id = keyCompressor.getId(new AttributeKeyWithIndexType(attributeKey, indexType));
		return NumberUtils.join(entityIndexPrimaryKey, id);
	}

	/**
	 * Returns the integer primary key of the {@link io.evitadb.index.EntityIndex} that owns this attribute index
	 * storage part. Together with the {@link AttributeIndexType} and the attribute key, this value forms the compound
	 * logical key used to locate the part in the persistent storage.
	 */
	@Nonnull
	Integer getEntityIndexPrimaryKey();

	/**
	 * Returns {@link AttributeKey} of the attribute which information is stored in this index.
	 */
	@Nonnull
	AttributeIndexKey getAttributeIndexKey();

	/**
	 * Returns type of the index that is represented by this part - multiple parts/indexes may target same attribute
	 * and this enum is used to distinguish these indexes among themselves by type.
	 */
	@Nonnull
	AttributeIndexType getIndexType();

	/**
	 * Allows setting computed `uniquePartId` to the container so that it is computed only once.
	 */
	void setStoragePartPK(@Nullable Long storagePartPK);

	/**
	 * Method computes `uniquePartId` for the current container using {@link KeyCompressor} in the parameter and sets
	 * the uniquePartId to local container so that it doesn't need to be computed again.
	 */
	@Override
	default long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = computeUniquePartId(getEntityIndexPrimaryKey(), getIndexType(), getAttributeIndexKey(), keyCompressor);
		final Long uniquePartId = getStoragePartPK();
		if (uniquePartId == null) {
			setStoragePartPK(computedUniquePartId);
		} else {
			Assert.isTrue(uniquePartId == computedUniquePartId, "Unique part ids must never differ!");
		}
		return computedUniquePartId;
	}

	/**
	 * Discriminator for the concrete kind of attribute index stored in an {@link AttributeIndexStoragePart}. Because
	 * a single attribute may have multiple index structures (e.g. both a filter bitmap index and a sort index), the
	 * type is embedded in the storage part key so each structure is persisted and loaded independently.
	 */
	enum AttributeIndexType {

		/** Index that enforces uniqueness of attribute values across all entities; supports exact-match lookups. */
		UNIQUE,

		/** Inverted bitmap index over discrete attribute values; supports equality and range filter queries. */
		FILTER,

		/** Sorted order index over attribute values; supports ordering results and range scans in sorted order. */
		SORT,

		/** Chain / predecessor index that tracks linked-list ordering of entities by attribute value. */
		CHAIN,

		/** Cardinality index that counts how many entities share each distinct attribute value; used for facets. */
		CARDINALITY

	}

}
