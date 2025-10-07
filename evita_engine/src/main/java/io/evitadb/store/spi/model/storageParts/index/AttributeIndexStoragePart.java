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

package io.evitadb.store.spi.model.storageParts.index;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.index.EntityIndex;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
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
	 * Method computes unique part id as long, that composes of integer primary key of the {@link io.evitadb.index.EntityIndex}
	 * attributes belong to and compressed attribute key integer that is assigned as soon as attribute is first stored.
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
	 * Returns {@link EntityIndex#getPrimaryKey()}
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
	 * This enum distinguishes different types of {@link AttributeIndexStoragePart}.
	 */
	enum AttributeIndexType {

		UNIQUE, FILTER, SORT, CHAIN, CARDINALITY

	}

}
