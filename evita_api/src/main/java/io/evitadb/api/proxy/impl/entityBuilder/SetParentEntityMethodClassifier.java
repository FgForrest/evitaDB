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

package io.evitadb.api.proxy.impl.entityBuilder;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.SealedEntityProxy.ProxyType;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.ParentEntity;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.CurriedMethodContextInvocationHandler;
import one.edee.oss.proxycian.DirectMethodClassification;
import one.edee.oss.proxycian.utils.GenericsUtils;
import one.edee.oss.proxycian.utils.GenericsUtils.GenericBundle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Identifies methods that are used to set entity parent into an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SetParentEntityMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final SetParentEntityMethodClassifier INSTANCE = new SetParentEntityMethodClassifier();

	public SetParentEntityMethodClassifier() {
		super(
			"setParentEntity",
			(method, proxyState) -> {
				// first we need to identify whether the method returns a parent entity
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final ParentEntity parentEntity = reflectionLookup.getAnnotationInstanceForProperty(method, ParentEntity.class);
				if (parentEntity == null || !proxyState.getEntitySchema().isWithHierarchy()) {
					return null;
				}

				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
				final int parameterCount = method.getParameterCount();
				if (parameterCount == 0 && method.isAnnotationPresent(RemoveWhenExists.class)) {
					if (returnType.equals(proxyState.getProxyClass())) {
						return removeParentEntityWithEntityBuilderResult();
					} else if (Boolean.class.isAssignableFrom(returnType) || boolean.class.isAssignableFrom(returnType)) {
						return removeParentEntityWithBooleanResult(returnType);
					} else if (NumberUtils.isIntConvertibleNumber(returnType)) {
						return removeParentEntityWithParentIdResult(returnType);
					} else if (void.class.isAssignableFrom(returnType)) {
						return removeParentEntityWithVoidResult();
					} else {
						return removeParentEntityWithParentEntityResult(returnType);
					}
				}

				OptionalInt parentIdIndex = OptionalInt.empty();
				OptionalInt consumerIndex = OptionalInt.empty();
				@SuppressWarnings("rawtypes")
				Optional<Class> consumerType = Optional.empty();
				for (int i = 0; i < parameterCount; i++) {
					final Class<?> parameterType = method.getParameterTypes()[i];
					if (NumberUtils.isIntConvertibleNumber(parameterType)) {
						parentIdIndex = OptionalInt.of(i);
					} else if (Consumer.class.isAssignableFrom(parameterType)) {
						consumerIndex = OptionalInt.of(i);
						final List<GenericBundle> genericType = GenericsUtils.getGenericType(proxyState.getProxyClass(), method.getGenericParameterTypes()[i]);
						consumerType = Optional.of(genericType.get(0).getResolvedType());
					}
				}

				final String entityType = proxyState.getEntitySchema().getName();
				@SuppressWarnings("rawtypes") final Class parameterType = method.getParameterTypes()[0];

				final Optional<EntityRecognizedIn> entityRecognizedIn = assertEntityAnnotationOnReferencedClassIsConsistent(
					reflectionLookup, entityType,
					parameterType,
					consumerType.orElse(null)
				);

				if (parameterCount == 1 && parentIdIndex.isPresent() && entityRecognizedIn.isEmpty()) {
					if (returnType.equals(proxyState.getProxyClass())) {
						return setParentIdWithBuilderResult();
					} else {
						return setParentIdWithVoidResult();
					}
				} else if (parameterCount == 1 && EntityClassifier.class.isAssignableFrom(parameterType) && entityRecognizedIn.isEmpty()) {
					if (returnType.equals(proxyState.getProxyClass())) {
						return setParentClassifierWithBuilderResult(entityType);
					} else {
						return setParentClassifierWithVoidResult(entityType);
					}
				} else if (parameterCount == 1 && entityRecognizedIn.map(EntityRecognizedIn.PARAMETER::equals).orElse(false)) {
					if (returnType.equals(proxyState.getProxyClass())) {
						return setParentEntityWithBuilderResult(entityType);
					} else {
						return setParentEntityWithVoidResult(entityType);
					}
				} else if (parameterCount == 1 && entityRecognizedIn.map(EntityRecognizedIn.CONSUMER::equals).orElse(false)) {
					if (returnType.equals(proxyState.getProxyClass())) {
						//noinspection unchecked
						return createParentEntityWithEntityBuilderResult(consumerType.orElse(null));
					} else {
						//noinspection unchecked
						return createParentEntityWithVoidResult(consumerType.orElse(null));
					}
				} else if (parameterCount == 2 && entityRecognizedIn.map(EntityRecognizedIn.CONSUMER::equals).orElse(false) && parentIdIndex.isPresent()) {
					if (returnType.equals(proxyState.getProxyClass())) {
						//noinspection unchecked,OptionalGetWithoutIsPresent
						return createParentEntityByIdWithEntityBuilderResult(
							consumerType.orElse(null),
							parentIdIndex.getAsInt(), consumerIndex.getAsInt()
						);
					} else {
						//noinspection unchecked,OptionalGetWithoutIsPresent
						return createParentEntityByIdWithVoidResult(
							consumerType.orElse(null),
							parentIdIndex.getAsInt(), consumerIndex.getAsInt()
						);
					}
				}
				return null;
			}
		);
	}

	/**
	 * Asserts that the entity annotation on the referenced class is consistent with the entity type of the parent.
	 *
	 * @param reflectionLookup the reflection lookup
	 * @param entityType       expected entity type of the parent
	 * @param parameterType    the parameter type to look for the annotation on
	 * @param consumerType       the return type to look for the annotation on
	 */
	@Nonnull
	private static Optional<EntityRecognizedIn> assertEntityAnnotationOnReferencedClassIsConsistent(
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull String entityType,
		@Nonnull Class<?> parameterType,
		@Nullable Class<?> consumerType
	) {
		final Entity parameterEntityInstance = reflectionLookup.getClassAnnotation(parameterType, Entity.class);
		final EntityRef parameterEntityRefInstance = reflectionLookup.getClassAnnotation(parameterType, EntityRef.class);
		final Entity consumerTypeEntityInstance = consumerType == null ? null : reflectionLookup.getClassAnnotation(consumerType, Entity.class);
		final EntityRef consumerTypeEntityRefInstance = consumerType == null ? null : reflectionLookup.getClassAnnotation(consumerType, EntityRef.class);

		final Optional<String> referencedEntityType = Stream.concat(
				Stream.of(parameterEntityInstance, consumerTypeEntityInstance).filter(Objects::nonNull).map(Entity::name),
				Stream.of(parameterEntityRefInstance, consumerTypeEntityRefInstance).filter(Objects::nonNull).map(EntityRef::value)
			)
			.findFirst();

		Assert.isTrue(
			referencedEntityType.map(it -> Objects.equals(it, entityType)).orElse(true),
			() -> {
				final Optional<Class<?>> problemType = Stream.concat(
						Stream.of(parameterEntityRefInstance, parameterEntityInstance).filter(Objects::nonNull).map(it -> parameterType),
						Stream.of(consumerTypeEntityRefInstance, consumerTypeEntityInstance).filter(Objects::nonNull).map(it -> consumerType)
					)
					.findFirst();
				return new EntityClassInvalidException(
					parameterType,
					"Referenced parent class type `" + problemType + "` must represent same entity type, " +
						" but the class `" + parameterType + "` is annotated with @Entity referencing `" +
						referencedEntityType.orElse("N/A") + "` entity type!"
				);
			}
		);

		return referencedEntityType
			.map(it -> {
				if (parameterEntityInstance != null || parameterEntityRefInstance != null) {
					return EntityRecognizedIn.PARAMETER;
				} else if (consumerTypeEntityInstance != null || consumerTypeEntityRefInstance != null) {
					return EntityRecognizedIn.CONSUMER;
				} else {
					return null;
				}
			});
	}

	/**
	 * Returns method implementation that sets the parent entity and return no result.
	 *
	 * @param entityType the parent entity type
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setParentEntityWithVoidResult(
		@Nonnull String entityType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final EntityClassifier parent = (EntityClassifier) args[0];
			if (parent == null) {
				entityBuilder.removeParent();
			} else {
				entityBuilder.setParent(parent.getPrimaryKey());
				theState.registerReferencedEntityObject(entityType, parent.getPrimaryKey(), parent, ProxyType.PARENT);
			}
			return null;
		};
	}

	/**
	 * Returns method implementation that sets the parent entity and return the reference to the proxy to allow
	 * chaining (builder pattern).
	 *
	 * @param entityType the parent entity type
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setParentEntityWithBuilderResult(
		@Nonnull String entityType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final EntityClassifier parent = (EntityClassifier) args[0];
			if (parent == null) {
				entityBuilder.removeParent();
			} else {
				entityBuilder.setParent(parent.getPrimaryKey());
				theState.registerReferencedEntityObject(entityType, parent.getPrimaryKey(), parent, ProxyType.PARENT);
			}
			return proxy;
		};
	}

	/**
	 * Returns method implementation that sets the parent entity and return the reference to the proxy to allow
	 * chaining (builder pattern).
	 *
	 * @param expectedType the type of the wrapped parent object
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> createParentEntityWithEntityBuilderResult(
		@Nonnull Class<? extends Serializable> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Serializable referencedEntityInstance = theState.createReferencedEntityProxyWithCallback(
				theState.getEntitySchema(), expectedType,
				ProxyType.PARENT,
				entityReference -> entityBuilder.setParent(entityReference.getPrimaryKey())
			);
			//noinspection unchecked
			final Consumer<Serializable> consumer = (Consumer<Serializable>) args[0];
			consumer.accept(referencedEntityInstance);
			return proxy;
		};
	}

	/**
	 * Returns method implementation that sets the parent entity and return the reference to the proxy to allow
	 * chaining (builder pattern).
	 *
	 * @param expectedType the type of the wrapped parent object
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> createParentEntityWithVoidResult(
		@Nonnull Class<? extends Serializable> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Serializable referencedEntityInstance = theState.createReferencedEntityProxyWithCallback(
				theState.getEntitySchema(), expectedType,
				ProxyType.PARENT,
				entityReference -> entityBuilder.setParent(entityReference.getPrimaryKey())
			);
			//noinspection unchecked
			final Consumer<Serializable> consumer = (Consumer<Serializable>) args[0];
			consumer.accept(referencedEntityInstance);
			return null;
		};
	}

	/**
	 * Returns method implementation that sets the parent entity and return the reference to the proxy to allow
	 * chaining (builder pattern).
	 *
	 * @param expectedType the type of the wrapped parent object
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> createParentEntityByIdWithEntityBuilderResult(
		@Nonnull Class<? extends Serializable> expectedType,
		int parentIdLocation,
		int consumerLocation
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final int parentId = EvitaDataTypes.toTargetType((Serializable) args[parentIdLocation], int.class);
			final Serializable referencedEntityInstance = theState.getOrCreateReferencedEntityProxy(
				theState.getEntitySchema(), expectedType, ProxyType.PARENT, parentId
			);
			entityBuilder.setParent(parentId);
			//noinspection unchecked
			final Consumer<Serializable> consumer = (Consumer<Serializable>) args[consumerLocation];
			consumer.accept(referencedEntityInstance);
			return proxy;
		};
	}

	/**
	 * Returns method implementation that sets the parent entity and return the reference to the proxy to allow
	 * chaining (builder pattern).
	 *
	 * @param expectedType the type of the wrapped parent object
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> createParentEntityByIdWithVoidResult(
		@Nonnull Class<? extends Serializable> expectedType,
		int parentIdLocation,
		int consumerLocation
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final int parentId = EvitaDataTypes.toTargetType((Serializable) args[parentIdLocation], int.class);
			final Serializable referencedEntityInstance = theState.getOrCreateReferencedEntityProxy(
				theState.getEntitySchema(), expectedType, ProxyType.PARENT, parentId
			);
			entityBuilder.setParent(parentId);
			//noinspection unchecked
			final Consumer<Serializable> consumer = (Consumer<Serializable>) args[consumerLocation];
			consumer.accept(referencedEntityInstance);
			return null;
		};
	}

	/**
	 * Returns method implementation that removes the parent entity and return the reference to the proxy to allow
	 * chaining (builder pattern).
	 *
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeParentEntityWithEntityBuilderResult() {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			entityBuilder.removeParent();
			return proxy;
		};
	}

	/**
	 * Returns method implementation that removes the parent entity and returns the primary key of removed parent.
	 *
	 * @return the method implementation
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeParentEntityWithParentIdResult(
		@Nonnull Class returnType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Optional<EntityClassifierWithParent> parentEntity = entityBuilder.getParentEntity();
			if (parentEntity.isPresent()) {
				entityBuilder.removeParent();
				return EvitaDataTypes.toTargetType(parentEntity.get().getPrimaryKey(), returnType);
			} else {
				return null;
			}
		};
	}

	/**
	 * Returns method implementation that removes the parent entity and returns the TRUE if parent was removed.
	 *
	 * @return the method implementation
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeParentEntityWithBooleanResult(
		@Nonnull Class returnType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Optional<EntityClassifierWithParent> parentEntity = entityBuilder.getParentEntity();
			if (parentEntity.isPresent()) {
				entityBuilder.removeParent();
				return true;
			} else {
				return false;
			}
		};
	}

	/**
	 * Returns method implementation that removes the parent entity and return no result.
	 *
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeParentEntityWithVoidResult() {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			entityBuilder.removeParent();
			return null;
		};
	}

	/**
	 * Returns method implementation that removes the parent entity and returns the entity proxy of this parent.
	 *
	 * @return the method implementation
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeParentEntityWithParentEntityResult(
		@Nonnull Class expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Optional<EntityClassifierWithParent> parentEntity = entityBuilder.getParentEntity();
			if (parentEntity.isPresent()) {
				entityBuilder.removeParent();
				final Optional<Object> referencedEntityObject = theState.getReferencedEntityObjectIfPresent(
					theState.getType(),
					parentEntity.get().getPrimaryKey(),
					expectedType,
					ProxyType.PARENT
				);
				return referencedEntityObject
					.orElseGet(() -> {
						if (parentEntity.get() instanceof EntityContract entityContract) {
							return theState.createReferencedEntityProxy(expectedType, entityContract);
						} else {
							throw ContextMissingException.hierarchyEntityContextMissing();
						}
					});
			} else {
				return null;
			}
		};
	}

	/**
	 * Returns method implementation that sets the parent entity classifier and return no result.
	 *
	 * @param entityType the parent entity type
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setParentClassifierWithVoidResult(
		@Nonnull String entityType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final EntityClassifier parent = (EntityClassifier) args[0];
			if (parent == null) {
				entityBuilder.removeParent();
			} else {
				Assert.isTrue(
					entityType.equals(parent.getType()),
					() -> new EntityClassInvalidException(
						theState.getProxyClass(),
						"Entity class type `" + theState.getProxyClass() + "` parent must represent same entity type, " +
							" but the parameter object refers to `" + parent.getType() + "` entity type!"
					)
			);
				entityBuilder.setParent(parent.getPrimaryKey());
				theState.registerReferencedEntityObject(entityType, parent.getPrimaryKey(), parent, ProxyType.PARENT);
			}
			return null;
		};
	}

	/**
	 * Returns method implementation that sets the parent entity and return the reference to the proxy to allow
	 * chaining (builder pattern).
	 *
	 * @param entityType the parent entity type
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setParentClassifierWithBuilderResult(
		@Nonnull String entityType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final EntityClassifier parent = (EntityClassifier) args[0];
			if (parent == null) {
				entityBuilder.removeParent();
			} else {
				Assert.isTrue(
					entityType.equals(parent.getType()),
					() -> new EntityClassInvalidException(
						theState.getProxyClass(),
						"Entity class type `" + theState.getProxyClass() + "` parent must represent same entity type, " +
							" but the parameter object refers to `" + parent.getType() + "` entity type!"
					)
				);
				entityBuilder.setParent(parent.getPrimaryKey());
				theState.registerReferencedEntityObject(entityType, parent.getPrimaryKey(), parent, ProxyType.PARENT);
			}
			return proxy;
		};
	}

	/**
	 * Returns method implementation that sets the parent entity id and return no result.
	 *
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setParentIdWithVoidResult() {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Serializable parent = (Serializable) args[0];
			if (parent == null) {
				entityBuilder.removeParent();
			} else {
				entityBuilder.setParent(
					EvitaDataTypes.toTargetType(parent, int.class)
				);
			}
			return null;
		};
	}

	/**
	 * Returns method implementation that sets the parent entity id and return the reference to the proxy to allow
	 * chaining (builder pattern).
	 *
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setParentIdWithBuilderResult() {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Serializable parent = (Serializable) args[0];
			if (parent == null) {
				entityBuilder.removeParent();
			} else {
				entityBuilder.setParent(
					EvitaDataTypes.toTargetType(parent, int.class)
				);
			}
			return proxy;
		};
	}

	/**
	 * Represents identification of the place which has been recognized as parent representation.
	 */
	private enum EntityRecognizedIn {
		PARAMETER,
		CONSUMER
	}

}
