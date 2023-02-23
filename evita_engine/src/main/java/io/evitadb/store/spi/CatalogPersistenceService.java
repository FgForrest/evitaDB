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

package io.evitadb.store.spi;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.buffer.BufferedChangeSet;
import io.evitadb.exception.InvalidClassifierFormatException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.store.exception.InvalidStoragePathException;
import io.evitadb.store.spi.exception.DirectoryNotEmptyException;
import io.evitadb.store.spi.model.CatalogBootstrap;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * This interface represents a link between {@link CatalogContract} and its persistent storage.
 * The interface contains all methods necessary for fetching or persisting catalog header to/from durable
 * storage and access to storages of catalog {@link EntityCollectionContract entity collections}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface CatalogPersistenceService extends PersistenceService {
	String HEADER_FILE_SUFFIX = ".header";
	String CATALOG_FILE_SUFFIX = ".catalog";
	String ENTITY_COLLECTION_FILE_SUFFIX = ".collection";

	/**
	 * Returns name of the header file that contains lead information to fetching the rest of catalog contents.
	 */
	@Nonnull
	static String getCatalogHeaderFileName(@Nonnull String catalogName) {
		return catalogName + HEADER_FILE_SUFFIX;
	}

	/**
	 * Returns name of the catalog data file that contains catalog schema and catalog indexes.
	 */
	@Nonnull
	static String getCatalogDataStoreFileName(@Nonnull String catalogName) {
		return catalogName + CATALOG_FILE_SUFFIX;
	}

	/**
	 * Returns name of the entity collection data file that contains entity schema, entity indexes and entity bodies.
	 */
	@Nonnull
	static String getEntityCollectionDataStoreFileName(@Nonnull String entityType) {
		return entityType + ENTITY_COLLECTION_FILE_SUFFIX;
	}

	/**
	 * Returns current instance of {@link CatalogBootstrap}. The header is initialized in the instance constructor
	 * and (because it's immutable) is exchanged with each {@link #storeHeader(CatalogState, long, int, List)} method call.
	 */
	@Nonnull
	CatalogBootstrap getCatalogBootstrap();

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
	void storeHeader(@Nonnull CatalogState catalogState, long transactionId, int lastEntityCollectionPrimaryKey, @Nonnull List<EntityCollectionHeader> entityHeaders)
		throws InvalidStoragePathException, DirectoryNotEmptyException, UnexpectedIOException;

	/**
	 * Method creates the service allowing access to the persisted contents of particular {@link EntityCollection}.
	 */
	@Nonnull
	EntityCollectionPersistenceService createEntityCollectionPersistenceService(
		@Nonnull String entityType, int entityTypePrimaryKey
	);

	/**
	 * Method initializes intermediate memory buffers keeper that are required when contents of the catalog are persisted.
	 * These buffers are not necessary when there are no updates to the catalog / collection, so it's wise to get rid
	 * of them if there is no actual need.
	 *
	 * The need is determined by the number of opened read write {@link EvitaSessionContract} to the catalog.
	 * If there is at least one opened read-write session we need to keep those outputs around. When there are only read
	 * sessions we don't need the outputs.
	 *
	 * The opening logic is responsible for calling {@link #release()} method that drops these buffers to the GC.
	 * TOBEDONE JNO - these methods will be moved to QueueWriter
	 *
	 * @see #release()
	 */
	void prepare();

	/**
	 * Method releases all intermediate (and large) write buffers and let the GC discard them.
	 * TOBEDONE JNO - these methods will be moved to QueueWriter
	 *
	 * @see #prepare()
	 */
	void release();

	/**
	 * Method combines {@link #prepare()} and {@link #release()} in a safe manner.
	 * If the write session is opened the prepare and release is not called.
	 * TOBEDONE JNO - these methods will be moved to QueueWriter
	 */
	<T> T executeWriteSafely(@Nonnull Supplier<T> lambda);

	/**
	 * Method deletes entire catalog persistent storage.
	 */
	void delete();

	/**
	 * Replaces folder of the `catalogNameToBeReplaced` with contents of this catalog.
	 */
	@Nonnull
	CatalogPersistenceService replaceWith(
		@Nonnull String catalogNameToBeReplaced,
		@Nonnull Map<NamingConvention, String> catalogNameVariationsToBeReplaced,
		@Nonnull CatalogSchema catalogSchema,
		long transactionId
	);

	/**
	 * Replaces file of the `entityType` with contents of `newEntityType`
	 * collection file.
	 */
	void replaceCollectionWith(
		@Nonnull String entityType,
		int entityTypePrimaryKey,
		@Nonnull String newEntityType,
		long transactionId
	);

	/**
	 * Method deletes entity collection persistent storage and removes collection from the schema.
	 */
	void deleteEntityCollection(@Nonnull String entityType);

	/**
	 * Flushes all trapped memory data to the persistent storage.
	 * This method doesn't take transactional memory into an account but only flushes changes for trapped updates.
	 */
	void flushTrappedUpdates(@Nonnull BufferedChangeSet<CatalogIndexKey, CatalogIndex> bufferedChangeSet);

	/**
	 * Method closes this persistence service and also all {@link EntityCollectionPersistenceService} that were created
	 * via. {@link #createEntityCollectionPersistenceService(String, int)}.
	 *
	 * You need to call {@link #storeHeader(CatalogState, long, int, List)} or {@link #flushTrappedUpdates(BufferedChangeSet)}
	 * before this method is called, or you will lose your data in memory buffers.
	 */
	@Override
	void close();
}
