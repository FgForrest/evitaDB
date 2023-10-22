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
import io.evitadb.api.proxy.impl.ProxycianFactory.ProxyEntityCacheKey;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.ExistingEntityBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.ReflectionLookup;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import one.edee.oss.proxycian.recipe.ProxyRecipe;
import one.edee.oss.proxycian.trait.localDataStore.LocalDataStoreProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
	 * TODO JNO - document me
	 */
	@Nullable protected EntityBuilder entityBuilder;
	/**
	 * Cache for the already generated proxies so that the same method call returns the same instance.
	 */
	@Nonnull private transient Map<ProxyInstanceCacheKey, Object> generatedProxyObjects = new ConcurrentHashMap<>();
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
	public EntityBuilder getEntityBuilder() {
		if (entityBuilder == null) {
			if (entity instanceof EntityDecorator entityDecorator) {
				entityBuilder = new ExistingEntityBuilder(entityDecorator);
			} else if (entity instanceof Entity theEntity) {
				entityBuilder = new ExistingEntityBuilder(theEntity);
			} else if (entity instanceof EntityBuilder theBuilder) {
				entityBuilder = theBuilder;
			} else {
				throw new EvitaInternalError("Unexpected entity type: " + entity.getClass().getName());
			}
		}
		return entityBuilder;
	}

	@Nonnull
	public Collection<EntityMutation> getMutations() {
		return this.entityBuilder == null ?
			Collections.emptyList() :
			this.entityBuilder.toMutation()
				.map(Collections::singletonList)
				.orElse(Collections.emptyList());
	}

	@Nonnull
	@Override
	public <T> T createEntityProxy(@Nonnull Class<T> expectedType, @Nonnull EntityContract entity) {
		//noinspection unchecked
		return (T) generatedProxyObjects.computeIfAbsent(
			new ProxyInstanceCacheKey(expectedType, entity.getType(), entity.getPrimaryKey(), ProxyType.ENTITY),
			key -> ProxycianFactory.createEntityProxy(
				key.proxyClass(), recipes, collectedRecipes, entity, getReflectionLookup()
			)
		);
	}

	@Nonnull
	@Override
	public <T> T createEntityBuilderProxy(@Nonnull Class<T> expectedType, @Nonnull EntityContract entity) throws EntityClassInvalidException {
		//noinspection unchecked
		return (T) generatedProxyObjects.computeIfAbsent(
			new ProxyInstanceCacheKey(expectedType, entity.getType(), entity.getPrimaryKey(), ProxyType.ENTITY_BUILDER),
			key -> ProxycianFactory.createEntityBuilderProxy(
				key.proxyClass(), recipes, collectedRecipes, entity, getReflectionLookup()
			)
		);
	}

	@Nonnull
	public <T> T createEntityReferenceProxy(@Nonnull Class<T> expectedType, @Nonnull EntityContract entity, @Nonnull ReferenceContract reference) {
		//noinspection unchecked
		return (T) generatedProxyObjects.computeIfAbsent(
			new ProxyInstanceCacheKey(expectedType, reference.getReferenceName(), reference.getReferencedPrimaryKey(), ProxyType.REFERENCE),
			key -> ProxycianFactory.createEntityReferenceProxy(
				key.proxyClass(), recipes, collectedRecipes, entity, reference, getReflectionLookup()
			)
		);
	}

	@Nonnull
	@Override
	public <T> T createEntityBuilderReferenceProxy(@Nonnull Class<T> expectedType, @Nonnull EntityContract entity, @Nonnull ReferenceContract reference) throws EntityClassInvalidException {
		//noinspection unchecked
		return (T) generatedProxyObjects.computeIfAbsent(
			new ProxyInstanceCacheKey(expectedType, reference.getReferenceName(), reference.getReferencedPrimaryKey(), ProxyType.REFERENCE_BUILDER),
			key -> ProxycianFactory.createEntityBuilderReferenceProxy(
				key.proxyClass(), recipes, collectedRecipes, entity, reference, getReflectionLookup()
			)
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
	 * Types of generated proxies.
	 */
	private enum ProxyType {
		ENTITY,
		ENTITY_BUILDER,
		REFERENCE,
		REFERENCE_BUILDER,
	}

	/**
	 * Cache key for generated proxies.
	 *
	 * @param proxyClass class of the proxy contract
	 * @param identifier entity of reference name
	 * @param primaryKey primary key of the object being wrapped
	 * @param proxyType  the type of the proxy (entity or reference)
	 */
	protected record ProxyInstanceCacheKey(
		@Nonnull Class<?> proxyClass,
		@Nonnull String identifier,
		int primaryKey,
		@Nonnull ProxyType proxyType
	) {
	}

}
