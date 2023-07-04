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

import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.impl.ProxycianFactory.ProxyRecipeCacheKey;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.utils.ReflectionLookup;
import lombok.experimental.Delegate;
import one.edee.oss.proxycian.recipe.ProxyRecipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SealedEntityProxyState
	extends AbstractEntityProxyState
	implements EntityClassifier, SealedEntityProxy, AttributesContract {
	@Serial private static final long serialVersionUID = 586508293856395550L;

	public SealedEntityProxyState(
		@Nonnull EvitaRequestContext context,
		@Nonnull SealedEntity sealedEntity,
		@Nonnull Class<?> proxyClass,
		@Nonnull Map<ProxyRecipeCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyRecipeCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		super(sealedEntity, context, proxyClass, recipes, collectedRecipes, reflectionLookup);
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

	@Delegate(types = {AttributesContract.class})
	@Nonnull
	@Override
	public SealedEntity getSealedEntity() {
		return super.getSealedEntity();
	}

	@Nullable
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Class<T> returnType) {
		return sealedEntity.getAssociatedData(associatedDataName, returnType, getReflectionLookup());
	}

	@Nullable
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nonnull Class<T> returnType) {
		return sealedEntity.getAssociatedData(associatedDataName, locale, returnType, getReflectionLookup());
	}

	@Override
	public String toString() {
		return sealedEntity.toString();
	}

}
