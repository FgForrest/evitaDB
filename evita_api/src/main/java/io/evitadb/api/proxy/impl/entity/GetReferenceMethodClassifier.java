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

package io.evitadb.api.proxy.impl.entity;

import io.evitadb.api.exception.AmbiguousReferenceException;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.ProxyReferenceFactory;
import io.evitadb.api.proxy.impl.ProxyUtils;
import io.evitadb.api.proxy.impl.ProxyUtils.OptionalProducingOperator;
import io.evitadb.api.proxy.impl.ProxyUtils.ResultWrapper;
import io.evitadb.api.proxy.impl.ReferencedObjectType;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.proxy.impl.SealedEntityProxyState.ProxyInput;
import io.evitadb.api.proxy.impl.SealedEntityReferenceProxyState;
import io.evitadb.api.proxy.impl.entityBuilder.SetReferenceMethodClassifier.ResolvedParameter;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferenceRef;
import io.evitadb.api.requestResponse.data.annotation.ReflectedReference;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.map.LazyHashMap;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.function.ExceptionRethrowingFunction;
import io.evitadb.function.TriFunction;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.CollectorUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.NumberUtils;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.CurriedMethodContextInvocationHandler;
import one.edee.oss.proxycian.DirectMethodClassification;
import one.edee.oss.proxycian.trait.ProxyStateAccessor;
import one.edee.oss.proxycian.utils.GenericsUtils;
import one.edee.oss.proxycian.utils.GenericsUtils.GenericBundle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.api.proxy.impl.ProxyUtils.getResolvedTypes;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

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
	 * Ensures that retrieving a single result is possible based on the provided reference schema and context availability.
	 * Throws an AmbiguousReferenceException if the cardinality of the reference schema allows more than one result and
	 * no further context is available to determine a single specific result.
	 *
	 * @param referenceSchema a non-null reference schema contract that provides information about the reference cardinality.
	 * @param noFurtherContextAvailable a boolean indicating whether any additional context is available to resolve ambiguity.
	 *                                   If true and the reference schema's cardinality allows more than one result, an exception will be thrown.
	 */
	private static void assertSingleResultPossible(
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean noFurtherContextAvailable
	) {
		if (noFurtherContextAvailable && referenceSchema.getCardinality().getMax() > 1) {
			throw new AmbiguousReferenceException(
				referenceSchema.getName(),
				referenceSchema.getCardinality()
			);
		}
	}

	/**
	 * Tries to identify parent from the class field related to the constructor parameter.
	 *
	 * @param expectedType     class the constructor belongs to
	 * @param parameter        constructor parameter
	 * @param reflectionLookup reflection lookup
	 * @return attribute name derived from the annotation if found
	 */
	@Nullable
	public static <T> ExceptionRethrowingFunction<EntityContract, Object> getExtractorIfPossible(
		@Nonnull EntitySchemaContract schema,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull Class<T> expectedType,
		@Nonnull Parameter parameter,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull BiFunction<Class<?>, EntityContract, Object> proxyFactory,
		@Nonnull ProxyReferenceFactory proxyReferenceFactory
	) {
		// now we need to identify reference schema that is being requested
		final ReferenceSchemaContract referenceSchema = getReferenceSchema(
			expectedType, parameter, reflectionLookup, schema
		);
		// if not found, this method is not classified by this implementation
		if (referenceSchema == null) {
			return null;
		} else {
			// finally provide implementation that will retrieve the reference or reference entity from the entity
			final String referenceName = referenceSchema.getName();
			// now we need to identify the return type
			@SuppressWarnings("rawtypes") final Class returnType = parameter.getType();
			final Class<?>[] resolvedTypes = getResolvedTypes(parameter, expectedType);

			@SuppressWarnings("rawtypes") final Class collectionType;
			@SuppressWarnings("rawtypes") final Class itemType;
			if (Collection.class.equals(resolvedTypes[0]) || List.class.isAssignableFrom(resolvedTypes[0]) || Set.class.isAssignableFrom(resolvedTypes[0])) {
				collectionType = resolvedTypes[0];
				itemType = resolvedTypes.length > 1 ? resolvedTypes[1] : EntityReference.class;
			} else if (resolvedTypes[0].isArray()) {
				collectionType = resolvedTypes[0];
				itemType = returnType.getComponentType();
			} else {
				collectionType = null;
				itemType = resolvedTypes[0];
			}

			// return the appropriate result
			final Entity entityInstance = reflectionLookup.getClassAnnotation(itemType, Entity.class);
			final EntityRef entityRefInstance = reflectionLookup.getClassAnnotation(itemType, EntityRef.class);
			if (EntityReferenceContract.class.isAssignableFrom(itemType)) {
				return getEntityReference(referenceSchema, collectionType);
			} else if (Number.class.isAssignableFrom(EvitaDataTypes.toWrappedForm(itemType))) {
				//noinspection unchecked
				return getEntityId(referenceSchema, collectionType, itemType);
			} else if (entityInstance != null) {
				return getReferencedEntity(schema, referenceSchema, entityInstance.name(), collectionType, itemType, proxyFactory);
			} else if (entityRefInstance != null) {
				return getReferencedEntity(schema, referenceSchema, entityRefInstance.value(), collectionType, itemType, proxyFactory);
			} else {
				return getReference(referencedEntitySchemas, referenceSchema, collectionType, expectedType, itemType, proxyReferenceFactory);
			}
		}
	}

	/**
	 * Retrieves appropriate reference schema from the annotations on the method. If no Evita annotation is found
	 * it tries to match the attribute name by the name of the method.
	 */
	@Nullable
	public static ReferenceSchemaContract getReferenceSchema(
		@Nonnull Method method,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull EntitySchemaContract entitySchema
	) {
		final Reference referenceInstance = reflectionLookup.getAnnotationInstanceForProperty(method, Reference.class);
		final ReferenceRef referenceRefInstance = reflectionLookup.getAnnotationInstanceForProperty(method, ReferenceRef.class);
		final ReflectedReference reflectedReferenceInstance = reflectionLookup.getAnnotationInstanceForProperty(method, ReflectedReference.class);
		if (referenceInstance != null) {
			return entitySchema.getReferenceOrThrowException(
				ofNullable(referenceInstance.name())
					.filter(it -> !it.isBlank())
					.orElseGet(() -> ReflectionLookup.getPropertyNameFromMethodName(method.getName()))
			);
		} else if (reflectedReferenceInstance != null) {
			return entitySchema.getReferenceOrThrowException(
				ofNullable(reflectedReferenceInstance.name())
					.filter(it -> !it.isBlank())
					.orElseGet(() -> ReflectionLookup.getPropertyNameFromMethodName(method.getName()))
			);
		} else if (referenceRefInstance != null) {
			return entitySchema.getReferenceOrThrowException(referenceRefInstance.value());
		} else if (!reflectionLookup.hasAnnotationForPropertyInSamePackage(method, Reference.class) && ClassUtils.isAbstract(method)) {
			final Optional<String> referenceName = ReflectionLookup.getPropertyNameFromMethodNameIfPossible(method.getName());
			return referenceName
				.flatMap(it -> entitySchema.getReferenceByName(it, NamingConvention.CAMEL_CASE))
				.orElse(null);
		} else {
			return null;
		}
	}

	/**
	 * Retrieves appropriate reference schema from the annotations on the parameter. If no Evita annotation is found
	 * it tries to match the attribute name by the name of the parameter.
	 */
	@Nullable
	private static ReferenceSchemaContract getReferenceSchema(
		@Nonnull Class<?> expectedType,
		@Nonnull Parameter parameter,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull EntitySchemaContract entitySchema
	) {
		final String parameterName = parameter.getName();
		final Reference referenceInstance = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, Reference.class);
		final ReferenceRef referenceRefInstance = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, ReferenceRef.class);
		final ReflectedReference reflectedReferenceInstance = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, ReflectedReference.class);
		if (referenceInstance != null) {
			return entitySchema.getReferenceOrThrowException(referenceInstance.name());
		} else if (reflectedReferenceInstance != null) {
			return entitySchema.getReferenceOrThrowException(reflectedReferenceInstance.name());
		} else if (referenceRefInstance != null) {
			return entitySchema.getReferenceOrThrowException(referenceRefInstance.value());
		} else {
			final Optional<String> referenceName = ReflectionLookup.getPropertyNameFromMethodNameIfPossible(parameter.getName());
			return referenceName
				.flatMap(it -> entitySchema.getReferenceByName(it, NamingConvention.CAMEL_CASE))
				.orElse(null);
		}
	}

	/**
	 * Method wraps passed {@link ReferenceContract} into a custom proxy instance of particular type.
	 */
	@Nullable
	private static <T> T createProxy(
		@Nonnull String entityName,
		@Nonnull String referenceName,
		@Nonnull Class<T> itemType,
		@Nonnull BiFunction<Class<T>, EntityContract, T> proxyFactory,
		@Nonnull ReferenceContract reference,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor
	) {
		Assert.isTrue(
			reference instanceof ReferenceDecorator,
			() -> ContextMissingException.referencedEntityContextMissing(entityName, referenceName)
		);
		final ReferenceDecorator referenceDecorator = (ReferenceDecorator) reference;
		return entityExtractor.apply(referenceDecorator)
			.map(it -> proxyFactory.apply(itemType, it))
			.orElse(null);
	}

	/**
	 * Creates an implementation of the method returning a single referenced entity wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleEntityReferenceResult(
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
			() -> {
				final Stream<ReferenceContract> references = referenceExtractor.apply(theState.entity(), theState, args);
				if (references == null) {
					return null;
				} else {
					final Optional<ReferenceContract> firstReference = references.findFirst();
					if (firstReference.isEmpty()) {
						return null;
					} else {
						final ReferenceContract theReference = firstReference.get();
						return new EntityReference(theReference.getReferencedEntityType(), theReference.getReferencedPrimaryKey());
					}
				}
			}
		);
	}

	/**
	 * Creates an implementation of the method returning a single referenced entity wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> singleEntityReferenceResult(
		@Nonnull String referenceName
	) {
		return sealedEntity -> {
			final Optional<ReferenceContract> firstReference = sealedEntity.getReferences(referenceName)
				.stream()
				.filter(Droppable::exists)
				.findFirst();
			if (firstReference.isEmpty()) {
				return null;
			} else {
				final ReferenceContract theReference = firstReference.get();
				return new EntityReference(theReference.getReferencedEntityType(), theReference.getReferencedPrimaryKey());
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a list of referenced entities wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> listOfEntityReferencesResult(
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.wrap(
				() -> ofNullable(referenceExtractor.apply(theState.entity(), theState, args))
					.map(refs -> refs
						.map(it -> new EntityReference(it.getReferencedEntityType(), it.getReferencedPrimaryKey()))
						.toList()
					).orElse(Collections.emptyList())
			);
	}

	/**
	 * Creates an implementation of the method returning a list of referenced entities wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> listOfEntityReferencesResult(
		@Nonnull String referenceName
	) {
		return sealedEntity -> {
			if (sealedEntity.referencesAvailable(referenceName)) {
				return sealedEntity.getReferences(referenceName)
					.stream()
					.filter(Droppable::exists)
					.map(it -> new EntityReference(it.getReferencedEntityType(), it.getReferencedPrimaryKey()))
					.toList();
			} else {
				return Collections.emptyList();
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a set of referenced entities wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOfEntityReferencesResult(
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.wrap(
				() -> ofNullable(referenceExtractor.apply(theState.entity(), theState, args))
					.map(
						refs -> refs
							.map(it -> new EntityReference(it.getReferencedEntityType(), it.getReferencedPrimaryKey()))
							.collect(CollectorUtils.toUnmodifiableLinkedHashSet())
					).orElse(Collections.emptySet())
			);
	}

	/**
	 * Creates an implementation of the method returning a set of referenced entities wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> setOfEntityReferencesResult(
		@Nonnull String referenceName
	) {
		return sealedEntity -> {
			if (sealedEntity.referencesAvailable(referenceName)) {
				return sealedEntity.getReferences(referenceName)
					.stream()
					.filter(Droppable::exists)
					.map(it -> new EntityReference(it.getReferencedEntityType(), it.getReferencedPrimaryKey()))
					.collect(CollectorUtils.toUnmodifiableLinkedHashSet());
			} else {
				return Collections.emptySet();
			}
		};
	}

	/**
	 * Creates an implementation of the method returning an array of referenced entities wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> arrayOfEntityReferencesResult(
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.wrap(
				() -> ofNullable(referenceExtractor.apply(theState.entity(), theState, args))
					.map(refs -> refs
						.map(it -> new EntityReference(it.getReferencedEntityType(), it.getReferencedPrimaryKey()))
						.toArray(EntityReference[]::new)
					).orElse(null)
			);
	}

	/**
	 * Creates an implementation of the method returning an array of referenced entities wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> arrayOfEntityReferencesResult(
		@Nonnull String referenceName
	) {
		return sealedEntity -> {
			if (sealedEntity.referencesAvailable(referenceName)) {
				return sealedEntity.getReferences(referenceName)
					.stream()
					.filter(Droppable::exists)
					.map(it -> new EntityReference(it.getReferencedEntityType(), it.getReferencedPrimaryKey()))
					.toArray(EntityReference[]::new);
			} else {
				return null;
			}
		};
	}

	/**
	 * Creates an implementation of the method returning an integer representing a primary key of referenced entity.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleEntityIdResult(
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
			() -> {
				final Stream<ReferenceContract> references = referenceExtractor.apply(theState.entity(), theState, args);
				if (references == null) {
					return null;
				} else {
					final Optional<ReferenceContract> firstReference = references.findFirst();
					if (firstReference.isEmpty()) {
						return null;
					} else {
						final ReferenceContract theReference = firstReference.get();
						return theReference.getReferencedPrimaryKey();
					}
				}
			}
		);
	}

	/**
	 * Creates an implementation of the method returning an integer representing a primary key of referenced entity.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> singleEntityIdResult(
		@Nonnull String referenceName
	) {
		return sealedEntity -> {
			if (sealedEntity.referencesAvailable(referenceName)) {
				final Optional<ReferenceContract> firstReference = sealedEntity.getReferences(referenceName)
					.stream()
					.filter(Droppable::exists)
					.findFirst();
				if (firstReference.isEmpty()) {
					return null;
				} else {
					final ReferenceContract theReference = firstReference.get();
					return theReference.getReferencedPrimaryKey();
				}
			} else {
				return null;
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a list of integers representing a primary keys of referenced entities.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> listOfEntityIdsResult(
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.wrap(
				() -> ofNullable(referenceExtractor.apply(theState.entity(), theState, args))
					.map(refs -> refs
						.map(ReferenceContract::getReferencedPrimaryKey)
						.toList()
					).orElse(Collections.emptyList())
			);
	}

	/**
	 * Creates an implementation of the method returning a list of integers representing a primary keys of referenced entities.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> listOfEntityIdsResult(
		@Nonnull String referenceName
	) {
		return sealedEntity -> {
			if (sealedEntity.referencesAvailable(referenceName)) {
				return sealedEntity.getReferences(referenceName)
					.stream()
					.filter(Droppable::exists)
					.map(ReferenceContract::getReferencedPrimaryKey)
					.toList();
			} else {
				return Collections.emptyList();
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a set of integers representing a primary keys of referenced entities.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOfEntityIdsResult(
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.wrap(
				() -> ofNullable(referenceExtractor.apply(theState.entity(), theState, args))
					.map(refs -> refs
						.map(ReferenceContract::getReferencedPrimaryKey)
						.collect(CollectorUtils.toUnmodifiableLinkedHashSet())
					).orElse(Collections.emptySet())
			);
	}

	/**
	 * Creates an implementation of the method returning a set of integers representing a primary keys of referenced entities.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> setOfEntityIdsResult(
		@Nonnull String referenceName
	) {
		return sealedEntity -> {
			if (sealedEntity.referencesAvailable(referenceName)) {
				return sealedEntity.getReferences(referenceName)
					.stream()
					.filter(Droppable::exists)
					.map(ReferenceContract::getReferencedPrimaryKey)
					.collect(CollectorUtils.toUnmodifiableLinkedHashSet());
			} else {
				return Collections.emptySet();
			}
		};
	}

	/**
	 * Creates an implementation of the method returning an array of integers representing a primary keys of referenced entities.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> arrayOfEntityIdsResult(
		@Nonnull Class<?> itemType,
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		if (itemType.isPrimitive()) {
			Assert.isTrue(
				int.class.equals(itemType),
				() -> "Only array of integers (`int[]`) is supported, but method returns `" + itemType + "[]`!"
			);
			return (entityClassifier, theMethod, args, theState, invokeSuper) ->
				resultWrapper.wrap(
					() -> ofNullable(referenceExtractor.apply(theState.entity(), theState, args))
						.map(
							refs -> refs
								.filter(Objects::nonNull)
								.mapToInt(ReferenceContract::getReferencedPrimaryKey)
								.toArray()
						).orElse(null)
				);
		} else {
			//noinspection unchecked
			return (entityClassifier, theMethod, args, theState, invokeSuper) ->
				resultWrapper.wrap(
					() -> ofNullable(referenceExtractor.apply(theState.entity(), theState, args))
						.map(
							refs -> refs
								.filter(Objects::nonNull)
								.map(ReferenceContract::getReferencedPrimaryKey)
								.map(it -> EvitaDataTypes.toTargetType(it, (Class<? extends Serializable>) itemType))
								.toArray(count -> (Object[]) Array.newInstance(itemType, count))
						).orElse(null)
				);
		}
	}

	/**
	 * Creates an implementation of the method returning an array of integers representing a primary keys of referenced entities.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> arrayOfEntityIdsResult(
		@Nonnull String referenceName,
		@Nonnull Class<? extends Serializable> itemType
	) {
		if (itemType.isPrimitive()) {
			Assert.isTrue(
				int.class.equals(itemType),
				() -> "Only array of integers (`int[]`) is supported, but method returns `" + itemType + "[]`!"
			);
			return sealedEntity -> {
				if (sealedEntity.referencesAvailable(referenceName)) {
					return sealedEntity.getReferences(referenceName)
						.stream()
						.filter(Droppable::exists)
						.mapToInt(ReferenceContract::getReferencedPrimaryKey)
						.toArray();
				} else {
					return null;
				}
			};
		} else {
			return sealedEntity -> {
				if (sealedEntity.referencesAvailable(referenceName)) {
					return sealedEntity.getReferences(referenceName)
						.stream()
						.filter(Droppable::exists)
						.map(ReferenceContract::getReferencedPrimaryKey)
						.map(it -> EvitaDataTypes.toTargetType(it, itemType))
						.toArray(count -> (Object[]) Array.newInstance(itemType, count));
				} else {
					return null;
				}
			};
		}
	}

	/**
	 * Creates an implementation of the method returning a single referenced entity wrapped into a custom proxy instance.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleEntityResult(
		@Nonnull String entityName,
		@Nonnull String referenceName,
		@Nonnull Class<?> itemType,
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		@Nonnull ResultWrapper resultWrapper,
		@Nonnull ReferencedObjectType referencedObjectType
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
			() -> {
				final Stream<ReferenceContract> references = referenceExtractor.apply(theState.entity(), theState, args);
				if (references == null) {
					return null;
				} else {
					final Optional<ReferenceContract> firstReference = references.findFirst();
					if (firstReference.isEmpty()) {
						return theState.getReferencedEntityObjectIfPresent(
							referenceName, Integer.MIN_VALUE, itemType, referencedObjectType
						).orElse(null);
					} else {
						final ReferenceContract theReference = firstReference.get();
						final Optional<?> referencedInstance = theState.getReferencedEntityObjectIfPresent(
							referenceName, theReference.getReferencedPrimaryKey(), itemType, referencedObjectType
						);
						if (referencedInstance.isPresent()) {
							return referencedInstance.get();
						} else {
							Assert.isTrue(
								theReference.getReferencedEntity().isPresent(),
								() -> ContextMissingException.referencedEntityContextMissing(entityName, referenceName)
							);
							return entityExtractor
								.apply((ReferenceDecorator) theReference)
								.map(it -> theState.getOrCreateReferencedEntityProxy(referenceName, itemType, it,
								                                                     referencedObjectType
								))
								.orElse(null);
						}
					}
				}
			}
		);
	}

	/**
	 * Creates an implementation of the method returning a single referenced entity wrapped into a custom proxy instance.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> singleEntityResult(
		@Nonnull String entityName,
		@Nonnull String referenceName,
		@Nonnull Class<?> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		@Nonnull BiFunction<Class<?>, EntityContract, Object> proxyFactory
	) {
		return sealedEntity -> {
			if (sealedEntity.referencesAvailable(referenceName)) {
				final Optional<ReferenceContract> firstReference = sealedEntity.getReferences(referenceName)
					.stream()
					.filter(Droppable::exists)
					.findFirst();
				//noinspection rawtypes,unchecked
				return firstReference
					.map(
						referenceContract -> createProxy(
							entityName, referenceName, (Class)itemType, (BiFunction)proxyFactory, referenceContract, entityExtractor
						)
					)
					.orElse(null);
			} else {
				return null;
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a list of referenced entities wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> listOfEntityResult(
		@Nonnull String entityName,
		@Nonnull String referenceName,
		@Nonnull Class<?> itemType,
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		@Nonnull ResultWrapper resultWrapper,
		@Nonnull ReferencedObjectType referencedObjectType
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.wrap(
				() -> ofNullable(referenceExtractor.apply(theState.entity(), theState, args))
					.map(
						refs -> refs
							.map(
								it -> createProxy(
									entityName, referenceName, itemType,
									(type, entity) -> theState.getOrCreateReferencedEntityProxy(
										referenceName, type, entity, referencedObjectType
									),
									it, entityExtractor
								)
							)
							.filter(Objects::nonNull)
							.toList()
					).orElse(Collections.emptyList())
			);
	}

	/**
	 * Creates an implementation of the method returning a list of referenced entities wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> listOfEntityResult(
		@Nonnull String entityName,
		@Nonnull String referenceName,
		@Nonnull Class<?> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		@Nonnull BiFunction<Class<?>, EntityContract, Object> proxyFactory
	) {
		return sealedEntity -> {
			if (sealedEntity.referencesAvailable(referenceName)) {
				final Collection<ReferenceContract> references = sealedEntity.getReferences(referenceName);
				if (references.isEmpty()) {
					return Collections.emptyList();
				} else {
					//noinspection rawtypes,unchecked
					return references.stream()
						.filter(Droppable::exists)
						.map(it -> createProxy(entityName, referenceName, (Class)itemType, (BiFunction)proxyFactory, it, entityExtractor))
						.filter(Objects::nonNull)
						.toList();
				}
			} else {
				return Collections.emptyList();
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a set of referenced entities wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOfEntityResult(
		@Nonnull String entityName,
		@Nonnull String referenceName,
		@Nonnull Class<?> itemType,
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		@Nonnull ResultWrapper resultWrapper,
		@Nonnull ReferencedObjectType referencedObjectType
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.wrap(
				() -> ofNullable(referenceExtractor.apply(theState.entity(), theState, args))
					.map(
						refs -> refs
							.map(
								it -> createProxy(
									entityName, referenceName, itemType,
									(type, entity) -> theState.getOrCreateReferencedEntityProxy(
										referenceName, type, entity, referencedObjectType
									),
									it, entityExtractor
								)
							)
							.filter(Objects::nonNull)
							.collect(CollectorUtils.toUnmodifiableLinkedHashSet())
					).orElse(Collections.emptySet())
			);
	}

	/**
	 * Creates an implementation of the method returning a set of referenced entities wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> setOfEntityResult(
		@Nonnull String entityName,
		@Nonnull String referenceName,
		@Nonnull Class<?> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		@Nonnull BiFunction<Class<?>, EntityContract, Object> proxyFactory
	) {
		return sealedEntity -> {
			if (sealedEntity.referencesAvailable(referenceName)) {
				final Collection<ReferenceContract> references = sealedEntity.getReferences(referenceName);
				if (references.isEmpty()) {
					return Collections.emptySet();
				} else {
					//noinspection rawtypes,unchecked
					return references.stream()
						.filter(Droppable::exists)
						.map(it -> createProxy(entityName, referenceName, (Class)itemType, (BiFunction)proxyFactory, it, entityExtractor))
						.filter(Objects::nonNull)
						.collect(CollectorUtils.toUnmodifiableLinkedHashSet());
				}
			} else {
				return Collections.emptySet();
			}
		};
	}

	/**
	 * Creates an implementation of the method returning an array of referenced entities wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> arrayOfEntityResult(
		@Nonnull String entityName,
		@Nonnull String referenceName,
		@Nonnull Class<?> itemType,
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		@Nonnull ResultWrapper resultWrapper,
		@Nonnull ReferencedObjectType referencedObjectType
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.wrap(
				() -> ofNullable(referenceExtractor.apply(theState.entity(), theState, args))
					.map(
						refs -> refs
							.map(
								it -> createProxy(
									entityName, referenceName, itemType,
									(type, entity) -> theState.getOrCreateReferencedEntityProxy(
										referenceName, type, entity, referencedObjectType
									),
									it, entityExtractor
								)
							)
							.filter(Objects::nonNull)
							.toArray(count -> (Object[]) Array.newInstance(itemType, count))
					).orElse(null)
			);
	}

	/**
	 * Creates an implementation of the method returning an array of referenced entities wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> arrayOfEntityResult(
		@Nonnull String entityName,
		@Nonnull String referenceName,
		@Nonnull Class<?> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		@Nonnull BiFunction<Class<?>, EntityContract, Object> proxyFactory
	) {
		return sealedEntity -> {
			if (sealedEntity.referencesAvailable(referenceName)) {
				final Collection<ReferenceContract> references = sealedEntity.getReferences(referenceName);
				if (references.isEmpty()) {
					return null;
				} else {
					//noinspection rawtypes,unchecked
					return references.stream()
						.filter(Droppable::exists)
						.map(it -> createProxy(entityName, referenceName, (Class)itemType, (BiFunction)proxyFactory, it, entityExtractor))
						.filter(Objects::nonNull)
						.toArray(count -> (Object[]) Array.newInstance(itemType, count));
				}
			} else {
				return null;
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a single reference wrapped into a custom proxy instance.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> singleReferenceResult(
		@Nonnull String referenceName,
		@Nonnull Class<?> mainType,
		@Nonnull Class<?> itemType,
		@Nonnull ProxyReferenceFactory proxyReferenceFactory,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas
	) {
		return sealedEntity -> {
			if (sealedEntity.referencesAvailable(referenceName)) {
				final Optional<ReferenceContract> firstReference = sealedEntity.getReferences(referenceName)
					.stream()
					.filter(Droppable::exists)
					.findFirst();
				return firstReference.map(
						referenceContract -> proxyReferenceFactory.createEntityReferenceProxy(
							mainType, itemType, sealedEntity, referencedEntitySchemas, referenceContract,
							new LazyHashMap<>(4)
						)
					)
					.orElse(null);
			} else {
				return null;
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a single reference wrapped into a custom proxy instance.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleReferenceResult(
		@Nonnull Class<?> itemType,
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
			() -> {
				final EntityContract entity = theState.entity();
				final Stream<ReferenceContract> references = referenceExtractor.apply(entity, theState, args);

				if (references == null) {
					return null;
				} else {
					final ReferenceContract[] referencesAsArray = references.toArray(ReferenceContract[]::new);
					if (referencesAsArray.length > 1) {
						final ReferenceContract firstReference = referencesAsArray[0];
						throw new AmbiguousReferenceException(
							firstReference.getReferenceName(),
							firstReference.getReferenceCardinality()
						);
					} else if (referencesAsArray.length == 0) {
						return null;
					} else {
						final ReferenceContract ref = referencesAsArray[0];
						return theState.getOrCreateEntityReferenceProxy(
							ref.getReferenceSchemaOrThrow(),
							itemType,
							ref,
							ProxyInput.READ_ONLY_REFERENCE
						);
					}
				}
			}
		);
	}

	/**
	 * Creates an implementation of the method returning a single reference by matching its primary key wrapped into
	 * a custom proxy instance.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleReferenceResultById(
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean noFurtherContextAvailable,
		@Nonnull Class<?> itemType,
		@Nonnull TriFunction<EntityContract, String, Integer, Optional<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		final String referenceName = referenceSchema.getName();
		assertSingleResultPossible(referenceSchema, noFurtherContextAvailable);
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
			() -> {
				final EntityContract entity = theState.entity();
				final Integer referencedId = Objects.requireNonNull(
					EvitaDataTypes.toTargetType((Serializable) args[0], int.class)
				);
				return referenceExtractor
					.apply(entity, referenceName, referencedId)
					.map(
						ref -> theState.getOrCreateEntityReferenceProxy(
							ref.getReferenceSchemaOrThrow(),
							itemType,
							ref,
							ProxyInput.READ_ONLY_REFERENCE
						)
					)
					.orElse(null);
			}
		);
	}

	/**
	 * Creates an implementation of the method returning a list of references wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> listOfReferenceResult(
		@Nonnull String referenceName,
		@Nonnull Class<?> mainType,
		@Nonnull Class<?> itemType,
		@Nonnull ProxyReferenceFactory proxyReferenceFactory,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas
	) {
		return sealedEntity -> {
			if (sealedEntity.referencesAvailable(referenceName)) {
				return sealedEntity.getReferences(referenceName)
					.stream()
					.filter(Droppable::exists)
					.map(
						it -> proxyReferenceFactory.createEntityReferenceProxy(
							mainType,
							itemType,
							sealedEntity,
							referencedEntitySchemas,
							it,
							new LazyHashMap<>(4)
						)
					)
					.toList();
			} else {
				return Collections.emptyList();
			}
		};
	}

	/**
	 * Creates an implementation of the method returning a list of references wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> listOfReferenceResult(
		@Nonnull Class<?> itemType,
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final EntityContract entity = theState.entity();
			return resultWrapper.wrap(
				() -> ofNullable(referenceExtractor.apply(entity, theState, args))
					.map(
						refs -> refs
							.map(
								it -> theState.getOrCreateEntityReferenceProxy(
									it.getReferenceSchemaOrThrow(),
									itemType,
									it,
									ProxyInput.READ_ONLY_REFERENCE
								)
							)
							.toList()
					).orElse(Collections.emptyList())
			);
		};
	}

	/**
	 * Creates an implementation of the method returning a set of references wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOfReferenceResult(
		@Nonnull Class<?> itemType,
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final EntityContract entity = theState.entity();
			return resultWrapper.wrap(
				() -> ofNullable(referenceExtractor.apply(entity, theState, args))
					.map(
						refs -> refs
							.map(
								it -> theState.getOrCreateEntityReferenceProxy(
									it.getReferenceSchemaOrThrow(),
									itemType,
									it,
									ProxyInput.READ_ONLY_REFERENCE
								)
							)
							.collect(CollectorUtils.toUnmodifiableLinkedHashSet())
					).orElse(Collections.emptySet())
			);
		};
	}

	/**
	 * Creates an implementation of the method returning a set of references wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> setOfReferenceResult(
		@Nonnull String referenceName,
		@Nonnull Class<?> mainType,
		@Nonnull Class<?> itemType,
		@Nonnull ProxyReferenceFactory proxyReferenceFactory,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas
	) {
		return sealedEntity -> {
			if (sealedEntity.referencesAvailable(referenceName)) {
				return sealedEntity.getReferences(referenceName)
					.stream()
					.filter(Droppable::exists)
					.map(
						it -> proxyReferenceFactory.createEntityReferenceProxy(
							mainType, itemType, sealedEntity, referencedEntitySchemas, it,
							new LazyHashMap<>(4)
						)
					)
					.collect(CollectorUtils.toUnmodifiableLinkedHashSet());
			} else {
				return Collections.emptySet();
			}
		};
	}

	/**
	 * Creates an implementation of the method returning an array of references wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> arrayOfReferenceResult(
		@Nonnull Class<?> itemType,
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final EntityContract entity = theState.entity();
			return resultWrapper.wrap(
				() -> ofNullable(referenceExtractor.apply(entity, theState, args))
					.map(
						refs -> refs
							.map(
								it -> theState.getOrCreateEntityReferenceProxy(
									it.getReferenceSchemaOrThrow(),
									itemType,
									it,
									ProxyInput.READ_ONLY_REFERENCE
								)
							)
							.toArray(count -> (Object[]) Array.newInstance(itemType, count))
					).orElse(null)
			);
		};
	}

	/**
	 * Creates an implementation of the method returning an array of references wrapped into a custom proxy instances.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> arrayOfReferenceResult(
		@Nonnull String referenceName,
		@Nonnull Class<?> mainType,
		@Nonnull Class<?> itemType,
		@Nonnull ProxyReferenceFactory proxyReferenceFactory,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas
	) {
		return sealedEntity -> {
			if (sealedEntity.referencesAvailable(referenceName)) {
				return sealedEntity.getReferences(referenceName)
					.stream()
					.filter(Droppable::exists)
					.map(
						it -> proxyReferenceFactory.createEntityReferenceProxy(
							mainType, itemType, sealedEntity, referencedEntitySchemas, it,
							new LazyHashMap<>(4)
						)
					)
					.toArray(count -> (Object[]) Array.newInstance(itemType, count));
			} else {
				return null;
			}
		};
	}

	/**
	 * Method returns implementation of the method returning referenced entities in the simple or complex (custom type)
	 * form.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getReferencedEntity(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean noFurtherContextAvailable,
		@Nonnull String targetEntityType,
		@Nullable Class<?> collectionType,
		@Nonnull Class<?> itemType,
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		// we return directly the referenced entity - either it or its group by matching the entity referenced name
		final String entityName = entitySchema.getName();
		final String referenceName = referenceSchema.getName();
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		final String referencedGroupType = referenceSchema.getReferencedGroupType();

		if (targetEntityType.equals(referencedEntityType)) {
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
				assertSingleResultPossible(referenceSchema, noFurtherContextAvailable);
				return singleEntityResult(
					entityName, referenceName, itemType, referenceExtractor,
					ReferenceDecorator::getReferencedEntity, resultWrapper, ReferencedObjectType.TARGET
				);
			} else if (collectionType.isArray()) {
				return arrayOfEntityResult(
					entityName, referenceName, itemType, referenceExtractor,
					ReferenceDecorator::getReferencedEntity, resultWrapper, ReferencedObjectType.TARGET
				);
			} else if (Set.class.isAssignableFrom(collectionType)) {
				return setOfEntityResult(
					entityName, referenceName, itemType, referenceExtractor,
					ReferenceDecorator::getReferencedEntity, resultWrapper, ReferencedObjectType.TARGET
				);
			} else {
				return listOfEntityResult(
					entityName, referenceName, itemType, referenceExtractor,
					ReferenceDecorator::getReferencedEntity, resultWrapper, ReferencedObjectType.TARGET
				);
			}
		} else if (targetEntityType.equals(referencedGroupType)) {
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
				return singleEntityResult(
					entityName, referenceName, itemType, referenceExtractor,
					ReferenceDecorator::getGroupEntity, resultWrapper, ReferencedObjectType.GROUP
				);
			} else if (collectionType.isArray()) {
				return arrayOfEntityResult(
					entityName, referenceName, itemType, referenceExtractor,
					ReferenceDecorator::getGroupEntity, resultWrapper, ReferencedObjectType.GROUP
				);
			} else if (Set.class.isAssignableFrom(collectionType)) {
				return setOfEntityResult(
					entityName, referenceName, itemType, referenceExtractor,
					ReferenceDecorator::getGroupEntity, resultWrapper, ReferencedObjectType.GROUP
				);
			} else {
				return listOfEntityResult(
					entityName, referenceName, itemType, referenceExtractor,
					ReferenceDecorator::getGroupEntity, resultWrapper, ReferencedObjectType.GROUP
				);
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
	 * Method returns implementation of the method returning referenced entities in the simple or complex (custom type)
	 * form.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> getReferencedEntity(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String targetEntityName,
		@Nullable Class<?> collectionType,
		@Nonnull Class<?> itemType,
		@Nonnull BiFunction<Class<?>, EntityContract, Object> proxyFactory
	) {
		// we return directly the referenced entity - either it or its group by matching the entity referenced name
		final String entityName = entitySchema.getName();
		final String referenceName = referenceSchema.getName();

		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		final String referencedGroupType = referenceSchema.getReferencedGroupType();
		if (targetEntityName.equals(referencedEntityType)) {
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
				assertSingleResultPossible(referenceSchema, true);
				return singleEntityResult(entityName, referenceName, itemType, ReferenceDecorator::getReferencedEntity, proxyFactory);
			} else if (collectionType.isArray()) {
				return arrayOfEntityResult(entityName, referenceName, itemType, ReferenceDecorator::getReferencedEntity, proxyFactory);
			} else if (Set.class.isAssignableFrom(collectionType)) {
				return setOfEntityResult(entityName, referenceName, itemType, ReferenceDecorator::getReferencedEntity, proxyFactory);
			} else {
				return listOfEntityResult(entityName, referenceName, itemType, ReferenceDecorator::getReferencedEntity, proxyFactory);
			}
		} else if (targetEntityName.equals(referencedGroupType)) {
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
				assertSingleResultPossible(referenceSchema, true);
				return singleEntityResult(entityName, referenceName, itemType, ReferenceDecorator::getGroupEntity, proxyFactory);
			} else if (collectionType.isArray()) {
				return arrayOfEntityResult(entityName, referenceName, itemType, ReferenceDecorator::getGroupEntity, proxyFactory);
			} else if (Set.class.isAssignableFrom(collectionType)) {
				return setOfEntityResult(entityName, referenceName, itemType, ReferenceDecorator::getGroupEntity, proxyFactory);
			} else {
				return listOfEntityResult(entityName, referenceName, itemType, ReferenceDecorator::getGroupEntity, proxyFactory);
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
	private static ExceptionRethrowingFunction<EntityContract, Object> getEntityReference(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Class<?> collectionType
	) {
		final String referenceName = referenceSchema.getName();
		if (collectionType == null) {
			assertSingleResultPossible(referenceSchema, true);
			return singleEntityReferenceResult(referenceName);
		} else if (collectionType.isArray()) {
			return arrayOfEntityReferencesResult(referenceName);
		} else if (Set.class.isAssignableFrom(collectionType)) {
			return setOfEntityReferencesResult(referenceName);
		} else {
			return listOfEntityReferencesResult(referenceName);
		}
	}

	/**
	 * Method returns implementation of the method returning referenced entities in the form of {@link EntityReference}.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getEntityReference(
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean noFurtherContextAvailable,
		@Nullable Class<?> collectionType,
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		if (collectionType == null) {
			assertSingleResultPossible(referenceSchema, noFurtherContextAvailable);
			return singleEntityReferenceResult(referenceExtractor, resultWrapper);
		} else if (collectionType.isArray()) {
			return arrayOfEntityReferencesResult(referenceExtractor, resultWrapper);
		} else if (Set.class.isAssignableFrom(collectionType)) {
			return setOfEntityReferencesResult(referenceExtractor, resultWrapper);
		} else {
			return listOfEntityReferencesResult(referenceExtractor, resultWrapper);
		}
	}

	/**
	 * Method returns implementation of the method returning referenced entities in the form of integer primary key.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getEntityId(
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean noFurtherContextAvailable,
		@Nullable Class<?> collectionType,
		@Nonnull Class<?> itemType,
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		if (collectionType == null) {
			assertSingleResultPossible(referenceSchema, noFurtherContextAvailable);
			return singleEntityIdResult(referenceExtractor, resultWrapper);
		} else if (collectionType.isArray()) {
			return arrayOfEntityIdsResult(itemType, referenceExtractor, resultWrapper);
		} else if (Set.class.isAssignableFrom(collectionType)) {
			return setOfEntityIdsResult(referenceExtractor, resultWrapper);
		} else {
			return listOfEntityIdsResult(referenceExtractor, resultWrapper);
		}
	}

	/**
	 * Method returns implementation of the method returning referenced entities in the form of integer primary key.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> getEntityId(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Class<?> collectionType,
		@Nonnull Class<? extends Serializable> itemType
	) {
		final String referenceName = referenceSchema.getName();
		if (collectionType == null) {
			assertSingleResultPossible(referenceSchema, true);
			return singleEntityIdResult(referenceName);
		} else if (collectionType.isArray()) {
			return arrayOfEntityIdsResult(referenceName, itemType);
		} else if (Set.class.isAssignableFrom(collectionType)) {
			return setOfEntityIdsResult(referenceName);
		} else {
			return listOfEntityIdsResult(referenceName);
		}
	}

	/**
	 * Method returns implementation of the method returning references entities in the form of custom proxied types.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getReference(
		@Nonnull ReferenceSchemaContract referenceSchema,
		boolean noFurtherContextAvailable,
		@Nullable Class<?> collectionType,
		@Nonnull TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor,
		@Nonnull Class<?> itemType,
		@Nonnull ResultWrapper resultWrapper
	) {
		if (collectionType == null) {
			assertSingleResultPossible(referenceSchema, noFurtherContextAvailable);
			return singleReferenceResult(itemType, referenceExtractor, resultWrapper);
		} else if (collectionType.isArray()) {
			return arrayOfReferenceResult(itemType, referenceExtractor, resultWrapper);
		} else if (Set.class.isAssignableFrom(collectionType)) {
			return setOfReferenceResult(itemType, referenceExtractor, resultWrapper);
		} else {
			return listOfReferenceResult(itemType, referenceExtractor, resultWrapper);
		}
	}

	/**
	 * Method returns implementation of the method returning references entities in the form of custom proxied types.
	 */
	@Nonnull
	private static ExceptionRethrowingFunction<EntityContract, Object> getReference(
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Class<?> collectionType,
		@Nonnull Class<?> mainType,
		@Nonnull Class<?> itemType,
		@Nonnull ProxyReferenceFactory proxyReferenceFactory
	) {
		final String referenceName = referenceSchema.getName();
		if (collectionType == null) {
			assertSingleResultPossible(referenceSchema, true);
			return singleReferenceResult(referenceName, mainType, itemType, proxyReferenceFactory, referencedEntitySchemas);
		} else if (collectionType.isArray()) {
			return arrayOfReferenceResult(referenceName, mainType, itemType, proxyReferenceFactory, referencedEntitySchemas);
		} else if (Set.class.isAssignableFrom(collectionType)) {
			return setOfReferenceResult(referenceName, mainType, itemType, proxyReferenceFactory, referencedEntitySchemas);
		} else {
			return listOfReferenceResult(referenceName, mainType, itemType, proxyReferenceFactory, referencedEntitySchemas);
		}
	}

	public GetReferenceMethodClassifier() {
		super(
			"getReference",
			(method, proxyState) -> {
				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();

				// we are interested only in abstract methods without parameters
				if (returnType.equals(proxyState.getProxyClass()) ||
					void.class.equals(returnType) ||
					method.isAnnotationPresent(CreateWhenMissing.class) ||
					Arrays.stream(method.getParameterAnnotations()).flatMap(Arrays::stream).anyMatch(CreateWhenMissing.class::isInstance) ||
					method.isAnnotationPresent(RemoveWhenExists.class) ||
					Arrays.stream(method.getParameterAnnotations()).flatMap(Arrays::stream).anyMatch(RemoveWhenExists.class::isInstance)
				) {
					return null;
				}

				// now we need to identify reference schema that is being requested
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final EntitySchemaContract entitySchema = proxyState.getEntitySchema();
				final ReferenceSchemaContract referenceSchema = getReferenceSchema(
					method, reflectionLookup, entitySchema
				);
				// if not found, this method is not classified by this implementation
				if (referenceSchema == null) {
					return null;
				} else {
					// now we need to identify the return type
					final Class<?>[] resolvedTypes = getResolvedTypes(method, proxyState.getProxyClass());
					final ResultWrapper resultWrapper = ProxyUtils.createOptionalWrapper(
						method, Optional.class.isAssignableFrom(resolvedTypes[0]) ? Optional.class : null
					);
					final int index = Optional.class.isAssignableFrom(resolvedTypes[0]) ? 1 : 0;

					final Class<?> collectionType;
					final Class<?> itemType;
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

					final ParsedArguments parsedArguments = getParsedArguments(method, proxyState);
					final boolean noFurtherContextAvailable = method.getParameterCount() == 0;

					@Nonnull final TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> referenceExtractor =
						parsedArguments.getReferenceExtractor(referenceSchema, resultWrapper);

					// return the appropriate result
					final Entity entityInstance = reflectionLookup.getClassAnnotation(itemType, Entity.class);
					final EntityRef entityRefInstance = reflectionLookup.getClassAnnotation(itemType, EntityRef.class);
					if (EntityReferenceContract.class.isAssignableFrom(itemType)) {
						return getEntityReference(referenceSchema, noFurtherContextAvailable, collectionType, referenceExtractor, resultWrapper);
					} else if (Number.class.isAssignableFrom(EvitaDataTypes.toWrappedForm(itemType))) {
						return getEntityId(referenceSchema, noFurtherContextAvailable, collectionType, itemType, referenceExtractor, resultWrapper);
					} else if (entityInstance != null) {
						return getReferencedEntity(
							entitySchema, referenceSchema, noFurtherContextAvailable,
							entityInstance.name(), collectionType, itemType, referenceExtractor, resultWrapper
						);
					} else if (entityRefInstance != null) {
						return getReferencedEntity(
							entitySchema, referenceSchema, noFurtherContextAvailable,
							entityRefInstance.value(), collectionType, itemType, referenceExtractor, resultWrapper
						);
					} else if (method.getParameterCount() == 1 && NumberUtils.isIntConvertibleNumber(method.getParameterTypes()[0]) && collectionType == null) {
						@Nonnull final TriFunction<EntityContract, String, Integer, Optional<ReferenceContract>> referenceByIdExtractor =
							resultWrapper instanceof OptionalProducingOperator ?
								(entity, theReferenceName, referencedEPK) -> entity.referencesAvailable(theReferenceName) ?
									entity.getReference(theReferenceName, referencedEPK).filter(Droppable::exists) : empty() :
								(theEntity, theRefName, referencedEPK) -> theEntity.getReference(theRefName, referencedEPK).filter(Droppable::exists);

						return singleReferenceResultById(
							referenceSchema, noFurtherContextAvailable, itemType, referenceByIdExtractor, resultWrapper
						);
					} else {
						return getReference(
							referenceSchema, noFurtherContextAvailable, collectionType, referenceExtractor, itemType, resultWrapper
						);
					}
				}
			}
		);
	}

	/**
	 * Parses the arguments for the given method and proxy state to determine their indices,
	 * types, and possible predicates in the context of the provided method implementation.
	 *
	 * @param method the method whose arguments are being inspected
	 * @param proxyState the proxy state associated with the sealed entity
	 * @return a ParsedArguments object containing information about the parsed arguments, such as indices of
	 *         referenced IDs, predicates, resolved parameter information, and constant predicates
	 */
	@Nonnull
	private static ParsedArguments getParsedArguments(
		@Nonnull Method method,
		@Nonnull SealedEntityProxyState proxyState
	) {
		OptionalInt referencedIdIndex = OptionalInt.empty();
		OptionalInt predicateIndex = OptionalInt.empty();
		Optional<ResolvedParameter> predicate = empty();
		Optional<BiPredicate<Object[], Object>> constantPredicate = empty();

		for (int i = 0; i < method.getParameterCount(); i++) {
			final int parameterIndex = i;
			final Class<?> parameterType = method.getParameterTypes()[i];
			final Optional<AttributeRef> attributeRef = Arrays.stream(method.getParameterAnnotations()[i])
			                                                  .filter(AttributeRef.class::isInstance)
			                                                  .map(AttributeRef.class::cast)
			                                                  .findFirst();
			final Parameter parameter = method.getParameters()[i];
			final String argumentName = attributeRef.map(AttributeRef::value)
			                                        .orElseGet(parameter::getName);

			if (NumberUtils.isIntConvertibleNumber(parameterType)) {
				referencedIdIndex = OptionalInt.of(i);
			} else if (Predicate.class.isAssignableFrom(parameterType)) {
				final List<GenericBundle> genericType = GenericsUtils.getGenericType(
					proxyState.getProxyClass(), method.getGenericParameterTypes()[i]);
				predicateIndex = OptionalInt.of(i);
				predicate = of(
					new ResolvedParameter(
						method.getParameterTypes()[i],
						genericType.get(0).getResolvedType()
					)
				);
			} else if (attributeRef.isPresent()) {
				final BiPredicate<Object[], Object> argumentPredicate;
				if (EvitaDataTypes.isSupportedType(parameterType)) {
					argumentPredicate = (args, reference) -> {
						if (reference instanceof ProxyStateAccessor psa && psa.getProxyState() instanceof SealedEntityReferenceProxyState serps) {
							return Objects.equals(
								args[parameterIndex], serps.getReference().getAttribute(argumentName));
						} else if (reference instanceof ReferenceContract rc) {
							return Objects.equals(args[parameterIndex], rc.getAttribute(argumentName));
						} else {
							throw new EvitaInvalidUsageException(
								"Unsupported argument type in automatic reference attribute predicate implementation! " +
									"Supported are only `SealedEntityReferenceProxy` and `ReferenceContract`. " +
									"Offending method: " + method
							);
						}
					};
				} else if (EvitaDataTypes.isSupportedTypeOrItsArray(parameterType)) {
					argumentPredicate = (args, reference) -> {
						final Serializable attributeValue;
						if (reference instanceof ProxyStateAccessor psa && psa.getProxyState() instanceof SealedEntityReferenceProxyState serps) {
							attributeValue = serps.getReference().getAttribute(argumentName);
						} else if (reference instanceof ReferenceContract rc) {
							attributeValue = rc.getAttribute(argumentName);
						} else {
							throw new EvitaInvalidUsageException(
								"Unsupported argument type in automatic reference attribute predicate implementation! " +
									"Supported are only `SealedEntityReferenceProxy` and `ReferenceContract`. " +
									"Offending method: " + method
							);
						}
						return Arrays.asList((Object[]) args[parameterIndex]).contains(attributeValue);
					};
				} else if (parameterType.isEnum()) {
					argumentPredicate = (args, reference) -> {
						if (reference instanceof ProxyStateAccessor psa && psa.getProxyState() instanceof SealedEntityReferenceProxyState serps) {
							return Objects.equals(
								((Enum<?>) args[parameterIndex]).name(),
								serps.getReference().getAttribute(argumentName)
							);
						} else if (reference instanceof ReferenceContract rc) {
							return Objects.equals(
								((Enum<?>) args[parameterIndex]).name(), rc.getAttribute(argumentName));
						} else {
							throw new EvitaInvalidUsageException(
								"Unsupported argument type in automatic reference attribute predicate implementation! " +
									"Supported are only `SealedEntityReferenceProxy` and `ReferenceContract`. " +
									"Offending method: " + method
							);
						}
					};
				} else {
					throw new EvitaInvalidUsageException(
						"Attribute reference can only be of supported attribute types! " +
							"Supported are primitive types, their arrays and enums. " +
							"Offending method: " + method
					);
				}
				//noinspection OptionalIsPresent
				if (constantPredicate.isEmpty()) {
					constantPredicate = of(argumentPredicate);
				} else {
					constantPredicate = of(constantPredicate.get().and(argumentPredicate));
				}
			}
		}

		return new ParsedArguments(
			referencedIdIndex, predicateIndex, predicate, constantPredicate
		);
	}

	/**
	 * ParsedArguments is a record class that represents the parsed arguments for a method call.
	 * It contains optional indexes for the referencedId and consumer, optional resolved type for the referencedType,
	 * a Class object for the return type, and an optional RecognizedContext object for the entityRecognizedIn.
	 *
	 * @param referencedIdIndex  the index of the referenced id parameter
	 * @param predicateIndex     the index of the predicate parameter
	 * @param predicateType      the resolved type of the predicate
	 * @param constantPredicate  the constant predicate if any
	 */
	private record ParsedArguments(
		@Nonnull OptionalInt referencedIdIndex,
		@Nonnull OptionalInt predicateIndex,
		@Nonnull Optional<ResolvedParameter> predicateType,
		@Nonnull Optional<BiPredicate<Object[], Object>> constantPredicate
	) {

		@SuppressWarnings("unchecked")
		@Nonnull
		public TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> getReferenceExtractor(
			@Nonnull ReferenceSchemaContract referenceSchema,
			@Nonnull ResultWrapper resultWrapper
		) {
			final String referenceName = referenceSchema.getName();
			final TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> retrievalFct;
			if (this.referencedIdIndex.isEmpty()) {
				retrievalFct = (entity, theState, args) ->
					entity.getReferences(referenceName).stream().filter(Droppable::exists);
			} else {
				final int index = this.referencedIdIndex.getAsInt();
				retrievalFct = (entity, theState, args) -> {
					final Number referencedEntityId = (Number) args[index];
					return entity.getReferences(referenceName, referencedEntityId.intValue())
						      .stream()
						      .filter(Droppable::exists);
				};
			}

			final TriFunction<EntityContract, SealedEntityProxyState, Object[], Stream<ReferenceContract>> finalRetrievalFct;
			if (this.predicateIndex.isPresent() || this.constantPredicate.isPresent()) {
				finalRetrievalFct = (entity, theState, args) -> {
					final Predicate<Object> predicate = this.predicateIndex.isPresent() ?
						(Predicate<Object>) args[this.predicateIndex.getAsInt()] : null;
					final Predicate<Object> combinedPredicate = combinePredicates(
						predicate, this.constantPredicate.orElse(null), args);
					return retrievalFct.apply(entity, theState, args)
						            .filter(reference -> {
							            final Object objectToTest;
							            if (predicateType().isPresent()) {
								            objectToTest = theState.getOrCreateEntityReferenceProxy(
									            referenceSchema,
									            this.predicateType().get().resolvedType(),
									            reference,
									            ProxyInput.READ_ONLY_REFERENCE
								            );
							            } else {
								            objectToTest = reference;
							            }
							            return combinedPredicate.test(objectToTest);
						            });
				};
			} else {
				finalRetrievalFct = retrievalFct;
			}

			if (resultWrapper instanceof OptionalProducingOperator) {
				return (theEntity, theState, args) ->
					theEntity.referencesAvailable(referenceName) ?
						finalRetrievalFct.apply(theEntity, theState, args) : null;
			} else {
				return finalRetrievalFct;
			}
		}

		/**
		 * Combines a given constant bi-predicate and a predicate into a single predicate.
		 * The combined predicate is constructed based on the provided inputs.
		 *
		 *
		 * @param predicate         an additional predicate to be combined with the constant bi-predicate; can be null
		 * @param constantPredicate a bi-predicate that performs a comparison between a constant argument and an input value; can be null
		 * @param args              an array of arguments, one of which is used by the constant bi-predicate
		 * @return a combined predicate that applies the logic of the constant bi-predicate and the input predicate
		 */
		@Nonnull
		private static Predicate<Object> combinePredicates(
			@Nullable Predicate<Object> predicate,
			@Nullable BiPredicate<Object[], Object> constantPredicate,
			@Nonnull Object[] args
		) {
			if (predicate == null) {
				if (constantPredicate == null) {
					return ref -> true;
				} else {
					return ref -> constantPredicate.test(args, ref);
				}
			} else {
				if (constantPredicate == null) {
					return predicate;
				} else {
					return ref -> constantPredicate.test(args, ref) && predicate.test(ref);
				}
			}
		}

	}

}
