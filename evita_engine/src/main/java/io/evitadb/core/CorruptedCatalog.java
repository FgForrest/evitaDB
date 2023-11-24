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

package io.evitadb.core;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.utils.FileUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * This implementation of {@link CatalogContract} represents a catalog instance that cannot be loaded into a memory due
 * an error. Most methods of this implementation throw {@link CatalogCorruptedException} when invoked. The original
 * exception and catalog path are accessible via. {@link #getCatalogStoragePath()} and {@link #getCause()} methods.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public final class CorruptedCatalog implements CatalogContract {
	private final String catalogName;
	@Getter private final Path catalogStoragePath;
	@Getter private final Throwable cause;

	@Nonnull
	@Override
	public SealedCatalogSchema getSchema() {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public @Nonnull CatalogSchemaContract updateSchema(@Nonnull EvitaSessionContract session, @Nonnull LocalCatalogSchemaMutation... schemaMutation) throws SchemaAlteringException {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public CatalogState getCatalogState() {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public String getName() {
		return catalogName;
	}

	@Override
	public long getVersion() {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public boolean supportsTransaction() {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public Set<String> getEntityTypes() {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public <S extends Serializable, T extends EvitaResponse<S>> T getEntities(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public EntityCollectionContract createCollectionForEntity(@Nonnull String entityType, @Nonnull EvitaSessionContract session) {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public Optional<EntityCollectionContract> getCollectionForEntity(@Nonnull String entityType) {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public EntityCollectionContract getCollectionForEntityOrThrowException(@Nonnull String entityType) throws CollectionNotFoundException {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public EntityCollection getCollectionForEntityPrimaryKeyOrThrowException(int entityTypePrimaryKey) throws CollectionNotFoundException {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public EntityCollection getOrCreateCollectionForEntity(@Nonnull String entityType, @Nonnull EvitaSessionContract session) {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public boolean replaceCollectionOfEntity(@Nonnull String entityTypeToBeReplaced, @Nonnull String entityTypeToBeReplacedWith, @Nonnull EvitaSessionContract session) {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public boolean deleteCollectionOfEntity(@Nonnull String entityType, @Nonnull EvitaSessionContract session) {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public boolean renameCollectionOfEntity(@Nonnull String entityType, @Nonnull String newName, @Nonnull EvitaSessionContract session) {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public void delete() {
		FileUtils.deleteDirectory(catalogStoragePath);
	}

	@Nonnull
	@Override
	public CatalogContract replace(@Nonnull CatalogSchemaContract updatedSchema, @Nonnull CatalogContract catalogToBeReplaced) {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public Map<String, EntitySchemaContract> getEntitySchemaIndex() {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public Optional<SealedEntitySchema> getEntitySchema(@Nonnull String entityType) {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public boolean goLive() {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public void terminate() {
		// we don't need to terminate corrupted catalog
	}

}
