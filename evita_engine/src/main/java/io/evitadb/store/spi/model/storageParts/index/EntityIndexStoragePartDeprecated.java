/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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


import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.store.entity.model.entity.price.PriceInternalIdContainer;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Class is used only for temporary period to retrieve `internalPriceIdSequence` from underlying storage.
 *
 * @deprecated This class is deprecated and will be removed in the future. Use {@link EntityIndexStoragePart} instead.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Deprecated(since = "2025.7", forRemoval = true)
public class EntityIndexStoragePartDeprecated extends EntityIndexStoragePart {
	@Serial private static final long serialVersionUID = 3455486364434181265L;

	/**
	 * Contains the last used id in the sequence for assigning {@link PriceInternalIdContainer#getInternalPriceId()} to
	 * a newly encountered prices in the input data. See {@link PriceInternalIdContainer} to see the reasons behind it.
	 */
	@Getter private final Integer internalPriceIdSequence;
	/**
	 * Incorrect / old / deprecated data structure for storing reference type cardinalities.
	 */
	@Getter private final AttributeCardinalityIndex referenceTypeCardinalityIndex;

	public EntityIndexStoragePartDeprecated(
		int primaryKey,
		int version,
		@Nonnull EntityIndexKey entityIndexKey,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull Set<AttributeIndexStorageKey> attributeIndexes,
		@Nonnull Set<PriceIndexKey> priceIndexes,
		boolean hierarchyIndex,
		@Nonnull Set<String> facetIndexes,
		// incorrect data structure here
		@Nonnull AttributeCardinalityIndex referenceTypeCardinalityIndex,
		@Nonnull Integer internalPriceIdSequence
	) {
		super(
			primaryKey, version, entityIndexKey,
			entityIds, entityIdsByLanguage, attributeIndexes, priceIndexes, hierarchyIndex, facetIndexes
		);
		this.internalPriceIdSequence = internalPriceIdSequence;
		this.referenceTypeCardinalityIndex = referenceTypeCardinalityIndex;
	}

}
