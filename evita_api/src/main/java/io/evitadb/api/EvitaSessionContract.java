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

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.EntityAlreadyRemovedException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.exception.UnexpectedResultException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.Page;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
import io.evitadb.api.query.require.Strip;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.ReflectedReference;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.system.StoredVersion;
import io.evitadb.api.task.Task;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Closeable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.require;

/**
 * Session are created by the clients to envelope a "piece of work" with evitaDB. In web environment it's a good idea
 * to have session per request, in batch processing it's recommended to keep session per "record page" or "transaction".
 * There may be multiple {@link TransactionContract transaction} during single session instance life but there is no support
 * for transactional overlap - there may be at most single transaction open in single session.
 *
 * EvitaSession transaction behave like <a href="https://en.wikipedia.org/wiki/Snapshot_isolation">Snapshot</a>
 * transaction. When no transaction is explicitly opened - each query to Evita behaves as one small transaction. Data
 * updates are not allowed without explicitly opened transaction.
 *
 * Remember to {@link #close()} when your work with Evita is finished.
 * EvitaSession contract is NOT thread safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public interface EvitaSessionContract extends Comparable<EvitaSessionContract>, Closeable {

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
	 * Returns unique catalog id that doesn't change with catalog schema changes - such as renaming.
	 * The id is assigned to the catalog when it is created and never changes.
	 *
	 * @return unique catalog id
	 */
	@Nonnull
	UUID getCatalogId();

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
	 * Returns version of the catalog that gets incremented with each transaction commit. When catalog state is set to
	 * {@link CatalogState#WARMING_UP} and is not yet transactional, the version stays at zero all the time.
	 *
	 * @return version of the catalog that gets incremented with each transaction commit
	 */
	long getCatalogVersion();

	/**
	 * Returns TRUE if session is active (and can be used).
	 */
	boolean isActive();

	/**
	 * Method switches catalog to the {@link CatalogState#ALIVE} state and terminates the current Evita session. It's not
	 * possible to open any session while catalog transitions from {@link CatalogState#WARMING_UP} to {@link CatalogState#ALIVE}.
	 * That means until this operation is fully finished. The next opened session will be operating in the new
	 * catalog state.
	 *
	 * Session is {@link #close() closed} immediately and method returns a {@link Progress} object that can be
	 * used to monitor the progress of the go-live operation or to wait for it to finish via. completion stage accessible
	 * via {@link Progress#onCompletion()}.
	 *
	 * @param progressObserver optional progress observer that can be used to monitor the percentage progress of the go-live operation.
	 * @return {@link Progress} object that can be used to monitor the progress of the go-live operation or to
	 *          wait for it to finish using completion stage accessible via {@link Progress#onCompletion()}
	 * @see CatalogState
	 */
	@Nonnull
	Progress<CommitVersions> goLiveAndCloseWithProgress(@Nullable IntConsumer progressObserver);

	/**
	 * Method switches catalog to the {@link CatalogState#ALIVE} state and terminates the current Evita session. It's not
	 * possible to open any session while catalog transitions from {@link CatalogState#WARMING_UP} to {@link CatalogState#ALIVE}.
	 * That means until this operation is fully finished. The next opened session will be operating in the new
	 * catalog state.
	 *
	 * Session is {@link #close() closed} immediately and method returns a {@link Progress} object that can be
	 * used to monitor the progress of the go-live operation or to wait for it to finish via. completion stage accessible
	 * via {@link Progress#onCompletion()}.
	 *
	 * @return {@link Progress} object that can be used to monitor the progress of the go-live operation or to
	 *          wait for it to finish using completion stage accessible via {@link Progress#onCompletion()}
	 * @see CatalogState
	 */
	@Nonnull
	default Progress<CommitVersions> goLiveAndCloseWithProgress() {
		return goLiveAndCloseWithProgress(null);
	}

	/**
	 * Method switches catalog to the {@link CatalogState#ALIVE} state and terminates the current Evita session. It's not
	 * possible to open any session while catalog transitions from {@link CatalogState#WARMING_UP} to {@link CatalogState#ALIVE}.
	 * That means until this operation is fully finished. The next opened session will be operating in the new
	 * catalog state.
	 *
	 * Session is {@link #close() closed} immediately. This method finishes when entire go-live operation is finished
	 * (i.e. block until the catalog is in {@link CatalogState#ALIVE} state), which may take some time depending on
	 * the size of the catalog and the number of changes that need to be processed.
	 *
	 * @see CatalogState
	 */
	default void goLiveAndClose() {
		goLiveAndCloseWithProgress(null)
			.onCompletion()
			.toCompletableFuture()
			.join();
	}

	/**
	 * Terminates Evita session and releases all used resources. This method renders the session unusable and any further
	 * calls to this session should end up with {@link InstanceTerminatedException}. In case there were any mutations
	 * in read/write session and the catalog is in transactional mode, the method is finished when the changes are
	 * propagated to indexes. The call is equivalent to calling {@link #closeWhen(CommitBehavior)} with
	 * {@link CommitBehavior#WAIT_FOR_CHANGES_VISIBLE}. This method follows synchronous principles, which are
	 * easier to understand and use.
	 *
	 * This method is idempotent and may be called multiple times. Only first call is really processed and others are
	 * ignored.
	 */
	@Override
	default void close() {
		closeWhen(getCommitBehavior());
	}

	/**
	 * Terminates Evita session and releases all used resources. This method renders the session unusable and any further
	 * calls to this session should end up with {@link InstanceTerminatedException}. In case there were any mutations
	 * in read/write session and the catalog is in transactional mode, the method is finished when the change processing
	 * reaches the given state. This method follows synchronous principles, which are easier to understand and use.
	 *
	 * This method is idempotent and may be called multiple times. Only first call is really processed and others are
	 * ignored.
	 */
	@Nonnull
	default CommitVersions closeWhen(@Nonnull CommitBehavior commitBehaviour) {
		return closeNow(commitBehaviour).toCompletableFuture().join();
	}

	/**
	 * Method terminates Evita session and releases all used resources. This method renders the session unusable and
	 * any further calls to this session should end up with {@link InstanceTerminatedException}. Method finishes
	 * immediately returning a {@link CompletionStage} that will be completed when:
	 *
	 * 1. catalog is in warm-up mode: immediately
	 * 2. catalog is in transactional mode and no changes were made: immediately
	 * 3. catalog is in transactional mode and changes were made: when the change processing reaches the given state
	 *
	 * This method is idempotent and may be called multiple times, it always returns the same future.
	 */
	@Nonnull
	CompletionStage<CommitVersions> closeNow(@Nonnull CommitBehavior commitBehaviour);

	/**
	 * Terminates the session and releases all resources, returning a {@link CommitProgress} object.
	 * This object exposes {@link CompletionStage}s for each significant transaction commit milestone,
	 * allowing callers to react asynchronously as the commit progresses (e.g. after conflict resolution,
	 * WAL persistence, index updates, and global visibility). Method finishes immediately returning
	 * an object with futures for each significant transaction commit milestone that a client might be
	 * interested in.
	 *
	 * This method is idempotent; repeated calls return the same progress object.
	 *
	 * @return a {@link CommitProgress} for observing commit milestones
	 */
	@Nonnull
	CommitProgress closeNowWithProgress();

	/**
	 * Creates new publisher that emits {@link ChangeCatalogCapture}s that match the request.
	 *
	 * @param request defines what events are captured
	 * @return publisher that emits {@link ChangeCatalogCapture}s that match the request
	 */
	@Nonnull
	ChangeCapturePublisher<ChangeCatalogCapture> registerChangeCatalogCapture(@Nonnull ChangeCatalogCaptureRequest request);

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
	 *
	 * @deprecated use {@link #getEntitySchemaOrThrowException(String)}
	 */
	@Nonnull
	@Deprecated(since = "2024.8", forRemoval = true)
	default SealedEntitySchema getEntitySchemaOrThrow(@Nonnull String entityType)
		throws CollectionNotFoundException {
		return getEntitySchemaOrThrowException(entityType);
	}

	/**
	 * Returns schema definition for entity of specified type or throws a standardized exception.
	 */
	@Nonnull
	SealedEntitySchema getEntitySchemaOrThrowException(@Nonnull String entityType)
		throws CollectionNotFoundException;

	/**
	 * Returns schema definition for entity of specified type or throws a standardized exception.
	 *
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 * @deprecated use {@link #getEntitySchemaOrThrowException(Class)}
	 */
	@Deprecated(since = "2024.8", forRemoval = true)
	@Nonnull
	default SealedEntitySchema getEntitySchemaOrThrow(@Nonnull Class<?> modelClass)
		throws CollectionNotFoundException, EntityClassInvalidException {
		return getEntitySchemaOrThrowException(modelClass);
	}

	/**
	 * Returns schema definition for entity of specified type or throws a standardized exception.
	 *
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	@Nonnull
	SealedEntitySchema getEntitySchemaOrThrowException(@Nonnull Class<?> modelClass)
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
	 *              for creation use {@link Query#query(HeadConstraint, FilterBy, OrderBy, Require)} or similar methods
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
	 *              for creation use {@link Query#query(HeadConstraint, FilterBy, OrderBy, Require)} or similar methods
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
	 *                     for creation use {@link Query#query(HeadConstraint, FilterBy, OrderBy, Require)} or similar methods
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
	 *              for creation use {@link Query#query(HeadConstraint, FilterBy, OrderBy, Require)} or similar methods
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
	 *              for creation use {@link Query#query(HeadConstraint, FilterBy, OrderBy, Require)} or similar methods
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
	 *                     for creation use {@link Query#query(HeadConstraint, FilterBy, OrderBy, Require)} or similar methods
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
	 *              for creation use {@link Query#query(HeadConstraint, FilterBy, OrderBy, Require)} or similar methods
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
	 *              for creation use {@link Query#query(HeadConstraint, FilterBy, OrderBy, Require)} or similar methods
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
							new RequireConstraint[]{entityFetch()},
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
	 *                     for creation use {@link Query#query(HeadConstraint, FilterBy, OrderBy, Require)} or similar methods
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
	 * Method returns entity by its type and primary key in requested form of completeness. This method allows quick
	 * access to the entity contents when primary key is known. Method returns only entity in live scope (archived
	 * entities are considered as removed).
	 *
	 * @param entityType type of the entity to be fetched
	 * @param primaryKey primary key of the entity to be fetched
	 * @param require    additional requirements for the entity fetching
	 */
	@Nonnull
	Optional<SealedEntity> getEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require);

	/**
	 * Method returns entity by its type and primary key in requested form of completeness. This method allows quick
	 * access to the entity contents when primary key is known.
	 *
	 * @param entityType type of the entity to be fetched
	 * @param primaryKey primary key of the entity to be fetched
	 * @param scopes     array of scopes that should be used for fetching the entity (at least one scope is required)
	 * @param require    additional requirements for the entity fetching
	 */
	@Nonnull
	Optional<SealedEntity> getEntity(@Nonnull String entityType, int primaryKey, @Nonnull Scope[] scopes, EntityContentRequire... require);

	/**
	 * Method returns entity by its type and primary key in requested form of completeness. This method allows quick
	 * access to the entity contents when primary key is known. Result object is not constrained to an evitaDB type but
	 * can represent any POJO, record or interface annotated with {@link io.evitadb.api.requestResponse.data.annotation}
	 * annotations.
	 *
	 * @param expectedType expected interface of the result entity
	 * @param primaryKey   primary key of the entity to be fetched
	 * @param require      additional requirements for the entity fetching
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	@Nonnull
	default <T extends Serializable> Optional<T> getEntity(
		@Nonnull Class<T> expectedType,
		int primaryKey,
		EntityContentRequire... require
	) throws EntityClassInvalidException {
		return getEntity(expectedType, primaryKey, Scope.DEFAULT_SCOPES, require);
	}

	/**
	 * Method returns entity by its type and primary key in requested form of completeness. This method allows quick
	 * access to the entity contents when primary key is known. Result object is not constrained to an evitaDB type but
	 * can represent any POJO, record or interface annotated with {@link io.evitadb.api.requestResponse.data.annotation}
	 * annotations.
	 *
	 * @param expectedType expected interface of the result entity
	 * @param primaryKey   primary key of the entity to be fetched
	 * @param scopes       array of scopes that should be used for fetching the entity (at least one scope is required)
	 * @param require      additional requirements for the entity fetching
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	@Nonnull
	<T extends Serializable> Optional<T> getEntity(
		@Nonnull Class<T> expectedType,
		int primaryKey,
		@Nonnull Scope[] scopes,
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
		return catalogSchemaBuilder
			.toMutation()
			.map(
				mutation -> updateCatalogSchema(mutation.getSchemaMutations())
			)
			// no mutation was provided, so we return the current version
			.orElseGet(() -> getCatalogSchema().version());
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
			.orElseGet(() -> getEntitySchemaOrThrowException(entitySchemaBuilder.getName()).version());
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
			.orElseGet(() -> getEntitySchemaOrThrowException(entitySchemaBuilder.getName()));
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
	 * @param entityBuilder that contains changed entity state
	 * @return modified entity fetched according to `require` definition
	 */
	@Nonnull
	SealedEntity upsertAndFetchEntity(@Nonnull EntityBuilder entityBuilder, EntityContentRequire... require);

	/**
	 * Method inserts to or updates entity in collection according to passed set of mutations.
	 *
	 * @param entityMutation list of mutation snippets that alter or form the entity
	 * @return modified entity fetched according to `require` definition
	 */
	@Nonnull
	SealedEntity upsertAndFetchEntity(@Nonnull EntityMutation entityMutation, EntityContentRequire... require);

	/**
	 * Method removes existing entity in collection by its primary key. All entities of other entity types that reference
	 * removed entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched except for
	 * {@link ReflectedReference} which are removed. The reflected references are set up automatically by the system
	 * when the main reference is created and that's why they are removed automatically when the main reference is removed.
	 *
	 * BEWARE: this method represents the hard delete operation and the entity will be removed from the catalog permanently.
	 * If you need to archive the entity instead of removing it, use the {@link #archiveEntity(String, int)} method.
	 *
	 * @return true if entity existed and was removed
	 */
	boolean deleteEntity(@Nonnull String entityType, int primaryKey);

	/**
	 * Method removes existing entity in collection by its primary key. All entities of other entity types that reference
	 * removed entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched except for
	 * {@link ReflectedReference} which are removed. The reflected references are set up automatically by the system
	 * when the main reference is created and that's why they are removed automatically when the main reference is removed.
	 *
	 * BEWARE: this method represents the hard delete operation and the entity will be removed from the catalog permanently.
	 * If you need to archive the entity instead of removing it, use the {@link #archiveEntity(Class, int)} method.
	 *
	 * @return true if entity existed and was removed
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	boolean deleteEntity(@Nonnull Class<?> modelClass, int primaryKey) throws EntityClassInvalidException;

	/**
	 * Method removes existing entity in collection by its primary key. All entities of other entity types that reference
	 * removed entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched except for
	 * {@link ReflectedReference} which are removed. The reflected references are set up automatically by the system
	 * when the main reference is created and that's why they are removed automatically when the main reference is removed.
	 *
	 * BEWARE: this method represents the hard delete operation and the entity will be removed from the catalog permanently.
	 * If you need to archive the entity instead of removing it, use the {@link #archiveEntity(String, int, EntityContentRequire...)} method.
	 *
	 * @return removed entity fetched according to `require` definition
	 */
	@Nonnull
	Optional<SealedEntity> deleteEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require);

	/**
	 * Method removes existing entity in collection by its primary key. All entities of other entity types that reference
	 * removed entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched except for
	 * {@link ReflectedReference} which are removed. The reflected references are set up automatically by the system
	 * when the main reference is created and that's why they are removed automatically when the main reference is removed.
	 *
	 * BEWARE: this method represents the hard delete operation and the entity will be removed from the catalog permanently.
	 * If you need to archive the entity instead of removing it, use the {@link #archiveEntity(Class, int, EntityContentRequire...)} method.
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
	 * Method moves existing entity from Live Scope to Archived Scope by its primary key. All entities of other entity types that reference
	 * this entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched except for
	 * {@link ReflectedReference} which are removed. The reflected references are set up automatically by the system
	 * when the main reference is created and that's why they are removed automatically when the main reference is removed.
	 *
	 * Archived Scope has usually fewer indexes for searching and sorting, which means the data occupies less memory compared to Live Scope.
	 *
	 * @return true if entity existed and was archived
	 */
	boolean archiveEntity(@Nonnull String entityType, int primaryKey);

	/**
	 * Method moves existing entity from Live Scope to Archived Scope by its primary key. All entities of other entity types that reference
	 * this entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * Archived Scope has usually fewer indexes for searching and sorting, which means the data occupies less memory compared to Live Scope.
	 *
	 * @return true if entity existed and was archived
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	boolean archiveEntity(@Nonnull Class<?> modelClass, int primaryKey) throws EntityClassInvalidException;

	/**
	 * Method moves existing entity from Live Scope to Archived Scope by its primary key. All entities of other entity types that reference
	 * this entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * Archived Scope has usually fewer indexes for searching and sorting, which means the data occupies less memory compared to Live Scope.
	 *
	 * @return archived entity fetched according to `require` definition
	 */
	@Nonnull
	Optional<SealedEntity> archiveEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require);

	/**
	 * Method moves existing entity from Live Scope to Archived Scope by its primary key. All entities of other entity types that reference
	 * this entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * Archived Scope has usually fewer indexes for searching and sorting, which means the data occupies less memory compared to Live Scope.
	 *
	 * @return archived entity fetched according to `require` definition
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	@Nonnull
	<T extends Serializable> Optional<T> archiveEntity(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require)
		throws EntityClassInvalidException;

	/**
	 * Method moves existing entity from Archived Scope to Live Scope by its primary key. All entities of other entity types that reference
	 * this entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * Live Scope has usually more indexes for searching and sorting, which means the data occupies more memory compared to Archived Scope.
	 *
	 * @return true if entity existed and was restored
	 */
	boolean restoreEntity(@Nonnull String entityType, int primaryKey);

	/**
	 * Method moves existing entity from Archived Scope to Live Scope by its primary key. All entities of other entity types that reference
	 * this entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * Live Scope has usually more indexes for searching and sorting, which means the data occupies more memory compared to Archived Scope.
	 *
	 * @return true if entity existed and was restored
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	boolean restoreEntity(@Nonnull Class<?> modelClass, int primaryKey) throws EntityClassInvalidException;

	/**
	 * Method moves existing entity from Archived Scope to Live Scope by its primary key. All entities of other entity types that reference
	 * this entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * Live Scope has usually more indexes for searching and sorting, which means the data occupies more memory compared to Archived Scope.
	 *
	 * @return restored entity fetched according to `require` definition
	 */
	@Nonnull
	Optional<SealedEntity> restoreEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require);

	/**
	 * Method moves existing entity from Archived Scope to Live Scope by its primary key. All entities of other entity types that reference
	 * this entity in their {@link SealedEntity#getReference(String, int)} still keep the data untouched.
	 *
	 * Live Scope has usually more indexes for searching and sorting, which means the data occupies more memory compared to Archived Scope.
	 *
	 * @return restored entity fetched according to `require` definition
	 * @throws EntityClassInvalidException when entity type cannot be extracted from the class
	 */
	@Nonnull
	<T extends Serializable> Optional<T> restoreEntity(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require)
		throws EntityClassInvalidException;

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
	 * Returns stream of change data captures (mutations) that occurred in the catalog that match the specified criteria
	 * in the request. The method returns the stream of changes in the reversed order - the most recent changes are
	 * returned first.
	 *
	 * !!! Important: remember to close the stream after you are done with it to release the resources
	 *
	 * @param request request that specifies the criteria for the changes to be returned, multiple criteria definitions
	 *                are combined with logical OR
	 * @return stream of change data captures that match the specified criteria in reversed order
	 * @throws TemporalDataNotAvailableException when data for particular moment is not available anymore
	 */
	@Nonnull
	Stream<ChangeCatalogCapture> getMutationsHistory(
		@Nonnull ChangeCatalogCaptureRequest request
	) throws TemporalDataNotAvailableException;

	/**
	 * Creates a backup of the specified catalog and returns an InputStream to read the binary data of the zip file.
	 *
	 * @param pastMoment     leave null for creating backup for actual dataset, or specify past moment to create backup for
	 *                       the dataset as it was at that moment
	 * @param catalogVersion precise catalog version to create backup for, or null to create backup for the latest version,
	 *                       when set not null, the pastMoment parameter is ignored
	 * @param includingWAL   if true, the backup will include the Write-Ahead Log (WAL) file and when the catalog is
	 *                       restored, it'll replay the WAL contents locally to bring the catalog to the current state
	 * @return jobId of the backup process
	 * @throws TemporalDataNotAvailableException when the past data is not available
	 */
	@Nonnull
	Task<?, FileForFetch> backupCatalog(
		@Nullable OffsetDateTime pastMoment,
		@Nullable Long catalogVersion,
		boolean includingWAL
	) throws TemporalDataNotAvailableException;

	/**
	 * Creates a backup of the specified catalog and returns an InputStream to read the binary data of the zip file.
	 * Full backup includes all data files, WAL files, and the catalog header file from the catalog storage.
	 * After restoring catalog from the full backup, the catalog will contain all the data - so you should be able to
	 * create even point-in-time backups from it.
	 *
	 * @return jobId of the backup process
	 */
	@Nonnull
	Task<?, FileForFetch> fullBackupCatalog();

	/**
	 * Default implementation uses ID for comparing two sessions (and to distinguish one session from another).
	 *
	 * @return 0 if both sessions are the same
	 */
	default int compareTo(@Nonnull EvitaSessionContract otherSession) {
		return getId().compareTo(otherSession.getId());
	}

	/**
	 * Returns {@link TransactionContract#getTransactionId()} of the currently opened transaction in this session. Returns empty
	 * value if no transaction is present.
	 */
	@Nonnull
	Optional<UUID> getOpenedTransactionId();

	/**
	 * Returns TRUE if transaction is currently open in this session.
	 */
	default boolean isTransactionOpen() {
		return getOpenedTransactionId().isPresent();
	}

	/**
	 * Returns true if currently opened transaction has rollback flag set on.
	 */
	boolean isRollbackOnly();

	/**
	 * Method marks current transaction to be rolled back on {@link #close()}.
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
	 * Method returns {@link CommitBehavior} set when the session was created. This behavior can be changed when
	 * the session is {@link #closeWhen(CommitBehavior) closed}, but is used when implicit {@link #close()} is called.
	 *
	 * @return commit behavior
	 */
	@Nonnull
	CommitBehavior getCommitBehavior();

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
}
