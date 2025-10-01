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

package io.evitadb.api.proxy.impl;

import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.ReferencedEntityBuilderProvider;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.SealedEntityProxy.EntityBuilderWithCallback;
import io.evitadb.api.proxy.SealedEntityProxy.Propagation;
import io.evitadb.api.proxy.SealedEntityProxy.ProxyType;
import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.proxy.impl.ProxycianFactory.ProxyEntityCacheKey;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import one.edee.oss.proxycian.recipe.ProxyRecipe;
import one.edee.oss.proxycian.trait.localDataStore.LocalDataStore;
import one.edee.oss.proxycian.trait.localDataStore.LocalDataStoreProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Abstract parent class for all entity proxy states. It contains all the common fields and methods.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@EqualsAndHashCode(of = {"entity", "proxyClass"})
abstract class AbstractEntityProxyState implements
	Serializable,
	LocalDataStoreProvider,
	EntityClassifier,
	ReferencedEntityBuilderProvider
{
	@Serial private static final long serialVersionUID = -6935480192166155348L;
	/**
	 * The sealed entity that is being proxied.
	 */
	@Nonnull protected final EntityContract entity;
	/**
	 * The class of the proxy was built upon.
	 */
	@Nonnull protected final Class<?> proxyClass;
	/**
	 * The map of recipes provided from outside that are used to build the proxy.
	 */
	@Nonnull protected final Map<ProxyEntityCacheKey, ProxyRecipe> recipes;
	/**
	 * The index of referenced entity schemas by their type at the time the proxy was built.
	 */
	@Getter
	@Nonnull protected final Map<String, EntitySchemaContract> referencedEntitySchemas;
	/**
	 * The merged map all recipes - the ones provided from outside and the ones created with default configuration on
	 * the fly during the proxy building.
	 */
	@Nonnull protected transient Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes;
	/**
	 * The reflection lookup instance used to access the reflection data in a memoized fashion.
	 */
	@Nonnull protected transient ReflectionLookup reflectionLookup;
	/**
	 * Cache for the already generated proxies so that the same method call returns the same instance.
	 */
	@Nonnull protected transient Map<ProxyInstanceCacheKey, ProxyWithUpsertCallback> generatedProxyObjects;
	/**
	 * The local data store that is used to store the data that are not part of the sealed entity.
	 */
	private Map<String, Serializable> localDataStore;

	protected AbstractEntityProxyState(
		@Nonnull EntityContract entity,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull Class<?> proxyClass,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		this.entity = entity;
		this.referencedEntitySchemas = referencedEntitySchemas;
		this.proxyClass = proxyClass;
		this.recipes = recipes;
		this.collectedRecipes = collectedRecipes;
		this.reflectionLookup = reflectionLookup;
		this.generatedProxyObjects = new ConcurrentHashMap<>();
	}

	protected AbstractEntityProxyState(
		@Nonnull EntityContract entity,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull Class<?> proxyClass,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Map<ProxyInstanceCacheKey, ProxyWithUpsertCallback> externalProxyObjectsCache
	) {
		this.entity = entity;
		this.referencedEntitySchemas = referencedEntitySchemas;
		this.proxyClass = proxyClass;
		this.recipes = recipes;
		this.collectedRecipes = collectedRecipes;
		this.reflectionLookup = reflectionLookup;
		this.generatedProxyObjects = externalProxyObjectsCache;
	}

	/**
	 * Returns the sealed entity that is being proxied.
	 */
	@Nonnull
	public EntityContract getEntity() {
		return this.entity;
	}

	/**
	 * Returns the primary key of the sealed entity that is being proxied or null.
	 */
	@Override
	@Nullable
	public Integer getPrimaryKey() {
		return this.entity.getPrimaryKey();
	}

	/**
	 * Returns the primary key of the sealed entity that is being proxied or throws exception.
	 */
	@Override
	public int getPrimaryKeyOrThrowException() {
		return EntityClassifier.super.getPrimaryKeyOrThrowException();
	}

	/**
	 * Returns the class of the proxy was built upon.
	 */
	@Nonnull
	public Class<?> getProxyClass() {
		return this.proxyClass;
	}

	/**
	 * Returns the map of recipes provided from outside that are used to build the proxy.
	 */
	@Nonnull
	public ReflectionLookup getReflectionLookup() {
		return this.reflectionLookup;
	}

	/**
	 * Returns entity schema from the {@link #entity}.
	 */
	@Nonnull
	public EntitySchemaContract getEntitySchema() {
		return this.entity.getSchema();
	}

	/**
	 * Returns entity schema for the passed entity type.
	 *
	 * @param entityType name of the entity type
	 * @return entity schema for the passed entity type or empty result
	 */
	@Nonnull
	public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
		return ofNullable(this.referencedEntitySchemas.get(entityType));
	}

	/**
	 * Returns entity schema for the passed entity type.
	 *
	 * @param entityType name of the entity type
	 * @return entity schema for the passed entity type or empty result
	 */
	@Nonnull
	public EntitySchemaContract getEntitySchemaOrThrow(@Nonnull String entityType) throws CollectionNotFoundException {
		return ofNullable(this.referencedEntitySchemas.get(entityType))
			.orElseThrow(() -> new CollectionNotFoundException(entityType));
	}

	/**
	 * Returns existing or creates new memory Map data store for data written by {@link LocalDataStore}.
	 */
	@Override
	public Map<String, Serializable> getOrCreateLocalDataStore() {
		if (this.localDataStore == null) {
			this.localDataStore = new ConcurrentHashMap<>();
		}
		return this.localDataStore;
	}

	/**
	 * Returns existing memory Map data store for data written by {@link LocalDataStore}. Might return null, if there
	 * are no data written yet.
	 */
	@Override
	public Map<String, Serializable> getLocalDataStoreIfPresent() {
		return this.localDataStore;
	}

	/**
	 * Creates new or returns existing proxy for an external entity that is accessible via reference of current entity.
	 * Possible referenced entities are {@link ProxyType#PARENT} or {@link ProxyType#REFERENCED_ENTITY}.
	 *
	 * This method should be used if the referenced entity is not known (doesn't exists) but its entity primary key is
	 * known upfront.
	 *
	 * @param entitySchema schema of the entity to be created
	 * @param expectedType contract that the proxy should implement
	 * @param proxyType    type of the proxy (either {@link ProxyType#PARENT} or {@link ProxyType#REFERENCED_ENTITY})
	 * @param primaryKey   primary key of the referenced entity
	 * @param <T>          type of contract that the proxy should implement
	 * @return proxy instance of sealed entity
	 * @throws EntityClassInvalidException if the proxy contract is not valid
	 */
	@Nonnull
	public <T> T getOrCreateReferencedEntityProxy(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Class<T> expectedType,
		@Nonnull ProxyType proxyType,
		int primaryKey
	) throws EntityClassInvalidException {
		final Supplier<ProxyWithUpsertCallback> instanceSupplier = () -> new ProxyWithUpsertCallback(
			ProxycianFactory.createEntityProxy(
				expectedType, this.recipes, this.collectedRecipes,
				new InitialEntityBuilder(entitySchema, primaryKey),
				this.referencedEntitySchemas,
				getReflectionLookup()
			)
		);
		return this.generatedProxyObjects.computeIfAbsent(
				new ProxyInstanceCacheKey(entitySchema.getName(), primaryKey, proxyType),
				key -> instanceSupplier.get()
			)
			.proxy(
				expectedType,
				instanceSupplier
			);
	}

	/**
	 * Creates new or returns existing proxy for an external entity that is accessible via reference of current entity.
	 * Possible referenced entities are {@link ProxyType#PARENT} or {@link ProxyType#REFERENCED_ENTITY}.
	 *
	 * This method should be used if the referenced entity is known.
	 *
	 * @param expectedType            contract that the proxy should implement
	 * @param entity                  sealed entity to create proxy for
	 * @param proxyType               type of the proxy (either {@link ProxyType#PARENT} or {@link ProxyType#REFERENCED_ENTITY})
	 * @param <T>                     type of contract that the proxy should implement
	 * @return proxy instance of sealed entity
	 */
	@Nonnull
	public <T> T getOrCreateReferencedEntityProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull EntityContract entity,
		@Nonnull ProxyType proxyType
	) {
		final Supplier<ProxyWithUpsertCallback> instanceSupplier = () -> new ProxyWithUpsertCallback(
			createReferencedEntityProxy(expectedType, entity)
		);
		return this.generatedProxyObjects.computeIfAbsent(
				new ProxyInstanceCacheKey(entity.getType(), entity.getPrimaryKeyOrThrowException(), proxyType),
				key -> instanceSupplier.get()
			)
			.proxy(expectedType, instanceSupplier);
	}

	/**
	 * Creates proxy instance of entity builder that implements `expectedType` contract and creates new entity builder.
	 *
	 * This method should be used if the referenced entity is not known (doesn't exists), and its primary key is also
	 * not known (the referenced entity needs to be persisted first).
	 *
	 * @param entitySchema schema of the entity to be created
	 * @param expectedType contract that the proxy should implement
	 * @param callback     callback that will be called when the entity is upserted
	 * @param <T>          type of contract that the proxy should implement
	 * @return proxy instance of sealed entity
	 * @throws EntityClassInvalidException if the proxy contract is not valid
	 */
	@Nonnull
	public <T> T createReferencedEntityProxyWithCallback(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Class<T> expectedType,
		@Nonnull ProxyType proxyType,
		@Nonnull Consumer<EntityReference> callback
	) throws EntityClassInvalidException {
		final Supplier<ProxyWithUpsertCallback> instanceSupplier = () -> new ProxyWithUpsertCallback(
			ProxycianFactory.createEntityProxy(
				expectedType, this.recipes, this.collectedRecipes,
				new InitialEntityBuilder(entitySchema),
				this.referencedEntitySchemas,
				getReflectionLookup()
			),
			callback
		);
		return this.generatedProxyObjects.computeIfAbsent(
				new ProxyInstanceCacheKey(entitySchema.getName(), Integer.MIN_VALUE, proxyType),
				key -> instanceSupplier.get()
			)
			.proxy(expectedType, instanceSupplier);
	}

	/**
	 * Creates new proxy for an entity that is accessible via reference of current entity.
	 *
	 * @param expectedType            contract that the proxy should implement
	 * @param entity                  sealed entity to create proxy for
	 * @param <T>                     type of contract that the proxy should implement
	 * @return proxy instance of sealed entity
	 */
	@Nonnull
	public <T> T createReferencedEntityProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull EntityContract entity
	) {
		return ProxycianFactory.createEntityProxy(
			expectedType, this.recipes, this.collectedRecipes, entity, this.referencedEntitySchemas, getReflectionLookup()
		);
	}

	/**
	 * Creates new proxy for a reference.
	 *
	 * This method should be used if the reference is known.
	 *
	 * @param expectedType            contract that the proxy should implement
	 * @param entity                  sealed entity to create proxy for
	 * @param reference               reference instance to create proxy for
	 * @param <T>                     type of contract that the proxy should implement
	 * @return proxy instance of sealed entity
	 */
	@Nonnull
	public <T> T createNewReferenceProxy(
		@Nonnull Class<?> mainType,
		@Nonnull Class<T> expectedType,
		@Nonnull EntityContract entity,
		@Nonnull ReferenceContract reference,
		@Nonnull Map<String, AttributeSchemaContract> referenceAttributeTypes
	) {
		return ProxycianFactory.createEntityReferenceProxy(
			mainType, expectedType, this.recipes, this.collectedRecipes,
			entity, this::getPrimaryKey,
			this.referencedEntitySchemas,
			reference, referenceAttributeTypes,
			getReflectionLookup(),
			this.generatedProxyObjects
		);
	}

	/**
	 * Creates new proxy for a reference.
	 *
	 * This method should be used if the referenced entity is not known (doesn't exists), and its primary key is also
	 * not known (the referenced entity needs to be persisted first).
	 *
	 * @param expectedType            contract that the proxy should implement
	 * @param reference               reference instance to create proxy for
	 * @param <T>                     type of contract that the proxy should implement
	 * @return proxy instance of sealed entity
	 */
	@Nonnull
	public <T> T getOrCreateEntityReferenceProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull ReferenceContract reference,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes
	) {
		final Supplier<ProxyWithUpsertCallback> instanceSupplier = () -> new ProxyWithUpsertCallback(
			ProxycianFactory.createEntityReferenceProxy(
				this.getProxyClass(), expectedType, this.recipes, this.collectedRecipes,
				this.entity, this::getPrimaryKey,
				this.referencedEntitySchemas, reference, attributeTypes, getReflectionLookup(),
				this.generatedProxyObjects
			)
		);
		return this.generatedProxyObjects.computeIfAbsent(
				new ProxyInstanceCacheKey(reference.getReferenceName(), reference.getReferencedPrimaryKey(), ProxyType.REFERENCE),
				key -> instanceSupplier.get()
			)
			.proxy(
				expectedType,
				instanceSupplier
			);
	}

	/**
	 * Creates new proxy for an entity wrapped by this proxy using passed entity but sharing outer contract and recipes.
	 *
	 * @param entity entity to be wrapped
	 * @param <T>    type of contract that the proxy should implement
	 * @return proxy instance of sealed entity
	 */
	@Nonnull
	public <T> T cloneProxy(@Nonnull EntityContract entity) {
		// noinspection unchecked
		return (T) ProxycianFactory.createEntityProxy(
			getProxyClass(), this.recipes, this.collectedRecipes, entity, this.referencedEntitySchemas, getReflectionLookup(),
			stateClone -> stateClone.registerReferencedInstancesObjects(this.generatedProxyObjects)
		);
	}

	/**
	 * Returns the registered proxy, matching the registration context in creation methods method.
	 *
	 * @param referencedEntityType the {@link EntitySchemaContract#getName()} of the referenced entity type
	 * @param referencedPrimaryKey the {@link EntityContract#getPrimaryKey()} of the referenced entity
	 * @param expectedType         the expected class type of the proxy
	 * @param proxyType            set of logical types to be searched for the proxy instance
	 */
	@Nonnull
	public <T> Optional<T> getReferencedEntityObjectIfPresent(
		@Nonnull String referencedEntityType,
		int referencedPrimaryKey,
		@Nonnull Class<T> expectedType,
		@Nonnull ProxyType proxyType
	) {
		return ofNullable(
			this.generatedProxyObjects.get(
				new ProxyInstanceCacheKey(referencedEntityType, referencedPrimaryKey, proxyType)
			)
		).flatMap(it -> it.proxyIfPossible(expectedType));
	}

	/**
	 * Method registers created proxy object that was created by this proxy instance and relates to referenced objects
	 * accessed via it. We need to provide exactly the same instances of those objects when the same method is called
	 * or the logically same object is retrieved via different method with compatible type.
	 *
	 * @param referencedEntityType the {@link EntitySchemaContract#getName()} of the referenced entity type
	 * @param referencedPrimaryKey the {@link EntityContract#getPrimaryKey()} of the referenced entity
	 * @param proxy                the proxy object
	 * @param logicalType          logical type of the proxy object
	 */
	public void registerReferencedEntityObject(
		@Nonnull String referencedEntityType,
		int referencedPrimaryKey,
		@Nonnull Object proxy,
		@Nonnull ProxyType logicalType
	) {
		this.generatedProxyObjects.put(
			new ProxyInstanceCacheKey(referencedEntityType, referencedPrimaryKey, logicalType),
			new ProxyWithUpsertCallback(proxy)
		);
	}

	/**
	 * Method unregisters created proxy object that was created by this proxy instance and relates to referenced objects
	 * accessed via it.
	 *
	 * @param referencedEntityType the {@link EntitySchemaContract#getName()} of the referenced entity type
	 * @param referencedPrimaryKey the {@link EntityContract#getPrimaryKey()} of the referenced entity
	 * @param logicalType          logical type of the proxy object
	 */
	public void unregisterReferencedEntityObject(
		@Nonnull String referencedEntityType,
		int referencedPrimaryKey,
		@Nonnull ProxyType logicalType
	) {
		this.generatedProxyObjects.remove(
			new ProxyInstanceCacheKey(referencedEntityType, referencedPrimaryKey, logicalType)
		);
	}

	@Override
	@Nonnull
	public Stream<EntityBuilderWithCallback> getReferencedEntityBuildersWithCallback(@Nonnull Propagation propagation) {
		return this.generatedProxyObjects.entrySet().stream()
			.filter(
				it -> it.getKey().proxyType() == ProxyType.PARENT ||
					it.getKey().proxyType() == ProxyType.REFERENCED_ENTITY
			)
			.flatMap(
				it -> Stream.concat(
					// we need first store the referenced entities of referenced entity (depth wise)
					it.getValue().getSealedEntityProxies()
						.flatMap(proxy -> proxy.getReferencedEntityBuildersWithCallback(propagation)),
					// and then the referenced entity itself
					it.getValue().getSealedEntityProxies()
						.map(proxy -> proxy.getEntityBuilderWithCallback(propagation))
						.filter(Optional::isPresent)
						.map(Optional::get)
						.map(
							mutation -> {
								final EntityBuilder theBuilder = mutation.builder();
								final Consumer<EntityReference> mutationCallback = mutation.upsertCallback();
								final Consumer<EntityReference> externalCallback = it.getValue().callback();
								return new EntityBuilderWithCallback(
									theBuilder,
									mutationCallback == null ?
										externalCallback :
										entityReference1 -> {
											mutation.entityUpserted(entityReference1);
											externalCallback.accept(entityReference1);
										}
								);
							}
						)
				)
			);
	}

	/**
	 * Method propagates references to the referenced instances to the {@link #generatedProxyObjects} cache of this state.
	 *
	 * @param generatedProxyObjects from previous state
	 */
	void registerReferencedInstancesObjects(@Nonnull Map<ProxyInstanceCacheKey, ProxyWithUpsertCallback> generatedProxyObjects) {
		this.generatedProxyObjects.putAll(generatedProxyObjects);
	}

	/**
	 * Method is called during Java (de)serialization process. It is used to initialize the transient fields.
	 */
	@Serial
	private void readObject(@Nonnull ObjectInputStream ois) throws ClassNotFoundException, IOException {
		ois.defaultReadObject();
		this.reflectionLookup = ReflectionLookup.NO_CACHE_INSTANCE;
		this.collectedRecipes = new ConcurrentHashMap<>(this.recipes);
		this.generatedProxyObjects = new ConcurrentHashMap<>();
	}

	/**
	 * Cache key for generated proxies.
	 *
	 * @param identifier entity of reference name
	 * @param primaryKey primary key of the object being wrapped
	 * @param proxyType  the type of the proxy (entity or reference)
	 */
	protected record ProxyInstanceCacheKey(
		@Nonnull String identifier,
		int primaryKey,
		@Nonnull ProxyType proxyType
	) {

	}

	/**
	 * Record contains reference to the proxy instance and the callback lambda, that needs to be invoked when the proxy
	 * mutations are persisted via. {@link io.evitadb.api.EvitaSessionContract#upsertEntity(EntityMutation)}. If there
	 * is no need to invoke any callback, the {@link #DO_NOTHING_CALLBACK} is used.
	 */
	protected static class ProxyWithUpsertCallback {
		private static final Consumer<EntityReference> DO_NOTHING_CALLBACK = entityReference -> {
		};
		@Nonnull private final List<Object> proxies;
		@Nonnull private Consumer<EntityReference> callback;

		public ProxyWithUpsertCallback(@Nonnull Object proxy, @Nonnull Consumer<EntityReference> callback) {
			this.proxies = new LinkedList<>();
			this.proxies.add(proxy);
			this.callback = callback;
		}

		public ProxyWithUpsertCallback(@Nonnull Object proxy) {
			this(proxy, DO_NOTHING_CALLBACK);
		}

		@Nonnull
		public Consumer<EntityReference> callback() {
			return this.callback;
		}

		@SuppressWarnings("unchecked")
		@Nonnull
		public <T> T proxy(@Nonnull Class<T> classToImplement, @Nonnull Supplier<ProxyWithUpsertCallback> newInstanceSupplier) {
			for (Object proxy : this.proxies) {
				if (classToImplement.isInstance(proxy)) {
					return (T) proxy;
				}
			}
			final ProxyWithUpsertCallback newInstance = newInstanceSupplier.get();
			final T proxy = newInstance.proxy(
				classToImplement,
				() -> {
					throw new GenericEvitaInternalError("Should not happen - the supplier should provide correct type!");
				}
			);
			this.proxies.add(proxy);
			if (newInstance.callback != DO_NOTHING_CALLBACK) {
				Assert.isTrue(this.callback == DO_NOTHING_CALLBACK, "Cannot merge two callbacks");
				this.callback = newInstance.callback;
			}
			return proxy;
		}

		@SuppressWarnings("unchecked")
		@Nonnull
		public <T> Optional<? extends T> proxyIfPossible(@Nonnull Class<T> classToImplement) {
			for (Object proxy : this.proxies) {
				if (classToImplement.isInstance(proxy)) {
					return of((T) proxy);
				}
			}
			return empty();
		}

		@Nonnull
		public Collection<Object> proxies(@Nonnull Propagation propagation) {
			if (propagation == Propagation.SHALLOW) {
				return this.proxies.isEmpty() ? List.of() : List.of(this.proxies.get(0));
			} else {
				return this.proxies;
			}
		}

		@Nonnull
		public Stream<SealedEntityProxy> getSealedEntityProxies() {
			return this.proxies.stream().filter(SealedEntityProxy.class::isInstance).map(SealedEntityProxy.class::cast);
		}

		@Nonnull
		public Stream<SealedEntityReferenceProxy> getSealedEntityReferenceProxies(@Nonnull Propagation propagation) {
			return switch (propagation) {
				case DEEP -> this.proxies.stream().filter(SealedEntityReferenceProxy.class::isInstance).map(SealedEntityReferenceProxy.class::cast);
				case SHALLOW -> this.proxies.stream().filter(SealedEntityReferenceProxy.class::isInstance).map(SealedEntityReferenceProxy.class::cast).findFirst().stream();
			};
		}

	}

}
