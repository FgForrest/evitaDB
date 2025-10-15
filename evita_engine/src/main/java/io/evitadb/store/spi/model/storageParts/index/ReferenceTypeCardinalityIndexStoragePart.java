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

import io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex;
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
import java.io.Serial;

/**
 * This storage part implementation maintains information about reference type cardinality in the entity index. The cardinality
 * represents how many times a specific reference type appears in combination with an entity. This information is crucial for
 * query optimization and statistics.
 *
 * The class stores references to the entity index, reference name, and maintains a cardinality index that maps
 * reference type-entity combinations to their occurrence counts. It implements both {@link StoragePart} and
 * {@link RecordWithCompressedId} interfaces to support storage and retrieval operations.
 *
 * This storage part is related to the {@link ReferenceTypeCardinalityIndex} data structure and is created only for persistence
 * purposes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
@AllArgsConstructor
@ToString(of = {"entityIndexPrimaryKey", "referenceName"})
public class ReferenceTypeCardinalityIndexStoragePart implements StoragePart, RecordWithCompressedId<String> {
	@Serial private static final long serialVersionUID = 8276690113370094734L;

	/**
	 * Unique id that identifies {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final Integer entityIndexPrimaryKey;
	/**
	 * Name of the reference this index is related to.
	 */
	@Getter private final String referenceName;
	/**
	 * This map contains cardinality of the attribute values. Key is the combination of attribute value and entity id.
	 * Value is the number of occurrences of this combination in the index.
	 */
	@Getter private final ReferenceTypeCardinalityIndex cardinalityIndex;
	/**
	 * Id used for lookups in file offset index for this particular container.
	 */
	@Getter @Setter private Long storagePartPK;

	/**
	 * Method computes unique part id as long, that composes of integer primary key of the {@link io.evitadb.index.EntityIndex}
	 * index belong to and compressed reference name that is assigned as soon as index is first stored.
	 */
	public static long computeUniquePartId(@Nonnull Integer entityIndexPrimaryKey, @Nonnull String referenceName, @Nonnull KeyCompressor keyCompressor) {
		return NumberUtils.join(entityIndexPrimaryKey, keyCompressor.getId(referenceName));
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = NumberUtils.join(this.entityIndexPrimaryKey, keyCompressor.getId(this.referenceName));
		Assert.isPremiseValid(
			this.storagePartPK == null || this.storagePartPK == computedUniquePartId,
			() -> "The storage part id was already set to different value!"
		);
		this.storagePartPK = computedUniquePartId;
		return computedUniquePartId;
	}

	@Override
	public String getStoragePartSourceKey() {
		return this.referenceName;
	}

}
