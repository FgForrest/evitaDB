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

import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.proxy.ProxyReferenceFactory;
import io.evitadb.api.proxy.SealedEntityProxy.ProxyType;
import io.evitadb.api.proxy.impl.ProxycianFactory.ProxyEntityCacheKey;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.ReflectionLookup;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import one.edee.oss.proxycian.recipe.ProxyRecipe;
import one.edee.oss.proxycian.trait.localDataStore.LocalDataStoreProvider;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;

/**
 * Abstract parent class for all entity proxy states. It contains all the common fields and methods.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@EqualsAndHashCode(of = {"entity", "proxyClass"})
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AbstractEntityProxyState implements Serializable, LocalDataStoreProvider, ProxyFactory, ProxyReferenceFactory {
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
	@Nonnull protected transient Map<ProxyInstanceCacheKey, ProxyWithUpsertCallback> generatedProxyObjects = new ConcurrentHashMap<>();
	/**
	 * The local data store that is used to store the data that are not part of the sealed entity.
	 */
	private Map<String, Serializable> localDataStore;

	/**
	 * Returns the sealed entity that is being proxied.
	 */
	@Nonnull
	public EntityContract getEntity() {
		return entity;
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

	@Override
	public Map<String, Serializable> getOrCreateLocalDataStore() {
		if (localDataStore == null) {
			localDataStore = new ConcurrentHashMap<>();
		}
		return localDataStore;
	}

	@Override
	public Map<String, Serializable> getLocalDataStoreIfPresent() {
		return localDataStore;
	}

	@Nonnull
	@Override
	public <T> T createEntityProxy(@Nonnull Class<T> expectedType, @Nonnull EntityContract entity) {
		return generatedProxyObjects.computeIfAbsent(
			new ProxyInstanceCacheKey(entity.getType(), entity.getPrimaryKey(), ProxyType.ENTITY),
			key -> new ProxyWithUpsertCallback(
				createNewNonCachedEntityProxy(expectedType, entity)
			)
		)
			.proxy(expectedType)
			.orElseThrow();
	}

	@Nonnull
	public <T> T createNewNonCachedEntityProxy(@Nonnull Class<T> expectedType, @Nonnull EntityContract entity) {
		return ProxycianFactory.createEntityProxy(
			expectedType, recipes, collectedRecipes, entity, getReflectionLookup()
		);
	}

	@Nonnull
	@Override
	public <T> T createEntityBuilderProxy(@Nonnull Class<T> expectedType, @Nonnull EntityContract entity) throws EntityClassInvalidException {
		return generatedProxyObjects.computeIfAbsent(
			new ProxyInstanceCacheKey(entity.getType(), entity.getPrimaryKey(), ProxyType.ENTITY_BUILDER),
			key -> {
				final ProxyWithUpsertCallback newProxy = new ProxyWithUpsertCallback(
					ProxycianFactory.createEntityBuilderProxy(
						expectedType, recipes, collectedRecipes, entity, getReflectionLookup()
					)
				);
				registerReferencedEntityObject(
					entity.getType(),
					ofNullable(entity.getPrimaryKey()).orElse(Integer.MIN_VALUE),
					newProxy,
					ProxyType.ENTITY_BUILDER
				);
				return newProxy;
			}
		)
			.proxy(expectedType)
			.orElseThrow();
	}

	@Override
	@Nonnull
	public <T> T createEntityReferenceProxy(@Nonnull Class<T> expectedType, @Nonnull EntityContract entity, @Nonnull ReferenceContract reference) {
		return generatedProxyObjects.computeIfAbsent(
			new ProxyInstanceCacheKey(reference.getReferenceName(), reference.getReferencedPrimaryKey(), ProxyType.REFERENCE),
			key -> new ProxyWithUpsertCallback(
				ProxycianFactory.createEntityReferenceProxy(
					expectedType, recipes, collectedRecipes, entity, reference, getReflectionLookup()
				)
			)
		)
			.proxy(expectedType)
			.orElseThrow();
	}

	@Nonnull
	@Override
	public <T> T createEntityBuilderReferenceProxy(@Nonnull Class<T> expectedType, @Nonnull EntityContract entity, @Nonnull ReferenceContract reference) throws EntityClassInvalidException {
		return generatedProxyObjects.computeIfAbsent(
			new ProxyInstanceCacheKey(reference.getReferenceName(), reference.getReferencedPrimaryKey(), ProxyType.REFERENCE_BUILDER),
			key -> new ProxyWithUpsertCallback(
				ProxycianFactory.createEntityBuilderReferenceProxy(
					expectedType, recipes, collectedRecipes, entity, reference, getReflectionLookup()
				)
			)
		)
			.proxy(expectedType)
			.orElseThrow();
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
	protected record ProxyWithUpsertCallback(
		@Nonnull Object proxyOfAnyType,
		@Nonnull Consumer<EntityReference> callback
	) {

		private static final Consumer<EntityReference> DO_NOTHING_CALLBACK = entityReference -> {};

		public ProxyWithUpsertCallback(@Nonnull Object proxy) {
			this(proxy, DO_NOTHING_CALLBACK);
		}

		@SuppressWarnings("unchecked")
		@Nonnull
		public <T> Optional<T> proxy(@Nonnull Class<T> classToImplement) {
			if (classToImplement.isInstance(proxyOfAnyType)) {
				return Optional.of((T) proxyOfAnyType);
			}
			return Optional.empty();
		}

	}

}
