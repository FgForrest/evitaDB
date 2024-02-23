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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.buffer.DataStoreIndexChanges;
import io.evitadb.exception.InvalidClassifierFormatException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.store.exception.InvalidStoragePathException;
import io.evitadb.store.spi.exception.DirectoryNotEmptyException;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * This interface represents a link between {@link CatalogContract} and its persistent storage.
 * The interface contains all methods necessary for fetching or persisting catalog header to/from durable
 * storage and access to storages of catalog {@link EntityCollectionContract entity collections}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public non-sealed interface CatalogPersistenceService extends PersistenceService<CatalogIndexKey, CatalogIndex> {
	/**
	 * This constant represents the current version of the storage protocol. The version is changed everytime
	 * the storage protocol on disk changes and the data with the old protocol version cannot be read by the new
	 * protocol version.
	 *
	 * This means that the data needs to be converted from old to new protocol version first.
	 */
	int STORAGE_PROTOCOL_VERSION = 1;
	String BOOT_FILE_SUFFIX = ".boot";
	String CATALOG_FILE_SUFFIX = ".catalog";
	String ENTITY_COLLECTION_FILE_SUFFIX = ".collection";
	String WAL_FILE_SUFFIX = ".wal";

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
	 * Returns name of the entity collection data file that contains entity schema, entity indexes and entity bodies.
	 */
	@Nonnull
	static String getEntityCollectionDataStoreFileName(@Nonnull String entityType, int fileIndex) {
		return entityType + '_' + fileIndex + ENTITY_COLLECTION_FILE_SUFFIX;
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
	static Path getWalFileName(@Nonnull String catalogName, int fileIndex) {
		return Path.of(catalogName + '_' + fileIndex + WAL_FILE_SUFFIX);
	}

	/**
	 * Returns the index extracted from the given Write-Ahead-Log file name.
	 *
	 * @param catalogName  the name of the catalog
	 * @param walFileName  the name of the WAL file
	 * @return the index extracted from the WAL file name
	 */
	static int getIndexFromWalFileName(@Nonnull String catalogName, @Nonnull String walFileName) {
		return Integer.parseInt(
			walFileName.substring(catalogName.length() + 1, walFileName.length() - WAL_FILE_SUFFIX.length())
		);
	}

	/**
	 * Retrieves the {@link CatalogStoragePartPersistenceService} associated with this {@link CatalogPersistenceService}.
	 *
	 * @return the {@link CatalogStoragePartPersistenceService} associated with this {@link CatalogPersistenceService}
	 */
	@Override
	@Nonnull
	CatalogStoragePartPersistenceService getStoragePartPersistenceService();

	/**
	 * Returns {@link CatalogHeader} that is used for this service. The header is initialized in the instance constructor
	 * and (because it's immutable) is exchanged with each {@link #storeHeader(CatalogState, long, int, TransactionMutation, List)}  method call.
	 */
	@Nonnull
	CatalogHeader getCatalogHeader();

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
	CatalogIndex readCatalogIndex(@Nonnull Catalog catalog);

	/**
	 * Serializes all {@link EntityCollection} of the catalog to the persistent storage.
	 *
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
		@Nonnull CatalogState catalogState,
		long catalogVersion,
		int lastEntityCollectionPrimaryKey,
		@Nullable TransactionMutation lastProcessedTransaction,
		@Nonnull List<EntityCollectionHeader> entityHeaders
	) throws InvalidStoragePathException, DirectoryNotEmptyException, UnexpectedIOException;

	/**
	 * Method creates the service allowing access to the persisted contents of particular {@link EntityCollection}.
	 */
	@Nonnull
	EntityCollectionPersistenceService createEntityCollectionPersistenceService(
		@Nonnull String entityType,
		int entityTypePrimaryKey
	);

	/**
	 * Method reads the header of the {@link EntityCollection} from the persistent storage.
	 *
	 * @param collectionFileReference reference to the collection type
	 * @return header of the entity collection
	 */
	@Nonnull
	EntityCollectionHeader getEntityCollectionHeader(@Nonnull CollectionFileReference collectionFileReference);

	/**
	 * Method creates the service allowing to store and read Write-Ahead-Log entries.
	 */
	@Nonnull
	IsolatedWalPersistenceService createIsolatedWalPersistenceService(@Nonnull UUID transactionId);

	/**
	 * Method deletes entire catalog persistent storage.
	 */
	void delete();

	/**
	 * Appends the given transaction mutation to the write-ahead log (WAL) and appends its mutation chain taken from
	 * offHeapWithFileBackupReference. After that it discards the specified off-heap data with file backup reference.
	 *
	 * @param transactionMutation The transaction mutation to append to the WAL.
	 * @param walReference        The off-heap data with file backup reference to discard.
	 */
	void appendWalAndDiscard(
		@Nonnull TransactionMutation transactionMutation,
		@Nonnull OffHeapWithFileBackupReference walReference
	);

	/**
	 * Replaces folder of the `catalogNameToBeReplaced` with contents of this catalog.
	 */
	@Nonnull
	CatalogPersistenceService replaceWith(
		@Nonnull String catalogNameToBeReplaced,
		@Nonnull Map<NamingConvention, String> catalogNameVariationsToBeReplaced,
		@Nonnull CatalogSchema catalogSchema
	);

	/**
	 * Replaces file of the `entityType` with contents of `newEntityType`
	 * collection file.
	 */
	@Nonnull
	EntityCollectionPersistenceService replaceCollectionWith(
		@Nonnull String entityType,
		int entityTypePrimaryKey,
		@Nonnull String newEntityType,
		long catalogVersion
	);

	/**
	 * Method deletes entity collection persistent storage and removes collection from the schema.
	 *
	 * TODO JNO - this method should be called only when there is no session working with it
	 */
	void deleteEntityCollection(@Nonnull String entityType);

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the catalog to the given version.
	 *
	 * @param catalogVersion version of the catalog to start the stream with
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	Stream<Mutation> getCommittedMutationStream(long catalogVersion);

	/**
	 * Retrieves the last catalog version written in the WAL stream.
	 *
	 * @return the last catalog version written in the WAL stream
	 */
	long getLastCatalogVersionInMutationStream();

	/**
	 * We need to forget all volatile data when the data written to catalog aren't going to be committed (incorporated
	 * in the final state). Usually the data written by {@link #getStoragePartPersistenceService()} are immediately
	 * written to the disk and are volatile until {@link #storeHeader(CatalogState, long, int, TransactionMutation, List)}
	 * is called. But those data can be read within particular transaction from the volatile storage and we need to
	 * forget them when the transaction is rolled back.
	 */
	void forgetVolatileData();

	/**
	 * Method closes this persistence service and also all {@link EntityCollectionPersistenceService} that were created
	 * via. {@link #createEntityCollectionPersistenceService(String, int)}.
	 *
	 * You need to call {@link #storeHeader(CatalogState, long, int, TransactionMutation, List)}  or {@link #flushTrappedUpdates(DataStoreIndexChanges)}
	 * before this method is called, or you will lose your data in memory buffers.
	 */
	@Override
	void close();

}
