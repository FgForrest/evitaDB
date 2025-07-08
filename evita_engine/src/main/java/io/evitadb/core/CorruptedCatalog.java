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

package io.evitadb.core;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.CatalogStatistics;
import io.evitadb.api.CatalogStatistics.EntityCollectionStatistics;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.GoLiveProgress;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.system.StoredVersion;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.utils.FileUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

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
	private boolean terminated;

	@Nonnull
	@Override
	public SealedCatalogSchema getSchema() {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public @Nonnull CatalogSchemaContract updateSchema(
		@Nullable UUID sessionId,
		@Nonnull LocalCatalogSchemaMutation... schemaMutation
	) throws SchemaAlteringException {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public UUID getCatalogId() {
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
		return this.catalogName;
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
	public EntityCollection getOrCreateCollectionForEntity(@Nonnull EvitaSessionContract session, @Nonnull String entityType) {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public boolean replaceCollectionOfEntity(@Nonnull EvitaSessionContract session, @Nonnull String entityTypeToBeReplaced, @Nonnull String entityTypeToBeReplacedWith) {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public boolean deleteCollectionOfEntity(@Nonnull EvitaSessionContract session, @Nonnull String entityType) {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public boolean renameCollectionOfEntity(@Nonnull String entityType, @Nonnull String newName, @Nonnull EvitaSessionContract session) {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public void terminateAndDelete() {
		FileUtils.deleteDirectory(this.catalogStoragePath);
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
	public void applyMutation(@Nonnull EvitaSessionContract session, @Nonnull CatalogBoundMutation mutation) throws InvalidMutationException {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public void processWriteAheadLog(@Nonnull Consumer<CatalogContract> updatedCatalog) {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public StoredVersion getCatalogVersionAt(@Nullable OffsetDateTime moment) throws TemporalDataNotAvailableException {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public PaginatedList<StoredVersion> getCatalogVersions(@Nonnull TimeFlow timeFlow, int page, int pageSize) {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public Stream<WriteAheadLogVersionDescriptor> getCatalogVersionDescriptors(long... catalogVersion) {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public Stream<CatalogBoundMutation> getCommittedMutationStream(long catalogVersion) {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public Stream<CatalogBoundMutation> getReversedCommittedMutationStream(@Nullable Long catalogVersion) {
		throw new CatalogCorruptedException(this);
	}

	@Override
	public boolean isGoingLive() {
		return false;
	}

	@Nonnull
	@Override
	public GoLiveProgress goLive(IntConsumer progressObserver) {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public ServerTask<Void, FileForFetch> backup(
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL,
		@Nullable LongConsumer onStart,
		@Nullable LongConsumer onComplete
	) throws TemporalDataNotAvailableException {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public ServerTask<?, FileForFetch> fullBackup(
		@Nullable LongConsumer onStart,
		@Nullable LongConsumer onComplete
	) {
		throw new CatalogCorruptedException(this);
	}

	@Nonnull
	@Override
	public CatalogStatistics getStatistics() {
		return new CatalogStatistics(
			null,
			this.catalogName,
			true,
			CatalogState.CORRUPTED,
			-1L,
			-1,
			-1,
			FileUtils.getDirectorySize(this.catalogStoragePath),
			new EntityCollectionStatistics[0]
		);
	}

	@Override
	public void terminate() {
		this.terminated = true;
	}

	@Override
	public boolean isTerminated() {
		return this.terminated;
	}

	@Nonnull
	@Override
	public ChangeCapturePublisher<ChangeCatalogCapture> registerChangeCatalogCapture(@Nonnull ChangeCatalogCaptureRequest request) {
		throw new CatalogCorruptedException(this);
	}
}
