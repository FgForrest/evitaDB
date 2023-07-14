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

import io.evitadb.api.proxy.impl.ProxycianFactory.ProxyEntityCacheKey;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.ReflectionLookup;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import one.edee.oss.proxycian.recipe.ProxyRecipe;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.evitadb.api.proxy.impl.ProxycianFactory.DEFAULT_ENTITY_RECIPE;
import static io.evitadb.api.proxy.impl.ProxycianFactory.DEFAULT_ENTITY_REFERENCE_RECIPE;

/**
 * Abstract parent class for all entity proxy states. It contains all the common fields and methods.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@EqualsAndHashCode(of = {"sealedEntity", "proxyClass"})
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
class AbstractEntityProxyState implements Serializable {
	@Serial private static final long serialVersionUID = -6935480192166155348L;
	/**
	 * The sealed entity that is being proxied.
	 */
	@Nonnull protected final SealedEntity sealedEntity;
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
	 * Returns the sealed entity that is being proxied.
	 */
	@Nonnull
	public SealedEntity getSealedEntity() {
		return sealedEntity;
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
	 * Returns entity schema from the {@link #sealedEntity}.
	 */
	@Nonnull
	public EntitySchemaContract getEntitySchema() {
		return sealedEntity.getSchema();
	}

	/**
	 * Method allows to create a new proxy instance of the given sealed entity, using actual proxy recipe configuration.
	 */
	@Nonnull
	public <T> T createEntityProxy(@Nonnull Class<T> entityContract, @Nonnull SealedEntity sealedEntity) {
		return ProxycianFactory.createProxy(
			entityContract, recipes, collectedRecipes, sealedEntity, getReflectionLookup(),
			cacheKey -> collectedRecipes.computeIfAbsent(cacheKey, DEFAULT_ENTITY_RECIPE)
		);
	}

	/**
	 * Method allows to create a new proxy instance of the given reference, using actual proxy recipe configuration.
	 */
	@Nonnull
	public <T> T createReferenceProxy(@Nonnull Class<T> referenceContract, @Nonnull ReferenceContract reference) {
		return ProxycianFactory.createProxy(
			referenceContract, recipes, collectedRecipes, sealedEntity, reference, getReflectionLookup(),
			cacheKey -> collectedRecipes.computeIfAbsent(cacheKey, DEFAULT_ENTITY_REFERENCE_RECIPE)
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
	}

}
