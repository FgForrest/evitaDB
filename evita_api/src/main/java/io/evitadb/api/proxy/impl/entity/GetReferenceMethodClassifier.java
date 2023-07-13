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

package io.evitadb.api.proxy.impl.entity;

import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
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
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.CollectorUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.CurriedMethodContextInvocationHandler;
import one.edee.oss.proxycian.DirectMethodClassification;
import one.edee.oss.proxycian.utils.GenericsUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Identifies methods that are used to get reference from a sealed entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetReferenceMethodClassifier extends DirectMethodClassification<EntityClassifier, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final GetReferenceMethodClassifier INSTANCE = new GetReferenceMethodClassifier();

	/**
	 * Retrieves appropriate reference schema from the annotations on the method. If no Evita annotation is found
	 * it tries to match the attribute name by the name of the method.
	 */
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

	/**
	 * Method wraps passed {@link ReferenceContract} into a custom proxy instance of particular type.
	 */
	@Nullable
	private static <T> T createProxy(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<T> itemType,
		@Nonnull SealedEntityProxyState theState,
		@Nonnull ReferenceContract reference,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor
	) {
		Assert.isTrue(
			reference instanceof ReferenceDecorator,
			() -> "Entity `" + theState.getSealedEntity().getType() + "` references of type `" +
				cleanReferenceName + "` were not fetched with `entityFetch` requirement. " +
				"Related entity body is not available."
		);
		final ReferenceDecorator referenceDecorator = (ReferenceDecorator) reference;
		return entityExtractor.apply(referenceDecorator)
			.map(it -> theState.createEntityProxy(itemType, it))
			.orElse(null);
	}

	/**
	 * Creates an implementation of the method returning a single referenced entity wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> singleEntityReferenceResult(@Nonnull String cleanReferenceName) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final Collection<ReferenceContract> references = theState.getSealedEntity().getReferences(cleanReferenceName);
			if (references.isEmpty()) {
				return null;
			} else {
				final ReferenceContract theReference = references.iterator().next();
				return new EntityReference(theReference.getReferencedEntityType(), theReference.getReferencedPrimaryKey());
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a list of referenced entities wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> listOfEntityReferencesResult(
		@Nonnull String cleanReferenceName
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> new EntityReference(it.getReferencedEntityType(), it.getReferencedPrimaryKey()))
				.toList();
	}

	/**
	 * Creates an implementation of the method returning a set of referenced entities wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> setOfEntityReferencesResult(
		@Nonnull String cleanReferenceName
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> new EntityReference(it.getReferencedEntityType(), it.getReferencedPrimaryKey()))
				.collect(CollectorUtils.toUnmodifiableLinkedHashSet());
	}

	/**
	 * Creates an implementation of the method returning an array of referenced entities wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> arrayOfEntityReferencesResult(
		@Nonnull String cleanReferenceName
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> new EntityReference(it.getReferencedEntityType(), it.getReferencedPrimaryKey()))
				.toArray(EntityReference[]::new);
	}

	/**
	 * Creates an implementation of the method returning an integer representing a primary key of referenced entity.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> singleEntityIdResult(
		@Nonnull String cleanReferenceName
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final Collection<ReferenceContract> references = theState.getSealedEntity().getReferences(cleanReferenceName);
			if (references.isEmpty()) {
				return null;
			} else {
				final ReferenceContract theReference = references.iterator().next();
				return theReference.getReferencedPrimaryKey();
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a list of integers representing a primary keys of referenced entities.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> listOfEntityIdsResult(@Nonnull String cleanReferenceName) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(ReferenceContract::getReferencedPrimaryKey)
				.toList();
	}

	/**
	 * Creates an implementation of the method returning a set of integers representing a primary keys of referenced entities.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> setOfEntityIdsResult(
		@Nonnull String cleanReferenceName
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(ReferenceContract::getReferencedPrimaryKey)
				.collect(CollectorUtils.toUnmodifiableLinkedHashSet());
	}

	/**
	 * Creates an implementation of the method returning an array of integers representing a primary keys of referenced entities.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> arrayOfEntityIdsResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<? extends Serializable> itemType
	) {
		if (itemType.isPrimitive()) {
			Assert.isTrue(
				int.class.equals(itemType),
				() -> "Only array of integers (`int[]`) is supported, but method returns `" + itemType + "[]`!"
			);
			return (entityClassifier, theMethod, args, theState, invokeSuper) ->
				theState.getSealedEntity().getReferences(cleanReferenceName)
					.stream().filter(Objects::nonNull)
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray();
		} else {
			return (entityClassifier, theMethod, args, theState, invokeSuper) ->
				theState.getSealedEntity().getReferences(cleanReferenceName)
					.stream().filter(Objects::nonNull)
					.map(ReferenceContract::getReferencedPrimaryKey)
					.map(it -> EvitaDataTypes.toTargetType(it, itemType))
					.toArray(count -> (Object[]) Array.newInstance(itemType, count));
		}
	}

	/**
	 * Creates an implementation of the method returning a single referenced entity wrapped into a custom proxy instance.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> singleEntityResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<? extends EntityClassifier> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final Collection<ReferenceContract> references = theState.getSealedEntity().getReferences(cleanReferenceName);
			if (references.isEmpty()) {
				return null;
			} else {
				return createProxy(cleanReferenceName, itemType, theState, references.iterator().next(), entityExtractor);
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a list of referenced entities wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> listOfEntityResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<? extends EntityClassifier> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> createProxy(it.getReferenceName(), itemType, theState, it, entityExtractor))
				.filter(Objects::nonNull)
				.toList();
	}

	/**
	 * Creates an implementation of the method returning a set of referenced entities wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> setOfEntityResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<? extends EntityClassifier> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> createProxy(it.getReferenceName(), itemType, theState, it, entityExtractor))
				.filter(Objects::nonNull)
				.collect(CollectorUtils.toUnmodifiableLinkedHashSet());
	}

	/**
	 * Creates an implementation of the method returning an array of referenced entities wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> arrayOfEntityResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<? extends EntityClassifier> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> createProxy(it.getReferenceName(), itemType, theState, it, entityExtractor))
				.filter(Objects::nonNull)
				.toArray(count -> (Object[]) Array.newInstance(itemType, count));
	}

	/**
	 * Creates an implementation of the method returning a single reference wrapped into a custom proxy instance.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> singleReferenceResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<?> itemType
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final Collection<ReferenceContract> references = theState.getSealedEntity().getReferences(cleanReferenceName);
			if (references.isEmpty()) {
				return null;
			} else {
				return theState.createReferenceProxy(itemType, references.iterator().next());
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a list of references wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> listOfReferenceResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<?> itemType
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> theState.createReferenceProxy(itemType, it))
				.toList();
	}

	/**
	 * Creates an implementation of the method returning a set of references wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> setOfReferenceResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<?> itemType
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> theState.createReferenceProxy(itemType, it))
				.collect(CollectorUtils.toUnmodifiableLinkedHashSet());
	}

	/**
	 * Creates an implementation of the method returning an array of references wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> arrayOfReferenceResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<?> itemType
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			theState.getSealedEntity().getReferences(cleanReferenceName)
				.stream()
				.map(it -> theState.createReferenceProxy(itemType, it))
				.toArray(count -> (Object[]) Array.newInstance(itemType, count));
	}

	/**
	 * Method returns implementation of the method returning referenced entities in the simple or complex (custom type)
	 * form.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> getReferencedEntity(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String cleanReferenceName,
		@Nullable Class<?> collectionType,
		@Nonnull Class<? extends EntityClassifier> itemType,
		@Nullable Entity entityInstance,
		@Nullable EntityRef entityRefInstance
	) {
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
				return singleEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getReferencedEntity);
			} else if (collectionType.isArray()) {
				return arrayOfEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getReferencedEntity);
			} else if (Set.class.isAssignableFrom(collectionType)) {
				return setOfEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getReferencedEntity);
			} else {
				return listOfEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getReferencedEntity);
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
			if (collectionType == null) {
				return singleEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getGroupEntity);
			} else if (collectionType.isArray()) {
				return arrayOfEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getGroupEntity);
			} else if (Set.class.isAssignableFrom(collectionType)) {
				return setOfEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getGroupEntity);
			} else {
				return listOfEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getGroupEntity);
			}
		} else {
			throw new EntityClassInvalidException(
				itemType,
				"Entity class type `" + itemType + "` is not compatible with reference `" +
					referenceSchema.getName() + "` of entity type `" + referencedEntityType +
					"` or group type `" + referencedGroupType + "`!"
			);
		}
	}

	/**
	 * Method returns implementation of the method returning referenced entities in the form of {@link EntityReference}.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> getEntityReference(
		@Nonnull String cleanReferenceName,
		@Nullable Class<?> collectionType
	) {
		if (collectionType == null) {
			return singleEntityReferenceResult(cleanReferenceName);
		} else if (collectionType.isArray()) {
			return arrayOfEntityReferencesResult(cleanReferenceName);
		} else if (Set.class.isAssignableFrom(collectionType)) {
			return setOfEntityReferencesResult(cleanReferenceName);
		} else {
			return listOfEntityReferencesResult(cleanReferenceName);
		}
	}

	/**
	 * Method returns implementation of the method returning referenced entities in the form of integer primary key.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> getEntityId(
		@Nonnull String cleanReferenceName,
		@Nullable Class<?> collectionType,
		@Nullable Class<? extends Serializable> itemType
	) {
		if (collectionType == null) {
			return singleEntityIdResult(cleanReferenceName);
		} else if (collectionType.isArray()) {
			return arrayOfEntityIdsResult(cleanReferenceName, itemType);
		} else if (Set.class.isAssignableFrom(collectionType)) {
			return setOfEntityIdsResult(cleanReferenceName);
		} else {
			return listOfEntityIdsResult(cleanReferenceName);
		}
	}

	/**
	 * Method returns implementation of the method returning references entities in the form of custom proxied types.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<EntityClassifier, SealedEntityProxyState> getReference(
		@Nonnull String cleanReferenceName,
		@Nullable Class<?> collectionType,
		@Nonnull Class<?> itemType) {
		if (collectionType == null) {
			return singleReferenceResult(cleanReferenceName, itemType);
		} else if (collectionType.isArray()) {
			return arrayOfReferenceResult(cleanReferenceName, itemType);
		} else if (Set.class.isAssignableFrom(collectionType)) {
			return setOfReferenceResult(cleanReferenceName, itemType);
		} else {
			return listOfReferenceResult(cleanReferenceName, itemType);
		}
	}

	public GetReferenceMethodClassifier() {
		super(
			"getReference",
			(method, proxyState) -> {
				// we are interested only in abstract methods without parameters
				if (!ClassUtils.isAbstractOrDefault(method) || method.getParameterCount() > 0) {
					return null;
				}
				// now we need to identify reference schema that is being requested
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final ReferenceSchemaContract referenceSchema = getReferenceSchema(
					method, reflectionLookup,
					proxyState.getEntitySchema()
				);
				// if not found, this method is not classified by this implementation
				if (referenceSchema == null) {
					return null;
				} else {
					// finally provide implementation that will retrieve the reference or reference entity from the entity
					final String cleanReferenceName = referenceSchema.getName();
					// now we need to identify the return type
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

					// return the appropriate result
					final Entity entityInstance = reflectionLookup.getClassAnnotation(itemType, Entity.class);
					final EntityRef entityRefInstance = reflectionLookup.getClassAnnotation(itemType, EntityRef.class);
					if (itemType.equals(EntityReference.class)) {
						return getEntityReference(cleanReferenceName, collectionType);
					} else if (Number.class.isAssignableFrom(EvitaDataTypes.toWrappedForm(itemType))) {
						//noinspection unchecked
						return getEntityId(cleanReferenceName, collectionType, itemType);
					} else if (entityInstance != null || entityRefInstance != null) {
						//noinspection unchecked
						return getReferencedEntity(
							referenceSchema, cleanReferenceName, collectionType, itemType,
							entityInstance, entityRefInstance
						);
					} else {
						return getReference(cleanReferenceName, collectionType, itemType);
					}
				}
			}
		);
	}

}
