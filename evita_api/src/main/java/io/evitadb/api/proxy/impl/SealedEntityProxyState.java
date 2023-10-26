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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.impl.ProxycianFactory.ProxyEntityCacheKey;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.ExistingEntityBuilder;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.data.structure.InitialReferenceBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.recipe.ProxyRecipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Proxy state for proxies that wrap sealed entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SealedEntityProxyState
	extends AbstractEntityProxyState
	implements EntityClassifier, SealedEntityProxy {
	@Serial private static final long serialVersionUID = 586508293856395550L;
	/**
	 * Optional reference to the {@link EntityBuilder} that is created on demand by calling {@link SealedEntity#openForWrite()}
	 * from internally wrapped entity {@link #getSealedEntity()}.
	 */
	@Nullable protected EntityBuilder entityBuilder;
	/**
	 * Optional information about the last {@link EntityReference} returned from {@link EvitaSessionContract#upsertEntity(EntityMutation)},
	 * it may contain newly assigned {@link EntityContract#getPrimaryKey()} that is not available in the wrapped entity.
	 */
	private EntityReference entityReference;

	public SealedEntityProxyState(
		@Nonnull EntityContract entity,
		@Nonnull Class<?> proxyClass,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		super(entity, proxyClass, recipes, collectedRecipes, reflectionLookup);
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
			.orElseGet(() -> ofNullable(entityReference)
				.map(EntityReference::getPrimaryKey)
				.orElse(null)
			);
	}

	@Nonnull
	@Override
	public SealedEntity getSealedEntity() {
		if (entity instanceof SealedEntity sealedEntity) {
			return sealedEntity;
		} else {
			throw new IllegalStateException("Proxy state does not wrap a sealed entity.");
		}
	}

	@Nonnull
	@Override
	public Optional<EntityMutationWithCallback> getEntityMutation() {
		return Optional.ofNullable(this.entityBuilder)
			.map(InstanceEditor::toMutation)
			.filter(Optional::isPresent)
			.map(Optional::get)
			.map(it -> new EntityMutationWithCallback(it, entityReference -> this.entityReference = entityReference));
	}

	@Override
	@Nonnull
	public Stream<EntityMutationWithCallback> getReferencedEntityMutations() {
		return this.generatedProxyObjects.entrySet().stream()
			.filter(it -> it.getKey().proxyType() == ProxyType.PARENT_BUILDER || it.getKey().proxyType() == ProxyType.REFERENCED_ENTITY_BUILDER)
			.flatMap(
				it -> Stream.concat(
					// we need first store the referenced entities of referenced entity (depth wise)
					((SealedEntityProxy) it.getValue().proxyOfAnyType()).getReferencedEntityMutations(),
					// and then the referenced entity itself
					((SealedEntityProxy) it.getValue().proxyOfAnyType()).getEntityMutation()
						.stream()
						.map(
							mutation -> {
								final EntityMutation theMutation = mutation.theMutation();
								final Consumer<EntityReference> mutationCallback = mutation.upsertCallback();
								final Consumer<EntityReference> externalCallback = it.getValue().callback();
								return new EntityMutationWithCallback(
									theMutation,
									mutationCallback == null ?
										externalCallback :
										entityReference -> {
											mutation.updateEntityReference(entityReference);
											externalCallback.accept(entityReference);
										}
								);
							}
						)
				)
			);
	}

	@Nonnull
	@Override
	public <T extends Serializable> Optional<T> getReferencedEntityObject(
		@Nonnull String referencedEntityType,
		int referencedPrimaryKey,
		@Nonnull Class<T> expectedType,
		@Nonnull ProxyType... proxyType
	) {
		Assert.isPremiseValid(proxyType.length > 0, "At least one proxy type must be specified.");
		return Arrays.stream(proxyType)
			.map(it -> generatedProxyObjects.get(
				new ProxyInstanceCacheKey(referencedEntityType, referencedPrimaryKey, it)
			))
			.filter(Objects::nonNull)
			.map(it -> it.proxy(expectedType).orElse(null))
			.filter(Objects::nonNull)
			.findFirst();
	}

	@Nonnull
	public Optional<EntityBuilder> getEntityBuilderIfPresent() {
		return ofNullable(this.entityBuilder);
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
	public <T> T createReferencedEntityBuilderProxy(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Class<T> expectedType,
		@Nonnull ProxyType proxyType,
		int primaryKey
	) throws EntityClassInvalidException {
		return generatedProxyObjects.computeIfAbsent(
			new ProxyInstanceCacheKey(entitySchema.getName(), primaryKey, proxyType),
			key -> {
				final ProxyWithUpsertCallback proxy = new ProxyWithUpsertCallback(
					ProxycianFactory.createEntityBuilderProxy(
						expectedType, recipes, collectedRecipes,
						new InitialEntityBuilder(entitySchema, primaryKey),
						getReflectionLookup()
					)
				);
				registerReferencedEntityObject(
					entitySchema.getName(), primaryKey,
					proxy, proxyType
				);
				return proxy;
			}
		)
			.proxy(expectedType)
			.orElseThrow();
	}

	@Nonnull
	public <T> T createReferencedEntityBuilderProxyWithCallback(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Class<T> expectedType,
		@Nonnull ProxyType proxyType,
		@Nonnull Consumer<EntityReference> callback
	) throws EntityClassInvalidException {
		return generatedProxyObjects.computeIfAbsent(
			new ProxyInstanceCacheKey(entitySchema.getName(), Integer.MIN_VALUE, proxyType),
			key -> {
				final ProxyWithUpsertCallback proxy = new ProxyWithUpsertCallback(
					ProxycianFactory.createEntityBuilderProxy(
						expectedType, recipes, collectedRecipes,
						new InitialEntityBuilder(entitySchema),
						getReflectionLookup()
					),
					callback
				);
				registerReferencedEntityObject(
					entitySchema.getName(), Integer.MIN_VALUE,
					proxy, proxyType
				);
				return proxy;
			}
		)
			.proxy(expectedType)
			.orElseThrow();
	}

	@Nonnull
	public <T> T createReferencedEntityBuilderProxy(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<T> expectedType,
		@Nonnull ProxyType proxyType,
		int primaryKey
	) throws EntityClassInvalidException {
		return generatedProxyObjects.computeIfAbsent(
				new ProxyInstanceCacheKey(referenceSchema.getName(), primaryKey, proxyType),
				key -> {
					final ProxyWithUpsertCallback proxy = new ProxyWithUpsertCallback(
						ProxycianFactory.createEntityBuilderReferenceProxy(
							expectedType, recipes, collectedRecipes,
							this.entity,
							new InitialReferenceBuilder(
								entitySchema,
								referenceSchema.getName(),
								primaryKey,
								referenceSchema.getCardinality(),
								referenceSchema.getReferencedEntityType()
							),
							getReflectionLookup()
						)
					);
					registerReferencedEntityObject(
						referenceSchema.getName(), primaryKey,
						proxy, proxyType
					);
					return proxy;
				}
			)
			.proxy(expectedType)
			.orElseThrow();
	}

	@Nonnull
	public <T> T createReferencedEntityBuilderProxyWithCallback(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<T> expectedType,
		@Nonnull ProxyType proxyType,
		@Nonnull Consumer<EntityReference> callback
	) throws EntityClassInvalidException {
		return generatedProxyObjects.computeIfAbsent(
				new ProxyInstanceCacheKey(referenceSchema.getName(), Integer.MIN_VALUE, proxyType),
				key -> {
					final InitialReferenceBuilder referenceBuilder = new InitialReferenceBuilder(
						entitySchema,
						referenceSchema.getName(),
						Integer.MIN_VALUE,
						referenceSchema.getCardinality(),
						referenceSchema.getReferencedEntityType()
					);
					final ProxyWithUpsertCallback proxy = new ProxyWithUpsertCallback(
						ProxycianFactory.createEntityBuilderReferenceProxy(
							expectedType, recipes, collectedRecipes,
							this.entity,
							referenceBuilder,
							getReflectionLookup()
						),
						entRef -> {
							referenceBuilder.setReferencedEntityPrimaryKey(entRef.getPrimaryKey());
							callback.accept(entRef);
						}
					);
					registerReferencedEntityObject(
						referenceSchema.getName(), Integer.MIN_VALUE,
						proxy, proxyType
					);
					return proxy;
				}
			)
			.proxy(expectedType)
			.orElseThrow();
	}

	@Override
	public String toString() {
		return entity.toString();
	}

}
