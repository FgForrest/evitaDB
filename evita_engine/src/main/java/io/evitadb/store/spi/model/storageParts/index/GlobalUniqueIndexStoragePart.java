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
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Scope;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.attribute.GlobalUniqueIndex.EntityWithTypeTuple;
import io.evitadb.store.model.RecordWithCompressedId;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;

/**
 * Filter index container stores index for single {@link AttributeSchema} of the single
 * {@link EntitySchema}. This container object serves only as a storage carrier for
 * {@link io.evitadb.index.attribute.UniqueIndex} which is a live memory representation of the data stored in this
 * container.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@RequiredArgsConstructor
@AllArgsConstructor
@ToString(of = "attributeKey")
public class GlobalUniqueIndexStoragePart implements StoragePart, RecordWithCompressedId<AttributeKey> {
	@Serial private static final long serialVersionUID = -7216725334566367295L;

	/**
	 * Scope of the {@link CatalogIndex} this unique index belongs to.
	 */
	@Getter private final Scope scope;
	/**
	 * Contains name and locale of the indexed attribute.
	 */
	@Getter private final AttributeKey attributeKey;
	/**
	 * Contains type of the attribute.
	 */
	@Getter private final Class<? extends Serializable> type;
	/**
	 * Keeps the unique value to record id mappings. Fairly large HashMap is expected here.
	 */
	@Getter private final Map<Serializable, EntityWithTypeTuple> uniqueValueToRecordId;
	/**
	 * Keeps the internal index of primary keys assigned to locales.
	 */
	@Getter private final Map<Integer, Locale> localeIndex;
	/**
	 * Id used for lookups in persistent storage for this particular container.
	 */
	@Getter @Setter private Long storagePartPK;

	/**
	 * Method computes unique part id as long, that composes of integer primary key of the {@link io.evitadb.index.EntityIndex}
	 * attributes belong to and compressed attribute key integer that is assigned as soon as attribute is first stored.
	 */
	public static long computeUniquePartId(@Nonnull Scope scope, @Nonnull AttributeKey attributeKey, @Nonnull KeyCompressor keyCompressor) {
		return NumberUtils.join(scope.ordinal(), keyCompressor.getId(attributeKey));
	}

	/**
	 * Method computes `uniquePartId` for the current container using {@link KeyCompressor} in the parameter and sets
	 * the uniquePartId to local container so that it doesn't need to be computed again.
	 */
	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = computeUniquePartId(getScope(), getAttributeKey(), keyCompressor);
		final Long uniquePartId = getStoragePartPK();
		if (uniquePartId == null) {
			setStoragePartPK(computedUniquePartId);
		} else {
			Assert.isTrue(uniquePartId == computedUniquePartId, "Unique part ids must never differ!");
		}
		return computedUniquePartId;
	}

	@Override
	public AttributeKey getStoragePartSourceKey() {
		return this.attributeKey;
	}

}
