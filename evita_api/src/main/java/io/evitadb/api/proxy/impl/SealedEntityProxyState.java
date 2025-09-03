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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.proxy.impl.ProxycianFactory.ProxyEntityCacheKey;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.ExistingEntityBuilder;
import io.evitadb.api.requestResponse.data.structure.ExistingReferenceBuilder;
import io.evitadb.api.requestResponse.data.structure.InitialReferenceBuilder;
import io.evitadb.api.requestResponse.data.structure.InternalEntityBuilder;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ReflectionLookup;
import lombok.Setter;
import one.edee.oss.proxycian.recipe.ProxyRecipe;
import one.edee.oss.proxycian.trait.ProxyStateAccessor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;

/**
 * Proxy state for proxies that wrap sealed entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SealedEntityProxyState
	extends AbstractEntityProxyState
	implements SealedEntityProxy {
	@Serial private static final long serialVersionUID = 586508293856395550L;
	/**
	 * Optional reference to the {@link EntityBuilder} that is created on demand by calling {@link SealedEntity#openForWrite()}
	 * from internally wrapped entity {@link #entity()}.
	 */
	@Nullable protected InternalEntityBuilder entityBuilder;
	/**
	 * Optional information about the last {@link EntityReference} returned from {@link EvitaSessionContract#upsertEntity(EntityMutation)},
	 * it may contain newly assigned {@link EntityContract#getPrimaryKey()} that is not available in the wrapped entity.
	 */
	@Setter private EntityReference entityReference;

	public SealedEntityProxyState(
		@Nonnull EntityContract entity,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull Class<?> proxyClass,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		super(entity, referencedEntitySchemas, proxyClass, recipes, collectedRecipes, reflectionLookup);
	}

	@Nonnull
	@Override
	public String getType() {
		return this.entity.getType();
	}

	@Nullable
	@Override
	public Integer getPrimaryKey() {
		return ofNullable(this.entity.getPrimaryKey())
			.orElseGet(() -> ofNullable(this.entityReference)
				.map(EntityReference::getPrimaryKey)
				.orElse(null)
			);
	}

	@Nonnull
	@Override
	public EntityContract entity() {
		return entityBuilderIfPresent()
			.map(EntityContract.class::cast)
			.orElse(this.entity);
	}

	@Nonnull
	@Override
	public Optional<EntityBuilderWithCallback> getEntityBuilderWithCallback() {
		propagateReferenceMutations();
		return Optional.ofNullable(this.entityBuilder)
		               .map(
			               it -> new EntityBuilderWithCallback(
				               it,
				               entityReference -> {
					               this.entityReference = entityReference;
					               this.entityBuilder = new ExistingEntityBuilder(
						               // we can cast it here, since we know that both InitialEntityBuilder and ExistingEntityBuilder
						               // fabricate Entity instances
						               (Entity) this.entityBuilder.toInstance()
					               );
					               // we need to mark all references as upserted, since they were persisted along with the entity
					               this.generatedProxyObjects
						               .entrySet()
						               .stream()
						               .filter(goEntry -> goEntry.getKey().proxyType() == ProxyType.REFERENCE)
						               .flatMap(goEntry -> goEntry.getValue().proxies().stream())
						               .forEach(
							               refProxy -> ((SealedEntityReferenceProxyState) ((ProxyStateAccessor) refProxy).getProxyState())
								               .notifyBuilderUpserted()
						               );
				               }
			               )
		               );
	}

	@Nonnull
	public EntityBuilder getEntityBuilderWithMutations(@Nonnull Collection<LocalMutation<?, ?>> mutations) {
		Assert.isPremiseValid(this.entityBuilder == null, "Entity builder already created!");
		if (this.entity instanceof EntityDecorator entityDecorator) {
			this.entityBuilder = new ExistingEntityBuilder(entityDecorator, mutations);
		} else if (this.entity instanceof Entity theEntity) {
			this.entityBuilder = new ExistingEntityBuilder(theEntity, mutations);
		} else if (this.entity instanceof EntityBuilder) {
			throw new GenericEvitaInternalError("Entity builder already created!");
		} else {
			throw new GenericEvitaInternalError("Unexpected entity type: " + this.entity.getClass().getName());
		}
		return this.entityBuilder;
	}

	@Nonnull
	public InternalEntityBuilder entityBuilder() {
		if (this.entityBuilder == null) {
			if (this.entity instanceof EntityDecorator entityDecorator) {
				this.entityBuilder = new ExistingEntityBuilder(entityDecorator);
			} else if (this.entity instanceof Entity theEntity) {
				this.entityBuilder = new ExistingEntityBuilder(theEntity);
			} else if (this.entity instanceof InternalEntityBuilder theBuilder) {
				this.entityBuilder = theBuilder;
			} else {
				throw new GenericEvitaInternalError("Unexpected entity type: " + this.entity.getClass().getName());
			}
		}
		return this.entityBuilder;
	}

	@Nonnull
	public Optional<EntityBuilder> entityBuilderIfPresent() {
		return ofNullable(this.entityBuilder);
	}

	@Nonnull
	public <T> T createEntityReferenceProxy(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<T> expectedType,
		@Nonnull ProxyType proxyType,
		int primaryKey
	) throws EntityClassInvalidException {
		final Supplier<ProxyWithUpsertCallback> instanceSupplier = () -> {
			final InternalEntityBuilder theEntityBuilder = entityBuilder();
			final Map<String, AttributeSchemaContract> attributeTypesForReference = getAttributeTypesForReference(
				referenceSchema.getName()
			);
			return this.entity
				.getReference(referenceSchema.getName(), primaryKey)
				.filter(
					ref -> !(theEntityBuilder instanceof ExistingEntityBuilder eeb) ||
						eeb.isPresentInBaseEntity(ref)
				)
				.map(
					existingReference -> new ProxyWithUpsertCallback(
						ProxycianFactory.createEntityReferenceProxy(
							this.getProxyClass(), expectedType,
							this.recipes,
							this.collectedRecipes,
							this.entity,
							this::getPrimaryKey,
							this.referencedEntitySchemas,
							new ExistingReferenceBuilder(
								existingReference,
								entitySchema,
								attributeTypesForReference
							),
							attributeTypesForReference,
							getReflectionLookup(),
							this.generatedProxyObjects
						)
					)
				)
				.orElseGet(
					() -> new ProxyWithUpsertCallback(
						ProxycianFactory.createEntityReferenceProxy(
							this.getProxyClass(), expectedType,
							this.recipes,
							this.collectedRecipes,
							this.entity,
							this::getPrimaryKey,
							getReferencedEntitySchemas(),
							new InitialReferenceBuilder(
								entitySchema,
								referenceSchema,
								referenceSchema.getName(),
								primaryKey,
								theEntityBuilder.getNextReferenceInternalId(),
								attributeTypesForReference
							),
							attributeTypesForReference,
							getReflectionLookup(),
							this.generatedProxyObjects
						)
					)
				);
		};
		return this.generatedProxyObjects
			.computeIfAbsent(
				new ProxyInstanceCacheKey(referenceSchema.getName(), primaryKey, proxyType),
				key -> instanceSupplier.get()
			).proxy(expectedType, instanceSupplier);
	}

	/**
	 * Retrieves or initializes a map of attribute types for the specified reference schema.
	 * This method ensures that the attribute types are stored in a local data store
	 * associated with the given reference schema.
	 *
	 * @param referenceName the name of the reference schema
	 * @return a map where the keys are attribute names and the values are attribute schema contracts for the reference schema
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	public Map<String, AttributeSchemaContract> getAttributeTypesForReference(@Nonnull String referenceName) {
		return (Map<String, AttributeSchemaContract>)
			getOrCreateLocalDataStore()
				.computeIfAbsent(
					"__referenceAttributes_" + referenceName,
					k -> CollectionUtils.createHashMap(4)
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
	public <T> T getOrCreateEntityReferenceProxy(@Nonnull Class<T> expectedType, @Nonnull ReferenceContract reference) {
		return getOrCreateEntityReferenceProxy(expectedType, reference, getAttributeTypesForReference(reference.getReferenceName()));
	}

	/**
	 * Method propagates all mutations in reference proxies to the {@link #entityBuilder()}.
	 */
	public void propagateReferenceMutations() {
		final InternalEntityBuilder theEntityBuilder = entityBuilder();
		this.generatedProxyObjects
			.entrySet()
			.stream()
			.filter(it -> it.getKey().proxyType() == ProxyType.REFERENCE)
			.flatMap(
				it -> it.getValue()
				        .proxy(
					        SealedEntityReferenceProxy.class,
					        () -> {
						        throw new EvitaInvalidUsageException("Unexpected proxy type!");
					        }
				        )
				        .getReferenceBuilderIfPresent()
				        .stream()
			)
			.forEach(theEntityBuilder::addOrReplaceReferenceMutations);
	}

	@Override
	public String toString() {
		return this.entity instanceof EntityBuilder eb ?
			eb.toInstance().toString() : this.entity.toString();
	}

}
