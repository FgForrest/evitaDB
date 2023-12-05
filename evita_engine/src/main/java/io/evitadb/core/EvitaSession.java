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

package io.evitadb.core;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.EvitaSessionTerminationCallback;
import io.evitadb.api.SchemaPostProcessor;
import io.evitadb.api.SchemaPostProcessorCapturingResult;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.exception.TransactionNotSupportedException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.exception.UnexpectedResultException;
import io.evitadb.api.exception.UnexpectedTransactionStateException;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.DeletedHierarchy;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.price.PriceMutation;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer;
import io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer.AnalysisResult;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.UUIDUtil;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.schema.ClassSchemaAnalyzer.extractEntityTypeFromClass;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Session are created by the clients to envelope a "piece of work" with evitaDB. In web environment it's a good idea
 * to have session per request, in batch processing it's recommended to keep session per "record page" or "transaction".
 * There may be multiple {@link Transaction transactions} during single session instance life but there is no support
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
@EqualsAndHashCode(of = "id")
@Slf4j
public final class EvitaSession implements EvitaInternalSessionContract {

	/**
	 * Evita instance this session is connected to.
	 */
	@Getter private final Evita evita;
	/**
	 * Contains unique identification of this particular Evita session. The id is not currently used, but may be
	 * propagated into the logs in the future.
	 */
	private final UUID id = UUIDUtil.randomUUID();
	/**
	 * Contains information passed at the time session was created that defines its behaviour
	 */
	private final SessionTraits sessionTraits;
	/**
	 * Contains reference to the callback that needs to be called one catalog contents are changed (i.e. transaction
	 * is committed).
	 */
	private final UnaryOperator<CatalogContract> updatedCatalogCallback;
	/**
	 * Reference, that allows to access transaction object.
	 */
	private final AtomicReference<Transaction> transactionAccessor = new AtomicReference<>();
	/**
	 * Callback that will be called when session is closed.
	 */
	private final EvitaSessionTerminationCallback terminationCallback;
	/**
	 * Reflection lookup is used to speed up reflection operation by memoizing the results for examined classes.
	 */
	private final ReflectionLookup reflectionLookup;
	/**
	 * Contains reference to the catalog to query / update.
	 */
	private final AtomicReference<CatalogContract> catalog;
	/**
	 * Contains reference to the proxy factory that is used to create proxies for the entities.
	 */
	@Getter private final ProxyFactory proxyFactory;
	/**
	 * Flag that is se to TRUE when Evita. is ready to serve application calls.
	 * Aim of this flag is to refuse any calls after {@link #close()} method has been called.
	 */
	private boolean active = true;
	/**
	 * Timestamp of the last session activity (call).
	 */
	private long lastCall = System.currentTimeMillis();

	/**
	 * Method creates implicit filtering constraint that contains price filtering constraints for price in case
	 * the `entityMutation` contains mutations related to prices and the require constraint require fetching prices
	 * {@link PriceContentMode#RESPECTING_FILTER}.
	 */
	@Nullable
	private static FilterConstraint createQueryForFetchAfterUpsert(@Nonnull EntityMutation entityMutation, @Nullable EntityContentRequire[] require) {
		final boolean shouldPricesRespectFilter = require != null && Arrays.stream(require)
			.filter(it -> it instanceof PriceContent)
			.map(it -> ((PriceContent) it).getFetchMode() == PriceContentMode.RESPECTING_FILTER)
			.findFirst()
			.orElse(false);
		final Set<PriceKey> usedPriceKeys;
		if (shouldPricesRespectFilter) {
			usedPriceKeys = entityMutation.getLocalMutations()
				.stream()
				.filter(it -> it instanceof PriceMutation)
				.map(it -> ((PriceMutation) it).getPriceKey())
				.collect(Collectors.toSet());
		} else {
			usedPriceKeys = Collections.emptySet();
		}
		return usedPriceKeys.isEmpty() ?
			null :
			and(
				priceInPriceLists(usedPriceKeys.stream().map(PriceKey::priceList).distinct().toArray(String[]::new)),
				of(usedPriceKeys.stream().map(PriceKey::currency).distinct().toArray(Currency[]::new))
					.filter(it -> it.length == 1)
					.map(it -> priceInCurrency(it[0]))
					.orElse(null)
			);
	}

	/**
	 * Returns an entity type from the given entity or throws an unified exception.
	 */
	@Nonnull
	private static <T extends Serializable> String getEntityTypeFromEntity(@Nonnull T partiallyLoadedEntity, @Nonnull ReflectionLookup reflectionLookup) {
		final String entityType;
		if (partiallyLoadedEntity instanceof EntityClassifier entityClassifier) {
			entityType = entityClassifier.getType();
		} else if (partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy) {
			entityType = sealedEntityProxy.entity().getType();
		} else {
			entityType = extractEntityTypeFromClass(partiallyLoadedEntity.getClass(), reflectionLookup)
				.orElseThrow(() -> new EvitaInvalidUsageException(
					"Unsupported entity type `" + partiallyLoadedEntity.getClass() + "`! The class doesn't implement EntityClassifier nor represents a SealedEntityProxy!",
					"Unsupported entity type!"
				));
		}
		return entityType;
	}

