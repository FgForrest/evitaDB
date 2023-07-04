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

import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.proxy.impl.ProxycianFactory.ProxyRecipeCacheKey;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.utils.ReflectionLookup;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Delegate;
import one.edee.oss.proxycian.recipe.ProxyRecipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.evitadb.api.proxy.impl.ProxycianFactory.DEFAULT_ENTITY_RECIPE;

/**
 * TODO JNO - document me
 * TODO JNO - extract shared logick to abstract class
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@EqualsAndHashCode(of = {"context", "sealedEntity", "reference", "proxyClass"})
public class SealedEntityReferenceProxyState implements EntityClassifier, SealedEntityReferenceProxy, Serializable, AttributesContract {
	@Serial private static final long serialVersionUID = 586508293856395550L;
	@Delegate(types = {AttributesContract.class})
	@Getter @Nonnull private final SealedEntity sealedEntity;
	@Getter @Nonnull private final ReferenceContract reference;
	@Getter @Nonnull private final EvitaRequestContext context;
	@Getter @Nonnull private final Class<?> proxyClass;
	@Nonnull private final Map<ProxyRecipeCacheKey, ProxyRecipe> recipes;
	@Nonnull private transient Map<ProxyRecipeCacheKey, ProxyRecipe> collectedRecipes;
	@Nonnull private transient ReflectionLookup reflectionLookup;

	public SealedEntityReferenceProxyState(
		@Nonnull EvitaRequestContext context,
		@Nonnull SealedEntity sealedEntity,
		@Nonnull ReferenceContract reference,
		@Nonnull Class<?> proxyClass,
		@Nonnull Map<ProxyRecipeCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyRecipeCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		this.context = context;
		this.sealedEntity = sealedEntity;
		this.reference = reference;
		this.proxyClass = proxyClass;
		this.recipes = recipes;
		this.collectedRecipes = collectedRecipes;
		this.reflectionLookup = reflectionLookup;
	}

	@Nonnull
	public ReflectionLookup getReflectionLookup() {
		return reflectionLookup;
	}

	@Nonnull
	public EntitySchemaContract getEntitySchema() {
		return sealedEntity.getSchema();
	}

	@Nullable
	public ReferenceSchemaContract getReferenceSchema() {
		return reference.getReferenceSchema().orElseThrow();
	}

	@Nonnull
	@Override
	public String getType() {
		return sealedEntity.getType();
	}

	@Nonnull
	@Override
	public Integer getPrimaryKey() {
		return sealedEntity.getPrimaryKey();
	}

	@Override
	public String toString() {
		return reference.toString();
	}

	public <T extends EntityClassifier> T wrapTo(@Nonnull Class<T> entityContract, @Nonnull SealedEntity sealedEntity) {
		return ProxycianFactory.createProxy(
			entityContract, recipes, collectedRecipes, sealedEntity, context, getReflectionLookup(),
			cacheKey -> collectedRecipes.computeIfAbsent(cacheKey, DEFAULT_ENTITY_RECIPE)
		);
	}

	@Serial
	private void readObject(@Nonnull ObjectInputStream ois) throws ClassNotFoundException, IOException {
		ois.defaultReadObject();
		this.reflectionLookup = ReflectionLookup.NO_CACHE_INSTANCE;
		this.collectedRecipes = new ConcurrentHashMap<>(recipes);
	}
}
