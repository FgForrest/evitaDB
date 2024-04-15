/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.index.cardinality.CardinalityIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.store.entity.model.entity.price.PriceInternalIdContainer;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * This container is used to store contents of the {@link io.evitadb.index.EntityIndex} to the persistent storage.
 * Attribute indexes are used separately in {@link FilterIndexStoragePart}, {@link UniqueIndexStoragePart} and
 * {@link SortIndexStoragePart} so that the size of the {@link EntityIndexStoragePart} is kept small. Also changes in
 * attribute indexes will trigger rewriting index only of this particular single index of single attribute. This will
 * also affect speed and storage requirements for the persistent storage.
 *
 * When loading {@link io.evitadb.index.EntityIndex} from persistent storage all information need to be collected
 * together in order complete {@link io.evitadb.index.EntityIndex} to be restored.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
@ToString(of = "entityIndexKey")
public class EntityIndexStoragePart implements StoragePart {
	@Serial private static final long serialVersionUID = -6245538251957498672L;

	/**
	 * Unique id that identifies {@link io.evitadb.index.EntityIndex}.
	 */
	@Getter private final int primaryKey;
	/**
	 * Version of the entity index that gets increased with each atomic change in the index (incremented by one when
	 * transaction is committed and anything in this index was changed).
	 */
	@Getter private final int version;
	/**
	 * Type of the index.
	 */
	@Getter private final EntityIndexKey entityIndexKey;
	/**
	 * IntegerBitmap contains all entity ids known to this index. This bitmap represents superset of all inner bitmaps.
	 */
	@Getter private final Bitmap entityIds;
	/**
	 * Map contains entity ids by their supported language.
	 */
	@Getter private final Map<Locale, TransactionalBitmap> entityIdsByLanguage;
	/**
	 * Contains references to the {@link AttributeIndexStoragePart} in the form of {@link AttributeIndexStorageKey} that
	 * allows to translate itself to a unique key allowing to fetch {@link StoragePart} from persistent storage.
	 */
	@Getter private final Set<AttributeIndexStorageKey> attributeIndexes;
	/**
	 * Contains the last used id in the sequence for assigning {@link PriceInternalIdContainer#getInternalPriceId()} to
	 * a newly encountered prices in the input data. See {@link PriceInternalIdContainer} to see the reasons behind it.
	 */
	@Getter private final Integer internalPriceIdSequence;
	/**
	 * Contains references to the {@link PriceListAndCurrencySuperIndexStoragePart} in the form of {@link PriceIndexKey} that
	 * allows to translate itself to a unique key allowing to fetch {@link StoragePart} from persistent storage.
	 */
	@Getter private final Set<PriceIndexKey> priceIndexes;
	/**
	 * Contains TRUE if there is {@link HierarchyIndexStoragePart} present in this index. So that it is loaded from
	 * persistent storage.
	 */
	@Getter private final boolean hierarchyIndex;
	/**
	 * Contains references to the {@link FacetIndexStoragePart} in the form of {@link String} entityType that
	 * allows to translate itself to a unique key allowing to fetch {@link StoragePart} from persistent storage.
	 */
	@Getter private final Set<String> facetIndexes;
	/**
	 * This field is initialized only by {@link io.evitadb.index.ReferencedTypeEntityIndex} - for other indexes it is
	 * empty.
	 */
	@Getter private final CardinalityIndex primaryKeyCardinality;

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return (long) primaryKey;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return primaryKey;
	}

}
