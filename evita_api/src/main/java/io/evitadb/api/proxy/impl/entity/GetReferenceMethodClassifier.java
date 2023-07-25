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
import io.evitadb.api.proxy.impl.ProxyUtils;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
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
import java.util.function.UnaryOperator;

import static io.evitadb.api.proxy.impl.ProxyUtils.getResolvedTypes;

/**
 * Identifies methods that are used to get reference from a sealed entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetReferenceMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleEntityReferenceResult(
		@Nonnull String cleanReferenceName,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final Collection<ReferenceContract> references = theState.getSealedEntity().getReferences(cleanReferenceName);
			if (references.isEmpty()) {
				return resultWrapper.apply(null);
			} else {
				final ReferenceContract theReference = references.iterator().next();
				return resultWrapper.apply(new EntityReference(theReference.getReferencedEntityType(), theReference.getReferencedPrimaryKey()));
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a list of referenced entities wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> listOfEntityReferencesResult(
		@Nonnull String cleanReferenceName,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.apply(
				theState.getSealedEntity().getReferences(cleanReferenceName)
					.stream()
					.map(it -> new EntityReference(it.getReferencedEntityType(), it.getReferencedPrimaryKey()))
					.toList()
			);
	}

	/**
	 * Creates an implementation of the method returning a set of referenced entities wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOfEntityReferencesResult(
		@Nonnull String cleanReferenceName,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.apply(
				theState.getSealedEntity().getReferences(cleanReferenceName)
					.stream()
					.map(it -> new EntityReference(it.getReferencedEntityType(), it.getReferencedPrimaryKey()))
					.collect(CollectorUtils.toUnmodifiableLinkedHashSet())
			);
	}

	/**
	 * Creates an implementation of the method returning an array of referenced entities wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> arrayOfEntityReferencesResult(
		@Nonnull String cleanReferenceName,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.apply(
				theState.getSealedEntity().getReferences(cleanReferenceName)
					.stream()
					.map(it -> new EntityReference(it.getReferencedEntityType(), it.getReferencedPrimaryKey()))
					.toArray(EntityReference[]::new)
			);
	}

	/**
	 * Creates an implementation of the method returning an integer representing a primary key of referenced entity.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleEntityIdResult(
		@Nonnull String cleanReferenceName,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final Collection<ReferenceContract> references = theState.getSealedEntity().getReferences(cleanReferenceName);
			if (references.isEmpty()) {
				return resultWrapper.apply(null);
			} else {
				final ReferenceContract theReference = references.iterator().next();
				return resultWrapper.apply(theReference.getReferencedPrimaryKey());
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a list of integers representing a primary keys of referenced entities.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> listOfEntityIdsResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Function<Object, Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.apply(
				theState.getSealedEntity().getReferences(cleanReferenceName)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.toList()
			);
	}

	/**
	 * Creates an implementation of the method returning a set of integers representing a primary keys of referenced entities.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOfEntityIdsResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Function<Object, Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.apply(
				theState.getSealedEntity().getReferences(cleanReferenceName)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.collect(CollectorUtils.toUnmodifiableLinkedHashSet())
			);
	}

	/**
	 * Creates an implementation of the method returning an array of integers representing a primary keys of referenced entities.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> arrayOfEntityIdsResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<? extends Serializable> itemType,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		if (itemType.isPrimitive()) {
			Assert.isTrue(
				int.class.equals(itemType),
				() -> "Only array of integers (`int[]`) is supported, but method returns `" + itemType + "[]`!"
			);
			return (entityClassifier, theMethod, args, theState, invokeSuper) ->
				resultWrapper.apply(
					theState.getSealedEntity().getReferences(cleanReferenceName)
						.stream().filter(Objects::nonNull)
						.mapToInt(ReferenceContract::getReferencedPrimaryKey)
						.toArray()
				);
		} else {
			return (entityClassifier, theMethod, args, theState, invokeSuper) ->
				resultWrapper.apply(
					theState.getSealedEntity().getReferences(cleanReferenceName)
						.stream().filter(Objects::nonNull)
						.map(ReferenceContract::getReferencedPrimaryKey)
						.map(it -> EvitaDataTypes.toTargetType(it, itemType))
						.toArray(count -> (Object[]) Array.newInstance(itemType, count))
				);
		}
	}

	/**
	 * Creates an implementation of the method returning a single referenced entity wrapped into a custom proxy instance.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleEntityResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<?> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final Collection<ReferenceContract> references = theState.getSealedEntity().getReferences(cleanReferenceName);
			if (references.isEmpty()) {
				return resultWrapper.apply(null);
			} else {
				return resultWrapper.apply(
					createProxy(cleanReferenceName, itemType, theState, references.iterator().next(), entityExtractor)
				);
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a list of referenced entities wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> listOfEntityResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<?> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.apply(
				theState.getSealedEntity().getReferences(cleanReferenceName)
					.stream()
					.map(it -> createProxy(it.getReferenceName(), itemType, theState, it, entityExtractor))
					.filter(Objects::nonNull)
					.toList()
			);
	}

	/**
	 * Creates an implementation of the method returning a set of referenced entities wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOfEntityResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<?> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.apply(
				theState.getSealedEntity().getReferences(cleanReferenceName)
					.stream()
					.map(it -> createProxy(it.getReferenceName(), itemType, theState, it, entityExtractor))
					.filter(Objects::nonNull)
					.collect(CollectorUtils.toUnmodifiableLinkedHashSet())
			);
	}

	/**
	 * Creates an implementation of the method returning an array of referenced entities wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> arrayOfEntityResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<?> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		UnaryOperator<Object> resultWrapper) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.apply(
				theState.getSealedEntity().getReferences(cleanReferenceName)
					.stream()
					.map(it -> createProxy(it.getReferenceName(), itemType, theState, it, entityExtractor))
					.filter(Objects::nonNull)
					.toArray(count -> (Object[]) Array.newInstance(itemType, count))
			);
	}

	/**
	 * Creates an implementation of the method returning a single reference wrapped into a custom proxy instance.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleReferenceResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<?> itemType,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final Collection<ReferenceContract> references = theState.getSealedEntity().getReferences(cleanReferenceName);
			if (references.isEmpty()) {
				return resultWrapper.apply(null);
			} else {
				return resultWrapper.apply(
					theState.createReferenceProxy(itemType, references.iterator().next())
				);
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a list of references wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> listOfReferenceResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<?> itemType,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.apply(
				theState.getSealedEntity().getReferences(cleanReferenceName)
					.stream()
					.map(it -> theState.createReferenceProxy(itemType, it))
					.toList()
			);
	}

	/**
	 * Creates an implementation of the method returning a set of references wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOfReferenceResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<?> itemType,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.apply(
				theState.getSealedEntity().getReferences(cleanReferenceName)
					.stream()
					.map(it -> theState.createReferenceProxy(itemType, it))
					.collect(CollectorUtils.toUnmodifiableLinkedHashSet())
			);
	}

	/**
	 * Creates an implementation of the method returning an array of references wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> arrayOfReferenceResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<?> itemType,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.apply(
				theState.getSealedEntity().getReferences(cleanReferenceName)
					.stream()
					.map(it -> theState.createReferenceProxy(itemType, it))
					.toArray(count -> (Object[]) Array.newInstance(itemType, count))
			);
	}

	/**
	 * Method returns implementation of the method returning referenced entities in the simple or complex (custom type)
	 * form.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getReferencedEntity(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String cleanReferenceName,
		@Nullable Class<?> collectionType,
		@Nonnull Class<?> itemType,
		@Nullable Entity entityInstance,
		@Nullable EntityRef entityRefInstance,
		@Nonnull UnaryOperator<Object> resultWrapper
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
				return singleEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getReferencedEntity, resultWrapper);
			} else if (collectionType.isArray()) {
				return arrayOfEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getReferencedEntity, resultWrapper);
			} else if (Set.class.isAssignableFrom(collectionType)) {
				return setOfEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getReferencedEntity, resultWrapper);
			} else {
				return listOfEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getReferencedEntity, resultWrapper);
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
				return singleEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getGroupEntity, resultWrapper);
			} else if (collectionType.isArray()) {
				return arrayOfEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getGroupEntity, resultWrapper);
			} else if (Set.class.isAssignableFrom(collectionType)) {
				return setOfEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getGroupEntity, resultWrapper);
			} else {
				return listOfEntityResult(cleanReferenceName, itemType, ReferenceDecorator::getGroupEntity, resultWrapper);
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getEntityReference(
		@Nonnull String cleanReferenceName,
		@Nullable Class<?> collectionType,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		if (collectionType == null) {
			return singleEntityReferenceResult(cleanReferenceName, resultWrapper);
		} else if (collectionType.isArray()) {
			return arrayOfEntityReferencesResult(cleanReferenceName, resultWrapper);
		} else if (Set.class.isAssignableFrom(collectionType)) {
			return setOfEntityReferencesResult(cleanReferenceName, resultWrapper);
		} else {
			return listOfEntityReferencesResult(cleanReferenceName, resultWrapper);
		}
	}

	/**
	 * Method returns implementation of the method returning referenced entities in the form of integer primary key.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getEntityId(
		@Nonnull String cleanReferenceName,
		@Nullable Class<?> collectionType,
		@Nullable Class<? extends Serializable> itemType,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		if (collectionType == null) {
			return singleEntityIdResult(cleanReferenceName, resultWrapper);
		} else if (collectionType.isArray()) {
			return arrayOfEntityIdsResult(cleanReferenceName, itemType, resultWrapper);
		} else if (Set.class.isAssignableFrom(collectionType)) {
			return setOfEntityIdsResult(cleanReferenceName, resultWrapper);
		} else {
			return listOfEntityIdsResult(cleanReferenceName, resultWrapper);
		}
	}

	/**
	 * Method returns implementation of the method returning references entities in the form of custom proxied types.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getReference(
		@Nonnull String cleanReferenceName,
		@Nullable Class<?> collectionType,
		@Nonnull Class<?> itemType, UnaryOperator<Object> resultWrapper) {
		if (collectionType == null) {
			return singleReferenceResult(cleanReferenceName, itemType, resultWrapper);
		} else if (collectionType.isArray()) {
			return arrayOfReferenceResult(cleanReferenceName, itemType, resultWrapper);
		} else if (Set.class.isAssignableFrom(collectionType)) {
			return setOfReferenceResult(cleanReferenceName, itemType, resultWrapper);
		} else {
			return listOfReferenceResult(cleanReferenceName, itemType, resultWrapper);
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
					final Class<?>[] resolvedTypes = getResolvedTypes(method, proxyState.getProxyClass());
					final UnaryOperator<Object> resultWrapper = ProxyUtils.createOptionalWrapper(Optional.class.isAssignableFrom(resolvedTypes[0]) ? Optional.class : null);
					final int index = Optional.class.isAssignableFrom(resolvedTypes[0]) ? 1 : 0;

					@SuppressWarnings("rawtypes") final Class collectionType;
					@SuppressWarnings("rawtypes") final Class itemType;
					if (Collection.class.equals(resolvedTypes[index]) || List.class.isAssignableFrom(resolvedTypes[index]) || Set.class.isAssignableFrom(resolvedTypes[index])) {
						collectionType = resolvedTypes[index];
						itemType = resolvedTypes.length > index + 1 ? resolvedTypes[index + 1] : EntityReference.class;
					} else if (resolvedTypes[index].isArray()) {
						collectionType = resolvedTypes[index];
						itemType = returnType.getComponentType();
					} else {
						collectionType = null;
						itemType = resolvedTypes[index];
					}

					// return the appropriate result
					final Entity entityInstance = reflectionLookup.getClassAnnotation(itemType, Entity.class);
					final EntityRef entityRefInstance = reflectionLookup.getClassAnnotation(itemType, EntityRef.class);
					if (itemType.equals(EntityReference.class)) {
						return getEntityReference(cleanReferenceName, collectionType, resultWrapper);
					} else if (Number.class.isAssignableFrom(EvitaDataTypes.toWrappedForm(itemType))) {
						//noinspection unchecked
						return getEntityId(cleanReferenceName, collectionType, itemType, resultWrapper);
					} else if (entityInstance != null || entityRefInstance != null) {
						//noinspection unchecked
						return getReferencedEntity(
							referenceSchema, cleanReferenceName, collectionType, itemType,
							entityInstance, entityRefInstance, resultWrapper
						);
					} else {
						return getReference(cleanReferenceName, collectionType, itemType, resultWrapper);
					}
				}
			}
		);
	}

}
