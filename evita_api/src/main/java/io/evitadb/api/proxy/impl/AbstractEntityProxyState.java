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

package io.evitadb.api.proxy.impl;

import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.SealedEntityProxy.ProxyType;
import io.evitadb.api.proxy.impl.ProxycianFactory.ProxyEntityCacheKey;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInternalError;
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
public abstract class AbstractEntityProxyState implements Serializable, LocalDataStoreProvider {
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
		return entity;
	}

	/**
	 * Returns the primary key of the sealed entity that is being proxied.
	 */
	@Nullable
	public Integer getPrimaryKey() {
		return entity.getPrimaryKey();
	}

	/**
	 * Returns the class of the proxy was built upon.
	 */
	@Nonnull
	public Class<?> getProxyClass() {
		return proxyClass;
	}

	/**
	 * Returns the map of recipes provided from outside that are used to build the proxy.
	 */
	@Nonnull
	public ReflectionLookup getReflectionLookup() {
		return reflectionLookup;
	}

	/**
	 * Returns entity schema from the {@link #entity}.
	 */
	@Nonnull
	public EntitySchemaContract getEntitySchema() {
		return entity.getSchema();
	}

	/**
	 * Returns entity schema for the passed entity type.
	 *
	 * @param entityType name of the entity type
	 * @return entity schema for the passed entity type or empty result
	 */
	@Nonnull
	public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
		return ofNullable(referencedEntitySchemas.get(entityType));
	}

	/**
	 * Returns entity schema for the passed entity type.
	 *
	 * @param entityType name of the entity type
	 * @return entity schema for the passed entity type or empty result
	 */
	@Nonnull
	public EntitySchemaContract getEntitySchemaOrThrow(@Nonnull String entityType) throws CollectionNotFoundException {
		return ofNullable(referencedEntitySchemas.get(entityType))
			.orElseThrow(() -> new CollectionNotFoundException(entityType));
	}

	/**
	 * Returns existing or creates new memory Map data store for data written by {@link LocalDataStore}.
	 */
	@Override
	public Map<String, Serializable> getOrCreateLocalDataStore() {
		if (localDataStore == null) {
			localDataStore = new ConcurrentHashMap<>();
		}
		return localDataStore;
	}

	/**
	 * Returns existing memory Map data store for data written by {@link LocalDataStore}. Might return null, if there
	 * are no data written yet.
	 */
	@Override
	public Map<String, Serializable> getLocalDataStoreIfPresent() {
		return localDataStore;
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
				expectedType, recipes, collectedRecipes,
				new InitialEntityBuilder(entitySchema, primaryKey),
				referencedEntitySchemas,
				getReflectionLookup()
			)
		);
		return generatedProxyObjects.computeIfAbsent(
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
		return generatedProxyObjects.computeIfAbsent(
				new ProxyInstanceCacheKey(entity.getType(), entity.getPrimaryKey(), proxyType),
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
				expectedType, recipes, collectedRecipes,
				new InitialEntityBuilder(entitySchema),
				referencedEntitySchemas,
				getReflectionLookup()
			),
			callback
		);
		return generatedProxyObjects.computeIfAbsent(
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
			expectedType, recipes, collectedRecipes, entity, referencedEntitySchemas, getReflectionLookup()
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
		@Nonnull Class<T> expectedType,
		@Nonnull EntityContract entity,
		@Nonnull ReferenceContract reference
	) {
		return ProxycianFactory.createEntityReferenceProxy(
			expectedType, recipes, collectedRecipes,
			entity, this::getPrimaryKey,
			referencedEntitySchemas, reference, getReflectionLookup(),
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
		@Nonnull ReferenceContract reference
	) {
		final Supplier<ProxyWithUpsertCallback> instanceSupplier = () -> new ProxyWithUpsertCallback(
			ProxycianFactory.createEntityReferenceProxy(
				expectedType, recipes, collectedRecipes,
				entity, this::getPrimaryKey,
				referencedEntitySchemas, reference, getReflectionLookup(),
				this.generatedProxyObjects
			)
		);
		return generatedProxyObjects.computeIfAbsent(
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
		//noinspection DataFlowIssue,unchecked
		return (T) ProxycianFactory.createEntityProxy(
			getProxyClass(), recipes, collectedRecipes, entity, referencedEntitySchemas, getReflectionLookup(),
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
			generatedProxyObjects.get(
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
		generatedProxyObjects.put(
			new ProxyInstanceCacheKey(referencedEntityType, referencedPrimaryKey, logicalType),
			new ProxyWithUpsertCallback(proxy)
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
		this.collectedRecipes = new ConcurrentHashMap<>(recipes);
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
			return callback;
		}

		@SuppressWarnings("unchecked")
		@Nonnull
		public <T> T proxy(@Nonnull Class<T> classToImplement, @Nonnull Supplier<ProxyWithUpsertCallback> newInstanceSupplier) {
			for (Object proxy : proxies) {
				if (classToImplement.isInstance(proxy)) {
					return (T) proxy;
				}
			}
			final ProxyWithUpsertCallback newInstance = newInstanceSupplier.get();
			final T proxy = newInstance.proxy(
				classToImplement,
				() -> {
					throw new EvitaInternalError("Should not happen - the supplier should provide correct type!");
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
			for (Object proxy : proxies) {
				if (classToImplement.isInstance(proxy)) {
					return of((T) proxy);
				}
			}
			return empty();
		}

		@Nonnull
		public Stream<SealedEntityProxy> getSealedEntityProxies() {
			return proxies.stream().filter(SealedEntityProxy.class::isInstance).map(SealedEntityProxy.class::cast);
		}
	}

}
