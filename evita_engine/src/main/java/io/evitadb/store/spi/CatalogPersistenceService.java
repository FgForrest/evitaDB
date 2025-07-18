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

package io.evitadb.store.spi;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.system.StoredVersion;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.InvalidClassifierFormatException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.function.BiIntConsumer;
import io.evitadb.index.CatalogIndex;
import io.evitadb.store.exception.InvalidStoragePathException;
import io.evitadb.store.spi.exception.DirectoryNotEmptyException;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * This interface represents a link between {@link CatalogContract} and its persistent storage.
 * The interface contains all methods necessary for fetching or persisting catalog header to/from durable
 * storage and access to storages of catalog {@link EntityCollectionContract entity collections}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public non-sealed interface CatalogPersistenceService extends RichPersistenceService {
	/**
	 * This constant represents the current version of the storage protocol. The version is changed everytime
	 * the storage protocol on disk changes and the data with the old protocol version cannot be read by the new
	 * protocol version.
	 *
	 * This means that the data needs to be converted from old to new protocol version first.
	 */
	String CATALOG_FILE_SUFFIX = ".catalog";
	String ENTITY_COLLECTION_FILE_SUFFIX = ".collection";
	String RESTORE_FLAG = ".restored";
	Pattern GENERIC_ENTITY_COLLECTION_PATTERN = Pattern.compile(".*-(\\d+)_(\\d+)" + ENTITY_COLLECTION_FILE_SUFFIX);

	/**
	 * Returns name of the bootstrap file that contains lead information to fetching the catalog header in fixed record
	 * size format. This file can be traversed by jumping on expected offsets.
	 */
	@Nonnull
	static String getCatalogBootstrapFileName(@Nonnull String catalogName) {
		return catalogName + BOOT_FILE_SUFFIX;
	}

	/**
	 * Returns name of the catalog data file that contains catalog schema and catalog indexes.
	 */
	@Nonnull
	static String getCatalogDataStoreFileName(@Nonnull String catalogName, int fileIndex) {
		return catalogName + '_' + fileIndex + CATALOG_FILE_SUFFIX;
	}

	/**
	 * Returns the pattern used to match the data store file names for a specific catalog.
	 *
	 * @param catalogName the name of the catalog to get the file name pattern for
	 * @return the pattern used to match the data store file names
	 */
	@Nonnull
	static Pattern getCatalogDataStoreFileNamePattern(@Nonnull String catalogName) {
		return Pattern.compile(catalogName + "_(\\d+)" + CATALOG_FILE_SUFFIX);
	}

	/**
	 * Returns the index extracted from the given catalog data store file name.
	 * This method uses character-by-character parsing similar to the WAL file name index extraction.
	 *
	 * @param fileName the name of the catalog data store file
	 * @return the index extracted from the catalog data store file name
	 */
	static int getIndexFromCatalogFileName(@Nonnull String fileName) {
		int endIndex = fileName.length() - CATALOG_FILE_SUFFIX.length();
		int startIndex = endIndex;
		while (startIndex > 0 && Character.isDigit(fileName.charAt(startIndex - 1))) {
			startIndex--;
		}

		if (startIndex >= endIndex) {
			throw new GenericEvitaInternalError(
				"Invalid catalog file name `" + fileName + "`! Cannot extract index from it!"
			);
		}

		return Integer.parseInt(fileName, startIndex, endIndex, 10);
	}

	/**
	 * Returns name of the entity collection data file that contains entity schema, entity indexes and entity bodies.
	 */
	@Nonnull
	static String getEntityCollectionDataStoreFileName(@Nonnull String entityType, int entityTypePrimaryKey, int fileIndex) {
		return StringUtils.toCamelCase(entityType) + '-' + entityTypePrimaryKey + '_' + fileIndex + ENTITY_COLLECTION_FILE_SUFFIX;
	}

	/**
	 * Returns the file name pattern for the entity collection data store file matching the given entity type.
	 *
	 * @param entityType the type of the entity
	 * @return the compiled pattern for the file name matching the entity collection data store file
	 */
	@Nonnull
	static Pattern getEntityCollectionDataStoreFileNamePattern(@Nonnull String entityType, int entityTypePrimaryKey) {
		return Pattern.compile(StringUtils.toCamelCase(entityType) + "-" + entityTypePrimaryKey + "_(\\d+)" + ENTITY_COLLECTION_FILE_SUFFIX);
	}

	/**
	 * Returns the index and entity type primary key extracted from the given entity collection data store file name.
	 *
	 * @param fileName the name of the entity collection data store file
	 * @return the index and entity type primary key extracted from the entity collection data store file name
	 */
	@Nonnull
	static EntityTypePrimaryKeyAndFileIndex getEntityPrimaryKeyAndIndexFromEntityCollectionFileName(@Nonnull String fileName) {
		final Matcher matcher = GENERIC_ENTITY_COLLECTION_PATTERN.matcher(fileName);
		if (matcher.matches()) {
			return new EntityTypePrimaryKeyAndFileIndex(
				Integer.parseInt(matcher.group(1)),
				Integer.parseInt(matcher.group(2))
			);
		} else {
			throw new GenericEvitaInternalError(
				"Entity collection file name does not match the expected pattern.",
				"Entity collection file name does not match the expected pattern: " + fileName
			);
		}
	}

	/**
	 * Returns name of the Write-Ahead-Log file that contains all mutations that were not yet propagated to the catalog
	 * data file.
	 *
	 * @param catalogName name of the catalog
	 * @param fileIndex   index of the WAL file
	 * @return name of the WAL file
	 */
	@Nonnull
	static String getWalFileName(@Nonnull String catalogName, int fileIndex) {
		return catalogName + '_' + fileIndex + WAL_FILE_SUFFIX;
	}

	/**
	 * Method for internal use - allows emitting start events when observability facilities are already initialized.
	 * If we didn't postpone this initialization, events would become lost.
	 */
	void emitObservabilityEvents();

	/**
	 * Retrieves the {@link CatalogStoragePartPersistenceService} associated with this {@link CatalogPersistenceService}.
	 *
	 * @param catalogVersion the version of the catalog
	 * @return the {@link CatalogStoragePartPersistenceService} associated with this {@link CatalogPersistenceService}
	 */
	@Nonnull
	CatalogStoragePartPersistenceService getStoragePartPersistenceService(long catalogVersion);

	/**
	 * Returns the last catalog version that was written to the persistent storage.
	 *
	 * @return the last catalog version that was written to the persistent storage
	 */
	long getLastCatalogVersion();

	/**
	 * Returns {@link CatalogHeader} that is used for this service. The header is initialized in the instance constructor
	 * and (because it's immutable) is exchanged with each {@link #storeHeader(UUID, CatalogState, long, int, TransactionMutation, List, DataStoreMemoryBuffer)}   method call.
	 *
	 * @param catalogVersion the version of the catalog
	 * @return the header of the catalog
	 */
	@Nonnull
	CatalogHeader getCatalogHeader(long catalogVersion);

	/**
	 * Verifies if passed entity type name is valid and unique among other entity types after file name normalization.
	 *
	 * @throws EntityTypeAlreadyPresentInCatalogSchemaException when multiple entity types translate to same file name
	 * @throws InvalidClassifierFormatException                 when entity type contains invalid characters or reserved keywords
	 */
	void verifyEntityType(@Nonnull Collection<EntityCollection> existingEntityCollections, @Nonnull String entityType)
		throws EntityTypeAlreadyPresentInCatalogSchemaException, InvalidClassifierFormatException;

	/**
	 * Method reconstructs catalog index from underlying containers.
	 */
	@Nonnull
	Optional<CatalogIndex> readCatalogIndex(
		@Nonnull Catalog catalog,
		@Nonnull Scope scope
	);

	/**
	 * Serializes all {@link EntityCollection} of the catalog to the persistent storage.
	 *
	 * @param catalogId                      the id of the catalog which doesn't change with catalog rename
	 * @param catalogState                   the state of the catalog
	 * @param catalogVersion                 the version of the catalog
	 * @param lastEntityCollectionPrimaryKey the last primary key of the entity collection
	 * @param lastProcessedTransaction       the last processed transaction
	 * @param entityHeaders                  the list of entity collection headers
	 * @throws InvalidStoragePathException when path is incorrect (cannot be created)
	 * @throws DirectoryNotEmptyException  when directory already contains some data
	 * @throws UnexpectedIOException       in case of any unknown IOException
	 */
	void storeHeader(
		@Nonnull UUID catalogId,
		@Nonnull CatalogState catalogState,
		long catalogVersion,
		int lastEntityCollectionPrimaryKey,
		@Nullable TransactionMutation lastProcessedTransaction,
		@Nonnull List<EntityCollectionHeader> entityHeaders,
		@Nonnull DataStoreMemoryBuffer dataStoreBuffer
	) throws InvalidStoragePathException, DirectoryNotEmptyException, UnexpectedIOException;

	/**
	 * Method creates the service allowing access to the persisted contents of particular {@link EntityCollection}.
	 *
	 * @param catalogVersion       the version of the catalog
	 * @param entityType           the type of the entity
	 * @param entityTypePrimaryKey the primary key of the entity type
	 */
	@Nonnull
	EntityCollectionPersistenceService getOrCreateEntityCollectionPersistenceService(
		long catalogVersion,
		@Nonnull String entityType,
		int entityTypePrimaryKey
	);

	/**
	 * Flushes changes in transactional memory to the persistent storage including the transactional id key.
	 *
	 * @param catalogVersion         new catalog version
	 * @param headerInfoSupplier     provides wrapping entity collection information for the header
	 * @param entityCollectionHeader the header of the entity collection
	 */
	@Nonnull
	Optional<EntityCollectionPersistenceService> flush(
		long catalogVersion,
		@Nonnull HeaderInfoSupplier headerInfoSupplier,
		@Nonnull EntityCollectionHeader entityCollectionHeader,
		@Nonnull DataStoreMemoryBuffer dataStoreBuffer
	);

	/**
	 * Method reads the header of the {@link EntityCollection} from the persistent storage.
	 *
	 * @param catalogVersion       version of the catalog
	 * @param entityTypePrimaryKey primary key of the entity type
	 * @return header of the entity collection
	 */
	@Nonnull
	EntityCollectionHeader getEntityCollectionHeader(
		long catalogVersion,
		int entityTypePrimaryKey
	);

	/**
	 * Transitions the catalog to LIVE state.
	 *
	 * @param catalogVersion the version of the catalog to transition to LIVE state
	 */
	void goLive(long catalogVersion);

	/**
	 * Method updates {@link CatalogHeader} with the given entity collection headers. All other information in the catalog
	 * header remains unchanged.
	 *
	 * @param catalogVersion       version of the catalog
	 * @param entityCollectionHeaders the array of entity collection headers to update
	 */
	void updateEntityCollectionHeaders(
		long catalogVersion,
		@Nonnull EntityCollectionHeader[] entityCollectionHeaders
	);

	/**
	 * Method creates the service allowing to store and read Write-Ahead-Log entries.
	 */
	@Nonnull
	IsolatedWalPersistenceService createIsolatedWalPersistenceService(@Nonnull UUID transactionId);

	/**
	 * Method deletes entire catalog persistent storage and closes the persistence factory.
	 */
	void closeAndDelete();

	/**
	 * Appends the given transaction mutation to the write-ahead log (WAL) and appends its mutation chain taken from
	 * offHeapWithFileBackupReference. After that it discards the specified off-heap data with file backup reference.
	 *
	 * @param catalogVersion      current version of the catalog
	 * @param transactionMutation The transaction mutation to append to the WAL.
	 * @param walReference        The off-heap data with file backup reference to discard.
	 * @return the number of Bytes written
	 */
	long appendWalAndDiscard(
		long catalogVersion,
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull OffHeapWithFileBackupReference walReference
	);

	/**
	 * Retrieves the first non-processed transaction in the WAL.
	 *
	 * @param catalogVersion version of the catalog
	 * @return the first non-processed transaction in the WAL
	 */
	@Nonnull
	Optional<TransactionMutation> getFirstNonProcessedTransactionInWal(
		long catalogVersion
	);

	/**
	 * Replaces folder of the `catalogNameToBeReplaced` with contents of this catalog.
	 *
	 * @param catalogVersion                    version of the catalog
	 * @param catalogNameToBeReplaced           name of the catalog to be replaced by this catalog
	 * @param catalogNameVariationsToBeReplaced variations of the catalog name to be replaced by this catalog
	 * @param catalogSchema                     the schema of the catalog
	 * @param progressObserver                  observer function accepting two integers - steps done and total steps
	 */
	@Nonnull
	CatalogPersistenceService replaceWith(
		long catalogVersion,
		@Nonnull String catalogNameToBeReplaced,
		@Nonnull Map<NamingConvention, String> catalogNameVariationsToBeReplaced,
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull DataStoreMemoryBuffer dataStoreMemoryBuffer,
		@Nonnull BiIntConsumer progressObserver
	);

	/**
	 * Replaces file of the `entityType` with contents of `newEntityType`
	 * collection file.
	 */
	@Nonnull
	EntityCollectionPersistenceService replaceCollectionWith(
		long catalogVersion,
		@Nonnull String entityType,
		int entityTypePrimaryKey,
		@Nonnull String newEntityType
	);

	/**
	 * Method deletes entity collection persistent storage and removes collection from the schema.
	 *
	 * @param catalogVersion         version of the catalog in which the collection should be considered removed
	 * @param entityCollectionHeader entity collection header
	 */
	void deleteEntityCollection(
		long catalogVersion,
		@Nonnull EntityCollectionHeader entityCollectionHeader
	);

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the catalog to the given version. The stream goes through all the mutations in this transaction and continues
	 * forward with next transaction after that until the end of the WAL.
	 *
	 * DO NOT USE THIS METHOD if the WAL is being actively written to. Use {@link #getCommittedLiveMutationStream(long, long)}
	 *
	 * @param catalogVersion version of the catalog to start the stream with
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	Stream<CatalogBoundMutation> getCommittedMutationStream(long catalogVersion);

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the catalog to the given version. The stream goes through all the mutations in this transaction from last to
	 * first one and continues backward with previous transaction after that until the beginning of the WAL.
	 *
	 * @param catalogVersion version of the catalog to start the stream with, if null is provided then the stream will
	 *                       start with the last transaction in the WAL
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	Stream<CatalogBoundMutation> getReversedCommittedMutationStream(@Nullable Long catalogVersion);

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the catalog to the given version. This method differs from {@link #getCommittedMutationStream(long)} in that
	 * it expects the WAL is being actively written to and the returned stream may be potentially infinite.
	 *
	 * @param startCatalogVersion     the catalog version to start reading from
	 * @param requestedCatalogVersion the minimal catalog version to finish reading
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	Stream<CatalogBoundMutation> getCommittedLiveMutationStream(long startCatalogVersion, long requestedCatalogVersion);

	/**
	 * Retrieves the last catalog version written in the WAL stream.
	 *
	 * @return the last catalog version written in the WAL stream
	 */
	long getLastCatalogVersionInMutationStream();

	/**
	 * We need to forget all volatile data when the data written to catalog aren't going to be committed (incorporated
	 * in the final state). Usually the data written by {@link #getStoragePartPersistenceService(long)}  are immediately
	 * written to the disk and are volatile until {@link #storeHeader(UUID, CatalogState, long, int, TransactionMutation, List, DataStoreMemoryBuffer)}
	 * is called. But those data can be read within particular transaction from the volatile storage and we need to
	 * forget them when the transaction is rolled back.
	 */
	void forgetVolatileData();

	/**
	 * Returns a paginated list of catalog versions based on the provided time flow, page number, and page size.
	 * It returns only versions that are known in history - there may be a lot of other versions for which we don't have
	 * information anymore, because the data were purged to save space.
	 *
	 * @param timeFlow the time flow used to filter the catalog versions
	 * @param page     the page number of the paginated list
	 * @param pageSize the number of versions per page
	 * @return a paginated list of {@link StoredVersion} instances
	 */
	@Nonnull
	PaginatedList<StoredVersion> getCatalogVersions(@Nonnull TimeFlow timeFlow, int page, int pageSize);

	/**
	 * Returns information about the version that was valid at the specified moment in time. If the moment is not
	 * specified method returns first version known to the catalog mutation history.
	 *
	 * @param moment the moment in time for which the catalog version should be returned
	 * @return catalog version that was valid at the specified moment in time, or first version known to the catalog
	 * mutation history if no moment was specified
	 * @throws TemporalDataNotAvailableException when data for particular moment is not available anymore
	 */
	@Nonnull
	StoredVersion getCatalogVersionAt(@Nullable OffsetDateTime moment) throws TemporalDataNotAvailableException;

	/**
	 * Returns a stream of {@link WriteAheadLogVersionDescriptor} instances for the given catalog versions. Descriptors will
	 * be ordered the same way as the input catalog versions, but may be missing some versions if they are not known in
	 * history. Creating a descriptor could be an expensive operation, so it's recommended to stream changes to clients
	 * gradually as the stream provides the data.
	 *
	 * @param catalogVersion the catalog versions to get descriptors for
	 * @return a stream of {@link WriteAheadLogVersionDescriptor} instances
	 */
	@Nonnull
	Stream<WriteAheadLogVersionDescriptor> getCatalogVersionDescriptors(long... catalogVersion);

	/**
	 * Method deletes all files in catalog folder which are not mentioned in the catalog header of currently used
	 * bootstrap record.
	 */
	void purgeAllObsoleteFiles();

	/**
	 * Creates a backup of the specified catalog and returns an InputStream to read the binary data of the zip file.
	 *
	 * @param pastMoment     leave null for creating backup for actual dataset, or specify past moment to create backup for
	 *                       the dataset as it was at that moment
	 * @param catalogVersion precise catalog version to create backup for, or null to create backup for the latest version,
	 *                       when set not null, the pastMoment parameter is ignored
	 * @param includingWAL   if true, the backup will include the Write-Ahead Log (WAL) file and when the catalog is
	 *                       restored, it'll replay the WAL contents locally to bring the catalog to the current state
	 * @param onStart        callback that is called before the backup starts
	 * @param onComplete     callback that is called when the backup is finished (either successfully or with an error)
	 * @return path to the file where the backup was created
	 * @throws TemporalDataNotAvailableException when the past data is not available
	 */
	@Nonnull
	ServerTask<?, FileForFetch> createBackupTask(
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL,
		@Nullable LongConsumer onStart,
		@Nullable LongConsumer onComplete
	) throws TemporalDataNotAvailableException;

	/**
	 * Creates a full backup of the specified catalog and returns an InputStream to read the binary data of the zip file.
	 *
	 * @param onStart        callback that is called before the backup starts
	 * @param onComplete     callback that is called when the backup is finished (either successfully or with an error)
	 * @return path to the file where the backup was created
	 */
	@Nonnull
	ServerTask<?, FileForFetch> createFullBackupTask(
		@Nullable LongConsumer onStart,
		@Nullable LongConsumer onComplete
	);

	/**
	 * Duplicates an existing catalog to create a new catalog with a different name.
	 *
	 * @param targetCatalogName name of the target catalog to be created
	 * @param storageOptions storage configuration options
	 * @return progressing future that tracks the duplication process
	 *
	 * @throws DirectoryNotEmptyException if the target directory is not empty
	 * @throws InvalidStoragePathException if the storage path is invalid
	 */
	@Nonnull
	ProgressingFuture<Void> duplicateCatalog(
		@Nonnull String targetCatalogName,
		@Nonnull StorageOptions storageOptions
	) throws DirectoryNotEmptyException, InvalidStoragePathException;

	/**
	 * Verifies the integrity of a system, component, or data structure.
	 * This method performs an internal check to ensure that the state
	 * or configuration adheres to expected standards or rules.
	 *
	 * The specific implementation of the integrity verification depends
	 * on the context in which this method is used. It may include tasks
	 * such as validating data consistency, ensuring correct initialization,
	 * or detecting anomalies that could indicate corruption or unexpected
	 * modifications.
	 *
	 * No inputs or return values are required. The method performs its
	 * operations as a void execution.
	 *
	 * Potential exceptions may be thrown if integrity violations are detected.
	 */
	void verifyIntegrity();

	/**
	 * Returns size taken by all catalog data structures in bytes.
	 *
	 * @return size taken by all catalog data structures in bytes
	 */
	long getSizeOnDiskInBytes();

	/**
	 * Method closes this persistence service and also all {@link EntityCollectionPersistenceService} that were created
	 * via. {@link #getOrCreateEntityCollectionPersistenceService(long, String, int)}.
	 *
	 * You need to call {@link #storeHeader(UUID, CatalogState, long, int, TransactionMutation, List, DataStoreMemoryBuffer)}
	 * or {@link #flushTrappedUpdates(long, TrappedChanges, IntConsumer)}  before this method is called, or you will lose your
	 * data in memory buffers.
	 */
	@Override
	void close();

	/**
	 * Tuple for returning multiple values from {@link #getEntityPrimaryKeyAndIndexFromEntityCollectionFileName} method.
	 *
	 * @param entityTypePrimaryKey the primary key of the entity type
	 * @param fileIndex            the index of the file
	 */
	record EntityTypePrimaryKeyAndFileIndex(
		int entityTypePrimaryKey,
		int fileIndex
	) {
	}

}
