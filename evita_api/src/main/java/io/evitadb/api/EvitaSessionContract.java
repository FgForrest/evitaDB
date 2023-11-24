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

package io.evitadb.api;

import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.EntityAlreadyRemovedException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.exception.UnexpectedResultException;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.Page;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
import io.evitadb.api.query.require.Strip;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.require;

/**
 * Session are created by the clients to envelope a "piece of work" with evitaDB. In web environment it's a good idea
 * to have session per request, in batch processing it's recommended to keep session per "record page" or "transaction".
 * There may be multiple {@link TransactionContract transactions} during single session instance life but there is no support
 * for transactional overlap - there may be at most single transaction open in single session.
 *
 * EvitaSession transactions behave like <a href="https://en.wikipedia.org/wiki/Snapshot_isolation">Snapshot</a>
 * transactions. When no transaction is explicitly opened - each query to Evita behaves as one small transaction. Data
 * updates are not allowed without explicitly opened transaction.
 *
 * Don't forget to {@link #close()} when your work with Evita is finished.
 * EvitaSession contract is NOT thread safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public interface EvitaSessionContract extends Comparable<EvitaSessionContract>, ClientContext, AutoCloseable {

	/**
	 * Returns Evita instance this session is connected to.
	 *
	 * @return Evita instance this session is connected to.
	 */
	@Nonnull
	EvitaContract getEvita();

	/**
	 * Returns unique id of the session.
	 *
	 * @return unique ID generated when session is created.
	 */
	@Nonnull
	UUID getId();

	/**
	 * Returns catalog schema of the catalog this session is connected to.
	 */
	@Nonnull
	SealedCatalogSchema getCatalogSchema();

	/**
	 * Returns name of the catalog this session is connected to.
	 */
	@Nonnull
	default String getCatalogName() {
		return getCatalogSchema().getName();
	}

	/**
	 * Returns current state of the catalog.
	 *
	 * @see CatalogState
	 */
	@Nonnull
	CatalogState getCatalogState();

	/**
	 * Returns TRUE if session is active (and can be used).
	 */
	boolean isActive();

	/**
	 * Switches catalog to the {@link CatalogState#ALIVE} state and terminates the Evita session so that next session is
	 * operating in the new catalog state.
	 *
	 * Session is {@link #close() closed} only when the state transition successfully occurs and this is signalized
	 * by return value.
	 *
	 * @return TRUE if catalog was successfully switched to {@link CatalogState#ALIVE} state
	 * @see CatalogState
	 */
	boolean goLiveAndClose();

	/**
	 * Terminates Evita session and releases all used resources. This method renders the session unusable and any further
	 * calls to this session should end up with {@link InstanceTerminatedException}
	 *
	 * This method is idempotent and may be called multiple times. Only first call is really processed and others are
	 * ignored.
	 */
	@Override
	void close();

	/**
	 * Method creates new a new entity schema and collection for it in the catalog this session is tied to. It returns
	 * an {@link EntitySchemaBuilder} that could be used for extending the initial "empty"
	 * {@link EntitySchemaContract entity schema}.
	 *
	 * If the collection already exists the method returns a builder for entity schema of the already existing
	 * entity collection - i.e. this method behaves the same as calling:
	 *
	 * ``` java
	 * getEntitySchema(`name`).map(SealedEntitySchema::openForWrite).orElse(null)
	 * ```
	 *
	 * @param entityType the name of the entity collection to be created - equals to {@link EntitySchemaContract#getName()}
	 */
	@Nonnull
	EntitySchemaBuilder defineEntitySchema(@Nonnull String entityType);

	/**
	 * Method allows to automatically adapt schema to passed model class. The class is expected to use annotations
	 * from `io.data.annotation` package. The idea behind this method is that the developers will maintain their
	 * model in plain java classes / interfaces and communicate with Evita using those model classes they are familiar
	 * with and avoid using directly generic model classes derived from {@link EntityContract}.
	 *
	 * When the developers evolve their model classes they just call this method to adapt the Evita schema accordingly
	 * without manually altering the schema by themselves.
	 *
	 * The analyzed schema is immediately applied in the working instance within current transaction. If no transaction
	 * is opened new transaction is opened enveloping all generated mutations.
	 *
	 * @param modelClass the schema template class to synchronize schema with
	 * @see io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer
	 */
	@Nonnull
	SealedEntitySchema defineEntitySchemaFromModelClass(@Nonnull Class<?> modelClass);

	/**
	 * Method allows to automatically adapt schema to passed model class. The class is expected to use annotations
	 * from `io.data.annotation` package. The idea behind this method is that the developers will maintain their
	 * model in plain java classes / interfaces and communicate with Evita using those model classes they are familiar
	 * with and avoid using directly generic model classes derived from {@link EntityContract}.
	 *
	 * When the developers evolve their model classes they just call this method to adapt the Evita schema accordingly
	 * without manually altering the schema by themselves.
	 *
	 * This variant doesn't immediately apply the changes to the evitaDB instance. It produces "pre-configured" builder
	 * instance and provide them to the `postProcessor` to consumer to alter them and when the post-processor finishes
	 * and only then it applies the changes to the evitaDB instance.
	 *
	 * @param modelClass    the schema template class to synchronize schema with
	 * @param postProcessor the consumer that may alter the prepared builder before applying the changes to evitaDB
	 * @see io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer
	 */
	@Nonnull
	SealedEntitySchema defineEntitySchemaFromModelClass(
		@Nonnull Class<?> modelClass,
		@Nonnull SchemaPostProcessor postProcessor
	);

	/**
	 * Returns schema definition for entity of specified type.
	 */
	@Nonnull
	Optional<SealedEntitySchema> getEntitySchema(@Nonnull String entityType);

	/**
	 * Returns schema definition for entity of specified type.
	 *
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	@Nonnull
	Optional<SealedEntitySchema> getEntitySchema(@Nonnull Class<?> modelClass)
		throws EntityClassInvalidException;

	/**
	 * Returns schema definition for entity of specified type or throws a standardized exception.
	 */
	@Nonnull
	SealedEntitySchema getEntitySchemaOrThrow(@Nonnull String entityType)
		throws CollectionNotFoundException;

	/**
	 * Returns schema definition for entity of specified type or throws a standardized exception.
	 *
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	@Nonnull
	SealedEntitySchema getEntitySchemaOrThrow(@Nonnull Class<?> modelClass)
		throws CollectionNotFoundException, EntityClassInvalidException;

	/**
	 * Returns list of all entity types available in this catalog.
	 */
	@Nonnull
	Set<String> getAllEntityTypes();

	/**
	 * Method executes query on {@link CatalogContract} data and returns zero or exactly one entity result. Method
	 * behaves exactly the same as {@link #query(Query, Class)} but verifies the count of returned results and
	 * translates it to simplified return type.
	 *
	 * @param query input query,
	 *              for creation use {@link Query#query(Collection, FilterBy, OrderBy, Require)} or similar methods
	 *              for defining constraint use {@link QueryConstraints} static methods
	 * @return found entity reference, empty optional object if none was found
	 * @throws UnexpectedResultException   when {@link EvitaResponse#getRecordPage()} contains data that are not assignable to `expectedType`
	 * @throws InstanceTerminatedException when session has been already terminated
	 * @see QueryConstraints for list of available filtering and ordering constraints and requirements
	 */
	@Nonnull
	default Optional<EntityReference> queryOneEntityReference(@Nonnull Query query)
		throws UnexpectedResultException, UnexpectedResultCountException, InstanceTerminatedException {
		return queryOne(query, EntityReference.class);
	}

	/**
	 * Method executes query on {@link CatalogContract} data and returns zero or exactly one entity result. Method
	 * behaves exactly the same as {@link #query(Query, Class)} but verifies the count of returned results and
	 * translates it to simplified return type.
	 *
	 * @param query input query,
	 *              for creation use {@link Query#query(Collection, FilterBy, OrderBy, Require)} or similar methods
	 *              for defining constraint use {@link QueryConstraints} static methods
	 * @return found entity itself, empty optional object if none was found
	 * @throws UnexpectedResultException   when {@link EvitaResponse#getRecordPage()} contains data that are not assignable to `expectedType`
	 * @throws InstanceTerminatedException when session has been already terminated
	 * @see QueryConstraints for list of available filtering and ordering constraints and requirements
	 */
	@Nonnull
	default Optional<SealedEntity> queryOneSealedEntity(@Nonnull Query query)
		throws UnexpectedResultException, UnexpectedResultCountException, InstanceTerminatedException {
		if (query.getRequire() == null) {
			return queryOne(
				Query.query(
					query.getCollection(),
					query.getFilterBy(),
					query.getOrderBy(),
					require(entityFetch())
				),
				SealedEntity.class
			);
		} else if (FinderVisitor.findConstraints(query.getRequire(), EntityFetch.class::isInstance, SeparateEntityContentRequireContainer.class::isInstance).isEmpty()) {
			return queryOne(
				Query.query(
					query.getCollection(),
					query.getFilterBy(),
					query.getOrderBy(),
					(Require) query.getRequire().getCopyWithNewChildren(
						ArrayUtils.mergeArrays(
							new RequireConstraint[]{require(entityFetch())},
							query.getRequire().getChildren()
						),
						query.getRequire().getAdditionalChildren()
					)
				),
				SealedEntity.class
			);
		} else {
			return queryOne(query, SealedEntity.class);
		}
	}

	/**
	 * Method executes query on {@link CatalogContract} data and returns zero or exactly one entity result. Method
	 * behaves exactly the same as {@link #query(Query, Class)} but verifies the count of returned results and
	 * translates it to simplified return type.
	 *
	 * Because result is generic and may contain different data as its contents (based on input query), additional
	 * parameter `expectedType` is passed. This parameter allows to check whether passed response contains the expected
	 * type of data before returning it back to the client. This should prevent late ClassCastExceptions on the client
	 * side.
	 *
	 * @param query        input query,
	 *                     for creation use {@link Query#query(Collection, FilterBy, OrderBy, Require)} or similar methods
	 *                     for defining constraint use {@link QueryConstraints} static methods
	 * @param expectedType type of object, that is expected to be in response data,
	 *                     use one of type: {@link EntityReference} or {@link SealedEntity}
	 * @return found entity reference or entity itself, empty optional object if none was found
	 * @throws UnexpectedResultException   when {@link EvitaResponse#getRecordPage()} contains data that are not assignable to `expectedType`
	 * @throws InstanceTerminatedException when session has been already terminated
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class and is not present in the query
	 * @see QueryConstraints for list of available filtering and ordering constraints and requirements
	 */
	@Nonnull
	<S extends Serializable> Optional<S> queryOne(@Nonnull Query query, @Nonnull Class<S> expectedType)
		throws UnexpectedResultException, UnexpectedResultCountException, InstanceTerminatedException;

	/**
	 * Method executes query on {@link CatalogContract} data and returns simplified list of results. Method behaves
	 * exactly the same as {@link #query(Query, Class)} but verifies the count of returned results and translates
	 * it to simplified return type. This method will throw out all possible extra results from, because there is
	 * no way how to propagate them in return value. If you require extra results or paginated list use
	 * the {@link #query(Query, Class)} method.
	 *
	 * @param query input query,
	 *              for creation use {@link Query#query(Collection, FilterBy, OrderBy, Require)} or similar methods
	 *              for defining constraint use {@link QueryConstraints} static methods
	 * @return shortened response - only list of found entities will be returned, the list respect paging / stripping
	 * requirements (if none defined the method behaves as first page with 20 results is requested)
	 * @throws UnexpectedResultException   when {@link EvitaResponse#getRecordPage()} contains data that are not assignable to `expectedType`
	 * @throws InstanceTerminatedException when session has been already terminated
	 * @see QueryConstraints for list of available filtering and ordering constraints and requirements
	 */
	@Nonnull
	default List<EntityReference> queryListOfEntityReferences(@Nonnull Query query)
		throws UnexpectedResultException, InstanceTerminatedException {
		return queryList(query, EntityReference.class);
	}

	/**
	 * Method executes query on {@link CatalogContract} data and returns simplified list of results. Method behaves
	 * exactly the same as {@link #query(Query, Class)} but verifies the count of returned results and translates
	 * it to simplified return type. This method will throw out all possible extra results from, because there is
	 * no way how to propagate them in return value. If you require extra results or paginated list use
	 * the {@link #query(Query, Class)} method.
	 *
	 * @param query input query,
	 *              for creation use {@link Query#query(Collection, FilterBy, OrderBy, Require)} or similar methods
	 *              for defining constraint use {@link QueryConstraints} static methods
	 * @return shortened response - only list of found entities will be returned, the list respect paging / stripping
	 * requirements (if none defined the method behaves as first page with 20 results is requested)
	 * @throws UnexpectedResultException   when {@link EvitaResponse#getRecordPage()} contains data that are not assignable to `expectedType`
	 * @throws InstanceTerminatedException when session has been already terminated
	 * @see QueryConstraints for list of available filtering and ordering constraints and requirements
	 */
	@Nonnull
	default List<SealedEntity> queryListOfSealedEntities(@Nonnull Query query)
		throws UnexpectedResultException, InstanceTerminatedException {
		if (query.getRequire() == null) {
			return queryList(
				Query.query(
					query.getCollection(),
					query.getFilterBy(),
					query.getOrderBy(),
					require(entityFetch())
				),
				SealedEntity.class
			);
		} else if (FinderVisitor.findConstraints(query.getRequire(), EntityFetch.class::isInstance, SeparateEntityContentRequireContainer.class::isInstance).isEmpty()) {
			return queryList(
				Query.query(
					query.getCollection(),
					query.getFilterBy(),
					query.getOrderBy(),
					(Require) query.getRequire().getCopyWithNewChildren(
						ArrayUtils.mergeArrays(
							new RequireConstraint[]{require(entityFetch())},
							query.getRequire().getChildren()
						),
						query.getRequire().getAdditionalChildren()
					)
				),
				SealedEntity.class
			);
		} else {
			return queryList(query, SealedEntity.class);
		}
	}

	/**
	 * Method executes query on {@link CatalogContract} data and returns simplified list of results. Method behaves
	 * exactly the same as {@link #query(Query, Class)} but verifies the count of returned results and translates
	 * it to simplified return type. This method will throw out all possible extra results from, because there is
	 * no way how to propagate them in return value. If you require extra results or paginated list use
	 * the {@link #query(Query, Class)} method.
	 *
	 * Because result is generic and may contain different data as its contents (based on input query), additional
	 * parameter `expectedType` is passed. This parameter allows to check whether passed response contains the expected
	 * type of data before returning it back to the client. This should prevent late ClassCastExceptions on the client
	 * side.
	 *
	 * @param query        input query,
	 *                     for creation use {@link Query#query(Collection, FilterBy, OrderBy, Require)} or similar methods
	 *                     for defining constraint use {@link QueryConstraints} static methods
	 * @param expectedType type of object, that is expected to be in response data,
	 *                     use one of type: {@link EntityReference} or {@link SealedEntity}
	 * @return shortened response - only list of found entities will be returned, the list respect paging / stripping
	 * requirements (if none defined the method behaves as first page with 20 results is requested)
	 * @throws UnexpectedResultException   when {@link EvitaResponse#getRecordPage()} contains data that are not assignable to `expectedType`
	 * @throws InstanceTerminatedException when session has been already terminated
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class and is not present in the query
	 * @see QueryConstraints for list of available filtering and ordering constraints and requirements
	 */
	@Nonnull
	<S extends Serializable> List<S> queryList(@Nonnull Query query, @Nonnull Class<S> expectedType)
		throws UnexpectedResultException, InstanceTerminatedException;

	/**
	 * Method executes query on {@link CatalogContract} data and returns result.
	 *
	 * @param query input query,
	 *              for creation use {@link Query#query(Collection, FilterBy, OrderBy, Require)} or similar methods
	 *              for defining constraint use {@link QueryConstraints} static methods
	 * @return full response data transfer object with all available data
	 * @throws UnexpectedResultException   when {@link EvitaResponse#getRecordPage()} contains data that are not assignable to `expectedType`
	 * @throws InstanceTerminatedException when session has been already terminated
	 * @see QueryConstraints for list of available filtering and ordering constraints and requirements
	 */
	@Nonnull
	default EvitaResponse<EntityReference> queryEntityReference(@Nonnull Query query)
		throws UnexpectedResultException, InstanceTerminatedException {
		return query(query, EntityReference.class);
	}

	/**
	 * Method executes query on {@link CatalogContract} data and returns result.
	 *
	 * @param query input query,
	 *              for creation use {@link Query#query(Collection, FilterBy, OrderBy, Require)} or similar methods
	 *              for defining constraint use {@link QueryConstraints} static methods
	 * @return full response data transfer object with all available data
	 * @throws UnexpectedResultException   when {@link EvitaResponse#getRecordPage()} contains data that are not assignable to `expectedType`
	 * @throws InstanceTerminatedException when session has been already terminated
	 * @see QueryConstraints for list of available filtering and ordering constraints and requirements
	 */
	@Nonnull
	default EvitaResponse<SealedEntity> querySealedEntity(@Nonnull Query query)
		throws UnexpectedResultException, InstanceTerminatedException {
		if (query.getRequire() == null) {
			return query(
				Query.query(
					query.getCollection(),
					query.getFilterBy(),
					query.getOrderBy(),
					require(entityFetch())
				),
				SealedEntity.class
			);
		} else if (FinderVisitor.findConstraints(query.getRequire(), EntityFetch.class::isInstance, SeparateEntityContentRequireContainer.class::isInstance).isEmpty()) {
			return query(
				Query.query(
					query.getCollection(),
					query.getFilterBy(),
					query.getOrderBy(),
					(Require) query.getRequire().getCopyWithNewChildren(
						ArrayUtils.mergeArrays(
							new RequireConstraint[]{require(entityFetch())},
							query.getRequire().getChildren()
						),
						query.getRequire().getAdditionalChildren()
					)
				),
				SealedEntity.class
			);
		} else {
			return query(query, SealedEntity.class);
		}
	}

	/**
	 * Method executes query on {@link CatalogContract} data and returns result. Because result is generic and may contain
	 * different data as its contents (based on input query), additional parameter `expectedType` is passed. This parameter
	 * allows to check whether passed response contains the expected type of data before returning it back to the client.
	 * This should prevent late ClassCastExceptions on the client side.
	 *
	 * @param query        input query,
	 *                     for creation use {@link Query#query(Collection, FilterBy, OrderBy, Require)} or similar methods
	 *                     for defining constraint use {@link QueryConstraints} static methods
	 * @param expectedType type of object, that is expected to be in response data,
	 *                     use one of type: {@link EntityReference} or {@link SealedEntity}
	 * @return full response data transfer object with all available data
	 * @throws UnexpectedResultException   when {@link EvitaResponse#getRecordPage()} contains data that are not assignable to `expectedType`
	 * @throws InstanceTerminatedException when session has been already terminated
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class and is not present in the query
	 * @see QueryConstraints for list of available filtering and ordering constraints and requirements
	 */
	@Nonnull
	<S extends Serializable, T extends EvitaResponse<S>> T query(@Nonnull Query query, @Nonnull Class<S> expectedType)
		throws UnexpectedResultException, InstanceTerminatedException;

	/**
	 * If {@link CatalogContract} supports transactions (see {@link CatalogContract#supportsTransaction()}) method
	 * executes application `logic` in current session and commits the transaction at the end. Transaction is
	 * automatically roll-backed when exception is thrown from the `logic` scope. Changes made by the updating logic are
	 * visible only within update function. Other threads outside the logic function work with non-changed data until
	 * transaction is committed to the index.
	 *
	 * When catalog doesn't support transactions application `logic` is immediately applied to the index data and logic
	 * operates in a <a href="https://en.wikipedia.org/wiki/Isolation_(database_systems)#Read_uncommitted">read
	 * uncommitted</a> mode. Application `logic` can only append new entities in non-transactional mode.
	 *
	 * @throws TransactionException when lambda function throws exception causing the transaction to be rolled back
	 */
	@Nullable
	<T> T execute(@Nonnull Function<EvitaSessionContract, T> logic) throws TransactionException;

	/**
	 * If {@link CatalogContract} supports transactions (see {@link CatalogContract#supportsTransaction()}) method
	 * executes application `logic` in current session and commits the transaction at the end. Transaction is
	 * automatically roll-backed when exception is thrown from the `logic` scope. Changes made by the updating logic are
	 * visible only within update function. Other threads outside the logic function work with non-changed data until
	 * transaction is committed to the index.
	 *
	 * When catalog doesn't support transactions application `logic` is immediately applied to the index data and logic
	 * operates in a <a href="https://en.wikipedia.org/wiki/Isolation_(database_systems)#Read_uncommitted">read
	 * uncommitted</a> mode. Application `logic` can only append new entities in non-transactional mode.
	 *
	 * @throws TransactionException when lambda function throws exception causing the transaction to be rolled back
	 */
	void execute(@Nonnull Consumer<EvitaSessionContract> logic) throws TransactionException;

	/**
	 * Method returns entity by its type and primary key in requested form of completeness. This method allows quick
	 * access to the entity contents when primary key is known.
	 */
	@Nonnull
	Optional<SealedEntity> getEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require);

	/**
	 * Method returns entity by its type and primary key in requested form of completeness. This method allows quick
	 * access to the entity contents when primary key is known. Result object is not constrained to an evitaDB type but
	 * can represent any POJO, record or interface annotated with {@link io.evitadb.api.requestResponse.data.annotation}
	 * annotations.
	 *
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	@Nonnull
	<T extends Serializable> Optional<T> getEntity(
		@Nonnull Class<T> expectedType,
		int primaryKey,
		EntityContentRequire... require
	) throws EntityClassInvalidException;

	/**
	 * Method returns entity with additionally loaded data specified by requirements in second argument. This method
	 * is particularly useful for implementation of lazy loading when application loads only parts of the entity it
	 * expects to be required for handling common client request and then load additional data if processing requires
	 * more in-depth view of the entity.
	 *
	 * @throws EntityAlreadyRemovedException when the entity has been already removed
	 */
	@Nonnull
	<T extends Serializable> T enrichEntity(@Nonnull T partiallyLoadedEntity, EntityContentRequire... require)
		throws EntityAlreadyRemovedException;

	/**
	 * Method returns entity with additionally loaded data specified by requirements in second argument. This method
	 * is particularly useful for implementation of lazy loading when application loads only parts of the entity it
	 * expects to be required for handling common client request and then load additional data if processing requires
	 * more in-depth view of the entity.
	 *
	 * @throws EntityAlreadyRemovedException when the entity has been already removed
	 */
	@Nonnull
	<T extends Serializable> T enrichOrLimitEntity(@Nonnull T partiallyLoadedEntity, EntityContentRequire... require)
		throws EntityAlreadyRemovedException;

	/**
	 * Method alters the {@link CatalogSchemaContract} of the catalog this session is tied to. The method is equivalent
	 * to {@link #updateCatalogSchema(LocalCatalogSchemaMutation...)} but accepts the original builder. This method variant
	 * is present as a shortcut option for the developers.
	 *
	 * @param catalogSchemaBuilder the builder that contains the mutations in the catalog schema
	 * @return version of the altered schema or current version if no modification occurred.
	 */
	default int updateCatalogSchema(@Nonnull CatalogSchemaBuilder catalogSchemaBuilder) throws SchemaAlteringException {
		Assert.isTrue(
			catalogSchemaBuilder.getName().equals(getCatalogName()),
			"Schema builder targets `" + catalogSchemaBuilder.getName() + "` catalog, but the session targets `" + getCatalogName() + "` catalog!"
		);
		return catalogSchemaBuilder.toMutation()
			.map(ModifyCatalogSchemaMutation::getSchemaMutations)
			.map(this::updateCatalogSchema)
			.orElseGet(() -> getCatalogSchema().getVersion());
	}

	/**
	 * Method alters the {@link CatalogSchemaContract} of the catalog this session is tied to. The method is equivalent
	 * to {@link #updateAndFetchCatalogSchema(LocalCatalogSchemaMutation...)} but accepts the original builder. This method
	 * variant is present as a shortcut option for the developers.
	 *
	 * @param catalogSchemaBuilder the builder that contains the mutations in the catalog schema
	 * @return possibly updated body of the {@link CatalogSchemaContract} or the original schema if no change occurred
	 */
	@Nonnull
	default SealedCatalogSchema updateAndFetchCatalogSchema(@Nonnull CatalogSchemaBuilder catalogSchemaBuilder) throws SchemaAlteringException {
		Assert.isTrue(
			catalogSchemaBuilder.getName().equals(getCatalogName()),
			"Schema builder targets `" + catalogSchemaBuilder.getName() + "` catalog, but the session targets `" + getCatalogName() + "` catalog!"
		);
		return catalogSchemaBuilder.toMutation()
			.map(ModifyCatalogSchemaMutation::getSchemaMutations)
			.map(this::updateAndFetchCatalogSchema)
			.orElseGet(this::getCatalogSchema);
	}

	/**
	 * Method alters the {@link CatalogSchemaContract} of the catalog this session is tied to. All mutations will be
	 * applied or none of them (method call is atomic). The method call is idempotent - it means that when the method
	 * is called multiple times with same mutations the changes occur only once.
	 *
	 * @param schemaMutation array of mutations that needs to be applied on current version of {@link CatalogSchemaContract}
	 * @return version of the altered schema or current version if no modification occurred.
	 */
	int updateCatalogSchema(@Nonnull LocalCatalogSchemaMutation... schemaMutation) throws SchemaAlteringException;

	/**
	 * Method alters the {@link CatalogSchemaContract} of the catalog this session is tied to. All mutations will be
	 * applied or none of them (method call is atomic). The method call is idempotent - it means that when the method
	 * is called multiple times with same mutations the changes occur only once.
	 *
	 * @param schemaMutation array of mutations that needs to be applied on current version of {@link CatalogSchemaContract}
	 * @return possibly updated body of the {@link CatalogSchemaContract} or the original schema if no change occurred
	 */
	@Nonnull
	SealedCatalogSchema updateAndFetchCatalogSchema(@Nonnull LocalCatalogSchemaMutation... schemaMutation) throws SchemaAlteringException;

	/**
	 * Method alters one of the {@link EntitySchemaContract entity schemas} of the catalog this session is tied to.
	 * The method is equivalent to {@link #updateEntitySchema(ModifyEntitySchemaMutation)}  but accepts the original builder.
	 * This method variant is present as a shortcut option for the developers.
	 *
	 * @param entitySchemaBuilder the builder that contains the mutations in the entity schema
	 * @return version of the altered schema or current version if no modification occurred.
	 */
	default int updateEntitySchema(@Nonnull EntitySchemaBuilder entitySchemaBuilder) throws SchemaAlteringException {
		return entitySchemaBuilder.toMutation()
			.map(this::updateEntitySchema)
			.orElse(getEntitySchemaOrThrow(entitySchemaBuilder.getName()).version());
	}

	/**
	 * Method alters one of the {@link EntitySchemaContract entity schemas} of the catalog this session is tied to.
	 * The method is equivalent to {@link #updateAndFetchEntitySchema(ModifyEntitySchemaMutation)}  but accepts
	 * the original builder. This method variant is present as a shortcut option for the developers.
	 *
	 * @param entitySchemaBuilder the builder that contains the mutations in the entity schema
	 * @return possibly updated body of the {@link EntitySchemaContract} or the original schema if no change occurred
	 */
	@Nonnull
	default SealedEntitySchema updateAndFetchEntitySchema(@Nonnull EntitySchemaBuilder entitySchemaBuilder) throws SchemaAlteringException {
		return entitySchemaBuilder.toMutation()
			.map(this::updateAndFetchEntitySchema)
			.orElse(getEntitySchemaOrThrow(entitySchemaBuilder.getName()));
	}

	/**
	 * Method alters one of the {@link EntitySchemaContract entity schemas} of the catalog this session is tied to. All
	 * mutations will be applied or none of them (method call is atomic). The method call is idempotent - it means that
	 * when the method is called multiple times with same mutations the changes occur only once.
	 *
	 * @param schemaMutation the builder that contains the mutations in the entity schema
	 * @return version of the altered schema or current version if no modification occurred.
	 */
	int updateEntitySchema(@Nonnull ModifyEntitySchemaMutation schemaMutation) throws SchemaAlteringException;

	/**
	 * Method alters one of the {@link EntitySchemaContract entity schemas} of the catalog this session is tied to. All
	 * mutations will be applied or none of them (method call is atomic). The method call is idempotent - it means that
	 * when the method is called multiple times with same mutations the changes occur only once.
	 *
	 * @param schemaMutation the builder that contains the mutations in the entity schema
	 * @return possibly updated body of the {@link EntitySchemaContract} or the original schema if no change occurred
	 */
	@Nonnull
	SealedEntitySchema updateAndFetchEntitySchema(@Nonnull ModifyEntitySchemaMutation schemaMutation) throws SchemaAlteringException;

	/**
	 * Deletes entire collection of entities along with its schema. After this operation there will be nothing left
	 * of the data that belong to the specified entity type.
	 *
	 * @param entityType type of the entity which collection should be deleted
	 * @return TRUE if collection was successfully deleted
	 */
	boolean deleteCollection(@Nonnull String entityType);

	/**
	 * Deletes entire collection of entities along with its schema. After this operation there will be nothing left
	 * of the data that belong to the specified entity type.
	 *
	 * @param modelClass model class for the entity which collection should be deleted
	 * @return TRUE if collection was successfully deleted
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	boolean deleteCollection(@Nonnull Class<?> modelClass) throws EntityClassInvalidException;

	/**
	 * Renames entire collection of entities along with its schema. After this operation there will be nothing left
	 * of the data that belong to the specified entity type, and entity collection under the new name becomes available.
	 * If you need to rename entity collection to a name of existing collection use
	 * the {@link #replaceCollection(String, String)} method instead.
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
	boolean renameCollection(@Nonnull String entityType, @Nonnull String newName)
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
	boolean replaceCollection(@Nonnull String entityTypeToBeReplaced, @Nonnull String entityTypeToBeReplacedWith);

	/**
	 * Method returns count of all entities stored in the collection of passed entity type.
	 *
	 * @throws IllegalArgumentException when entity collection doesn't exist
	 */
	int getEntityCollectionSize(@Nonnull String entityType);

	/**
	 * Creates entity builder for new entity without specified primary key needed to be inserted to the collection.
	 *
	 * @param entityType type of the entity that should be created
	 * @return builder instance to be filled up and stored via {@link #upsertEntity(Serializable)}
	 */
	@Nonnull
	EntityBuilder createNewEntity(@Nonnull String entityType);

	/**
	 * Creates entity builder for new entity without specified primary key needed to be inserted to the collection.
	 * The expected typ might any class that is annotated with {@link Entity} annotation. It may implement
	 * {@link InstanceEditor}, which allows you to call {@link InstanceEditor#upsertVia(EvitaSessionContract)} method
	 * and analyze the gathered mutations.  Beware - the returned instance is always mutable and not thread safe!
	 *
	 * @param expectedType type of the entity that should be created annotated with {@link Entity}
	 * @return builder instance to be filled up and stored via {@link #upsertEntity(Serializable)}
	 */
	@Nonnull
	<S extends Serializable> S createNewEntity(@Nonnull Class<S> expectedType);

	/**
	 * Creates entity builder for new entity with externally defined primary key needed to be inserted to
	 * the collection.
	 *
	 * @param entityType type of the entity that should be created
	 * @param primaryKey externally assigned primary key for the entity
	 * @return builder instance to be filled up and stored via {@link #upsertEntity(Serializable)} 
	 */
	@Nonnull
	EntityBuilder createNewEntity(@Nonnull String entityType, int primaryKey);

	/**
	 * Creates entity builder for new entity without specified primary key needed to be inserted to the collection.
	 * The expected typ might any class that is annotated with {@link Entity} annotation. It may implement
	 * {@link InstanceEditor}, which allows you to call {@link InstanceEditor#upsertVia(EvitaSessionContract)} method
	 * and analyze the gathered mutations.  Beware - the returned instance is always mutable and not thread safe!
	 *
	 * @param expectedType type of the entity that should be created annotated with {@link Entity}
	 * @param primaryKey   externally assigned primary key for the entity
	 * @return builder instance to be filled up and stored via {@link #upsertEntity(Serializable)}
	 */
	@Nonnull
	<S extends Serializable> S createNewEntity(@Nonnull Class<S> expectedType, int primaryKey);

	/**
	 * Shorthand method for {@link #upsertEntity(EntityMutation)} that accepts custom entity instance that can produce
	 * mutation. The entity instance must represent a proxied instance that can be cast to {@link InstanceEditor}
	 * under the cover (it may not explicitly implement this interface, but the proxying mechanism will do that).
	 *
	 * It's not possible to upsert non-proxied instances such as Java records or final or sealed classes.
	 *
	 * @param customEntity that contains changed entity state
	 */
	@Nonnull
	<S extends Serializable> EntityReference upsertEntity(@Nonnull S customEntity);

	/**
	 * Shorthand method for {@link #upsertEntity(EntityMutation)} that accepts custom entity instance that can produce
	 * mutation. The entity instance must represent a proxied instance that can be cast to {@link InstanceEditor}
	 * under the cover (it may not explicitly implement this interface, but the proxying mechanism will do that).
	 *
	 * It's not possible to upsert non-proxied instances such as Java records or final or sealed classes.
	 *
	 * @param customEntity that contains changed entity state
	 */
	@Nonnull
	<S extends Serializable> List<EntityReference> upsertEntityDeeply(@Nonnull S customEntity);

	/**
	 * Method inserts to or updates entity in collection according to passed set of mutations.
	 *
	 * @param entityMutation list of mutation snippets that alter or form the entity
	 */
	@Nonnull
	EntityReference upsertEntity(@Nonnull EntityMutation entityMutation);

	/**
	 * Shorthand method for {@link #upsertEntity(EntityMutation)} that accepts {@link EntityBuilder} that can produce
	 * mutation.
	 *
	 * TOBEDONE #43 - support new variants for the model class
	 *
	 * @param entityBuilder that contains changed entity state
	 * @return modified entity fetched according to `require` definition
	 */
	@Nonnull
	SealedEntity upsertAndFetchEntity(@Nonnull EntityBuilder entityBuilder, EntityContentRequire... require);

	/**
	 * Method inserts to or updates entity in collection according to passed set of mutations.
	 *
	 * TOBEDONE #43 - support new variants for the model class
	 *
	 * @param entityMutation list of mutation snippets that alter or form the entity
	 * @return modified entity fetched according to `require` definition
	 */
	@Nonnull
	SealedEntity upsertAndFetchEntity(@Nonnull EntityMutation entityMutation, EntityContentRequire... require);

	/**
	 * Method removes existing entity in collection by its primary key. All entities of other entity types that reference
	 * removed entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * @return true if entity existed and was removed
	 */
	boolean deleteEntity(@Nonnull String entityType, int primaryKey);

	/**
	 * Method removes existing entity in collection by its primary key. All entities of other entity types that reference
	 * removed entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * @return true if entity existed and was removed
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	boolean deleteEntity(@Nonnull Class<?> modelClass, int primaryKey) throws EntityClassInvalidException;

	/**
	 * Method removes existing entity in collection by its primary key. All entities of other entity types that reference
	 * removed entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * @return removed entity fetched according to `require` definition
	 */
	@Nonnull
	Optional<SealedEntity> deleteEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require);

	/**
	 * Method removes existing entity in collection by its primary key. All entities of other entity types that reference
	 * removed entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * @return removed entity fetched according to `require` definition
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	@Nonnull
	<T extends Serializable> Optional<T> deleteEntity(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require)
		throws EntityClassInvalidException;

	/**
	 * Method removes existing hierarchical entity in collection by its primary key. Method also removes all entities
	 * of the same type that are transitively referencing the removed entity as its parent. All entities of other entity
	 * types that reference removed entities in their {@link SealedEntity#getReference(String, int)} still keep
	 * the data untouched.
	 *
	 * @return number of removed entities
	 * @throws EvitaInvalidUsageException when entity type has not hierarchy support enabled in schema
	 */
	int deleteEntityAndItsHierarchy(@Nonnull String entityType, int primaryKey)
		throws EvitaInvalidUsageException;

	/**
	 * Method removes existing hierarchical entity in collection by its primary key. Method also removes all entities
	 * of the same type that are transitively referencing the removed entity as its parent. All entities of other entity
	 * types that reference removed entities in their {@link SealedEntity#getReference(String, int)} still keep
	 * the data untouched.
	 *
	 * @return number of removed entities and the body of the deleted root entity
	 * @throws EvitaInvalidUsageException when entity type has not hierarchy support enabled in schema
	 */
	@Nonnull
	DeletedHierarchy<SealedEntity> deleteEntityAndItsHierarchy(@Nonnull String entityType, int primaryKey, EntityContentRequire... require)
		throws EvitaInvalidUsageException;

	/**
	 * Method removes existing hierarchical entity in collection by its primary key. Method also removes all entities
	 * of the same type that are transitively referencing the removed entity as its parent. All entities of other entity
	 * types that reference removed entities in their {@link SealedEntity#getReference(String, int)} still keep
	 * the data untouched.
	 *
	 * @return number of removed entities and the body of the deleted root entity
	 * @throws EvitaInvalidUsageException  when entity type has not hierarchy support enabled in schema
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	@Nonnull
	<T extends Serializable> DeletedHierarchy<T> deleteEntityAndItsHierarchy(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require)
		throws EvitaInvalidUsageException, EntityClassInvalidException;

	/**
	 * Method removes all entities that match passed query. All entities of other entity types that reference removed
	 * entities in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * Beware: you need to provide {@link Page} or {@link Strip} in the query to control the maximum number of removed
	 * entities. Otherwise, the default value of maximum of `20` entities to remove will be used.
	 *
	 * @return number of deleted entities
	 */
	int deleteEntities(@Nonnull Query query);

	/**
	 * Method removes all entities that match passed query. All entities of other entity types that reference removed
	 * entities in their {@link SealedEntity#getReference(String, int)} still keep the data untouched. This variant of
	 * the delete by query method allows returning partial of full bodies of the removed entities.
	 *
	 * Beware: you need to provide {@link Page} or {@link Strip} in the query to control the maximum number of removed
	 * entities. Otherwise, the default value of maximum of `20` entities to remove will be used.
	 *
	 * @return bodies of deleted entities according to {@link Query#getRequire() requirements}
	 */
	@Nonnull
	SealedEntity[] deleteSealedEntitiesAndReturnBodies(@Nonnull Query query);

	/**
	 * Default implementation uses ID for comparing two sessions (and to distinguish one session from another).
	 *
	 * @return 0 if both sessions are the same
	 */
	default int compareTo(@Nonnull EvitaSessionContract otherSession) {
		return getId().compareTo(otherSession.getId());
	}

	/**
	 * Opens a new transaction.
	 *
	 * @return transaction id
	 */
	long openTransaction();

	/**
	 * Returns {@link TransactionContract#getId()} of the currently opened transaction in this session. Returns empty
	 * value if no transaction is present.
	 */
	@Nonnull
	Optional<Long> getOpenedTransactionId();

	/**
	 * Returns TRUE if transaction is currently open in this session.
	 */
	default boolean isTransactionOpen() {
		return getOpenedTransactionId().isPresent();
	}

	/**
	 * Terminates opened transaction - either by rollback or commit depending on {@link TransactionContract#isRollbackOnly()}.
	 * This method throws exception only when transaction hasn't been opened.
	 */
	void closeTransaction();

	/**
	 * Returns true if currently opened transaction has rollback flag set on.
	 */
	boolean isRollbackOnly();

	/**
	 * Method marks current transaction to be rolled back on {@link #closeTransaction()}.
	 * Changes made in transaction will never make it to the database.
	 */
	void setRollbackOnly();

	/**
	 * Method returns true if the session is in read-only mode. That means no transaction is opened and no data will
	 * be modified within this session. Read-only sessions allow more aggressive optimizations, such as using cached
	 * results.
	 *
	 * @see io.evitadb.api.SessionTraits.SessionFlags#READ_WRITE
	 */
	boolean isReadOnly();

	/**
	 * Returns true if session is switched to binary format output.
	 *
	 * @see io.evitadb.api.SessionTraits.SessionFlags#BINARY
	 * @see io.evitadb.api.requestResponse.EvitaBinaryEntityResponse
	 */
	boolean isBinaryFormat();

	/**
	 * Returns true if session has dry run flag that means that no updates will be committed whatsoever.
	 *
	 * @see io.evitadb.api.SessionTraits.SessionFlags#DRY_RUN
	 */
	boolean isDryRun();

	/**
	 * Returns period in seconds this session has been inactive (no call occurred on it).
	 */
	long getInactivityDurationInSeconds();

	/**
	 * Returns implementation of the proxy factory that is used to wrap the returned {@link SealedEntity} into a custom
	 * Java types.
	 */
	@Nonnull
	ProxyFactory getProxyFactory();

	@Override
	default void executeWithClientAndRequestId(@Nonnull String clientId, @Nonnull String requestId, @Nonnull Runnable lambda) {
		getEvita().executeWithClientAndRequestId(clientId, requestId, lambda);
	}

	@Override
	default void executeWithClientId(@Nonnull String clientId, @Nonnull Runnable lambda) {
		getEvita().executeWithClientId(clientId, lambda);
	}

	@Override
	default void executeWithRequestId(@Nonnull String requestId, @Nonnull Runnable lambda) {
		getEvita().executeWithRequestId(requestId, lambda);
	}

	@Override
	default <T> T executeWithClientAndRequestId(@Nonnull String clientId, @Nonnull String requestId, @Nonnull Supplier<T> lambda) {
		return getEvita().executeWithClientAndRequestId(clientId, requestId, lambda);
	}

	@Override
	default <T> T executeWithClientId(@Nonnull String clientId, @Nonnull Supplier<T> lambda) {
		return getEvita().executeWithClientId(clientId, lambda);
	}

	@Override
	default <T> T executeWithRequestId(@Nonnull String requestId, @Nonnull Supplier<T> lambda) {
		return getEvita().executeWithRequestId(requestId, lambda);
	}

	@Nonnull
	@Override
	default Optional<String> getClientId() {
		return getEvita().getClientId();
	}

	@Nonnull
	@Override
	default Optional<String> getRequestId() {
		return getEvita().getRequestId();
	}
}
