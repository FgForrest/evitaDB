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
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
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
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.system.StoredVersion;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.task.ServerTask;
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
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

/**
 * This implementation of {@link CatalogContract} represents a unusable catalog instance that is not loaded into
 * a memory and cannot process requests. Most methods of this implementation throw exception when invoked.
 * The appropriate exception and catalog path are accessible via. {@link #getCatalogStoragePath()} and
 * {@link #getCause()} methods. The catalog can provide only its name, state and storage path.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public final class UnusableCatalog implements CatalogContract {
	private final String catalogName;
	private final CatalogState catalogState;
	@Getter private final Path catalogStoragePath;
	@Getter private final BiFunction<String, Path, RuntimeException> cause;
	private boolean terminated;

	@Nonnull
	@Override
	public SealedCatalogSchema getSchema() {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public SealedCatalogSchema updateSchema(
		@Nonnull EvitaContract evita, @Nullable UUID sessionId,
		@Nonnull LocalCatalogSchemaMutation... schemaMutation
	) throws SchemaAlteringException {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public UUID getCatalogId() {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public CatalogState getCatalogState() {
		return this.catalogState;
	}

	@Nonnull
	@Override
	public String getName() {
		return this.catalogName;
	}

	@Override
	public long getVersion() {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Override
	public boolean supportsTransaction() {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public Set<String> getEntityTypes() {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public <S extends Serializable, T extends EvitaResponse<S>> T getEntities(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public Optional<EntityCollectionContract> getCollectionForEntity(@Nonnull String entityType) {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public EntityCollectionContract getCollectionForEntityOrThrowException(@Nonnull String entityType) throws CollectionNotFoundException {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public EntityCollection getCollectionForEntityPrimaryKeyOrThrowException(int entityTypePrimaryKey) throws CollectionNotFoundException {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public EntityCollection getOrCreateCollectionForEntity(@Nonnull EvitaSessionContract session, @Nonnull String entityType) {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Override
	public void terminateAndDelete() {
		FileUtils.deleteDirectory(this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public ProgressingFuture<CatalogContract> replace(@Nonnull CatalogSchemaContract updatedSchema, @Nullable CatalogContract catalogToBeReplaced) {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public Map<String, EntitySchemaContract> getEntitySchemaIndex() {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public Optional<SealedEntitySchema> getEntitySchema(@Nonnull String entityType) {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Override
	public void applyMutation(@Nonnull EvitaSessionContract session, @Nonnull CatalogBoundMutation mutation) throws InvalidMutationException {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Override
	public void processWriteAheadLog(@Nonnull Consumer<CatalogContract> updatedCatalog) {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public StoredVersion getCatalogVersionAt(@Nullable OffsetDateTime moment) throws TemporalDataNotAvailableException {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public PaginatedList<StoredVersion> getCatalogVersions(@Nonnull TimeFlow timeFlow, int page, int pageSize) {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public Stream<WriteAheadLogVersionDescriptor> getCatalogVersionDescriptors(long... catalogVersion) {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public Stream<CatalogBoundMutation> getCommittedMutationStream(long catalogVersion) {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public Stream<CatalogBoundMutation> getReversedCommittedMutationStream(@Nullable Long catalogVersion) {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Override
	public boolean isGoingLive() {
		return false;
	}

	@Nonnull
	@Override
	public CatalogContract goLive() {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
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
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public ServerTask<?, FileForFetch> fullBackup(
		@Nullable LongConsumer onStart,
		@Nullable LongConsumer onComplete
	) {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public ProgressingFuture<Void> duplicateTo(@Nonnull String targetCatalogName) {
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	@Nonnull
	@Override
	public CatalogStatistics getStatistics() {
		return new CatalogStatistics(
			null,
			this.catalogName,
			true,
			false,
			this.catalogState,
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
		throw this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

	/**
	 * Returns the exception detailing the cause of the corrupted catalog.
	 * The exception is generated by applying the cause function to the catalog name
	 * and the catalog storage path.
	 *
	 * @return a RuntimeException indicating the cause of the catalog corruption
	 */
	@Nonnull
	public RuntimeException getRepresentativeException() {
		return this.cause.apply(this.catalogName, this.catalogStoragePath);
	}

}
