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

package io.evitadb.api.proxy.impl.referenceBuilder;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.SealedEntityProxy.ProxyType;
import io.evitadb.api.proxy.impl.SealedEntityReferenceProxyState;
import io.evitadb.api.proxy.impl.entityBuilder.SetReferenceMethodClassifier.EntityRecognizedIn;
import io.evitadb.api.proxy.impl.entityBuilder.SetReferenceMethodClassifier.RecognizedContext;
import io.evitadb.api.proxy.impl.entityBuilder.SetReferenceMethodClassifier.ResolvedParameter;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntityGroup;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
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
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static io.evitadb.api.proxy.impl.entityBuilder.SetReferenceMethodClassifier.isEntityRecognizedIn;
import static io.evitadb.api.proxy.impl.entityBuilder.SetReferenceMethodClassifier.recognizeCallContext;
import static io.evitadb.api.proxy.impl.entityBuilder.SetReferenceMethodClassifier.resolvedTypeIs;
import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * Identifies methods that are used to set entity referenced group into an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SetReferenceGroupMethodClassifier extends DirectMethodClassification<Object, SealedEntityReferenceProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final SetReferenceGroupMethodClassifier INSTANCE = new SetReferenceGroupMethodClassifier();

	/**
	 * Method returns the referenced entity group type and verifies that it is managed by evitaDB.
	 * @param referenceSchema the reference schema
	 * @return the referenced entity group type
	 */
	@Nullable
	private static String getReferencedGroupType(@Nonnull ReferenceSchemaContract referenceSchema, boolean requireManagedOnly) {
		final String referencedEntityType = referenceSchema.getReferencedGroupType();
		Assert.isTrue(
			!requireManagedOnly || referenceSchema.isReferencedGroupTypeManaged(),
			"Referenced entity group type `" + referencedEntityType + "` is not managed " +
				"by evitaDB and cannot be created by method call!"
		);
		return referencedEntityType;
	}

	/**
	 * Return a method implementation that removes the single reference if exists.
	 *
	 * @param referenceSchema the reference schema to use
	 * @return the method implementation
	 */
	@Nullable
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> removeReferencedEntity(
		@Nonnull SealedEntityReferenceProxyState proxyState,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> returnType,
		boolean entityRecognizedInReturnType
	) {
		if (returnType.equals(proxyState.getProxyClass())) {
			return (proxy, theMethod, args, theState, invokeSuper) -> {
				theState.getReferenceBuilder().removeGroup();
				return proxy;
			};
		} else if (Number.class.isAssignableFrom(returnType)) {
			return (proxy, theMethod, args, theState, invokeSuper) -> {
				final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
				final Optional<GroupEntityReference> groupRef = referenceBuilder.getGroup();
				if (groupRef.isPresent()) {
					referenceBuilder.removeGroup();
					return true;
				} else {
					return false;
				}
			};
		} else if (Boolean.class.isAssignableFrom(returnType)) {
			return (proxy, theMethod, args, theState, invokeSuper) -> {
				final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
				final Optional<GroupEntityReference> groupRef = referenceBuilder.getGroup();
				if (groupRef.isPresent()) {
					referenceBuilder.removeGroup();
					return groupRef.get().getPrimaryKey();
				} else {
					return null;
				}
			};
		} else if (returnType.equals(void.class)) {
			return (proxy, theMethod, args, theState, invokeSuper) -> {
				theState.getReferenceBuilder().removeGroup();
				return null;
			};
		} else if (entityRecognizedInReturnType) {
			return removeReferencedEntityAndReturnItsProxy(referenceSchema, returnType);
		} else {
			return null;
		}
	}

	/**
	 * Method implementation that removes the single reference if exists and returns the removed reference proxy.
	 *
	 * @param referenceSchema the reference schema to use
	 * @param returnType      the return type
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> removeReferencedEntityAndReturnItsProxy(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> returnType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			final String referenceName = referenceSchema.getName();
			final Optional<SealedEntity> groupEntity = referenceBuilder.getGroupEntity();
			if (groupEntity.isPresent()) {
				// do nothing
				if (referenceBuilder.getGroup().isPresent()) {
					throw ContextMissingException.referencedEntityContextMissing(theState.getType(), referenceName);
				} else {
					return null;
				}
			} else {
				referenceBuilder.removeGroup();
				final SealedEntity referencedEntity = groupEntity.get();
				return theState.getOrCreateReferencedEntityProxy(
					returnType, referencedEntity, ProxyType.REFERENCED_ENTITY
				);
			}
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * (without knowing its primary key since it hasn't been assigned yet) and returns the reference to the created
	 * proxy allowing to set reference properties on it.
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType    the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> getOrCreateWithReferencedEntityGroupResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class expectedType
	) {
		final String referencedEntityType = getReferencedGroupType(referenceSchema, true);
		final String referenceName = referenceSchema.getName();
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			final Optional<GroupEntityReference> group = referenceBuilder.getGroup();
			if (group.isEmpty()) {
				return theState.createReferencedEntityProxyWithCallback(
					theState.getEntitySchemaOrThrow(referencedEntityType), expectedType, ProxyType.REFERENCED_ENTITY,
					entityReference -> referenceBuilder.setGroup(entityReference.primaryKey())
				);
			} else {
				final Optional<?> referencedInstance = theState.getReferencedEntityObjectIfPresent(
					referencedEntityType, group.get().getPrimaryKey(),
					expectedType, ProxyType.REFERENCED_ENTITY
				);
				if (referencedInstance.isPresent()) {
					return referencedInstance.get();
				} else {
					final Optional<SealedEntity> groupEntity = referenceBuilder.getGroupEntity();
					Assert.isTrue(
						groupEntity.isPresent(),
						() -> ContextMissingException.referencedEntityContextMissing(theState.getType(), referenceName)
					);
					return groupEntity
						.map(
							it -> theState.getOrCreateReferencedEntityProxy(
								expectedType, it, ProxyType.REFERENCED_ENTITY
							)
						)
						.orElse(null);
				}
			}
		};
	}

	/**
	 * Returns method implementation that creates or updates reference by passing directly the entities fetched from
	 * other sources. This implementation doesn't allow to set attributes on the reference.
	 *
	 * @param proxyState      the proxy state
	 * @param returnType      the return type
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setReferencedEntityByEntity(
		@Nonnull SealedEntityReferenceProxyState proxyState,
		@Nonnull Class<?> returnType,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		if (returnType.equals(proxyState.getProxyClass())) {
			return setReferencedEntityGroupWithBuilderResult(referenceSchema);
		} else {
			return setReferencedEntityGroupWithVoidResult(referenceSchema);
		}
	}

	/**
	 * Returns method implementation that sets the referenced entity by extracting it from {@link SealedEntityProxy}
	 * and return no result.
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setReferencedEntityGroupWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String expectedEntityType = getReferencedGroupType(referenceSchema, true);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			final SealedEntityProxy referencedEntity = (SealedEntityProxy) args[0];
			final EntityContract sealedEntity = referencedEntity.getEntity();
			Assert.isTrue(
				expectedEntityType.equals(sealedEntity.getType()),
				"Entity type `" + sealedEntity.getType() + "` in passed argument " +
					"doesn't match the referencedGroupEntity entity type: `" + expectedEntityType + "`!"
			);
			referenceBuilder.setGroup(sealedEntity.getPrimaryKey());
			return null;
		};
	}

	/**
	 * Returns method implementation that sets the referenced entity by extracting it from {@link SealedEntityProxy}
	 * and return the reference to the proxy to allow chaining (builder pattern).
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setReferencedEntityGroupWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String expectedEntityType = getReferencedGroupType(referenceSchema, true);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			final SealedEntityProxy referencedEntity = (SealedEntityProxy) args[0];
			final EntityContract sealedEntity = referencedEntity.getEntity();
			Assert.isTrue(
				expectedEntityType.equals(sealedEntity.getType()),
				"Entity type `" + sealedEntity.getType() + "` in passed argument " +
					"doesn't match the referencedGroupEntity entity type: `" + expectedEntityType + "`!"
			);
			referenceBuilder.setGroup(sealedEntity.getPrimaryKey());
			return proxy;
		};
	}

	/**
	 * Returns method implementation that creates or updates reference by consumer lambda, that
	 * could immediately modify referenced entity. This method creates the reference without possibility to set
	 * attributes on the reference and works directly with the referenced entity. The referenced entity has no primary
	 * key, which is assigned later when the entity is persisted.
	 *
	 * @param method          the method
	 * @param proxyState      the proxy state
	 * @param referenceSchema the reference schema
	 * @param returnType      the return type
	 * @param expectedType    the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nullable
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setReferencedEntityGroupByEntityConsumer(
		@Nonnull Method method,
		@Nonnull SealedEntityReferenceProxyState proxyState,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> returnType,
		@Nonnull Class<?> expectedType
	) {
		final String referencedGroupType = getReferencedGroupType(referenceSchema, true);
		if (method.isAnnotationPresent(CreateWhenMissing.class) ||
			Arrays.stream(method.getParameterAnnotations()[0]).anyMatch(CreateWhenMissing.class::isInstance)) {
			if (returnType.equals(proxyState.getProxyClass())) {
				return createReferencedEntityGroupWithEntityBuilderResult(
					referencedGroupType,
					expectedType
				);
			} else if (void.class.equals(returnType)) {
				return createReferencedEntityGroupWithVoidResult(
					referencedGroupType,
					expectedType
				);
			} else {
				return null;
			}
		} else {
			if (returnType.equals(proxyState.getProxyClass())) {
				return updateReferencedEntityWithEntityGroupBuilderResult(
					referenceSchema.getName(),
					expectedType
				);
			} else if (void.class.equals(returnType)) {
				return updateReferencedEntityGroupWithVoidResult(
					referenceSchema.getName(), expectedType
				);
			} else {
				return null;
			}
		}
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * (without knowing its primary key since it hasn't been assigned yet) and returns the reference to the entity proxy
	 * to allow chaining (builder pattern).
	 *
	 * @param expectedType    the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> createReferencedEntityGroupWithEntityBuilderResult(
		@Nonnull String referencedEntityType,
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			final Object referencedEntityInstance = theState.createReferencedEntityProxyWithCallback(
				theState.getEntitySchemaOrThrow(referencedEntityType),
				expectedType,
				ProxyType.REFERENCED_ENTITY,
				entityReference -> referenceBuilder.setGroup(entityReference.getPrimaryKey())
			);
			//noinspection unchecked
			final Consumer<Object> consumer = (Consumer<Object>) args[0];
			consumer.accept(referencedEntityInstance);
			return proxy;
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * (without knowing its primary key since it hasn't been assigned yet) and returns no result.
	 *
	 * @param expectedType    the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> createReferencedEntityGroupWithVoidResult(
		@Nonnull String referencedEntityType,
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			final Object referencedEntityInstance = theState.createReferencedEntityProxyWithCallback(
				theState.getEntitySchemaOrThrow(referencedEntityType),
				expectedType,
				ProxyType.REFERENCED_ENTITY,
				entityReference -> referenceBuilder.setGroup(entityReference.getPrimaryKey())
			);
			//noinspection unchecked
			final Consumer<Object> consumer = (Consumer<Object>) args[0];
			consumer.accept(referencedEntityInstance);
			return null;
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * (without knowing its primary key since it hasn't been assigned yet) and returns the reference to the entity proxy
	 * to allow chaining (builder pattern).
	 *
	 * @param expectedType the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> updateReferencedEntityWithEntityGroupBuilderResult(
		@Nonnull String referenceName,
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			final Optional<GroupEntityReference> group = referenceBuilder.getGroup();
			if (group.isEmpty()) {
				throw ContextMissingException.referenceContextMissing(referenceName);
			} else {
				final Object referencedEntityInstance = theState.getOrCreateReferencedEntityProxy(
					expectedType,
					referenceBuilder.getGroupEntity()
						.orElseThrow(() -> ContextMissingException.referencedEntityContextMissing(theState.getType(), referenceName)),
					ProxyType.REFERENCED_ENTITY
				);
				//noinspection unchecked
				final Consumer<Object> consumer = (Consumer<Object>) args[0];
				consumer.accept(referencedEntityInstance);
				return proxy;
			}
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * (without knowing its primary key since it hasn't been assigned yet) and returns no result.
	 *
	 * @param expectedType the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> updateReferencedEntityGroupWithVoidResult(
		@Nonnull String referenceName,
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			final Optional<GroupEntityReference> group = referenceBuilder.getGroup();
			if (group.isEmpty()) {
				throw ContextMissingException.referenceContextMissing(referenceName);
			} else {
				final Object referencedEntityInstance = theState.getOrCreateReferencedEntityProxy(
					expectedType,
					referenceBuilder.getGroupEntity()
						.orElseThrow(() -> ContextMissingException.referencedEntityContextMissing(theState.getType(), referenceName)),
					ProxyType.REFERENCED_ENTITY
				);
				//noinspection unchecked
				final Consumer<Object> consumer = (Consumer<Object>) args[0];
				consumer.accept(referencedEntityInstance);
				return null;
			}
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * and returns the reference to the created proxy allowing to set reference properties on it.
	 *
	 * @param expectedType the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setOrRemoveReferencedEntityByEntityReturnType(
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			final int referencedGroupId = EvitaDataTypes.toTargetType((Serializable) args[0], int.class);
			final Object referencedEntityInstance = theState.getOrCreateReferencedEntityProxy(
				theState.getEntitySchema(),
				expectedType,
				ProxyType.REFERENCED_ENTITY,
				referencedGroupId
			);
			referenceBuilder.setGroup(referencedGroupId);
			return referencedEntityInstance;
		};
	}

	/**
	 * Returns method implementation that creates, updates or removes reference by passing referenced entity ids.
	 * This implementation doesn't allow to set attributes on the reference.
	 *
	 * @param proxyState      the proxy state
	 * @param method          the method
	 * @param returnType      the return type
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setOrRemoveReferencedGroupById(
		@Nonnull SealedEntityReferenceProxyState proxyState,
		@Nonnull Method method,
		@Nonnull Class<?> returnType
	) {
		if (method.isAnnotationPresent(RemoveWhenExists.class)) {
			throw new EntityClassInvalidException(
				proxyState.getProxyClass(),
				"Method `" + method.getName() + "` of entity `" + proxyState.getType() + "` " +
					"that accepts integer argument cannot be annotated with `@RemoveWhenExists`!"
			);
		} else {
			if (returnType.equals(proxyState.getProxyClass())) {
				return setReferencedEntityGroupIdWithBuilderResult();
			} else if (returnType.equals(void.class)) {
				return setReferencedEntityGroupIdWithVoidResult();
			} else if (method.isAnnotationPresent(CreateWhenMissing.class)) {
				return getOrCreateByGroupIdWithReferencedEntityResult(returnType);
			} else {
				return null;
			}
		}
	}

	/**
	 * Returns method implementation that sets the referenced group entity id and return no result.
	 *
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setReferencedEntityGroupIdWithVoidResult() {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			final Serializable referencedPrimaryKey = (Serializable) args[0];
			referenceBuilder.setGroup(
				EvitaDataTypes.toTargetType(referencedPrimaryKey, int.class)
			);
			return null;
		};
	}

	/**
	 * Returns method implementation that sets the referenced entity group id and return the reference to the proxy to
	 * allow chaining (builder pattern).
	 *
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setReferencedEntityGroupIdWithBuilderResult() {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			final Serializable referencedPrimaryKey = (Serializable) args[0];
			referenceBuilder.setGroup(
				EvitaDataTypes.toTargetType(referencedPrimaryKey, int.class)
			);
			return proxy;
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * group and returns the created proxy allowing to set properties on it.
	 *
	 * @param expectedType    the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> getOrCreateByGroupIdWithReferencedEntityResult(
		@Nonnull Class expectedType
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final int referencedId = EvitaDataTypes.toTargetType((Serializable) args[0], int.class);
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			referenceBuilder.setGroup(referencedId);
			return theState.getOrCreateReferencedEntityProxy(
				expectedType,
				theState.getEntity(),
				ProxyType.REFERENCED_ENTITY
			);
		};
	}

	/**
	 * Returns method implementation that creates or updates reference group by passing {@link EntityClassifier}
	 * instances to the method parameter.
	 *
	 * @param proxyState         the proxy state
	 * @param returnType         the return type
	 * @param referenceSchema    the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setReferenceByEntityClassifier(
		@Nonnull SealedEntityReferenceProxyState proxyState,
		@Nonnull Class<?> returnType,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		if (returnType.equals(proxyState.getProxyClass())) {
			return setReferencedEntityGroupClassifierWithBuilderResult(referenceSchema);
		} else {
			return setReferencedEntityGroupClassifierWithVoidResult(referenceSchema);
		}
	}

	/**
	 * Returns method implementation that sets the referenced entity by extracting it from {@link EntityClassifier}
	 * and return no result.
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setReferencedEntityGroupClassifierWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String expectedEntityType = getReferencedGroupType(referenceSchema, false);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			final EntityClassifier referencedClassifier = (EntityClassifier) args[0];
			Assert.isTrue(
				expectedEntityType.equals(referencedClassifier.getType()),
				"Entity type `" + referencedClassifier.getType() + "` in passed argument " +
					"doesn't match the referenced entity group type: `" + expectedEntityType + "`!"
			);
			referenceBuilder.setGroup(referencedClassifier.getPrimaryKey());
			return null;
		};
	}

	/**
	 * Returns method implementation that sets the referenced entity by extracting it from {@link EntityClassifier}
	 * and return the reference to the proxy to allow chaining (builder pattern).
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> setReferencedEntityGroupClassifierWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String expectedEntityType = getReferencedGroupType(referenceSchema, false);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final ReferenceBuilder referenceBuilder = theState.getReferenceBuilder();
			final EntityClassifier referencedClassifier = (EntityClassifier) args[0];
			Assert.isTrue(
				expectedEntityType.equals(referencedClassifier.getType()),
				"Entity type `" + referencedClassifier.getType() + "` in passed argument " +
					"doesn't match the referenced entity group type: `" + expectedEntityType + "`!"
			);
			referenceBuilder.setGroup(referencedClassifier.getPrimaryKey());
			return proxy;
		};
	}

	public SetReferenceGroupMethodClassifier() {
		super(
			"setReferencedGroup",
			(method, proxyState) -> {
				final int parameterCount = method.getParameterCount();
				// now we need to identify reference schema that is being requested
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final ReferenceSchemaContract referenceSchema = proxyState.getReferenceSchema();

				final ReferencedEntityGroup referencedEntityGroup = reflectionLookup.getAnnotationInstanceForProperty(method, ReferencedEntityGroup.class);
				if (referencedEntityGroup == null) {
					return null;
				}

				final ResolvedParameter firstParameter;
				if (method.getParameterCount() > 0) {
					if (Collection.class.isAssignableFrom(method.getParameterTypes()[0])) {
						final List<GenericBundle> genericType = GenericsUtils.getGenericType(proxyState.getProxyClass(), method.getGenericParameterTypes()[0]);
						firstParameter = new ResolvedParameter(method.getParameterTypes()[0], genericType.get(0).getResolvedType());
					} else if (method.getParameterTypes()[0].isArray()) {
						firstParameter = new ResolvedParameter(method.getParameterTypes()[0], method.getParameterTypes()[0].getComponentType());
					} else {
						firstParameter = new ResolvedParameter(method.getParameterTypes()[0], method.getParameterTypes()[0]);
					}
				} else {
					firstParameter = null;
				}

				Optional<ResolvedParameter> referencedType = empty();
				for (int i = 0; i < parameterCount; i++) {
					final Class<?> parameterType = method.getParameterTypes()[i];
					if (EntityReferenceContract.class.isAssignableFrom(parameterType)) {
						referencedType = of(new ResolvedParameter(EntityReferenceContract.class, EntityReferenceContract.class));
					} else if (Consumer.class.isAssignableFrom(parameterType)) {
						final List<GenericBundle> genericType = GenericsUtils.getGenericType(proxyState.getProxyClass(), method.getGenericParameterTypes()[i]);
						referencedType = of(
							new ResolvedParameter(
								method.getParameterTypes()[i],
								genericType.get(0).getResolvedType()
							)
						);
					}
				}


				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
				final Optional<RecognizedContext> entityRecognizedIn = recognizeCallContext(
					reflectionLookup, referenceSchema,
					of(returnType)
						.filter(it -> !void.class.equals(it) && !returnType.equals(proxyState.getProxyClass()))
						.orElse(null),
					firstParameter,
					referencedType.map(ResolvedParameter::resolvedType).orElse(null)
				);

				final boolean noDirectlyReferencedEntityRecognized = entityRecognizedIn.isEmpty();
				final Class<?> expectedType = referencedType.map(ResolvedParameter::resolvedType).orElse(null);

				if (parameterCount == 0) {
					if (method.isAnnotationPresent(RemoveWhenExists.class)) {
						//noinspection unchecked
						return removeReferencedEntity(
							proxyState, referenceSchema,
							entityRecognizedIn.map(it -> it.entityContract().resolvedType()).orElse(returnType),
							isEntityRecognizedIn(entityRecognizedIn, EntityRecognizedIn.RETURN_TYPE)
						);
					} else if (isEntityRecognizedIn(entityRecognizedIn, EntityRecognizedIn.RETURN_TYPE)) {
						//noinspection unchecked
						return getOrCreateWithReferencedEntityGroupResult(
							referenceSchema,
							entityRecognizedIn.map(it -> it.entityContract().resolvedType()).orElse(returnType)
						);
					} else {
						return null;
					}
				} else if (parameterCount == 1) {
					final boolean parameterIsNumber = NumberUtils.isIntConvertibleNumber(method.getParameterTypes()[0]);
					if (parameterIsNumber && noDirectlyReferencedEntityRecognized) {
						return setOrRemoveReferencedGroupById(proxyState, method, returnType);
					} else if (resolvedTypeIs(referencedType, EntityReferenceContract.class) && noDirectlyReferencedEntityRecognized) {
						return setReferenceByEntityClassifier(proxyState, returnType, referenceSchema);
					} else if (isEntityRecognizedIn(entityRecognizedIn, EntityRecognizedIn.PARAMETER)) {
						return setReferencedEntityByEntity(proxyState, returnType, referenceSchema);
					} else if (isEntityRecognizedIn(entityRecognizedIn, EntityRecognizedIn.CONSUMER)) {
						return setReferencedEntityGroupByEntityConsumer(method, proxyState, referenceSchema, returnType, expectedType);
					} else if (isEntityRecognizedIn(entityRecognizedIn, EntityRecognizedIn.RETURN_TYPE) && parameterIsNumber) {
						return setOrRemoveReferencedEntityByEntityReturnType(entityRecognizedIn.get().entityContract().resolvedType());
					}
				}

				return null;
			}
		);
	}

}
