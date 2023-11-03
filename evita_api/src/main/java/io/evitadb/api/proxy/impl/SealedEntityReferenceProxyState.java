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
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.structure.ExistingReferenceBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.utils.ReflectionLookup;
import lombok.EqualsAndHashCode;
import one.edee.oss.proxycian.recipe.ProxyRecipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Map;
import java.util.Optional;

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
	/**
	 * Optional reference to the {@link ReferenceBuilder} that is created on demand by calling mutation method on
	 * internally wrapped entity {@link #getReference()}.
	 */
	@Nullable protected ReferenceBuilder referenceBuilder;
	/**
	 * Wrapped sealed entity reference.
	 */
	@Nonnull private final ReferenceContract reference;

	public SealedEntityReferenceProxyState(
		@Nonnull EntityContract entity,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull ReferenceContract reference,
		@Nonnull Class<?> proxyClass,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		super(entity, referencedEntitySchemas, proxyClass, recipes, collectedRecipes, reflectionLookup);
		this.reference = reference;
	}

	/**
	 * Method returns reference schema of the wrapped sealed entity reference.
	 * @return reference schema
	 */
	@Nullable
	public ReferenceSchemaContract getReferenceSchema() {
		return reference.getReferenceSchema().orElseThrow();
	}

	@Override
	@Nonnull
	public ReferenceBuilder getReferenceBuilder() {
		if (referenceBuilder == null) {
			if (reference instanceof ReferenceBuilder theBuilder) {
				referenceBuilder = theBuilder;
			} else {
				referenceBuilder = new ExistingReferenceBuilder(
					reference, getEntitySchema()
				);
			}
		}
		return referenceBuilder;
	}

	@Nonnull
	@Override
	public Optional<ReferenceBuilder> getReferenceBuilderIfPresent() {
		return Optional.of(referenceBuilder)
			.filter(ReferenceBuilder::hasChanges);
	}

	@Override
	@Nonnull
	public ReferenceContract getReference() {
		return reference;
	}

	@Nonnull
	@Override
	public String getType() {
		return entity.getType();
	}

	@Nonnull
	@Override
	public Integer getPrimaryKey() {
		return entity.getPrimaryKey();
	}

	@Override
	public String toString() {
		return reference instanceof ReferenceBuilder rb ?
			rb.build().toString() : reference.toString();
	}
}
