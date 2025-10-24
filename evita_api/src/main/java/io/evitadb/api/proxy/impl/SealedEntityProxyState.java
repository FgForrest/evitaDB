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
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
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
	@Setter private EntityReferenceContract entityReference;

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
				.map(EntityReferenceContract::getPrimaryKey)
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
	public Optional<EntityBuilderWithCallback> getEntityBuilderWithCallback(@Nonnull Propagation propagation) {
		propagateReferenceMutations(propagation);
		return Optional
			.ofNullable(this.entityBuilder)
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
							.filter(goEntry -> goEntry.getKey() instanceof ReferenceProxyCacheKey)
							.flatMap(goEntry -> goEntry.getValue().proxies(propagation).stream())
							.forEach(
								refProxy -> ((SealedEntityReferenceProxyState) ((ProxyStateAccessor) refProxy).getProxyState())
									.notifyBuilderUpserted(entityReference)
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

	/**
	 * Creates a proxy for an entity reference based on the specified parameters. The proxy will represent the entity’s
	 * reference and manage its interaction with the system. If the reference already exists, the proxy handles the
	 * existing instance; otherwise, a new reference is created and initialized.
	 *
	 * @param referenceSchema the schema of the reference to be proxied
	 * @param expectedType    the expected type of the proxy to be created, defining the contract it should implement
	 * @param reference       the reference contract instance representing the entity reference
	 * @param <T>             the type of the proxy that will be returned
	 * @return a proxy instance implementing the specified contract for the reference
	 * @throws EntityClassInvalidException if the entity class or its schema configurations are invalid or incompatible
	 */
	@Nonnull
	public <T> T getOrCreateEntityReferenceProxy(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<T> expectedType,
		@Nonnull ReferenceContract reference,
		@Nonnull ProxyInput proxyInput
	) throws EntityClassInvalidException {
		return getOrCreateEntityReferenceProxy(
			referenceSchema,
			expectedType,
			reference.getReferenceKey(),
			reference,
			proxyInput
		);
	}

	/**
	 * Creates a proxy for an entity reference based on the specified parameters. The proxy will represent the entity’s
	 * reference and manage its interaction with the system. If the reference already exists, the proxy handles the
	 * existing instance; otherwise, a new reference is created and initialized.
	 *
	 * @param referenceSchema the schema of the reference to be proxied
	 * @param expectedType    the expected type of the proxy to be created, defining the contract it should implement
	 * @param referenceKey    the unique key associated with the reference, including its name, primary key, and potentially
	 *                        an internal identifier
	 * @param <T>             the type of the proxy that will be returned
	 * @return a proxy instance implementing the specified contract for the reference
	 * @throws EntityClassInvalidException if the entity class or its schema configurations are invalid or incompatible
	 */
	@Nonnull
	public <T> T getOrCreateEntityReferenceProxy(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<T> expectedType,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull ProxyInput proxyInput
	) throws EntityClassInvalidException {
		return getOrCreateEntityReferenceProxy(
			referenceSchema,
			expectedType,
			referenceKey,
			null,
			proxyInput
		);
	}

	/**
	 * Creates a proxy for an entity reference based on the specified parameters. The proxy will represent the entity’s
	 * reference and manage its interaction with the system. If the reference already exists, the proxy handles the
	 * existing instance; otherwise, a new reference is created and initialized.
	 *
	 * @param referenceSchema the schema of the reference to be proxied
	 * @param expectedType    the expected type of the proxy to be created, defining the contract it should implement
	 * @param referenceKey    the unique key associated with the reference, including its name, primary key, and potentially
	 *                        an internal identifier
	 * @param <T>             the type of the proxy that will be returned
	 * @return a proxy instance implementing the specified contract for the reference
	 * @throws EntityClassInvalidException if the entity class or its schema configurations are invalid or incompatible
	 */
	@Nonnull
	public <T> T getOrCreateEntityReferenceProxy(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<T> expectedType,
		@Nonnull ReferenceKey referenceKey,
		@Nullable ReferenceContract reference,
		@Nonnull ProxyInput proxyInput
	) throws EntityClassInvalidException {
		final EntityContract theEntity = entityBuilderIfPresent()
			.map(EntityContract.class::cast)
			.orElse(this.entity);
		final Optional<ReferenceContract> existingReference = reference == null ?
			theEntity.getReference(referenceKey) : Optional.of(reference);
		final ReferenceKey resolvedReferenceKey = existingReference
			.map(ReferenceContract::getReferenceKey)
			.orElseGet(() -> new ReferenceKey(referenceKey.referenceName(), referenceKey.primaryKey(), entityBuilder().getNextReferenceInternalId()));

		final Supplier<ProxyWithUpsertCallback> instanceSupplier = () -> {
			final Map<String, AttributeSchemaContract> attributeTypesForReference = getAttributeTypesForReference(
				referenceSchema.getName()
			);
			return existingReference
				.filter(
					ref -> entityBuilderIfPresent()
						.filter(ExistingEntityBuilder.class::isInstance)
						.map(ExistingEntityBuilder.class::cast)
						.map(it -> it.isPresentInBaseEntity(ref))
						.orElse(true)
				)
				.map(
					it -> switch (proxyInput) {
						case EXISTING_REFERENCE_BUILDER -> getOrCreateEntityReferenceProxy(
							expectedType,
							attributeTypesForReference,
							new ExistingReferenceBuilder(
								it,
								getEntitySchema(),
								attributeTypesForReference
							)
						);
						case READ_ONLY_REFERENCE -> getOrCreateEntityReferenceProxy(
							expectedType,
							attributeTypesForReference,
							it
						);
						case INITIAL_REFERENCE_BUILDER -> getOrCreateEntityReferenceProxy(
							expectedType,
							attributeTypesForReference,
							new InitialReferenceBuilder(
								getEntitySchema(),
								referenceSchema,
								referenceSchema.getName(),
								referenceKey.primaryKey(),
								resolvedReferenceKey.internalPrimaryKey(),
								attributeTypesForReference
							)
						);
					}
				)
				.orElseGet(
					() -> getOrCreateEntityReferenceProxy(
						expectedType, attributeTypesForReference,
						new InitialReferenceBuilder(
							getEntitySchema(),
							referenceSchema,
							referenceSchema.getName(),
							referenceKey.primaryKey(),
							resolvedReferenceKey.internalPrimaryKey(),
							attributeTypesForReference
						)
					)
				);
		};

		Assert.isPremiseValid(
			!resolvedReferenceKey.isUnknownReference(),
			"Cannot create reference proxy for reference without assigned primary key!"
		);
		return this.generatedProxyObjects
			.computeIfAbsent(
				new ReferenceProxyCacheKey(resolvedReferenceKey),
				key -> instanceSupplier.get()
			).proxy(expectedType, instanceSupplier);
	}

	/**
	 * Creates a proxy for an entity reference, allowing for upserts (insert or update) when interacting with
	 * the referenced entity and its associated attributes.
	 *
	 * @param expectedType the expected type of the proxy to be created, which defines the contract the proxy should implement
	 * @param attributeTypesForReference a map of attribute names to their respective schema details for the reference
	 * @param reference the reference contract instance representing the entity reference
	 * @param <T> the type of the proxy to be created
	 * @return a {@code ProxyWithUpsertCallback} instance representing the proxy for the specified entity reference
	 */
	private <T> @Nonnull ProxyWithUpsertCallback getOrCreateEntityReferenceProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypesForReference,
		@Nonnull ReferenceContract reference
	) {
		return new ProxyWithUpsertCallback(
			ProxycianFactory.createEntityReferenceProxy(
				this.getProxyClass(), expectedType,
				this.recipes,
				this.collectedRecipes,
				this.entity,
				this::getPrimaryKey,
				this.referencedEntitySchemas,
				reference,
				attributeTypesForReference,
				getReflectionLookup(),
				this.generatedProxyObjects
			)
		);
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
	 * Method propagates all mutations in reference proxies to the {@link #entityBuilder()}.
	 */
	public void propagateReferenceMutations(@Nonnull Propagation propagation) {
		final InternalEntityBuilder theEntityBuilder = entityBuilder();
		this.generatedProxyObjects
			.entrySet()
			.stream()
			.filter(it -> it.getKey() instanceof ReferenceProxyCacheKey)
			.flatMap(
				it -> it.getValue()
				        .getSealedEntityReferenceProxies(propagation)
				        .map(SealedEntityReferenceProxy::getReferenceBuilderIfPresent)
				        .filter(Optional::isPresent)
				        .map(Optional::get)
			)
			.forEach(refBuilder -> theEntityBuilder.addOrReplaceReferenceMutations(refBuilder, false));
	}

	@Override
	public String toString() {
		return this.entity instanceof EntityBuilder eb ?
			eb.toInstance().toString() : this.entity.toString();
	}

	/**
	 * Enum `ProxyInput` defines the mechanisms used to create or initialize a proxy for an entity reference.
	 * It determines how a reference proxy interacts with an existing reference or builds a new one.
	 */
	public enum ProxyInput {

		/**
		 * Reference proxy will be created with access to the existing reference only.
		 */
		READ_ONLY_REFERENCE,
		/**
		 * Reference proxy will be created with access to the initial reference builder, even if the reference already
		 * exists.
		 */
		INITIAL_REFERENCE_BUILDER,
		/**
		 * Reference proxy will be created with access to the existing reference builder, if the reference exists.
		 */
		EXISTING_REFERENCE_BUILDER

	}

}
