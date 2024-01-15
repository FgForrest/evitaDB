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
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
	 * TODO JNO - DOCUMENT ME
	 * @return
	 */
	@Override
	@Nonnull
	CatalogStoragePartPersistenceService getStoragePartPersistenceService();

	/**
	 * Returns {@link CatalogHeader} that is used for this service. The header is initialized in the instance constructor
	 * and (because it's immutable) is exchanged with each {@link #storeHeader(CatalogState, long, int, List)} method call.
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
	 * @throws InvalidStoragePathException when path is incorrect (cannot be created)
	 * @throws DirectoryNotEmptyException  when directory already contains some data
	 * @throws UnexpectedIOException       in case of any unknown IOException
	 */
	void storeHeader(
		@Nonnull CatalogState catalogState,
		long catalogVersion,
		int lastEntityCollectionPrimaryKey,
		@Nonnull List<EntityCollectionHeader> entityHeaders
	)
		throws InvalidStoragePathException, DirectoryNotEmptyException, UnexpectedIOException;

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
	 * TODO JNO - document me
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
	void replaceCollectionWith(
		@Nonnull String entityType,
		int entityTypePrimaryKey,
		@Nonnull String newEntityType
	);

	/**
	 * Method deletes entity collection persistent storage and removes collection from the schema.
	 */
	void deleteEntityCollection(@Nonnull String entityType);

	/**
	 * TODO JNO - document me
	 */
	@Nonnull
	Iterator<Mutation> getCommittedMutationIterator(long catalogVersion);

	/**
	 * Method closes this persistence service and also all {@link EntityCollectionPersistenceService} that were created
	 * via. {@link #createEntityCollectionPersistenceService(String, int)}.
	 *
	 * You need to call {@link #storeHeader(CatalogState, long, int, List)} or {@link #flushTrappedUpdates(DataStoreIndexChanges)}
	 * before this method is called, or you will lose your data in memory buffers.
	 */
	@Override
	void close();
}
