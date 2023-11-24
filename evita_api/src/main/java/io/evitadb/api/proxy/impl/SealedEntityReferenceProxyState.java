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
import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.proxy.impl.ProxycianFactory.ProxyEntityCacheKey;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.ExistingReferenceBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import lombok.EqualsAndHashCode;
import one.edee.oss.proxycian.recipe.ProxyRecipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;

/**
 * Proxy state for proxies that wrap sealed entity reference.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@EqualsAndHashCode(of = {"reference"}, callSuper = true)
public class SealedEntityReferenceProxyState
	extends AbstractEntityProxyState
	implements SealedEntityReferenceProxy {
	@Serial private static final long serialVersionUID = 586508293856395550L;
	/**
	 * Supplier of the wrapped entity primary key that might be assigned later when {@link SealedEntityProxy} is persisted.
	 */
	@Nonnull private final Supplier<Integer> entityPrimaryKeySupplier;
	/**
	 * Optional reference to the {@link ReferenceBuilder} that is created on demand by calling mutation method on
	 * internally wrapped entity {@link #getReference()}.
	 */
	@Nullable protected ReferenceBuilder referenceBuilder;
	/**
	 * Wrapped sealed entity reference.
	 */
	@Nonnull private final ReferenceContract reference;
	/**
	 * Proxy class of the main entity class this reference proxy belongs to.
	 */
	@Nonnull private Class<?> entityProxyClass;

	public SealedEntityReferenceProxyState(
		@Nonnull EntityContract entity,
		@Nonnull Supplier<Integer> entityPrimaryKeySupplier,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull ReferenceContract reference,
		@Nonnull Class<?> entityProxyClass,
		@Nonnull Class<?> proxyClass,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nullable Map<ProxyInstanceCacheKey, ProxyWithUpsertCallback> entityInstanceCache
	) {
		super(entity, referencedEntitySchemas, proxyClass, recipes, collectedRecipes, reflectionLookup, entityInstanceCache);
		this.entityProxyClass = entityProxyClass;
		this.entityPrimaryKeySupplier = entityPrimaryKeySupplier;
		this.reference = reference;
	}

	@Nonnull
	@Override
	public EntityClassifier getEntityClassifier() {
		return new EntityReference(getType(), getPrimaryKey());
	}

	/**
	 * Method returns reference schema of the wrapped sealed entity reference.
	 * @return reference schema
	 */
	@Nullable
	public ReferenceSchemaContract getReferenceSchema() {
		return reference.getReferenceSchema().orElseThrow();
	}

	@Nonnull
	public ReferenceBuilder getReferenceBuilderWithMutations(@Nonnull Collection<LocalMutation<?,?>> mutations) {
		Assert.isPremiseValid(referenceBuilder == null, "Entity builder already created!");
		if (reference instanceof ReferenceBuilder) {
			Assert.isPremiseValid(referenceBuilder == null, "Entity builder already created!");
		} else {
			referenceBuilder = new ExistingReferenceBuilder(
				reference, getEntitySchema(), mutations
			);
		}
		return referenceBuilder;
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

	@Override
	public void notifyBuilderUpserted() {
		if (referenceBuilder != null) {
			this.referenceBuilder = new ExistingReferenceBuilder(
				this.referenceBuilder.build(), getEntitySchema()
			);
		}
	}

	@Nonnull
	@Override
	public Optional<ReferenceBuilder> getReferenceBuilderIfPresent() {
		return ofNullable(referenceBuilder);
	}

	@Override
	@Nonnull
	public ReferenceContract getReference() {
		return this.referenceBuilder == null ?
			this.reference : this.referenceBuilder;
	}

	@Nonnull
	@Override
	public String getType() {
		return entity.getType();
	}

	@Nullable
	@Override
	public Integer getPrimaryKey() {
		return ofNullable(entity.getPrimaryKey())
			.orElseGet(entityPrimaryKeySupplier);
	}

	/**
	 * Returns proxy class of the main entity class this reference proxy belongs to.
	 * @return proxy class of the entity proxy
	 */
	@Nonnull
	public Class<?> getEntityProxyClass() {
		return entityProxyClass;
	}

	@Override
	public String toString() {
		return reference instanceof ReferenceBuilder rb ?
			rb.build().toString() : reference.toString();
	}
}
