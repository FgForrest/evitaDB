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

import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.Map;

/**
 * Facet index collocates information about facets in entities. This container object serves only as a storage carrier
 * for {@link io.evitadb.index.facet.FacetIndex} which is a live memory representation of the data stored in this
 * container.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@ToString(of = {"referenceName", "entityIndexPrimaryKey"})
public class FacetIndexStoragePart implements StoragePart {
	@Serial private static final long serialVersionUID = -2348533783771242845L;

	/**
	 * Unique id that identifies {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final int entityIndexPrimaryKey;
	/**
	 * Refers to {@link EntitySchema#getName()}
	 */
	@Getter private final String referenceName;
	/**
	 * Refers to facets that are not assigned to any group.
	 */
	@Getter private final Map<Integer, Bitmap> noGroupFacetingEntities;
	/**
	 * Contains information about referenced entities facet.
	 */
	@Getter private final Map<Integer, Map<Integer, Bitmap>> facetingEntities;
	/**
	 * Id used for lookups in persistent storage for this particular container.
	 */
	@Getter @Setter private Long storagePartPK;

	public FacetIndexStoragePart(int entityIndexPrimaryKey, @Nonnull String referenceName, @Nullable Map<Integer, Bitmap> noGroupFacetingEntities, @Nonnull Map<Integer, Map<Integer, Bitmap>> facetingEntities) {
		this.entityIndexPrimaryKey = entityIndexPrimaryKey;
		this.referenceName = referenceName;
		this.noGroupFacetingEntities = noGroupFacetingEntities;
		this.facetingEntities = facetingEntities;
	}

	public static long computeUniquePartId(Integer entityIndexPrimaryKey, String referenceName, KeyCompressor keyCompressor) {
		return NumberUtils.join(entityIndexPrimaryKey, keyCompressor.getId(new ReferenceNameKey(referenceName)));
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		final long computedUniquePartId = computeUniquePartId(this.entityIndexPrimaryKey, this.referenceName, keyCompressor);
		final Long theUniquePartId = getStoragePartPK();
		if (theUniquePartId == null) {
			setStoragePartPK(computedUniquePartId);
		} else {
			Assert.isTrue(theUniquePartId == computedUniquePartId, "Unique part ids must never differ!");
		}
		return computedUniquePartId;
	}

}
