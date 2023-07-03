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

package io.evitadb.api.proxy.impl.method;

import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferenceRef;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.CurriedMethodContextInvocationHandler;
import one.edee.oss.proxycian.DirectMethodClassification;
import one.edee.oss.proxycian.utils.GenericsUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * TODO JNO - document me
 * TODO JNO - podporovat optional, možná i obecně pro atributy a tak
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetReferenceMethodClassifier extends DirectMethodClassification<EntityClassifier, SealedEntityProxyState> {
	public static final GetReferenceMethodClassifier INSTANCE = new GetReferenceMethodClassifier();

	@Nullable
	private static ReferenceSchemaContract getReferenceSchema(
		@Nonnull Method method,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull EntitySchemaContract entitySchema
	) {
		final Reference referenceInstance = reflectionLookup.getAnnotationInstance(method, Reference.class);
		final ReferenceRef referenceRefInstance = reflectionLookup.getAnnotationInstance(method, ReferenceRef.class);
		if (referenceInstance != null) {
			return entitySchema.getReferenceOrThrowException(referenceInstance.name());
		} else if (referenceRefInstance != null) {
			return entitySchema.getReferenceOrThrowException(referenceRefInstance.value());
		} else if (!reflectionLookup.hasAnnotationInSamePackage(method, Reference.class)) {
			final Optional<String> referenceName = ReflectionLookup.getPropertyNameFromMethodNameIfPossible(method.getName());
			return referenceName
				.flatMap(it -> entitySchema.getReferenceByName(it, NamingConvention.CAMEL_CASE))
				.orElse(null);
		} else {
			return null;
		}
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> singleEntityReferenceResult(@Nonnull String cleanReferenceName) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final Collection<ReferenceContract> references = theState.getSealedEntity().getReferences(cleanReferenceName);
			if (references.isEmpty()) {
				return null;
			} else {
				final ReferenceContract theReference = references.iterator().next();
				return new EntityReference(theReference.getReferenceName(), theReference.getReferencedPrimaryKey());
			}
		};
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> listOfEntityReferencesResult(@Nonnull String cleanReferenceName) {
		// TODO JNO - we should provide our own set, that reflects the entity builder state, but is immutable
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> new EntityReference(it.getReferenceName(), it.getReferencedPrimaryKey()))
				.toList();
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> setOfEntityReferencesResult(@Nonnull String cleanReferenceName) {
		// TODO JNO - we should provide our own set, that reflects the entity builder state, but is immutable
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> new EntityReference(it.getReferenceName(), it.getReferencedPrimaryKey()))
				.collect(Collectors.toUnmodifiableSet());
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> arrayOfEntityReferencesResult(@Nonnull String cleanReferenceName) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> new EntityReference(it.getReferenceName(), it.getReferencedPrimaryKey()))
				.toArray(EntityReference[]::new);
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> singleEntityResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<? extends EntityClassifier> itemType
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final Collection<ReferenceContract> references = theState.getSealedEntity().getReferences(cleanReferenceName);
			if (references.isEmpty()) {
				return null;
			} else {
				return wrapToType(cleanReferenceName, itemType, theState, references.iterator().next());
			}
		};
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> listOfEntityResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<? extends EntityClassifier> itemType
	) {
		// TODO JNO - we should provide our own set, that reflects the entity builder state, but is immutable
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> wrapToType(it.getReferenceName(), itemType, theState, it))
				.toList();
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> setOfEntityResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<? extends EntityClassifier> itemType
	) {
		// TODO JNO - we should provide our own set, that reflects the entity builder state, but is immutable
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> wrapToType(it.getReferenceName(), itemType, theState, it))
				.collect(Collectors.toUnmodifiableSet());
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> arrayOfEntityResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<? extends EntityClassifier> itemType
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> wrapToType(it.getReferenceName(), itemType, theState, it))
				.toArray(count -> (Object[]) Array.newInstance(itemType, count));
	}

	@Nullable
	private static EntityClassifier wrapToType(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<? extends EntityClassifier> itemType,
		@Nonnull SealedEntityProxyState theState,
		@Nonnull ReferenceContract reference
	) {
		Assert.isTrue(
			reference instanceof ReferenceDecorator,
			() -> "Entity `" + theState.getSealedEntity().getType() + "` references of type `" +
				cleanReferenceName + "` were not fetched with `entityFetch` requirement. " +
				"Related entity body is not available."
		);
		final ReferenceDecorator referenceDecorator = (ReferenceDecorator) reference;
		return referenceDecorator
			.getReferencedEntity()
			.map(it -> theState.wrapTo(itemType, it))
			.orElse(null);
	}

	public GetReferenceMethodClassifier() {
		super(
			"getReference",
			(method, proxyState) -> {
				if (method.getParameterCount() > 0) {
					return null;
				}
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final ReferenceSchemaContract referenceSchema = getReferenceSchema(
					method, reflectionLookup,
					proxyState.getEntitySchema()
				);
				if (referenceSchema == null) {
					return null;
				} else {
					final String cleanReferenceName = referenceSchema.getName();
					@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
					@SuppressWarnings("rawtypes") final Class collectionType;
					@SuppressWarnings("rawtypes") final Class itemType;
					if (Collection.class.equals(returnType) || List.class.isAssignableFrom(returnType) || Set.class.isAssignableFrom(returnType)) {
						collectionType = returnType;
						itemType = GenericsUtils.getGenericTypeFromCollection(method.getDeclaringClass(), method.getGenericReturnType());
					} else if (returnType.isArray()) {
						collectionType = returnType;
						itemType = returnType.getComponentType();
					} else {
						collectionType = null;
						itemType = returnType;
					}

					final Entity entityInstance = reflectionLookup.getClassAnnotation(itemType, Entity.class);
					final EntityRef entityRefInstance = reflectionLookup.getClassAnnotation(itemType, EntityRef.class);
					if (itemType.equals(EntityReference.class)) {
						if (collectionType == null) {
							return singleEntityReferenceResult(cleanReferenceName);
						} else if (collectionType.isArray()) {
							return arrayOfEntityReferencesResult(cleanReferenceName);
						} else if (Set.class.isAssignableFrom(collectionType)) {
							return setOfEntityReferencesResult(cleanReferenceName);
						} else {
							return listOfEntityReferencesResult(cleanReferenceName);
						}
					} else if (entityInstance != null || entityRefInstance != null) {
						// we return directly the referenced entity - either it or its group by matching the entity referenced name
						final String entityName = entityInstance == null ? entityRefInstance.value() : entityInstance.name();
						final String referencedEntityType = referenceSchema.getReferencedEntityType();
						final String referencedGroupType = referenceSchema.getReferencedGroupType();
						if (entityName.equals(referencedEntityType)) {
							Assert.isTrue(
								referenceSchema.isReferencedEntityTypeManaged(),
								() -> new EntityClassInvalidException(
									itemType,
									"Entity class type `" + itemType + "` reference `" +
										referenceSchema.getName() + "` relates to the entity type `" + referencedEntityType +
										"` which is not managed by evitaDB and cannot be wrapped into a proxy class!"
								)
							);
							if (collectionType == null) {
								//noinspection unchecked
								return singleEntityResult(cleanReferenceName, itemType);
							} else if (collectionType.isArray()) {
								//noinspection unchecked
								return arrayOfEntityResult(cleanReferenceName, itemType);
							} else if (Set.class.isAssignableFrom(collectionType)) {
								//noinspection unchecked
								return setOfEntityResult(cleanReferenceName, itemType);
							} else {
								//noinspection unchecked
								return listOfEntityResult(cleanReferenceName, itemType);
							}
						} else if (entityName.equals(referencedGroupType)) {
							Assert.isTrue(
								referenceSchema.isReferencedGroupTypeManaged(),
								() -> new EntityClassInvalidException(
									itemType,
									"Entity class type `" + itemType + "` reference `" +
										referenceSchema.getName() + "` relates to the group type `" + referencedGroupType +
										"` which is not managed by evitaDB and cannot be wrapped into a proxy class!"
								)
							);
						} else {
							throw new EntityClassInvalidException(
								itemType,
								"Entity class type `" + itemType + "` is not compatible with reference `" +
									referenceSchema.getName() + "` of entity type `" + referencedEntityType +
									"` or group type `" + referencedGroupType + "`!"
							);
						}
					} else {

					}
					//noinspection unchecked
					return (entityClassifier, theMethod, args, theState, invokeSuper) -> EvitaDataTypes.toTargetType(
						theState.getAttribute(cleanReferenceName), returnType
					);
				}
			}
		);
	}

}
