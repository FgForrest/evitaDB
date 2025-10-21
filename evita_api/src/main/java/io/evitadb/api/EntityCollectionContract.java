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

package io.evitadb.api;

import io.evitadb.api.CatalogStatistics.EntityCollectionStatistics;
import io.evitadb.api.exception.EntityAlreadyRemovedException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.query.filter.EntityScope;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Optional;
import java.util.UUID;

/**
 * Entity collection maintains all entities of same {@link Entity#getType()}. Entity collection could be imagined
 * as single table in RDBMS environment or document type in case of the Elasticsearch or Mongo DB no sql databases.
 *
 * EntityCollection is set of records of the same type. In the relational world it would represent a table (or a single
 * main table with several other tables containing records referring to that main table). Entity collection maintains
 * all entities of the same type (i.e. same {@link EntitySchemaContract}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface EntityCollectionContract {

	/**
	 * Returns a unique identifier of the entity type that is assigned on entity collection creation and never changes.
	 * The primary key can be used interchangeably to {@link EntitySchemaContract#getName() String entity type}.
	 */
	int getEntityTypePrimaryKey();

	/**
	 * Method returns entity by its type and primary key in requested form of completeness. This method allows quick
	 * access to the entity contents when primary key is known.
	 */
	@Nonnull
	Optional<SealedEntity> getEntity(int primaryKey, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session);

	/**
	 * Method returns a response containing entities that match passed `evitaRequest`. This is universal method for
	 * accessing multiple entities from the collection in a paginated fashion in requested form of completeness.
	 */
	@Nonnull
	<S extends Serializable, T extends EvitaResponse<S>> T getEntities(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session);

	/**
	 * Method returns entity with additionally loaded data specified by requirements in second argument. This method
	 * is particularly useful for implementation of lazy loading when application loads only parts of the entity it
	 * expects to be required for handling common client request and then load additional data if processing requires
	 * more in-depth view of the entity.
	 *
	 * @param evitaRequest - request has no filter / order - only envelopes additional requirements for the loaded entity,
	 *                     so that utility methods in request can be reused
	 * @param session      that connect this request with an opened session
	 * @throws EntityAlreadyRemovedException when the entity has been already removed
	 */
	@Nonnull
	SealedEntity enrichEntity(@Nonnull EntityContract entity, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session)
		throws EntityAlreadyRemovedException;

	/**
	 * Method returns entity with limited scope of data visibility that matches the passed `evitaRequest`. This method
	 * is particularly useful for implementation of cache when the cache might contain fully loaded entities while
	 * the client requests (and expects) only small part of it. This method allows to "hide" the data that exists in
	 * the entity but that we don't want to reveal to the client.
	 *
	 * @param evitaRequest - request has no filter / order - only envelopes additional requirements for the loaded entity,
	 *                     so that utility methods in request can be reused
	 * @param session      that connect this request with an opened session
	 */
	@Nonnull
	SealedEntity limitEntity(@Nonnull EntityContract entity, @Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session);

	/**
	 * Returns UNIQUE name of the entity collection in the catalog.
	 */
	@Nonnull
	String getEntityType();

	/**
	 * Creates entity builder for new entity without specified primary key needed to be inserted to the collection.
	 *
	 * @return builder instance to be filled up and stored via {@link #upsertEntity(EvitaSessionContract, EntityMutation)}
	 */
	@Nonnull
	EntityBuilder createNewEntity();

	/**
	 * Creates entity builder for new entity with externally defined primary key needed to be inserted to
	 * the collection.
	 *
	 * @param primaryKey externally assigned primary key for the entity
	 * @return builder instance to be filled up and stored via {@link #upsertEntity(EvitaSessionContract, EntityMutation)}
	 */
	@Nonnull
	EntityBuilder createNewEntity(int primaryKey);

	/**
	 * Method inserts to or updates entity in collection according to passed set of mutations.
	 *
	 * @param session        that connect this request with an opened session
	 * @param entityMutation list of mutation snippets that alter or form the entity
	 * @throws InvalidMutationException when mutation cannot be executed - it is throw when there is attempt to insert
	 *                                  twice entity with the same primary key, or execute update that has no sense
	 */
	@Nonnull
	EntityReferenceContract upsertEntity(@Nonnull EvitaSessionContract session, @Nonnull EntityMutation entityMutation) throws InvalidMutationException;

	/**
	 * Method inserts to or updates entity in collection according to passed set of mutations.
	 *
	 * @param session        that connect this request with an opened session
	 * @param entityMutation list of mutation snippets that alter or form the entity
	 * @throws InvalidMutationException when mutation cannot be executed - it is throw when there is attempt to insert
	 *                                  twice entity with the same primary key, or execute update that has no sense
	 */
	@Nonnull
	SealedEntity upsertAndFetchEntity(@Nonnull EvitaSessionContract session, @Nonnull EntityMutation entityMutation, @Nonnull EvitaRequest evitaRequest);

	/**
	 * Method removes existing entity in collection by its primary key. All entities of other entity types that reference
	 * removed entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * @param session    that connect this request with an opened session
	 * @param primaryKey primary key of the entity to be removed
	 * @return true if entity existed and was removed
	 */
	boolean deleteEntity(@Nonnull EvitaSessionContract session, int primaryKey);

	/**
	 * Method removes existing entity in collection by its primary key. All entities of other entity types that reference
	 * removed entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * @param session      that connect this request with an opened session
	 * @param evitaRequest allowing to propagate instructions for fetching the deleted entity
	 * @return removed entity fetched according to `require` definition
	 */
	@Nonnull
	<T extends Serializable> Optional<T> deleteEntity(@Nonnull EvitaSessionContract session, @Nonnull EvitaRequest evitaRequest);

	/**
	 * Method removes existing hierarchical entity in collection by its primary key. Method also removes all entities
	 * of the same type that are transitively referencing the removed entity as its parent. All entities of other entity
	 * types that reference removed entities in their {@link SealedEntity#getReference(String, int)} still keep
	 * the data untouched.
	 *
	 * @param session that connect this request with an opened session
	 * @return number of removed entities
	 * @throws EvitaInvalidUsageException when entity type has not hierarchy support enabled in schema
	 */
	int deleteEntityAndItsHierarchy(int primaryKey, @Nonnull EvitaSessionContract session);

	/**
	 * Method removes existing hierarchical entity in collection by its primary key. Method also removes all entities
	 * of the same type that are transitively referencing the removed entity as its parent. All entities of other entity
	 * types that reference removed entities in their {@link SealedEntity#getReference(String, int)} still keep
	 * the data untouched.
	 *
	 * @return number of removed entities and the body of the deleted root entity
	 * @throws EvitaInvalidUsageException when entity type has not hierarchy support enabled in schema
	 */
	<T extends Serializable> DeletedHierarchy<T> deleteEntityAndItsHierarchy(@Nonnull EvitaRequest evitaRequest, @Nonnull EvitaSessionContract session);

	/**
	 * Method removes all entities that match passed query. All entities of other entity types that reference removed
	 * entities in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * @param session      that connect this request with an opened session
	 * @param evitaRequest allowing to propagate instructions for fetching the deleted entity
	 * @return number of deleted entities
	 */
	int deleteEntities(@Nonnull EvitaSessionContract session, @Nonnull EvitaRequest evitaRequest);

	/**
	 * Method archives existing active (living) entity in collection by its primary key. Archiving in evitaDB resembles
	 * soft-delete in the sense that the entity is not removed from the collection but is marked as archived and is not
	 * visible in the regular queries. The entity can be restored back to the active state by calling {@link #restoreEntity(EvitaSessionContract, int)}.
	 * Archived entities can still be retrieved using query using {@link EntityScope} requirement with {@link Scope#ARCHIVED}.
	 * Archived entities have the same schema structure, but by default none of their data (except for primary key) are
	 * indexed so that soft-deleted entities consume only minimal space in the memory. Set of indexed data can be
	 * extended using schema definition process.
	 *
	 * All entities of other entity types that reference removed entity in their {@link SealedEntity#getReference(String, int)}
	 * still keep the data untouched. Automatically created - bi-directional references in the archived entity and
	 * the entities on the opposite side are automatically removed along with the entity.
	 *
	 * @param session      that connect this request with an opened session
	 * @param primaryKey primary key of the entity to be archived
	 * @return true if entity existed in living scope and was archived
	 */
	boolean archiveEntity(@Nonnull EvitaSessionContract session, int primaryKey);

	/**
	 * Method archives existing active (living) entity in collection by its primary key. Archiving in evitaDB resembles
	 * soft-delete in the sense that the entity is not removed from the collection but is marked as archived and is not
	 * visible in the regular queries. The entity can be restored back to the active state by calling {@link #restoreEntity(EvitaSessionContract, int)}.
	 * Archived entities can still be retrieved using query using {@link EntityScope} requirement with {@link Scope#ARCHIVED}.
	 * Archived entities have the same schema structure, but by default none of their data (except for primary key) are
	 * indexed so that soft-deleted entities consume only minimal space in the memory. Set of indexed data can be
	 * extended using schema definition process.
	 *
	 * All entities of other entity types that reference removed entity in their {@link SealedEntity#getReference(String, int)}
	 * still keep the data untouched. Automatically created - bi-directional references in the archived entity and
	 * the entities on the opposite side are automatically removed along with the entity.
	 *
	 * @param session      that connect this request with an opened session
	 * @param evitaRequest allowing to propagate instructions for fetching the archived entity
	 * @return archived entity fetched according to `require` definition
	 */
	@Nonnull
	<T extends Serializable> Optional<T> archiveEntity(@Nonnull EvitaSessionContract session, @Nonnull EvitaRequest evitaRequest);

	/**
	 * Method restores existing archived entity in collection by its primary key. Restoring process reverts the effects
	 * of the archiving.
	 *
	 * The automatically created - bi-directional references in the restored entity and the entities on the opposite
	 * side are automatically recreated along with the entity.
	 *
	 * @param session that connect this request with an opened session
	 * @param primaryKey primary key of the entity to be restored
	 * @return true if entity was found in archive and was restored back to the living scope
	 */
	boolean restoreEntity(@Nonnull EvitaSessionContract session, int primaryKey);

	/**
	 * Method restores existing archived entity in collection by its primary key. Restoring process reverts the effects
	 * of the archiving.
	 *
	 * The automatically created - bi-directional references in the restored entity and the entities on the opposite
	 * side are automatically recreated along with the entity.
	 *
	 * @param session      that connect this request with an opened session
	 * @param evitaRequest allowing to propagate instructions for fetching the deleted entity
	 * @return removed entity fetched according to `require` definition
	 */
	@Nonnull
	<T extends Serializable> Optional<T> restoreEntity(@Nonnull EvitaSessionContract session, @Nonnull EvitaRequest evitaRequest);

	/**
	 * Method returns true if there is no single entity in the collection.
	 */
	boolean isEmpty();

	/**
	 * Returns count of all elements in the storage.
	 */
	int size();

	/**
	 * Returns read-only schema of the entity type that is used for formal verification of the data consistency and indexing
	 * prescription. If you need to alter the schema use {@link SealedEntitySchema#openForWrite()} method to convert schema to
	 * a builder that allows to generate necessary mutations for you.
	 * The mutations can be applied by {@link #updateSchema(UUID, CatalogSchemaContract, LocalEntitySchemaMutation...)}    method.
	 */
	@Nonnull
	SealedEntitySchema getSchema();

	/**
	 * Applies mutation to the entity collection. This is a generic method that accepts any mutation and tries to apply
	 * it to the collection. If the mutation is not applicable to the catalog, exception is thrown.
	 *
	 * @param session        that connect this request with an opened session
	 * @param entityMutation mutation to be applied
	 * @throws InvalidMutationException when mutation cannot be executed
	 */
	void applyMutation(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntityMutation entityMutation
	) throws InvalidMutationException;

	/**
	 * Alters existing schema applying passed schema mutation.
	 * Note that the schema update might have updated also instances of other schemas (for example due to reflected
	 * reference schemas).
	 *
	 * @return new updated schema
	 * @throws SchemaAlteringException signalizing that the schema alteration has failed and was not applied
	 */
	@Nonnull
	SealedEntitySchema updateSchema(
		@Nullable UUID sessionId,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull LocalEntitySchemaMutation... schemaMutation
	) throws SchemaAlteringException;

	/**
	 * Returns catalog entity header version that is incremented with each update. Version is not stored on the disk,
	 * it serves only to distinguish whether there is any change made in the header and whether it needs to be persisted
	 * on disk.
	 */
	long getVersion();

	/**
	 * Returns entity collection statistics aggregating basic information about the entity collection and the data
	 * stored in it.
	 *
	 * @return statistics about the entity collection
	 */
	@Nonnull
	EntityCollectionStatistics getStatistics();

	/**
	 * Method terminates this instance of the {@link EntityCollectionContract} and marks this instance as unusable to
	 * any following invocations. In bulk mode ({@link CatalogState#WARMING_UP}) the flush should
	 * be called prior calling #terminate().
	 */
	void terminate();
}