	EvitaSession(
		@Nonnull Evita evita,
		@Nonnull Catalog catalog,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nullable EvitaSessionTerminationCallback terminationCallback,
		@Nonnull SessionTraits sessionTraits
	) {
		this.evita = evita;
		this.catalog = new AtomicReference<>(catalog);
		this.reflectionLookup = reflectionLookup;
		this.proxyFactory = catalog.getProxyFactory();
		this.updatedCatalogCallback = null;
		this.sessionTraits = sessionTraits;
		this.terminationCallback = terminationCallback;
	}

	EvitaSession(
		@Nonnull Evita evita,
		@Nonnull Catalog catalog,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nullable EvitaSessionTerminationCallback terminationCallback,
		@Nonnull UnaryOperator<CatalogContract> updatedCatalogCallback,
		@Nonnull SessionTraits sessionTraits
	) {
		this.evita = evita;
		this.catalog = new AtomicReference<>(catalog);
		this.reflectionLookup = reflectionLookup;
		this.proxyFactory = catalog.getProxyFactory();
		this.updatedCatalogCallback = updatedCatalogCallback;
		this.sessionTraits = sessionTraits;
		this.terminationCallback = terminationCallback;
	}

	@Nonnull
	@Override
	public UUID getId() {
		return id;
	}

	@Nonnull
	@Override
	public SealedCatalogSchema getCatalogSchema() {
		assertActive();
		return getCatalog().getSchema();
	}

	@Nonnull
	@Override
	public CatalogState getCatalogState() {
		return getCatalog().getCatalogState();
	}

	@Override
	public boolean isActive() {
		return active;
	}

	@Override
	public boolean goLiveAndClose() {
		final CatalogContract theCatalog = getCatalog();
		Assert.isTrue(!theCatalog.supportsTransaction(), "Catalog went live already and is currently in transactional mode!");
		if (theCatalog.goLive()) {
			close();
			return true;
		}
		return false;
	}

