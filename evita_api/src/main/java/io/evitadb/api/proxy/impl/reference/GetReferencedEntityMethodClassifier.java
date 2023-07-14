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

package io.evitadb.api.proxy.impl.reference;

import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.impl.ProxyUtils;
import io.evitadb.api.proxy.impl.SealedEntityReferenceProxyState;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntityGroup;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.CurriedMethodContextInvocationHandler;
import one.edee.oss.proxycian.DirectMethodClassification;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

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
	 * Creates an implementation of the method returning a single referenced entity wrapped into {@link EntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> singleEntityReferenceResult(
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final ReferenceContract theReference = theState.getReference();
			return resultWrapper.apply(
				new EntityReference(theReference.getReferencedEntityType(), theReference.getReferencedPrimaryKey())
			);
		};
	}

	/**
	 * Creates an implementation of the method returning a single referenced group {@link GroupEntityReference} object.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> singleEntityGroupReferenceResult(
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final ReferenceContract theReference = theState.getReference();
			return resultWrapper.apply(
				theReference.getGroup().orElse(null)
			);
		};
	}

	/**
	 * Creates an implementation of the method returning a single referenced entity wrapped into a custom proxy instance.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> singleEntityResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<? extends EntityClassifier> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor,
		@Nonnull UnaryOperator<Object> resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final ReferenceContract reference = theState.getReference();
			Assert.isTrue(
				reference instanceof ReferenceDecorator,
				() -> "Entity `" + theState.getSealedEntity().getType() + "` references of type `" +
					cleanReferenceName + "` were not fetched with `entityFetch` requirement. " +
					"Related entity body is not available."
			);
			final ReferenceDecorator referenceDecorator = (ReferenceDecorator) reference;
			return resultWrapper.apply(
				entityExtractor.apply(referenceDecorator)
					.map(it -> theState.createEntityProxy(itemType, it))
					.orElse(null)
			);
		};
	}

	/**
	 * Method returns ture if the entity type represents a referenced entity type.
	 */
	private static boolean isReferencedEntityType(
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
	private static boolean isReferencedEntityGroupType(
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
				if (!ClassUtils.isAbstractOrDefault(method) || method.getParameterCount() > 0) {
					return null;
				}


				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final ReferenceSchemaContract referenceSchema = proxyState.getReferenceSchema();
				final String cleanReferenceName = referenceSchema.getName();
				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
				@SuppressWarnings("rawtypes") final Class wrappedGenericType = getWrappedGenericType(method, proxyState.getProxyClass());
				final UnaryOperator<Object> resultWrapper = ProxyUtils.createOptionalWrapper(wrappedGenericType);
				@SuppressWarnings("rawtypes") final Class valueType = wrappedGenericType == null ? returnType : wrappedGenericType;

				// we need to determine whether the method returns referenced entity or its group
				final ReferencedEntity referencedEntity = reflectionLookup.getAnnotationInstance(method, ReferencedEntity.class);
				final ReferencedEntityGroup referencedEntityGroup = reflectionLookup.getAnnotationInstance(method, ReferencedEntityGroup.class);
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
				if (valueType.equals(EntityReference.class)) {
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
						//noinspection unchecked
						return singleEntityResult(cleanReferenceName, valueType, ReferenceDecorator::getReferencedEntity, resultWrapper);
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
						//noinspection unchecked
						return singleEntityResult(cleanReferenceName, valueType, ReferenceDecorator::getGroupEntity, resultWrapper);
					} else if (entityType.isPresent()) {
						// otherwise return entity or group based on entity type matching result
						if (entityIsReferencedEntity) {
							//noinspection unchecked
							return singleEntityResult(cleanReferenceName, valueType, ReferenceDecorator::getReferencedEntity, resultWrapper);
						} else if (entityIsReferencedGroup) {
							//noinspection unchecked
							return singleEntityResult(cleanReferenceName, valueType, ReferenceDecorator::getGroupEntity,resultWrapper);
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
