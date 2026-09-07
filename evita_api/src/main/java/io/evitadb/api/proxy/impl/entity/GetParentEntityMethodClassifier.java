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

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.proxy.impl.ProxyUtils;
import io.evitadb.api.proxy.impl.ProxyUtils.OptionalProducingOperator;
import io.evitadb.api.proxy.impl.ProxyUtils.ResultWrapper;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.query.require.HierarchyParentsBehaviour;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.ParentEntity;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.EntityReferenceWithParent;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.function.ExceptionRethrowingFunction;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.CurriedMethodContextInvocationHandler;
import one.edee.oss.proxycian.DirectMethodClassification;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.evitadb.api.proxy.impl.ProxyUtils.getWrappedGenericType;
import static java.util.Optional.ofNullable;

/**
 * Identifies methods that are used to get parent entity from an sealed entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetParentEntityMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final GetParentEntityMethodClassifier INSTANCE = new GetParentEntityMethodClassifier();

	/**
	 * Tries to identify parent from the class field related to the constructor parameter.
	 *
	 * The extractor returned for a parameter typed as a custom proxy contract needs the parent body, so it raises the
	 * {@link ContextMissingException} chosen by {@link #parentBodyUnavailable(Object)} when the chain reports the
	 * parent as a bodyless pointer instead - the same exception the equally typed getter raises, see
	 * {@link #singleParentEntityResult(Class, BiFunction, ResultWrapper)}. Unlike the getter, the extractor is wrapped
	 * in no {@link ResultWrapper}, so the exception always propagates to the constructor caller.
	 *
	 * @param referencedEntitySchemas index of entity schemas the created proxy may need to resolve its references
	 * @param expectedType            class the constructor belongs to
	 * @param parameter               constructor parameter
	 * @param reflectionLookup        reflection lookup
	 * @param proxyFactory            factory creating the proxy over an ancestor body when the parameter is typed
	 *                                as a custom contract
	 * @param <T>                     type of the class the constructor belongs to
	 * @return the extractor reading the parent out of an entity in the shape the parameter declares, or NULL when
	 *         the parameter carries no {@link ParentEntity} annotation
	 */
	@Nullable
	public static <T> ExceptionRethrowingFunction<EntityContract, Object> getExtractorIfPossible(
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull Class<T> expectedType,
		@Nonnull Parameter parameter,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ProxyFactory proxyFactory
	) {
		final String parameterName = parameter.getName();
		final Class<?> parameterType = parameter.getType();
		final ParentEntity parentEntity = reflectionLookup.getAnnotationInstanceForProperty(expectedType, parameterName, ParentEntity.class);
		if (parentEntity != null) {
			if (int.class.equals(parameterType) || Integer.class.equals(parameterType)) {
				return sealedEntity -> sealedEntity.getParentEntity().map(EntityClassifier::getPrimaryKey).orElse(null);
			} else if (EntityReferenceContract.class.isAssignableFrom(parameterType)) {
				return sealedEntity -> sealedEntity.getParentEntity().map(it -> new EntityReference(it.getType(), it.getPrimaryKeyOrThrowException())).orElse(null);
			} else if (EntityClassifier.class.equals(parameterType) || EntityClassifierWithParent.class.equals(parameterType)) {
				return sealedEntity -> sealedEntity.getParentEntity().orElse(null);
			} else {
				return sealedEntity -> sealedEntity.getParentEntity()
					.map(it -> {
						if (it instanceof SealedEntity parentBody) {
							return proxyFactory.createEntityProxy(parameterType, parentBody, referencedEntitySchemas);
						} else {
							throw parentBodyUnavailable(it);
						}
					})
					.orElse(null);
			}
		}

		return null;
	}

	/**
	 * Implementation that returns an integer value of parent entity id.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleParentIdResult(
		@Nonnull Function<EntityContract, Optional<EntityClassifierWithParent>> parentEntityExtractor,
		@Nonnull ResultWrapper resultWrapper,
		@Nonnull Class<? extends Serializable> returnType
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) ->
			resultWrapper.wrap(
				() -> parentEntityExtractor.apply(theState.entity())
					.map(EntityClassifier::getPrimaryKey)
					.map(it -> EvitaDataTypes.toTargetType(it, returnType))
					.orElse(null)
			);
	}

	/**
	 * Implementation that returns an {@link EntityReference} of parent entity.
	 */
	@Nonnull
	private static <T> CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleParentReferenceResult(
		@Nonnull Function<EntityContract, Optional<EntityClassifierWithParent>> parentEntityExtractor,
		@Nonnull Class<T> expectedType,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final EntityContract sealedEntity = theState.entity();
			//noinspection unchecked
			return resultWrapper.wrap(
				() -> parentEntityExtractor.apply(sealedEntity)
					.map(
						it -> theState.getParentEntityProxyIfPresent(expectedType, it.getPrimaryKeyOrThrowException())
							.orElseGet(() -> (T) new EntityReference(sealedEntity.getType(), it.getPrimaryKeyOrThrowException()))
					)
					.orElse(null)
			);
		};
	}

	/**
	 * Implementation that returns an {@link EntityClassifierWithParent} of parent entity.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleParentClassifierResult(
		@Nonnull BiFunction<EntityContract, SealedEntityProxyState, Optional<?>> parentEntityExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final EntityContract sealedEntity = theState.entity();
			return resultWrapper.wrap(
				() -> parentEntityExtractor.apply(sealedEntity, theState)
					.orElse(null)
			);
		};
	}

	/**
	 * Implementation that returns a custom proxy class wrapping a {@link SealedEntity} object of parent entity.
	 *
	 * A parent chain may hand over an ancestor with no body to build a proxy over. It is then reported the same way
	 * a reference whose body was not fetched is: with the {@link ContextMissingException} chosen by
	 * {@link #parentBodyUnavailable(Object)}, which the wrapper chosen by
	 * {@link ProxyUtils#createOptionalWrapper(java.lang.reflect.Method, Class)} either rethrows to a getter that
	 * declares it, or swallows into an empty result for a getter that does not.
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> singleParentEntityResult(
		@Nonnull Class<?> itemType,
		@Nonnull BiFunction<EntityContract, SealedEntityProxyState, Optional<?>> parentEntityExtractor,
		@Nonnull ResultWrapper resultWrapper
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> resultWrapper.wrap(
			() -> parentEntityExtractor.apply(theState.entity(), theState)
				.map(it -> {
					if (itemType.isInstance(it)) {
						return it;
					} else if (it instanceof SealedEntity sealedEntity) {
						return theState.getOrCreateParentEntityProxy(itemType, sealedEntity);
					} else {
						throw parentBodyUnavailable(it);
					}
				})
				.orElse(null)
		);
	}

	/**
	 * Chooses the {@link ContextMissingException} that reports an ancestor no proxy can be built over.
	 *
	 * A bodyless {@link EntityReferenceWithParent} pointer identifies the ancestor it stands for, so the message can
	 * name it: the chain does carry the ancestor - either because no ancestor body was requested at all, or because
	 * the requested body could not be materialized and
	 * {@link HierarchyParentsBehaviour#COMPLETE} kept the ancestor in the chain regardless. Any other shape carries
	 * no identity to report, so the generic "parent was not fetched" message is the only thing that can be said.
	 *
	 * The parameter is untyped because one of the two call sites reads the ancestor out of an {@code Optional<?>}
	 * that may equally well hold an already built proxy.
	 *
	 * @param parent the ancestor that carries no body
	 * @return the exception to raise for that ancestor, never null
	 */
	@Nonnull
	private static ContextMissingException parentBodyUnavailable(@Nonnull Object parent) {
		return parent instanceof EntityReferenceWithParent parentPointer ?
			ContextMissingException.hierarchyEntityBodyMissing(parentPointer.type(), parentPointer.primaryKey()) :
			ContextMissingException.hierarchyEntityContextMissing();
	}

	public GetParentEntityMethodClassifier() {
		super(
			"getParentEntity",
			(method, proxyState) -> {
				// Method must be abstract and have no parameters
				if (method.getParameterCount() > 0) {
					return null;
				}

				// first we need to identify whether the method returns a parent entity
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final ParentEntity parentEntity = reflectionLookup.getAnnotationInstanceForProperty(method, ParentEntity.class);
				if (parentEntity == null ||
					method.isAnnotationPresent(CreateWhenMissing.class) ||
					method.isAnnotationPresent(RemoveWhenExists.class)) {
					return null;
				}

				// it must also return a class that is annotated with entity annotation and its entity name must exactly
				// match the parent entity name derived from actual schema
				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
				@SuppressWarnings("rawtypes") final Class wrappedGenericType = getWrappedGenericType(method, proxyState.getProxyClass());
				final ResultWrapper resultWrapper = ProxyUtils.createOptionalWrapper(method, wrappedGenericType);
				final Class<?> valueType = wrappedGenericType == null ? returnType : wrappedGenericType;

				final Entity entityInstance = reflectionLookup.getClassAnnotation(valueType, Entity.class);
				final EntityRef entityRefInstance = reflectionLookup.getClassAnnotation(valueType, EntityRef.class);
				final Optional<String> entityType = ofNullable(entityInstance)
					.map(Entity::name)
					.or(() -> ofNullable(entityRefInstance).map(EntityRef::value));

				final String expectedParentEntityType = proxyState.getEntitySchema().getName();
				Assert.isTrue(
					entityType.map(it -> Objects.equals(it, expectedParentEntityType)).orElse(true),
					() -> new EntityClassInvalidException(
						valueType,
						"Entity class type `" + proxyState.getProxyClass() + "` parent must represent same entity type, " +
							" but the return class `" + valueType + "` is annotated with @Entity referencing `" +
							entityType.orElse("N/A") + "` entity type!"
					)
				);

				final Function<EntityContract, Optional<EntityClassifierWithParent>> parentEntityExtractor =
					resultWrapper instanceof OptionalProducingOperator ?
						sealedEntity -> sealedEntity.parentAvailable() ? sealedEntity.getParentEntity() : Optional.empty() :
						EntityContract::getParentEntity;

				// now we need to identify the return type and return appropriate implementation
				if (Number.class.isAssignableFrom(EvitaDataTypes.toWrappedForm(valueType))) {
					return singleParentIdResult(parentEntityExtractor, resultWrapper, EvitaDataTypes.toWrappedForm(valueType));
				} else if (EntityReferenceContract.class.isAssignableFrom(valueType)) {
					return singleParentReferenceResult(parentEntityExtractor, valueType, resultWrapper);
				} else {
					//noinspection rawtypes, unchecked
					final BiFunction<EntityContract, SealedEntityProxyState, Optional<?>> simpleCachedParentEntityExtractor = (sealedEntity, sealedEntityProxyState) -> sealedEntity.getParentEntity()
						.map(parent -> sealedEntityProxyState
							.getParentEntityProxyIfPresent((Class)valueType, parent.getPrimaryKeyOrThrowException())
							.orElse(parent)
						);
					final BiFunction<EntityContract, SealedEntityProxyState, Optional<?>> cachedParentEntityExtractor =
						resultWrapper instanceof OptionalProducingOperator ?
							(sealedEntity, sealedEntityProxyState) -> sealedEntity.parentAvailable() ? simpleCachedParentEntityExtractor.apply(sealedEntity, sealedEntityProxyState) : Optional.empty() :
							simpleCachedParentEntityExtractor;

					if (valueType.equals(EntityClassifier.class) || valueType.equals(EntityClassifierWithParent.class)) {
						return singleParentClassifierResult(cachedParentEntityExtractor, resultWrapper);
					} else {
						return singleParentEntityResult(
							valueType,
							cachedParentEntityExtractor, resultWrapper
						);
					}
				}
			}
		);
	}

}