	@Override
	public void close() {
		if (active) {
			// set itself inactive to avoid future calls
			active = false;
			// flush changes if we're not in transactional mode
			final CatalogContract theCatalog = catalog.get();
			if (theCatalog.getCatalogState() == CatalogState.WARMING_UP) {
				Assert.isPremiseValid(
					transactionAccessor.get() == null,
					"In warming-up mode no transaction is expected to be opened!"
				);
				// this should be always true - corrupted catalog would have thrown exception on getting catalog state
				if (theCatalog instanceof Catalog theCatalogToFlush) {
					theCatalogToFlush.flush();
				}
			} else {
				// close transaction if present
				ofNullable(transactionAccessor.get())
					.ifPresent(Transaction::close);
			}

			// then apply termination callbacks
			ofNullable(terminationCallback)
				.ifPresent(it -> it.onTermination(this));
		}
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder defineEntitySchema(@Nonnull String entityType) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().createCollectionForEntity(entityType, session);
			return collection.getSchema().openForWrite();
		});
	}

	@Nonnull
	@Override
	public SealedEntitySchema defineEntitySchemaFromModelClass(@Nonnull Class<?> modelClass) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final ClassSchemaAnalyzer classSchemaAnalyzer = new ClassSchemaAnalyzer(modelClass, reflectionLookup);
			final AnalysisResult analysisResult = classSchemaAnalyzer.analyze(this);
			updateCatalogSchema(analysisResult.mutations());
			return getEntitySchemaOrThrow(analysisResult.entityType());
		});
	}

	@Nonnull
	@Override
	public SealedEntitySchema defineEntitySchemaFromModelClass(@Nonnull Class<?> modelClass, @Nonnull SchemaPostProcessor postProcessor) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final ClassSchemaAnalyzer classSchemaAnalyzer = new ClassSchemaAnalyzer(modelClass, reflectionLookup, postProcessor);
			final AnalysisResult analysisResult = classSchemaAnalyzer.analyze(this);
			if (postProcessor instanceof SchemaPostProcessorCapturingResult capturingResult) {
				capturingResult.captureResult(analysisResult.mutations());
			}
			updateCatalogSchema(analysisResult.mutations());
			return getEntitySchemaOrThrow(analysisResult.entityType());
		});
	}

	@Override
	@Nonnull
	public Optional<SealedEntitySchema> getEntitySchema(@Nonnull String entityType) {
		assertActive();
		final Optional<EntityCollectionContract> collection = getCatalog().getCollectionForEntity(entityType);
		return collection.map(EntityCollectionContract::getSchema);
	}

	@Nonnull
	@Override
	public Optional<SealedEntitySchema> getEntitySchema(@Nonnull Class<?> modelClass) throws EntityClassInvalidException {
		return getEntitySchema(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass))
		);
	}

	@Nonnull
	@Override
	public SealedEntitySchema getEntitySchemaOrThrow(@Nonnull String entityType) {
		assertActive();
		return getCatalog().getCollectionForEntityOrThrowException(entityType).getSchema();
	}

	@Nonnull
	@Override
	public SealedEntitySchema getEntitySchemaOrThrow(@Nonnull Class<?> modelClass) throws CollectionNotFoundException, EntityClassInvalidException {
		return getEntitySchemaOrThrow(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass))
		);
	}

	@Override
	@Nonnull
	public Set<String> getAllEntityTypes() {
		return getCatalog().getEntityTypes();
	}

	@Nonnull
	@Override
	public <S extends Serializable> Optional<S> queryOne(@Nonnull Query query, @Nonnull Class<S> expectedType) throws UnexpectedResultException, UnexpectedResultCountException, InstanceTerminatedException {
		return queryOne(
			new EvitaRequest(
				query.normalizeQuery(),
				OffsetDateTime.now(),
				expectedType,
				extractEntityTypeFromClass(expectedType, reflectionLookup).orElse(null),
				this::createEntityProxy
			)
		);
	}

	@Nonnull
	@Override
	public <S extends Serializable> List<S> queryList(@Nonnull Query query, @Nonnull Class<S> expectedType)
		throws UnexpectedResultException, InstanceTerminatedException {
		return query(query, expectedType).getRecordData();
	}

	@Nonnull
	@Override
	public <S extends Serializable, T extends EvitaResponse<S>> T query(@Nonnull Query query, @Nonnull Class<S> expectedType) throws UnexpectedResultException, InstanceTerminatedException {
		final EvitaRequest request = new EvitaRequest(
			query.normalizeQuery(),
			OffsetDateTime.now(),
			expectedType,
			extractEntityTypeFromClass(expectedType, reflectionLookup).orElse(null),
			this::createEntityProxy
		);

		return query(request);
	}

	@Override
	public <T> T execute(@Nonnull Function<EvitaSessionContract, T> logic) {
		return executeInTransactionIfPossible(logic);
	}

	@Override
	public void execute(@Nonnull Consumer<EvitaSessionContract> logic) {
		executeInTransactionIfPossible(
			evitaSessionContract -> {
				logic.accept(evitaSessionContract);
				return null;
			}
		);
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		assertActive();
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityType),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			EntityReference.class,
			null,
			this::createEntityProxy
		);
		final EntityCollectionContract entityCollection = getCatalog().getCollectionForEntityOrThrowException(entityType);
		return entityCollection.getEntity(
			primaryKey,
			evitaRequest,
			this
		);
	}

	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> getEntity(@Nonnull Class<T> expectedType, int primaryKey, EntityContentRequire... require) {
		assertActive();
		final String entityType = extractEntityTypeFromClass(expectedType, reflectionLookup)
			.orElseThrow(() -> new CollectionNotFoundException(expectedType));
		final EntityCollectionContract entityCollection = getCatalog().getCollectionForEntityOrThrowException(entityType);
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityType),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			expectedType,
			entityType,
			this::createEntityProxy
		);
		return entityCollection.getEntity(
			primaryKey,
			evitaRequest,
			this
		).map(it -> this.createEntityProxy(expectedType, it));
	}

	@Nonnull
	@Override
	public <T extends Serializable> T enrichEntity(@Nonnull T partiallyLoadedEntity, EntityContentRequire... require) {
		assertActive();
		final String entityType = getEntityTypeFromEntity(partiallyLoadedEntity, reflectionLookup);
		final EntityCollectionContract entityCollection = getCatalog().getCollectionForEntityOrThrowException(entityType);
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityType),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			EntityReference.class,
			entityType,
			this::createEntityProxy
		);
		if (partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy) {
			//noinspection unchecked
			return (T) this.createEntityProxy(
				sealedEntityProxy.getProxyClass(),
				entityCollection.enrichEntity(
					sealedEntityProxy.entity(),
					evitaRequest,
					this
				)
			);
		} else {
			Assert.isTrue(partiallyLoadedEntity instanceof EntityDecorator, "Expected entity decorator in the input.");
			final EntityDecorator entityDecorator = (EntityDecorator) partiallyLoadedEntity;
			//noinspection unchecked
			return (T) entityCollection.enrichEntity(
				entityDecorator,
				evitaRequest,
				this
			);
		}
	}

	@Nonnull
	@Override
	public <T extends Serializable> T enrichOrLimitEntity(@Nonnull T partiallyLoadedEntity, EntityContentRequire... require) {
		assertActive();
		final String entityType = getEntityTypeFromEntity(partiallyLoadedEntity, reflectionLookup);
		final EntityCollectionContract entityCollection = getCatalog().getCollectionForEntity(entityType)
			.orElseThrow(() -> new CollectionNotFoundException(entityType));
		final EvitaRequest evitaRequest = new EvitaRequest(
			Query.query(
				collection(entityType),
				require(
					entityFetch(require)
				)
			),
			OffsetDateTime.now(),
			partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy ?
				sealedEntityProxy.getProxyClass() : partiallyLoadedEntity.getClass(),
			entityType,
			this::createEntityProxy
		);
		if (partiallyLoadedEntity instanceof SealedEntityProxy sealedEntityProxy) {
			//noinspection unchecked
			return (T) this.createEntityProxy(
				sealedEntityProxy.getProxyClass(),
				entityCollection.limitEntity(
					entityCollection.enrichEntity(
						sealedEntityProxy.entity(),
						evitaRequest,
						this
					),
					evitaRequest, this
				)
			);
		} else {
			Assert.isTrue(partiallyLoadedEntity instanceof EntityDecorator, "Expected entity decorator in the input.");
			final EntityDecorator entityDecorator = (EntityDecorator) partiallyLoadedEntity;
			//noinspection unchecked
			return (T) entityCollection.limitEntity(
				entityCollection.enrichEntity(entityDecorator, evitaRequest, this),
				evitaRequest, this
			);
		}
	}

	@Override
	public int updateCatalogSchema(@Nonnull LocalCatalogSchemaMutation... schemaMutation) throws SchemaAlteringException {
		assertActive();
		if (ArrayUtils.isEmpty(schemaMutation)) {
			return 0;
		}
		return executeInTransactionIfPossible(session -> {
			getCatalog().updateSchema(session, schemaMutation);
			return getCatalogSchema().getVersion();
		});
	}

	@Nonnull
	@Override
	public SealedCatalogSchema updateAndFetchCatalogSchema(@Nonnull LocalCatalogSchemaMutation... schemaMutation) throws SchemaAlteringException {
		assertActive();
		if (ArrayUtils.isEmpty(schemaMutation)) {
			return getCatalogSchema();
		}
		return executeInTransactionIfPossible(session -> {
			getCatalog().updateSchema(session, schemaMutation);
			return getCatalogSchema();
		});
	}

	@Override
	public int updateEntitySchema(@Nonnull ModifyEntitySchemaMutation schemaMutation) throws SchemaAlteringException {
		return updateAndFetchEntitySchema(schemaMutation).version();
	}

	@Nonnull
	@Override
	public SealedEntitySchema updateAndFetchEntitySchema(@Nonnull ModifyEntitySchemaMutation schemaMutation) throws SchemaAlteringException {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final String entityType = schemaMutation.getEntityType();
			final EntityCollectionContract entityCollection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			if (ArrayUtils.isEmpty(schemaMutation.getSchemaMutations())) {
				return entityCollection.getSchema();
			}
			entityCollection.updateSchema(getCatalogSchema(), schemaMutation);
			return entityCollection.getSchema();
		});
	}

	@Override
	public boolean deleteCollection(@Nonnull String entityType) {
		assertActive();
		return executeInTransactionIfPossible(session -> getCatalog().deleteCollectionOfEntity(entityType, session));
	}

	@Override
	public boolean deleteCollection(@Nonnull Class<?> modelClass) throws EntityClassInvalidException {
		return deleteCollection(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass))
		);
	}

	@Override
	public boolean renameCollection(@Nonnull String entityType, @Nonnull String newName) {
		assertActive();
		return executeInTransactionIfPossible(session -> getCatalog().renameCollectionOfEntity(entityType, newName, session));
	}

	@Override
	public boolean replaceCollection(@Nonnull String entityTypeToBeReplaced, @Nonnull String entityTypeToBeReplacedWith) {
		assertActive();
		return executeInTransactionIfPossible(session -> getCatalog().replaceCollectionOfEntity(entityTypeToBeReplaced, entityTypeToBeReplacedWith, session));
	}

	@Override
	public int getEntityCollectionSize(@Nonnull String entityType) {
		assertActive();
		return getCatalog().getCollectionForEntityOrThrowException(entityType).size();
	}

	@Nonnull
	@Override
	public EntityBuilder createNewEntity(@Nonnull String entityType) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			return collection.createNewEntity();
		});
	}

	@Nonnull
	@Override
	public <S extends Serializable> S createNewEntity(@Nonnull Class<S> expectedType) {
		return proxyFactory.createEntityProxy(
			expectedType,
			createNewEntity(
				extractEntityTypeFromClass(expectedType, reflectionLookup)
					.orElseThrow(() -> new CollectionNotFoundException(expectedType))
			),
			getEntitySchemaIndex()
		);
	}

	@Nonnull
	@Override
	public EntityBuilder createNewEntity(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			return collection.createNewEntity(primaryKey);
		});
	}

	@Nonnull
	@Override
	public <S extends Serializable> S createNewEntity(@Nonnull Class<S> expectedType, int primaryKey) {
		return proxyFactory.createEntityProxy(
			expectedType,
			createNewEntity(
				extractEntityTypeFromClass(expectedType, reflectionLookup)
					.orElseThrow(() -> new CollectionNotFoundException(expectedType)),
				primaryKey
			),
			getEntitySchemaIndex()
		);
	}

	@Nonnull
	@Override
	public <S extends Serializable> EntityReference upsertEntity(@Nonnull S customEntity) {
		if (customEntity instanceof SealedEntityProxy sealedEntityProxy) {
			return sealedEntityProxy.getEntityBuilderWithCallback()
				.map(entityBuilderWithCallback -> {
					final EntityReference entityReference = upsertEntity(entityBuilderWithCallback.builder());
					entityBuilderWithCallback.updateEntityReference(entityReference);
					return entityReference;
				})
				.orElseGet(() -> {
					// no modification occurred, we can return the reference to the original entity
					// the `toInstance` method should be cost-free in this case, as no modifications occurred
					final EntityContract entity = sealedEntityProxy.entity();
					return new EntityReference(entity.getType(), entity.getPrimaryKey());
				});
		} else if (customEntity instanceof InstanceEditor<?> ie) {
			return ie.toMutation()
				.map(this::upsertEntity)
				.orElseGet(() -> {
					// no modification occurred, we can return the reference to the original entity
					// the `toInstance` method should be cost-free in this case, as no modifications occurred
					final EntityContract entity = (EntityContract) ie.toInstance();
					return new EntityReference(entity.getType(), entity.getPrimaryKey());
				});
		} else {
			throw new EvitaInvalidUsageException(
				"Method `upsertEntity` expects an instance of InstanceEditor, " +
					"yet the provided instance is of type `" + customEntity.getClass() + "` doesn't implement it!",
				"Invalid usage of method `upsertEntity`!"
			);
		}
	}

	@Nonnull
	@Override
	public <S extends Serializable> List<EntityReference> upsertEntityDeeply(@Nonnull S customEntity) {
		if (customEntity instanceof SealedEntityReferenceProxy sealedEntityReferenceProxy) {
			return Stream.concat(
					// we need first to store the referenced entities (deep wise)
					sealedEntityReferenceProxy.getReferencedEntityBuildersWithCallback()
						.map(entityBuilderWithCallback -> {
							final EntityReference entityReference = upsertEntity(entityBuilderWithCallback.builder());
							entityBuilderWithCallback.updateEntityReference(entityReference);
							return entityReference;
						}),
					// and then the reference itself
					sealedEntityReferenceProxy
						.getReferenceBuilderIfPresent()
						.stream()
						.map(it -> {
								final EntityClassifier entityClassifier = sealedEntityReferenceProxy.getEntityClassifier();
								final EntityUpsertMutation entityUpsertMutation = new EntityUpsertMutation(
									entityClassifier.getType(),
									entityClassifier.getPrimaryKey(),
									EntityExistence.MUST_EXIST,
									it.buildChangeSet().collect(Collectors.toList())
								);
								final EntityReference entityReference = this.upsertEntity(entityUpsertMutation);
								sealedEntityReferenceProxy.notifyBuilderUpserted();
								return entityReference;
							}
						)
				)
				.toList();
		} else if (customEntity instanceof SealedEntityProxy sealedEntityProxy) {
			return Stream.concat(
					// we need first to store the referenced entities (deep wise)
					sealedEntityProxy.getReferencedEntityBuildersWithCallback(),
					// then the entity itself
					sealedEntityProxy.getEntityBuilderWithCallback().stream()
				)
				.map(entityBuilderWithCallback -> {
					final EntityReference entityReference = upsertEntity(entityBuilderWithCallback.builder());
					entityBuilderWithCallback.updateEntityReference(entityReference);
					return entityReference;
				})
				.toList();
		} else if (customEntity instanceof InstanceEditor<?> ie) {
			return ie.toMutation()
				.map(this::upsertEntity)
				.map(List::of)
				.orElse(Collections.emptyList());
		} else {
			throw new EvitaInvalidUsageException(
				"Method `upsertEntity` expects an instance of InstanceEditor, " +
					"yet the provided instance is of type `" + customEntity.getClass() + "` doesn't implement it!",
				"Invalid usage of method `upsertEntity`!"
			);
		}
	}

	@Nonnull
	@Override
	public EntityReference upsertEntity(@Nonnull EntityMutation entityMutation) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityMutation.getEntityType(), session);
			return collection.upsertEntity(entityMutation);
		});
	}

	@Nonnull
	@Override
	public SealedEntity upsertAndFetchEntity(@Nonnull EntityBuilder entityBuilder, EntityContentRequire... require) {
		return entityBuilder.toMutation()
			.map(it -> upsertAndFetchEntity(it, require))
			.orElseGet(
				() -> (SealedEntity) getEntity(entityBuilder.getType(), entityBuilder.getPrimaryKey(), require)
					.orElseThrow(() -> new EvitaInvalidUsageException("Entity `" + entityBuilder.getType() + "` with id `" + entityBuilder.getPrimaryKey() + "` doesn't exist!"))
			);
	}

	@Nonnull
	@Override
	public SealedEntity upsertAndFetchEntity(@Nonnull EntityMutation entityMutation, EntityContentRequire... require) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityMutation.getEntityType(), session);
			return collection.upsertAndFetchEntity(
				entityMutation,
				new EvitaRequest(
					Query.query(
						collection(entityMutation.getEntityType()),
						filterBy(
							createQueryForFetchAfterUpsert(entityMutation, require)
						),
						require(
							entityFetch(require)
						)
					),
					OffsetDateTime.now(),
					SealedEntity.class,
					null,
					this::createEntityProxy
				),
				this
			);
		});
	}

	@Override
	public boolean deleteEntity(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			return collection.deleteEntity(primaryKey);
		});
	}

	@Override
	public boolean deleteEntity(@Nonnull Class<?> modelClass, int primaryKey) throws EntityClassInvalidException {
		return deleteEntity(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			primaryKey
		);
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> deleteEntity(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		return deleteEntityInternal(entityType, SealedEntity.class, primaryKey, require);
	}

	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> deleteEntity(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require) throws EntityClassInvalidException {
		return deleteEntityInternal(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			modelClass, primaryKey, require
		);
	}

	@Override
	public int deleteEntityAndItsHierarchy(@Nonnull String entityType, int primaryKey) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			Assert.isTrue(
				collection.getSchema().isWithHierarchy(),
				"Entity type " + entityType + " doesn't represent a hierarchical entity!"
			);
			return collection.deleteEntityAndItsHierarchy(primaryKey, this);
		});
	}

	@Nonnull
	@Override
	public DeletedHierarchy<SealedEntity> deleteEntityAndItsHierarchy(@Nonnull String entityType, int primaryKey, EntityContentRequire... require) {
		return deleteEntityAndItsHierarchyInternal(entityType, SealedEntity.class, primaryKey, require);
	}

	@Nonnull
	@Override
	public <T extends Serializable> DeletedHierarchy<T> deleteEntityAndItsHierarchy(@Nonnull Class<T> modelClass, int primaryKey, EntityContentRequire... require) throws EvitaInvalidUsageException, EntityClassInvalidException {
		return deleteEntityAndItsHierarchyInternal(
			extractEntityTypeFromClass(modelClass, reflectionLookup)
				.orElseThrow(() -> new CollectionNotFoundException(modelClass)),
			modelClass, primaryKey, require
		);
	}

	@Override
	public int deleteEntities(@Nonnull Query query) {
		assertActive();
		final EvitaRequest request = new EvitaRequest(
			query.normalizeQuery(),
			OffsetDateTime.now(),
			EntityReference.class,
			null,
			this::createEntityProxy
		);
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog()
				.getOrCreateCollectionForEntity(request.getEntityTypeOrThrowException("entities to be deleted"), session);
			return collection.deleteEntities(request, session);
		});
	}

	@Nonnull
	@Override
	public SealedEntity[] deleteSealedEntitiesAndReturnBodies(@Nonnull Query query) {
		assertActive();
		final EvitaRequest request = new EvitaRequest(
			query.normalizeQuery(),
			OffsetDateTime.now(),
			SealedEntity.class,
			null,
			this::createEntityProxy
		);
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog()
				.getOrCreateCollectionForEntity(request.getEntityTypeOrThrowException("entities to be deleted"), session);
			return collection.deleteEntitiesAndReturnThem(request, session);
		});
	}

	@Override
	public long openTransaction() {
		assertTransactionIsNotOpened();
		final Transaction transaction = createAndInitTransaction();
		this.transactionAccessor.set(transaction);
		return transaction.getId();
	}

	@Nonnull
	@Override
	public Optional<Long> getOpenedTransactionId() {
		return ofNullable(transactionAccessor.get())
			.filter(it -> !it.isClosed())
			.map(Transaction::getId);
	}

	@Override
	public void closeTransaction() {
		transactionAccessor.getAndUpdate(transaction -> {
			Assert.isPremiseValid(transaction != null, "Transaction unexpectedly not present!");
			transaction.close();
			return null;
		});
	}

	@Override
	public boolean isRollbackOnly() {
		return ofNullable(transactionAccessor.get())
			.map(Transaction::isRollbackOnly)
			.orElse(false);
	}

	@Override
	public void setRollbackOnly() {
		getOpenedTransaction()
			.ifPresentOrElse(
				Transaction::setRollbackOnly,
				() -> {
					throw new UnexpectedTransactionStateException("No transaction has been opened!");
				}
			);
	}

	@Override
	public boolean isReadOnly() {
		return !sessionTraits.isReadWrite();
	}

	@Override
	public boolean isBinaryFormat() {
		return sessionTraits.isBinary();
	}

	@Override
	public boolean isDryRun() {
		return sessionTraits.isDryRun();
	}

	@Override
	public long getInactivityDurationInSeconds() {
		return (System.currentTimeMillis() - lastCall) / 1000;
	}

	@Nonnull
	@Override
	public <S extends Serializable> Optional<S> queryOne(@Nonnull EvitaRequest evitaRequest)
		throws UnexpectedResultException, UnexpectedResultCountException, InstanceTerminatedException {

		assertActive();
		final String entityType = evitaRequest.getEntityType();
		final EvitaResponse<S> response;
		final CatalogContract theCatalog = getCatalog();
		if (entityType == null) {
			response = theCatalog.getEntities(evitaRequest, this);
		} else {
			final EntityCollectionContract entityCollection = theCatalog.getCollectionForEntityOrThrowException(entityType);
			response = entityCollection.getEntities(evitaRequest, this);
		}

		final List<S> recordData = response.getRecordData();
		if (recordData.isEmpty()) {
			return empty();
		} else if (recordData.size() > 1) {
			throw new UnexpectedResultCountException(recordData.size());
		} else {
			return of(recordData.get(0));
		}
	}

	@Nonnull
	@Override
	public <S extends Serializable> List<S> queryList(@Nonnull EvitaRequest evitaRequest) throws UnexpectedResultException, InstanceTerminatedException {
		//noinspection unchecked
		return (List<S>) query(evitaRequest).getRecordData();
	}

	@Nonnull
	@Override
	public <S extends Serializable, T extends EvitaResponse<S>> T query(@Nonnull EvitaRequest evitaRequest) throws UnexpectedResultException, InstanceTerminatedException {
		assertActive();
		final String entityType = evitaRequest.getEntityType();
		final T response;
		final CatalogContract theCatalog = getCatalog();
		if (entityType == null) {
			response = theCatalog.getEntities(evitaRequest, this);
		} else {
			final EntityCollectionContract entityCollection = theCatalog.getCollectionForEntityOrThrowException(entityType);
			response = entityCollection.getEntities(evitaRequest, this);
		}
		return response;
	}

	@Nonnull
	public Optional<Transaction> getOpenedTransaction() {
		return ofNullable(transactionAccessor.get())
			.filter(it -> !it.isClosed());
	}

	/**
	 * Method will exchange reference to the current catalog. This method is called everytime the catalog contents are
	 * modified (committed) so that the changes can be safely visible and usable in all other active sessions. The new
	 * contract must not affect the reference held in currently open {@link Transaction} - since the transactions are
	 * guaranteed to have SNAPSHOT isolation. But newly opened transaction in the session will see the changes from
	 * other concurrent transactions.
	 */
	void updateCatalogReference(@Nonnull CatalogContract catalog) {
		this.catalog.set(catalog);
	}

	/**
	 * Delegates call to internal {@link #proxyFactory#createEntityProxy(Class, SealedEntity, Map)}.
	 *
	 * @param contract     contract of the entity to be created
	 * @param sealedEntity sealed entity to be used as a source of data
	 * @param <S>          type of the entity
	 * @return new instance of the entity proxy
	 */
	@Nonnull
	private <S> S createEntityProxy(@Nonnull Class<S> contract, @Nonnull SealedEntity sealedEntity) {
		return this.proxyFactory.createEntityProxy(contract, sealedEntity, getEntitySchemaIndex());
	}

	/**
	 * Returns map with current {@link Catalog#getSchema() catalog} {@link EntitySchemaContract entity schema} instances
	 * indexed by their {@link EntitySchemaContract#getName() name}.
	 *
	 * @return map with current {@link Catalog#getSchema() catalog} {@link EntitySchemaContract entity schema} instances
	 */
	@Nonnull
	private Map<String, EntitySchemaContract> getEntitySchemaIndex() {
		return catalog.get().getEntitySchemaIndex();
	}

	/**
	 * Internal implementation for deleting the entity.
	 *
	 * @see #deleteEntity(String, int)
	 * @see #deleteEntity(Class, int)
	 */
	@Nonnull
	private <T> Optional<T> deleteEntityInternal(
		@Nonnull String entityType,
		@Nonnull Class<T> expectedType,
		int primaryKey,
		EntityContentRequire... require
	) {
		assertActive();
		return executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			//noinspection unchecked
			return (Optional<T>) collection.deleteEntity(
				new EvitaRequest(
					Query.query(
						collection(entityType),
						filterBy(entityPrimaryKeyInSet(primaryKey)),
						require(
							entityFetch(require)
						)
					),
					OffsetDateTime.now(),
					expectedType,
					extractEntityTypeFromClass(expectedType, reflectionLookup)
						.orElse(null),
					this::createEntityProxy
				),
				this
			);
		});
	}

	/**
	 * Internal implementation for deleting the entity with hierarchy.
	 *
	 * @see #deleteEntityAndItsHierarchy(String, int, EntityContentRequire...)
	 * @see #deleteEntityAndItsHierarchy(Class, int, EntityContentRequire...)
	 */
	@Nonnull
	private <T> DeletedHierarchy<T> deleteEntityAndItsHierarchyInternal(
		@Nonnull String entityType,
		@Nonnull Class<T> expectedType,
		int primaryKey,
		EntityContentRequire... require
	) {
		assertActive();
		//noinspection unchecked
		return (DeletedHierarchy<T>) executeInTransactionIfPossible(session -> {
			final EntityCollectionContract collection = getCatalog().getOrCreateCollectionForEntity(entityType, session);
			Assert.isTrue(
				collection.getSchema().isWithHierarchy(),
				"Entity type " + entityType + " doesn't represent a hierarchical entity!"
			);
			return collection.deleteEntityAndItsHierarchy(
				new EvitaRequest(
					Query.query(
						collection(entityType),
						filterBy(entityPrimaryKeyInSet(primaryKey)),
						require(
							entityFetch(require)
						)
					),
					OffsetDateTime.now(),
					expectedType,
					extractEntityTypeFromClass(expectedType, reflectionLookup)
						.orElse(null),
					this::createEntityProxy
				),
				this
			);
		});
	}

	/**
	 * Returns the catalog instance this session refers to.
	 */
	private CatalogContract getCatalog() {
		return catalog.get();
	}

	/**
	 * Creates new transaction a wraps it into carrier object.
	 */
	private Transaction createTransaction() {
		Assert.isTrue(!isReadOnly(), "Evita session is read only!");
		final Transaction transaction = new Transaction(
			getCatalog(),
			(currentCatalog, updatedCatalog) -> {
				final CatalogContract previousCatalog = this.catalog.compareAndExchange(currentCatalog, updatedCatalog);
				Assert.isPremiseValid(
					previousCatalog == currentCatalog, "The expected catalog instance didn't match!"
				);
				ofNullable(this.updatedCatalogCallback)
					.ifPresent(it -> this.catalog.set(it.apply(updatedCatalog)));
			}
		);
		// when the session is marked as "dry run" we never commit the transaction but always roll-back
		if (sessionTraits.isDryRun()) {
			transaction.setRollbackOnly();
		}
		return transaction;
	}

	/**
	 * Verifies this instance is still active.
	 */
	private void assertActive() {
		if (active) {
			this.lastCall = System.currentTimeMillis();
		} else {
			throw new InstanceTerminatedException("session");
		}
	}

	/**
	 * Verifies this instance is still active.
	 */
	private void assertTransactionIsNotOpened() {
		if (transactionAccessor.get() != null) {
			throw new UnexpectedTransactionStateException("Transaction has been already opened. Evita doesn't support nested transactions!");
		}
	}

	/**
	 * Initializes transaction reference.
	 */
	@Nonnull
	private Transaction createAndInitTransaction() {
		if (!sessionTraits.isReadWrite()) {
			throw new TransactionNotSupportedException("Transaction cannot be opened in read only session!");
		}
		if (!getCatalog().supportsTransaction()) {
			throw new TransactionNotSupportedException("Catalog " + getCatalog().getName() + " doesn't support transactions yet. Call `goLiveAndClose()` method first!");
		}
		final Transaction tx = createTransaction();
		transactionAccessor.getAndUpdate(transaction -> {
			Assert.isPremiseValid(transaction == null, "Transaction unexpectedly found!");
			return tx;
		});
		return tx;
	}

	/**
	 * Executes passed lambda in existing transaction or throws exception.
	 *
	 * @throws UnexpectedTransactionStateException if transaction is not open
	 */
	private <T> T executeInTransactionIfPossible(Function<EvitaSessionContract, T> logic) {
		if (transactionAccessor.get() == null && getCatalog().supportsTransaction()) {
			try (final Transaction newTransaction = createTransaction()) {
				transactionAccessor.set(newTransaction);
				return Transaction.executeInTransactionIfProvided(
					newTransaction,
					() -> logic.apply(this)
				);
			} catch (Throwable ex) {
				ofNullable(transactionAccessor.get())
					.ifPresent(Transaction::setRollbackOnly);
				throw ex;
			} finally {
				transactionAccessor.set(null);
			}
		} else {
			// the transaction might already exist
			try {
				return Transaction.executeInTransactionIfProvided(
					transactionAccessor.get(),
					() -> logic.apply(this)
				);
			} catch (Throwable ex) {
				ofNullable(transactionAccessor.get())
					.ifPresent(Transaction::setRollbackOnly);
				throw ex;
			}
		}
	}

}
