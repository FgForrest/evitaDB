/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.expression.proxy;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.spi.store.catalog.persistence.accessor.EntityStoragePartAccessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.utils.ArrayUtils.EMPTY_CLASS_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_OBJECT_ARRAY;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Static utility that instantiates entity and reference proxies at trigger time using pre-composed partial arrays
 * from an {@link ExpressionProxyDescriptor} and raw storage parts fetched per the descriptor's
 * {@link StoragePartRecipe}.
 *
 * The instantiator performs three steps:
 *
 * 1. **Fetch** — reads only the storage parts specified by the recipe from the storage accessor
 * 2. **Build state** — constructs immutable state records ({@link EntityProxyState}, {@link ReferenceProxyState})
 * 3. **Instantiate** — creates ByteBuddy proxy objects backed by the partial arrays and state records
 *
 * For expressions accessing `$reference.referencedEntity.*` or `$reference.groupEntity?.*`, the instantiator
 * additionally creates nested entity proxies implementing {@link SealedEntity} and wires them into the
 * {@link ReferenceProxyState}.
 */
public final class ExpressionProxyInstantiator {

	/**
	 * Instantiates entity and (optionally) reference proxies from the given descriptor and storage accessor.
	 *
	 * @param descriptor        the schema-load-time descriptor with partial arrays and recipe
	 * @param entitySchema      the entity schema
	 * @param entityPrimaryKey  the primary key of the entity to proxy
	 * @param referenceSchema   the reference schema, or `null` if no reference proxy is needed
	 * @param referenceKey      the reference key identifying the specific reference, or `null`
	 * @param storageAccessor   the accessor for fetching storage parts
	 * @param schemaResolver    function resolving entity type name to entity schema (for nested entity proxies)
	 * @return instantiation result with entity proxy and optional reference proxy
	 */
	@Nonnull
	public static InstantiationResult instantiate(
		@Nonnull ExpressionProxyDescriptor descriptor,
		@Nonnull EntitySchemaContract entitySchema,
		int entityPrimaryKey,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable ReferenceKey referenceKey,
		@Nonnull EntityStoragePartAccessor storageAccessor,
		@Nonnull Function<String, EntitySchemaContract> schemaResolver
	) {
		final StoragePartRecipe recipe = descriptor.entityRecipe();
		final EntityProxyState entityState = buildEntityState(
			entitySchema, entityPrimaryKey, recipe, storageAccessor
		);

		// instantiate entity proxy
		final EntityContract entityProxy = ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(entityState, descriptor.entityPartials()),
			new Class<?>[]{ EntityContract.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);

		// build reference proxy if needed
		final ReferenceContract referenceProxy;
		if (descriptor.referencePartials() != null && referenceKey != null && referenceSchema != null) {
			referenceProxy = instantiateReferenceProxy(
				descriptor, entityState, referenceSchema, referenceKey, storageAccessor, schemaResolver
			);
		} else {
			referenceProxy = null;
		}

		return new InstantiationResult(entityProxy, referenceProxy);
	}

	/**
	 * Builds an {@link EntityProxyState} by fetching storage parts per the given recipe.
	 *
	 * @param schema          the entity schema
	 * @param primaryKey      the entity primary key
	 * @param recipe          the recipe specifying which storage parts to fetch
	 * @param storageAccessor the storage accessor
	 * @return the assembled entity proxy state
	 */
	@Nonnull
	private static EntityProxyState buildEntityState(
		@Nonnull EntitySchemaContract schema,
		int primaryKey,
		@Nonnull StoragePartRecipe recipe,
		@Nonnull EntityStoragePartAccessor storageAccessor
	) {
		final String entityType = schema.getName();

		final EntityBodyStoragePart bodyPart = recipe.needsEntityBody()
			? storageAccessor.getEntityStoragePart(entityType, primaryKey, EntityExistence.MUST_EXIST)
			: null;

		final AttributesStoragePart globalAttrs = recipe.needsGlobalAttributes()
			? storageAccessor.getAttributeStoragePart(entityType, primaryKey)
			: null;

		// resolve Locale.ROOT sentinel to actual entity locales from body part
		final Set<Locale> resolvedAttributeLocales = resolveLocales(
			recipe.neededAttributeLocales(), bodyPart
		);
		final Map<Locale, AttributesStoragePart> localeAttrs = fetchLocaleAttributes(
			resolvedAttributeLocales, entityType, primaryKey, storageAccessor
		);

		final ReferencesStoragePart refsPart = recipe.needsReferences()
			? storageAccessor.getReferencesStoragePart(entityType, primaryKey)
			: null;

		// resolve Locale.ROOT sentinel for associated data locales
		final Set<Locale> resolvedAdLocales = resolveLocales(
			recipe.neededAssociatedDataLocales(), bodyPart
		);
		final Map<AssociatedDataKey, AssociatedDataStoragePart> adParts = fetchAssociatedData(
			recipe.neededAssociatedDataNames(), resolvedAdLocales,
			entityType, primaryKey, storageAccessor
		);

		return new EntityProxyState(
			schema, bodyPart, globalAttrs, localeAttrs, adParts,
			EntityProxyState.indexReferences(refsPart)
		);
	}

