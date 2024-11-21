/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.dataType.Scope;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Set;

/**
 * This container is used to store contents of the {@link io.evitadb.index.CatalogIndex} to the persistent storage.
 * Attribute unique indexes are used separately in {@link GlobalUniqueIndexStoragePart} and so that the size of the
 * {@link CatalogIndexStoragePart} is kept small. Also changes in attribute indexes will trigger rewriting index only
 * of this particular single index of single attribute. This will also affect speed and storage requirements for
 * the persistent storage.
 *
 * When loading {@link io.evitadb.index.CatalogIndex} from persistent storage all information need to be collected
 * together in order complete {@link io.evitadb.index.CatalogIndex} to be restored.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class CatalogIndexStoragePart implements StoragePart {
	@Serial private static final long serialVersionUID = -2897805224966000541L;

	/**
	 * Version of the entity index that gets increased with each atomic change in the index (incremented by one when
	 * transaction is committed and anything in this index was changed).
	 */
	@Getter private final int version;
	/**
	 * Type of the index.
	 */
	@Getter private final CatalogIndexKey catalogIndexKey;
	/**
	 * Contains references to the {@link GlobalUniqueIndexStoragePart} in the form of {@link AttributeKey} that
	 * allows to translate itself to a unique key allowing to fetch {@link StoragePart} from persistent storage.
	 */
	@Getter private final Set<AttributeKey> sharedAttributeUniqueIndexes;

	/**
	 * Returns the storage part primary key for the given scope.
	 *
	 * @param scope the scope for which the storage part primary key needs to be determined
	 * @return the primary key (1L for LIVE scope, 2L for other scopes)
	 */
	public static long getStoragePartPKForScope(@Nonnull Scope scope) {
		return scope == Scope.LIVE ? 1L : 2L;
	}

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return getStoragePartPKForScope(catalogIndexKey.scope());
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return 1L;
	}

}
