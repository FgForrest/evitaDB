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
import io.evitadb.api.proxy.impl.ProxycianFactory.ProxyEntityCacheKey;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.utils.ReflectionLookup;
import lombok.EqualsAndHashCode;
import one.edee.oss.proxycian.recipe.ProxyRecipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Map;

/**
 * Proxy state for proxies that wrap sealed entity reference.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@EqualsAndHashCode(of = {"reference"}, callSuper = true)
public class SealedEntityReferenceProxyState
	extends AbstractEntityProxyState
	implements EntityClassifier, SealedEntityReferenceProxy {
	@Serial private static final long serialVersionUID = 586508293856395550L;
	@Nonnull private final ReferenceContract reference;

	public SealedEntityReferenceProxyState(
		@Nonnull SealedEntity sealedEntity,
		@Nonnull ReferenceContract reference,
		@Nonnull Class<?> proxyClass,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		super(sealedEntity, proxyClass, recipes, collectedRecipes, reflectionLookup);
		this.reference = reference;
	}

	/**
	 * Method returns reference schema of the wrapped sealed entity reference.
	 */
	@Nullable
	public ReferenceSchemaContract getReferenceSchema() {
		return reference.getReferenceSchema().orElseThrow();
	}

	@Override
	@Nonnull
	public ReferenceContract getReference() {
		return reference;
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

}
