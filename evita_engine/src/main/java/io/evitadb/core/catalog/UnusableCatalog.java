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

package io.evitadb.core.catalog;

import io.evitadb.api.CatalogVersionPin;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.statistics.CatalogIdentity;
import io.evitadb.api.statistics.CatalogStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.ComponentAvailability;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics;
import io.evitadb.api.statistics.StorageSizeStatistics;
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
import io.evitadb.api.requestResponse.system.MaterializedVersionBlock;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.spi.store.engine.CatalogFolderOperations;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.stream.Stream;

/**
 * This implementation of {@link CatalogContract} represents a unusable catalog instance that is not loaded into
 * a memory and cannot process requests. Most methods of this implementation throw exception when invoked.
 * The appropriate exception and folder binding are accessible via. {@link #getCatalogFolderId()} and
 * {@link #getCause()} methods. The catalog can provide only its name, state and folder binding.
 *
 * Being the placeholder for a catalog that has no persistence service, it cannot perform folder-level work
 * itself and is handed a {@link CatalogFolderOperations} handle for the one piece of folder-level work it still
 * has to do — measuring the folder for {@link #getStatistics()}. Removal is not among them: it tombstones the
 * folder and wipes it through the folder context, never through the catalog instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public final class UnusableCatalog implements CatalogContract {
	private final String catalogName;
	private final CatalogState catalogState;
	@Getter private final CatalogFolderId catalogFolderId;
	/**
	 * Configured root holding all catalog folders. Reported alongside the folder token in error messages so an
	 * operator can locate the folder without the engine ever joining the two — knowing the root is
	 * configuration, knowing the join rule is storage layout. See `CatalogFolderId` for the boundary rule.
	 */
	private final Path storageRoot;
	private final CatalogFolderOperations folderOperations;
	@Getter private final UnusableCatalogExceptionFactory cause;
	private boolean terminated;

	/**
	 * Produces the exception every unusable-catalog operation throws.
	 *
	 * Takes the folder token and the storage root separately rather than a resolved path, so that the failure
	 * message can name a concrete location without the engine deriving one.
	 */
	@FunctionalInterface
	public interface UnusableCatalogExceptionFactory {

		/**
		 * Creates the exception describing why this catalog is unusable.
		 *
		 * @param catalogName name of the unusable catalog
		 * @param folderId    token identifying the folder bound to the catalog
		 * @param storageRoot configured root directory holding all catalog folders
		 * @return exception to be thrown from the invoked operation
		 */
		@Nonnull
		RuntimeException create(
			@Nonnull String catalogName,
			@Nonnull CatalogFolderId folderId,
			@Nonnull Path storageRoot
		);

	}

	@Nonnull
	@Override
	public SealedCatalogSchema getSchema() {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public SealedCatalogSchema updateSchema(
		@Nonnull EvitaContract evita, @Nullable UUID sessionId,
		@Nonnull LocalCatalogSchemaMutation... schemaMutation
	) throws SchemaAlteringException {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public UUID getCatalogId() {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
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
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Override
	public boolean supportsTransaction() {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public Set<String> getEntityTypes() {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public <S extends Serializable, T extends EvitaResponse<S>> T getEntities(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session) {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public Optional<EntityCollectionContract> getCollectionForEntity(@Nonnull String entityType) {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public EntityCollectionContract getCollectionForEntityOrThrowException(@Nonnull String entityType) throws CollectionNotFoundException {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public EntityCollection getCollectionForEntityPrimaryKeyOrThrowException(int entityTypePrimaryKey) throws CollectionNotFoundException {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public EntityCollection getOrCreateCollectionForEntity(@Nonnull EvitaSessionContract session, @Nonnull String entityType) {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public ProgressingFuture<CatalogContract> replace(@Nonnull CatalogSchemaContract updatedSchema, @Nullable CatalogContract catalogToBeReplaced) {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public Map<String, EntitySchemaContract> getEntitySchemaIndex() {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public Optional<SealedEntitySchema> getEntitySchema(@Nonnull String entityType) {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Override
	public void applyMutation(@Nonnull EvitaSessionContract session, @Nonnull CatalogBoundMutation mutation) throws InvalidMutationException {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Override
	public void processWriteAheadLog(@Nonnull Consumer<CatalogContract> updatedCatalog) {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public MaterializedVersionBlock getFirstCatalogVersionAfter(@Nullable OffsetDateTime moment) throws TemporalDataNotAvailableException {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public MaterializedVersionBlock getLastCatalogVersionBefore(
		@Nullable OffsetDateTime moment
	) throws TemporalDataNotAvailableException {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public PaginatedList<MaterializedVersionBlock> getCatalogVersions(@Nonnull TimeFlow timeFlow, int page, int pageSize) {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public List<WriteAheadLogVersionDescriptor> getCatalogVersionDescriptors(long... catalogVersion) {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public Stream<CatalogBoundMutation> getCommittedMutationStream(long catalogVersion) {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public Stream<CatalogBoundMutation> getReversedCommittedMutationStream(@Nullable Long catalogVersion) {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Override
	public boolean isGoingLive() {
		return false;
	}

	@Nonnull
	@Override
	public CatalogContract goLive() {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public ServerTask<Void, FileForFetch> backup(
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL,
		@Nullable LongFunction<CatalogVersionPin> onStart
	) throws TemporalDataNotAvailableException {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public ServerTask<?, FileForFetch> fullBackup(
		@Nullable LongFunction<CatalogVersionPin> onStart
	) {
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public CatalogStatistics getStatistics(@Nonnull Set<CatalogStatisticsComponent> components) {
		CatalogStatisticsComponent.assertNotEmpty(components);
		final CatalogStatistics.Builder builder = CatalogStatistics.builder(
			new CatalogIdentity(
				null,
				this.catalogName,
				this.catalogState,
				-1L,
				false,
				true,
				false,
				false,
				-1
			)
		);
		for (final CatalogStatisticsComponent component : components) {
			switch (component) {
				// always recorded by the builder itself, since nothing else can be interpreted without it
				case IDENTITY -> { }
				// the one component that survives a catalog which would not load: file lengths are readable whether or
				// not the contents parse, and how much disk a corrupted catalog is holding is exactly what an operator
				// needs to know about it.
				case STORAGE_SIZE -> builder.withStorageSize(measureStorageSize());
				// everything else is derived from state that could not be loaded. Reporting that explicitly is the
				// point - a client must be able to tell this apart from a catalog that is merely empty.
				//
				// `default` is deliberate here rather than an exhaustive list: it is not a silent skip but the total
				// answer, since any component this catalog cannot compute today it cannot compute for the same reason
				// tomorrow. A component added later and forgotten here would otherwise throw on exactly the degraded
				// catalog an operator is trying to inspect.
				default -> builder.withUnavailable(
					component,
					ComponentAvailability.CATALOG_UNUSABLE,
					"Catalog `" + this.catalogName + "` could not be loaded, so `" + component + "` cannot be computed."
				);
			}
		}
		return builder.build();
	}

	/**
	 * Measures the disk footprint of a catalog that would not load, through the same classifier a loaded catalog
	 * uses - handing it no generations, which is how "the header could not be read" is expressed.
	 *
	 * Two classes survive that, because both are recognised from the file name alone: the bootstrap file and the
	 * write-ahead log. Telling an operator which of the two is holding the disk of a corrupted catalog is the
	 * difference between "restore it" and "shorten WAL retention". Everything else reads as unaccounted, including
	 * the data store files - separating live records from compaction waste, or a current generation from a
	 * superseded one, needs the header. {@link StorageSizeStatistics} documents that reading.
	 *
	 * The listing itself is asked of {@link CatalogFolderOperations} rather than taken here: measuring means
	 * resolving the folder token to a directory, and joining a token with the storage root is the storage layer's
	 * to do - see {@link CatalogFolderId}.
	 *
	 * @return the {@link CatalogStatisticsComponent#STORAGE_SIZE} component of an unusable catalog
	 */
	@Nonnull
	private StorageSizeStatistics measureStorageSize() {
		return StorageSizeProjection.toStorageSizeStatistics(
			this.folderOperations.catalogFolderFootprint(this.catalogFolderId, this.catalogName)
		);
	}

	@Nonnull
	@Override
	public IndexBrowseResult browseIndexes(@Nonnull IndexBrowseCriteria criteria) {
		// no empty page here, where `getStatistics` answers per component with `CATALOG_UNUSABLE`: that response has a
		// slot to carry the reason in, and a browse result has none - an empty page would read as "this catalog holds no
		// indexes", which is precisely the reading an operator must not be given about a catalog that would not load
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public IndexDetail describeIndex(int indexPrimaryKey) {
		// not `IndexNotFoundException`, which would claim the catalog was read and found to hold no such index
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	@Nonnull
	@Override
	public List<SchemaCapabilityUsageStatistics> listCapabilityUsage() {
		// an empty list would read as "nothing has ever asked for any of this catalog's capabilities" - the one answer
		// an operator hunting for flags to drop must never be given about a catalog that would not load. Like the
		// browse beside it, this response has no slot to carry a reason in, so the failure has to be the answer
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
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
		throw this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

	/**
	 * Returns the exception detailing the cause of the corrupted catalog.
	 * The exception is generated by applying the cause factory to the catalog name, its folder token and the
	 * configured storage root.
	 *
	 * @return a RuntimeException indicating the cause of the catalog corruption
	 */
	@Nonnull
	public RuntimeException getRepresentativeException() {
		return this.cause.create(this.catalogName, this.catalogFolderId, this.storageRoot);
	}

}
