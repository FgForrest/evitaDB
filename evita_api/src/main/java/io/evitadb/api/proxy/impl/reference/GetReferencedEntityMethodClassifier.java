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

package io.evitadb.api.proxy.impl.reference;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.proxy.SealedEntityProxy.ProxyType;
import io.evitadb.api.proxy.impl.ProxyUtils;
import io.evitadb.api.proxy.impl.ProxyUtils.ResultWrapper;
import io.evitadb.api.proxy.impl.SealedEntityReferenceProxyState;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntityGroup;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.function.ExceptionRethrowingBiFunction;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.CurriedMethodContextInvocationHandler;
import one.edee.oss.proxycian.DirectMethodClassification;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.evitadb.api.proxy.impl.ProxyUtils.getResolvedTypes;
import static io.evitadb.api.proxy.impl.ProxyUtils.getWrappedGenericType;

/**
 * Identifies methods that are used to get reference from a sealed entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetReferencedEntityMethodClassifier extends DirectMethodClassification<Object, SealedEntityReferenceProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final GetReferencedEntityMethodClassifier INSTANCE = new GetReferencedEntityMethodClassifier();

	/**
	 * Tries to identify reference attribute request from the class field related to the constructor parameter.
	 *
	 * @param expectedType     class the constructor belongs to
	 * @param parameter        constructor parameter
	 * @param reflectionLookup reflection lookup
	 * @param referenceSchema  reference schema
	 * @return attribute name derived from the annotation if found
	 */
	@Nullable
	public static <T> ExceptionRethrowingBiFunction<EntityContract, ReferenceContract, Object> getExtractorIfPossible(
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull Class<T> expectedType,
		@Nonnull Parameter parameter,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ProxyFactory proxyFactory
	) {
		final String referenceName = referenceSchema.getName();
		final Class<?>[] resolvedTypes = getResolvedTypes(parameter, expectedType);
		@SuppressWarnings("rawtypes") final Class valueType = resolvedTypes.length > 1 ? resolvedTypes[1] : resolvedTypes[0];

		// we need to determine whether the method returns referenced entity or its group
		final String parameterName = parameter.getName();
		final ReferencedEntity referencedEntity = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, ReferencedEntity.class);
		final ReferencedEntityGroup referencedEntityGroup = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, ReferencedEntityGroup.class);
		final Entity entityInstance = reflectionLookup.getClassAnnotation(valueType, Entity.class);
		final EntityRef entityRefInstance = reflectionLookup.getClassAnnotation(valueType, EntityRef.class);

		// extract the entity type from entity annotations
		final Optional<String> entityType = Optional.ofNullable(entityInstance)
			.map(Entity::name)
			.or(() -> Optional.ofNullable(entityRefInstance).map(EntityRef::value));

		// determine if entity type represents referenced entity type
		final boolean entityIsReferencedEntity = entityType
			.map(it -> isReferencedEntityType(expectedType, referenceSchema, it))
			.orElse(false);
		// or its group type
		final boolean entityIsReferencedGroup = entityType
			.map(it -> isReferencedEntityGroupType(expectedType, referenceSchema, it))
			.orElse(false);

		// if entity reference is returned, return appropriate implementation
		if (EntityReferenceContract.class.isAssignableFrom(valueType)) {
			if (referencedEntity != null || entityIsReferencedEntity) {
				return (sealedEntity, reference) -> new EntityReference(reference.getReferencedEntityType(), reference.getReferencedPrimaryKey());
			} else {
				if (referencedEntityGroup != null || entityIsReferencedGroup) {
					return (sealedEntity, reference) -> reference.getGroup()
						.map(theReference -> new EntityReference(theReference.getType(), theReference.getPrimaryKey()))
						.orElse(null);
				} else {
					return null;
				}
			}
		} else {
			if (referencedEntity != null) {
				// or return complex type of the referenced entity
				Assert.isTrue(
					entityType.isEmpty() || entityIsReferencedEntity,
					() -> new EntityClassInvalidException(
						valueType,
						"Entity class type `" + expectedType + "` reference `" +
							referenceSchema.getName() + "` relates to the referenced entity type `" +
							referenceSchema.getReferencedEntityType() + "`, but the return " +
							"class `" + valueType + "` is annotated with @Entity referencing `" +
							entityType.orElse("N/A") + "` entity type!"
					)
				);
				return (sealedEntity, reference) -> reference.getReferencedEntity()
					.map(it -> proxyFactory.createEntityProxy(parameter.getType(), it, referencedEntitySchemas))
					.orElse(null);
			} else if (referencedEntityGroup != null) {
				// or return complex type of the referenced entity group
				Assert.isTrue(
					entityType.isEmpty() || entityIsReferencedGroup,
					() -> new EntityClassInvalidException(
						valueType,
						"Entity class type `" + expectedType + "` reference `" +
							referenceSchema.getName() + "` relates to the referenced entity group type `" +
							referenceSchema.getReferencedGroupType() + "`, but the return " +
							"class `" + valueType + "` is annotated with @Entity referencing `" +
							entityType.orElse("N/A") + "` entity type!"
					)
				);
				return singleEntityResult(referencedEntitySchemas, referenceName, valueType, ReferenceDecorator::getGroupEntity, proxyFactory);
			} else if (entityType.isPresent()) {
				// otherwise return entity or group based on entity type matching result
				if (entityIsReferencedEntity) {
					return singleEntityResult(referencedEntitySchemas, referenceName, valueType, ReferenceDecorator::getReferencedEntity, proxyFactory);
				} else if (entityIsReferencedGroup) {
					return singleEntityResult(referencedEntitySchemas, referenceName, valueType, ReferenceDecorator::getGroupEntity, proxyFactory);
				} else {
					return null;
				}
			} else {
				return null;
			}
		}
	}

	/**
	 * Creates an implementation of the method returning a single referenced entity wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> singleEntityReferenceResult(
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final ReferenceContract theReference = theState.getReference();
			return resultWrapper.wrap(
				() -> new EntityReference(theReference.getReferencedEntityType(), theReference.getReferencedPrimaryKey())
			);
		};
	}

	/**
	 * Creates an implementation of the method returning a single referenced group {@link GroupEntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> singleEntityGroupReferenceResult(
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final ReferenceContract theReference = theState.getReference();
			return resultWrapper.wrap(
				() -> theReference.getGroup().orElse(null)
			);
		};
	}

	/**
	 * Creates an implementation of the method returning a single referenced entity wrapped into a custom proxy instance.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> singleEntityResult(
		@Nonnull String referenceName,
		@Nonnull Class<?> itemType,
		@Nonnull BiFunction<String, ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final ReferenceContract reference = theState.getReference();
			Assert.isTrue(
				reference instanceof ReferenceDecorator,
				() -> ContextMissingException.referencedEntityContextMissing(theState.getType(), referenceName)
			);
			final ReferenceDecorator referenceDecorator = (ReferenceDecorator) reference;
			return resultWrapper.wrap(
				() -> entityExtractor.apply(theState.getType(), referenceDecorator)
					.map(it -> theState.getOrCreateReferencedEntityProxy(itemType, it, ProxyType.REFERENCED_ENTITY))
					.orElse(null)
			);
		};
	}

	/**
	 * Creates an implementation of the method returning a single referenced entity wrapped into a custom proxy instance.
	 */
	@Nonnull
	private static ExceptionRethrowingBiFunction<EntityContract, ReferenceContract, Object> singleEntityResult(
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull String referenceName,
		@Nonnull Class<?> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		@Nonnull ProxyFactory proxyFactory
	) {
		return (sealedEntity, reference) -> {
			Assert.isTrue(
				reference instanceof ReferenceDecorator,
				() -> ContextMissingException.referencedEntityContextMissing(sealedEntity.getType(), referenceName)
			);
			final ReferenceDecorator referenceDecorator = (ReferenceDecorator) reference;
			return entityExtractor.apply(referenceDecorator)
				.map(it -> proxyFactory.createEntityProxy(itemType, it, referencedEntitySchemas))
				.orElse(null);
		};
	}

	/**
	 * Method returns ture if the entity type represents a referenced entity type.
	 */
	public static boolean isReferencedEntityType(
		@Nonnull Class<?> proxyClass,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String entityType
	) {
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		if (entityType.equals(referencedEntityType)) {
			Assert.isTrue(
				referenceSchema.isReferencedEntityTypeManaged(),
				() -> new EntityClassInvalidException(
					proxyClass,
					"Entity class type `" + proxyClass + "` reference `" +
						referenceSchema.getName() + "` relates to the entity type `" + referencedEntityType +
						"` which is not managed by evitaDB and cannot be wrapped into a proxy class!"
				)
			);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Method returns ture if the entity type represents a referenced entity group type.
	 */
	public static boolean isReferencedEntityGroupType(
		@Nonnull Class<?> proxyClass,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String entityGroupType
	) {
		final String referencedGroupType = referenceSchema.getReferencedGroupType();
		if (entityGroupType.equals(referencedGroupType)) {
			Assert.isTrue(
				referenceSchema.isReferencedEntityTypeManaged(),
				() -> new EntityClassInvalidException(
					proxyClass,
					"Entity class type `" + proxyClass + "` reference `" +
						referenceSchema.getName() + "` relates to the entity group type `" + referencedGroupType +
						"` which is not managed by evitaDB and cannot be wrapped into a proxy class!"
				)
			);
			return true;
		} else {
			return false;
		}
	}

	public GetReferencedEntityMethodClassifier() {
		super(
			"getReferencedEntity",
			(method, proxyState) -> {
				// we are interested only in abstract methods with no arguments
				if (!ClassUtils.isAbstractOrDefault(method) ||
					method.getParameterCount() > 0 ||
					method.isAnnotationPresent(CreateWhenMissing.class) ||
					method.isAnnotationPresent(RemoveWhenExists.class)) {
					return null;
				}

				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final ReferenceSchemaContract referenceSchema = Objects.requireNonNull(proxyState.getReferenceSchema());
				final String referenceName = referenceSchema.getName();
				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
				@SuppressWarnings("rawtypes") final Class wrappedGenericType = getWrappedGenericType(method, proxyState.getProxyClass());
				final ResultWrapper resultWrapper = ProxyUtils.createOptionalWrapper(method, wrappedGenericType);
				@SuppressWarnings("rawtypes") final Class valueType = wrappedGenericType == null ? returnType : wrappedGenericType;

				// we need to determine whether the method returns referenced entity or its group
				final ReferencedEntity referencedEntity = reflectionLookup.getAnnotationInstanceForProperty(method, ReferencedEntity.class);
				final ReferencedEntityGroup referencedEntityGroup = reflectionLookup.getAnnotationInstanceForProperty(method, ReferencedEntityGroup.class);
				final Entity entityInstance = reflectionLookup.getClassAnnotation(valueType, Entity.class);
				final EntityRef entityRefInstance = reflectionLookup.getClassAnnotation(valueType, EntityRef.class);

				// extract the entity type from entity annotations
				final Optional<String> entityType = Optional.ofNullable(entityInstance)
					.map(Entity::name)
					.or(() -> Optional.ofNullable(entityRefInstance).map(EntityRef::value));

				// determine if entity type represents referenced entity type
				final boolean entityIsReferencedEntity = entityType
					.map(it -> isReferencedEntityType(proxyState.getProxyClass(), referenceSchema, it))
					.orElse(false);
				// or its group type
				final boolean entityIsReferencedGroup = entityType
					.map(it -> isReferencedEntityGroupType(proxyState.getProxyClass(), referenceSchema, it))
					.orElse(false);

				// if entity reference is returned, return appropriate implementation
				if (EntityReferenceContract.class.isAssignableFrom(valueType)) {
					if (referencedEntity != null || entityIsReferencedEntity) {
						return singleEntityReferenceResult(resultWrapper);
					} else {
						if (referencedEntityGroup != null || entityIsReferencedGroup) {
							return singleEntityGroupReferenceResult(resultWrapper);
						} else {
							return null;
						}
					}
				} else {
					if (referencedEntity != null) {
						// or return complex type of the referenced entity
						Assert.isTrue(
							entityType.isEmpty() || entityIsReferencedEntity,
							() -> new EntityClassInvalidException(
								valueType,
								"Entity class type `" + proxyState.getProxyClass() + "` reference `" +
									referenceSchema.getName() + "` relates to the referenced entity type `" +
									referenceSchema.getReferencedEntityType() + "`, but the return " +
									"class `" + valueType + "` is annotated with @Entity referencing `" +
									entityType.orElse("N/A") + "` entity type!"
							)
						);
						return singleEntityResult(
							referenceName, valueType,
							(theEntityType, referenceDecorator) -> {
								if (referenceDecorator.getReferencedEntity().isPresent()) {
									return referenceDecorator.getReferencedEntity();
								} else {
									throw ContextMissingException.referencedEntityContextMissing(theEntityType, referenceName);
								}
							},
							resultWrapper
						);
					} else if (referencedEntityGroup != null) {
						// or return complex type of the referenced entity group
						Assert.isTrue(
							entityType.isEmpty() || entityIsReferencedGroup,
							() -> new EntityClassInvalidException(
								valueType,
								"Entity class type `" + proxyState.getProxyClass() + "` reference `" +
									referenceSchema.getName() + "` relates to the referenced entity group type `" +
									referenceSchema.getReferencedGroupType() + "`, but the return " +
									"class `" + valueType + "` is annotated with @Entity referencing `" +
									entityType.orElse("N/A") + "` entity type!"
							)
						);
						return singleEntityResult(
							referenceName, valueType,
							(theEntityType, referenceDecorator) -> {
								if (referenceDecorator.getGroup().isEmpty()) {
									return Optional.empty();
								} else if (referenceDecorator.getGroupEntity().isPresent()) {
									return referenceDecorator.getGroupEntity();
								} else {
									throw ContextMissingException.referencedEntityGroupContextMissing(theEntityType, referenceName);
								}
							},
							resultWrapper
						);
					} else if (entityType.isPresent()) {
						// otherwise return entity or group based on entity type matching result
						if (entityIsReferencedEntity) {
							return singleEntityResult(
								referenceName, valueType,
								(theEntityType, referenceDecorator) -> {
									if (referenceDecorator.getReferencedEntity().isPresent()) {
										return referenceDecorator.getReferencedEntity();
									} else {
										throw ContextMissingException.referencedEntityContextMissing(theEntityType, referenceName);
									}
								},
								resultWrapper
							);
						} else if (entityIsReferencedGroup) {
							return singleEntityResult(
								referenceName, valueType,
								(theEntityType, referenceDecorator) -> {
									if (referenceDecorator.getGroup().isEmpty()) {
										return Optional.empty();
									} else if (referenceDecorator.getGroupEntity().isPresent()) {
										return referenceDecorator.getGroupEntity();
									} else {
										throw ContextMissingException.referencedEntityGroupContextMissing(theEntityType, referenceName);
									}
								},
								resultWrapper
							);
						} else {
							return null;
						}
					} else {
						return null;
					}
				}
			}
		);
	}

}
