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

package io.evitadb.store.spi.model;

import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * evitaDB catalog header that contains all key information for loading/persisting single catalog of evitaDB to disk.
 *
 * TOBEDONE JNO - we should change the format of the bootstrap file to fixed length file that would contain minuscule
 * records that will map catalog file offset index file location only. All key information should be part of the catalog
 * file offset index file. This way we could any time revert database to a previous moment in time.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
public class CatalogBootstrap {
	/**
	 * Contains state of the {@link Catalog}.
	 */
	@Getter private final CatalogState catalogState;
	/**
	 * Contains last committed transaction id for this catalog.
	 */
	@Getter private final long lastTransactionId;
	/**
	 * Contains the catalog header that contains {@link CatalogSchema} as well as catalog wide
	 * indexes and also set of {@link EntityCollectionHeader};
	 */
	@Getter private final CatalogHeader catalogHeader;
	/**
	 * Contains index of all {@link EntityCollection} headers that are necessary for accessing the entities.
	 *
	 * TOBEDONE JNO - THIS COULD BE PART OF CATALOG FILE OFFSET INDEX IN THE FUTURE
	 */
	@Getter private final Map<String, EntityCollectionHeader> collectionHeaders;

	public CatalogBootstrap(@Nonnull CatalogState catalogState) {
		this.catalogHeader = null;
		this.catalogState = catalogState;
		this.lastTransactionId = 0L;
		this.collectionHeaders = Collections.emptyMap();
	}

	public CatalogBootstrap(@Nonnull CatalogState catalogState, long lastTransactionId, @Nonnull CatalogHeader catalogHeader, @Nonnull Collection<EntityCollectionHeader> collectionHeaders) {
		this.catalogHeader = catalogHeader;
		this.catalogState = catalogState;
		this.lastTransactionId = lastTransactionId;
		this.collectionHeaders = collectionHeaders
			.stream()
			.collect(
				Collectors.toMap(
					EntityCollectionHeader::getEntityType,
					Function.identity()
				)
			);
	}

	/**
	 * Returns list of all known entity types registered in this header.
	 */
	public Collection<EntityCollectionHeader> getEntityTypeHeaders() {
		return collectionHeaders.values();
	}

	/**
	 * Returns returns {@link EntityCollectionHeader} for specified `entityType` if it's known to this header.
	 */
	@Nullable
	public EntityCollectionHeader getEntityTypeHeader(@Nonnull String entityType) {
		return collectionHeaders.get(entityType);
	}

	/**
	 * Returns true if catalog supports transactions.
	 */
	public boolean supportsTransaction() {
		return catalogState == CatalogState.ALIVE;
	}

}
