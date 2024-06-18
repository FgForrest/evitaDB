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

package io.evitadb.api;

import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.system.CatalogVersion;
import io.evitadb.api.requestResponse.system.CatalogVersionDescriptor;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.UnexpectedIOException;

import javax.annotation.Nonnull;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Catalog is a fragment of evitaDB database that can be compared to a schema of relational database. Catalog allows
 * handling multiple isolated data collections inside single evitaDB instance. Catalogs in evitaDB are isolated one from
 * another and share no single thing. They have separate {@link CatalogSchemaContract}, separate data and cannot share
 * {@link EvitaSessionContract}. It means that EvitaSession is bound to its catalog since creation and cannot query
 * different catalog than this one.
 *
 * Catalog is an abstraction for "database" in the sense of relational databases. Catalog contains all entities and data
 * connected with single client. In the e-commerce world catalog means "single e-shop" although it may not be the truth
 * in every case. Catalog manages set of {@link EntityCollectionContract} uniquely identified by their
 * {@link EntityCollectionContract#getEntityType()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface CatalogContract {

	/**
	 * Returns unique catalog id that doesn't change with catalog schema changes - such as renaming.
	 * The id is assigned to the catalog when it is created and never changes.
	 *
	 * @return unique catalog id
	 */
	@Nonnull
	UUID getCatalogId();

	/**
	 * Returns read-only catalog configuration.
	 */
	@Nonnull
	SealedCatalogSchema getSchema();

	/**
	 * Alters existing schema applying passed schema mutation.
	 */
	@Nonnull
	CatalogSchemaContract updateSchema(@Nonnull LocalCatalogSchemaMutation... schemaMutation) throws SchemaAlteringException;

	/**
	 * Returns state of this catalog instance.
	 */
	@Nonnull
	CatalogState getCatalogState();

	/**
	 * Returns name of the Catalog instance. Name must be unique across all catalogs inside same {@link EvitaContract}
	 * instance. Name of the catalog must be equal to {@link CatalogSchemaContract#getName()}.
	 */
	@Nonnull
	String getName();

	/**
	 * Returns catalog header version that is incremented with each update. Version is not stored on the disk,
	 * it serves only to distinguish whether there is any change made in the header and whether it needs to be persisted
	 * on disk.
	 */
	long getVersion();

	/**
	 * Returns true if catalog supports transaction.
	 */
	boolean supportsTransaction();

	/**
	 * Returns set of all maintained {@link EntityCollectionContract} - i.e. entity types.
	 */
	@Nonnull
	Set<String> getEntityTypes();

	/**
	 * Method returns a response containing entities that match passed `evitaRequest`. This is universal method for
	 * accessing multiple entities from the collection in a paginated fashion in requested form of completeness.
	 *
	 * The method is used to locate entities of up-front unknown type by their globally unique attributes.
	 */
	@Nonnull
	<S extends Serializable, T extends EvitaResponse<S>> T getEntities(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session);

	/**
	 * Applies mutation to the catalog. This is a generic method that accepts any mutation and tries to apply it to
	 * the catalog. If the mutation is not applicable to the catalog, exception is thrown.
	 */
	void applyMutation(@Nonnull Mutation mutation) throws InvalidMutationException;

	/**
	 * Creates and returns collection maintaining all entities of same type. If collection for the entity type exists
	 * existing collection is returned.
	 *
	 * @param entityType type (name) of the entity
	 */
	@Nonnull
	EntityCollectionContract createCollectionForEntity(@Nonnull String entityType, @Nonnull EvitaSessionContract session);

	/**
	 * Returns collection maintaining all entities of same type.
	 *
	 * @param entityType type (name) of the entity
	 */
	@Nonnull
	Optional<EntityCollectionContract> getCollectionForEntity(@Nonnull String entityType);

	/**
	 * Returns collection maintaining all entities of same type or throws standardized exception.
	 *
	 * @param entityType type (name) of the entity
	 */
	@Nonnull
	EntityCollectionContract getCollectionForEntityOrThrowException(@Nonnull String entityType) throws CollectionNotFoundException;

	/**
	 * Returns collection maintaining all entities of same type or throws standardized exception.
	 *
	 * @param entityTypePrimaryKey primary key of the entity collection - see {@link EntityCollectionContract#getEntityTypePrimaryKey()}
	 */
	@Nonnull
	EntityCollectionContract getCollectionForEntityPrimaryKeyOrThrowException(int entityTypePrimaryKey) throws CollectionNotFoundException;

	/**
	 * Returns collection maintaining all entities of same type. If no such collection exists new one is created.
	 *
	 * @param entityType type (name) of the entity
	 * @throws SchemaAlteringException when collection doesn't exist and {@link CatalogSchemaContract#getCatalogEvolutionMode()}
	 *                                 doesn't allow {@link CatalogEvolutionMode#ADDING_ENTITY_TYPES}
	 */
	@Nonnull
	EntityCollectionContract getOrCreateCollectionForEntity(@Nonnull String entityType, @Nonnull EvitaSessionContract session)
		throws SchemaAlteringException;

	/**
	 * Deletes entire collection of entities along with its schema. After this operation there will be nothing left
	 * of the data that belong to the specified entity type.
	 *
	 * @param entityType type of the entity which collection should be deleted
	 * @return TRUE if collection was successfully deleted
	 */
	boolean deleteCollectionOfEntity(@Nonnull String entityType, @Nonnull EvitaSessionContract session);

	/**
	 * Renames entire collection of entities along with its schema. After this operation there will be nothing left
	 * of the data that belong to the specified entity type, and entity collection under the new name becomes available.
	 * If you need to rename entity collection to a name of existing collection use
	 * the {@link #replaceCollectionOfEntity(String, String, EvitaSessionContract)} method instead.
	 *
	 * In case exception occurs the original collection (`entityType`) is guaranteed to be untouched,
	 * and the `newName` will not be present.
	 *
	 * @param entityType current name of the entity collection
	 * @param newName    new name of the entity collection
	 * @return TRUE if collection was successfully renamed
	 * @throws EntityTypeAlreadyPresentInCatalogSchemaException when there is already entity collection with `newName`
	 *                                                          present
	 */
	boolean renameCollectionOfEntity(@Nonnull String entityType, @Nonnull String newName, @Nonnull EvitaSessionContract session)
		throws EntityTypeAlreadyPresentInCatalogSchemaException;

	/**
	 * Replaces existing entity collection of particular with the contents of the another collection. When this method
	 * is successfully finished, the entity collection `entityTypeToBeReplaced` will be known under the name of the
	 * `entityTypeToBeReplacedWith` and the original contents of the `entityTypeToBeReplaced` will be purged entirely.
	 *
	 * In case exception occurs, both the original collection (`entityTypeToBeReplaced`) and replaced collection
	 * (`entityTypeToBeReplacedWith`) are guaranteed to be untouched.
	 *
	 * @param entityTypeToBeReplaced     name of the collection that will be replaced and dropped
	 * @param entityTypeToBeReplacedWith name of the collection that will become the successor of the original catalog
	 * @return TRUE if collection was successfully replaced
	 */
	boolean replaceCollectionOfEntity(@Nonnull String entityTypeToBeReplaced, @Nonnull String entityTypeToBeReplacedWith, @Nonnull EvitaSessionContract session);

	/**
	 * Removes entire catalog storage from persistent storage.
	 */
	void delete();

	/**
	 * Replaces folder of the `catalogToBeReplaced` with contents of this catalog.
	 */
	@Nonnull
	CatalogContract replace(@Nonnull CatalogSchemaContract updatedSchema, @Nonnull CatalogContract catalogToBeReplaced);

	/**
	 * Returns map with current {@link EntitySchemaContract entity schema} instances indexed by their
	 * {@link EntitySchemaContract#getName() name}.
	 *
	 * @return map with current {@link EntitySchemaContract entity schema} instances indexed by their name
	 */
	@Nonnull
	Map<String, EntitySchemaContract> getEntitySchemaIndex();

	/**
	 * Returns {@link EntitySchemaContract} for passed `entityType` or throws {@link IllegalArgumentException} if schema for
	 * this type is not yet known.
	 */
	@Nonnull
	Optional<SealedEntitySchema> getEntitySchema(@Nonnull String entityType);

	/**
	 * Changes state of the catalog from {@link CatalogState#WARMING_UP} to {@link CatalogState#ALIVE}.
	 *
	 * @see CatalogState
	 */
	boolean goLive();

	/**
	 * Method checks whether there are new records in the WAL that haven't been incorporated into the catalog yet and
	 * processes them. The method returns completable future that is completed when all records are processed.
	 */
	void processWriteAheadLog(@Nonnull Consumer<CatalogContract> updatedCatalog);

	/**
	 * Returns a paginated list of catalog versions based on the provided time flow, page number, and page size.
	 * It returns only versions that are known in history - there may be a lot of other versions for which we don't have
	 * information anymore, because the data were purged to save space.
	 *
	 * @param timeFlow   the time flow used to filter the catalog versions
	 * @param page       the page number of the paginated list
	 * @param pageSize   the number of versions per page
	 * @return a paginated list of {@link CatalogVersion} instances
	 */
	@Nonnull
	PaginatedList<CatalogVersion> getCatalogVersions(@Nonnull TimeFlow timeFlow, int page, int pageSize);

	/**
	 * Returns a stream of {@link CatalogVersionDescriptor} instances for the given catalog versions. Descriptors will
	 * be ordered the same way as the input catalog versions, but may be missing some versions if they are not known in
	 * history. Creating a descriptor could be an expensive operation, so it's recommended to stream changes to clients
	 * gradually as the stream provides the data.
	 *
	 * @param catalogVersion the catalog versions to get descriptors for
	 * @return a stream of {@link CatalogVersionDescriptor} instances
	 */
	@Nonnull
	Stream<CatalogVersionDescriptor> getCatalogVersionDescriptors(long... catalogVersion);

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the catalog to the given version. The stream goes through all the mutations in this transaction and continues
	 * forward with next transaction after that until the end of the WAL.
	 *
	 * BEWARE! Stream implements {@link java.io.Closeable} and needs to be closed to release resources.
	 *
	 * @param catalogVersion version of the catalog to start the stream with
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	Stream<Mutation> getCommittedMutationStream(long catalogVersion);

	/**
	 * Retrieves a stream of committed mutations starting with a {@link TransactionMutation} that will transition
	 * the catalog to the given version. The stream goes through all the mutations in this transaction from last to
	 * first one and continues backward with previous transaction after that until the beginning of the WAL.
	 *
	 * BEWARE! Stream implements {@link java.io.Closeable} and needs to be closed to release resources.
	 *
	 * @param catalogVersion version of the catalog to start the stream with
	 * @return a stream containing committed mutations
	 */
	@Nonnull
	Stream<Mutation> getReversedCommittedMutationStream(long catalogVersion);

	/**
	 * Creates a backup of the specified catalog and returns an InputStream to read the binary data of the zip file.
	 *
	 * @param outputStream an OutputStream to write the binary data of the zip file
	 * @throws UnexpectedIOException if an I/O error occurs during reading the catalog contents
	 */
	void backup(OutputStream outputStream) throws UnexpectedIOException;

	/**
	 * Terminates catalog instance and frees all claimed resources. Prepares catalog instance to be garbage collected.
	 *
	 * This method is idempotent and may be called multiple times. Only first call is really processed and others are
	 * ignored.
	 */
	void terminate();

}