	/**
	 * Instantiates a nested entity proxy implementing {@link SealedEntity} for use as a referenced or group entity
	 * within a reference proxy.
	 *
	 * @param schema          the nested entity schema
	 * @param primaryKey      the nested entity primary key
	 * @param partials        the partial array for the nested entity proxy
	 * @param recipe          the recipe for fetching the nested entity's storage parts
	 * @param storageAccessor the storage accessor
	 * @return the nested entity proxy implementing SealedEntity
	 */
	@Nonnull
	private static SealedEntity instantiateNestedEntityProxy(
		@Nonnull EntitySchemaContract schema,
		int primaryKey,
		@Nonnull PredicateMethodClassification<?, ?, ?>[] partials,
		@Nonnull StoragePartRecipe recipe,
		@Nonnull EntityStoragePartAccessor storageAccessor
	) {
		final EntityProxyState nestedState = buildEntityState(schema, primaryKey, recipe, storageAccessor);
		return ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(nestedState, partials),
			new Class<?>[]{ SealedEntity.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
	}

	/**
	 * Instantiates a reference proxy from the entity state's pre-indexed references, optionally wiring nested
	 * entity proxies for `referencedEntity` and `groupEntity`.
	 *
	 * @param descriptor      the descriptor with reference partial arrays and nested entity info
	 * @param entityState     the entity state containing indexed references
	 * @param referenceSchema the reference schema
	 * @param referenceKey    the key identifying the specific reference
	 * @param storageAccessor the storage accessor (needed for nested entity proxy fetching)
	 * @param schemaResolver  function resolving entity type name to entity schema
	 * @return the reference proxy, or `null` if the reference was not found
	 */
	@Nullable
	private static ReferenceContract instantiateReferenceProxy(
		@Nonnull ExpressionProxyDescriptor descriptor,
		@Nonnull EntityProxyState entityState,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull EntityStoragePartAccessor storageAccessor,
		@Nonnull Function<String, EntitySchemaContract> schemaResolver
	) {
		final Map<String, List<ReferenceContract>> refsByName = entityState.referencesByName();
		if (refsByName == null) {
			return null;
		}

		final List<ReferenceContract> refsForName = refsByName.get(referenceKey.referenceName());
		if (refsForName == null) {
			return null;
		}

		// find the specific reference by key
		ReferenceContract matchingRef = null;
		for (final ReferenceContract ref : refsForName) {
			if (ref.getReferenceKey().equals(referenceKey)) {
				matchingRef = ref;
				break;
			}
		}

		if (matchingRef == null) {
			return null;
		}

		// extract reference data for state
		final Collection<AttributeValue> attrValues = matchingRef.getAttributeValues();
		final AttributeValue[] sortedAttrs = attrValues.toArray(new AttributeValue[0]);
		Arrays.sort(sortedAttrs);

		final GroupEntityReference group = matchingRef.getGroup().orElse(null);
		final Set<Locale> attributeLocales = matchingRef.getAttributeLocales();

		// wire nested referenced entity proxy
		final SealedEntity referencedEntity;
		if (descriptor.needsReferencedEntityProxy()) {
			final String referencedEntityType = referenceSchema.getReferencedEntityType();
			final EntitySchemaContract referencedSchema = schemaResolver.apply(referencedEntityType);
			referencedEntity = instantiateNestedEntityProxy(
				referencedSchema,
				referenceKey.primaryKey(),
				descriptor.referencedEntityPartialsOrThrowException(),
				descriptor.referencedEntityRecipeOrThrowException(),
				storageAccessor
			);
		} else {
			referencedEntity = null;
		}

		// wire nested group entity proxy
		final SealedEntity groupEntity;
		if (descriptor.needsGroupEntityProxy() && group != null) {
			final String groupType = referenceSchema.getReferencedGroupType();
			final EntitySchemaContract groupSchema = schemaResolver.apply(groupType);
			groupEntity = instantiateNestedEntityProxy(
				groupSchema,
				group.getPrimaryKey(),
				descriptor.groupEntityPartialsOrThrowException(),
				descriptor.groupEntityRecipeOrThrowException(),
				storageAccessor
			);
		} else {
			groupEntity = null;
		}

		final ReferenceProxyState refState = new ReferenceProxyState(
			referenceSchema,
			referenceKey,
			matchingRef.version(),
			sortedAttrs.length > 0 ? sortedAttrs : null,
			attributeLocales,
			group,
			referencedEntity,
			groupEntity
		);

		return ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(refState, descriptor.referencePartialsOrThrowException()),
			new Class<?>[]{ ReferenceContract.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
	}

	/**
	 * Resolves the {@link Locale#ROOT} sentinel in the given locale set to the actual entity locales from the
	 * body part. If the set contains `Locale.ROOT`, the sentinel is replaced with the entity's real locales
	 * obtained from {@link EntityBodyStoragePart#getLocales()}. If the set does not contain the sentinel, it is
	 * returned as-is.
	 *
	 * @param neededLocales the locale set potentially containing a `Locale.ROOT` sentinel
	 * @param bodyPart      the entity body part (must not be `null` if the sentinel is present)
	 * @return the resolved set of actual locales
	 */
	@Nonnull
	private static Set<Locale> resolveLocales(
		@Nonnull Set<Locale> neededLocales,
		@Nullable EntityBodyStoragePart bodyPart
	) {
		if (!neededLocales.contains(Locale.ROOT)) {
			return neededLocales;
		}
		// Locale.ROOT is a sentinel meaning "all locales" — resolve from the body part
		if (bodyPart == null) {
			return Set.of();
		}
		return bodyPart.getLocales();
	}

	/**
	 * Fetches locale-specific attribute storage parts for the given set of locales.
	 *
	 * @param neededLocales   set of locales to fetch
	 * @param entityType      entity type name
	 * @param entityPK        entity primary key
	 * @param storageAccessor storage accessor
	 * @return map of locale to attributes part, or `null` if no locales needed
	 */
	@Nullable
	private static Map<Locale, AttributesStoragePart> fetchLocaleAttributes(
		@Nonnull Set<Locale> neededLocales,
		@Nonnull String entityType,
		int entityPK,
		@Nonnull EntityStoragePartAccessor storageAccessor
	) {
		if (neededLocales.isEmpty()) {
			return null;
		}
		final Map<Locale, AttributesStoragePart> result = createHashMap(neededLocales.size());
		for (final Locale locale : neededLocales) {
			result.put(locale, storageAccessor.getAttributeStoragePart(entityType, entityPK, locale));
		}
		return result;
	}

	/**
	 * Fetches associated data storage parts for the given names and locales.
	 *
	 * @param neededNames     set of associated data names
	 * @param neededLocales   set of locales for localized associated data
	 * @param entityType      entity type name
	 * @param entityPK        entity primary key
	 * @param storageAccessor storage accessor
	 * @return map of associated data key to storage part, or `null` if none needed
	 */
	@Nullable
	private static Map<AssociatedDataKey, AssociatedDataStoragePart> fetchAssociatedData(
		@Nonnull Set<String> neededNames,
		@Nonnull Set<Locale> neededLocales,
		@Nonnull String entityType,
		int entityPK,
		@Nonnull EntityStoragePartAccessor storageAccessor
	) {
		if (neededNames.isEmpty()) {
			return null;
		}
		final int estimatedSize = neededNames.size() * (1 + neededLocales.size());
		final Map<AssociatedDataKey, AssociatedDataStoragePart> result = createHashMap(estimatedSize);

		// global (non-localized) associated data
		for (final String name : neededNames) {
			final AssociatedDataKey key = new AssociatedDataKey(name);
			result.put(key, storageAccessor.getAssociatedDataStoragePart(entityType, entityPK, key));
		}

		// localized associated data
		for (final Locale locale : neededLocales) {
			for (final String name : neededNames) {
				final AssociatedDataKey key = new AssociatedDataKey(name, locale);
				result.put(key, storageAccessor.getAssociatedDataStoragePart(entityType, entityPK, key));
			}
		}

		return result;
	}

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private ExpressionProxyInstantiator() {
		// utility class
	}

	/**
	 * Result of proxy instantiation containing the entity proxy and an optional reference proxy.
	 *
	 * @param entityProxy    the instantiated entity proxy backed by storage parts
	 * @param referenceProxy the instantiated reference proxy, or `null` if the expression does not access references
	 */
	public record InstantiationResult(
		@Nonnull EntityContract entityProxy,
		@Nullable ReferenceContract referenceProxy
	) {

	}
}
