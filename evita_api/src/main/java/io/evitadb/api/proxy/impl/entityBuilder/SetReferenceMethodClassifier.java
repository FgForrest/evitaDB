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
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.SealedEntityProxy.ProxyType;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenNull;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.proxy.impl.entity.GetReferenceMethodClassifier.getReferenceSchema;
import static io.evitadb.api.proxy.impl.entityBuilder.EntityBuilderAdvice.REMOVAL_KEYWORDS;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Identifies methods that are used to set entity references into an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SetReferenceMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final SetReferenceMethodClassifier INSTANCE = new SetReferenceMethodClassifier();

	/**
	 * Asserts that the entity annotation on the referenced class is consistent with the entity type of the parent.
	 *
	 * @param reflectionLookup the reflection lookup
	 * @param referenceSchema  reference schema that is being processed
	 * @param parameterType    the parameter type to look for the annotation on
	 * @param consumerType     the return type to look for the annotation on
	 */
	@Nonnull
	private static Optional<RecognizedContext> recognizeCallContext(
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Class<?> returnType,
		@Nullable ResolvedParameter parameterType,
		@Nullable Class<?> consumerType
	) {
		final Optional<Entity> parameterEntityInstance = parameterType == null ? empty() : ofNullable(reflectionLookup.getClassAnnotation(parameterType.resolvedType(), Entity.class));
		final Optional<EntityRef> parameterEntityRefInstance = parameterType == null ? empty() : ofNullable(reflectionLookup.getClassAnnotation(parameterType.resolvedType(), EntityRef.class));
		final Optional<Entity> consumerTypeEntityInstance = consumerType == null ? empty() : ofNullable(reflectionLookup.getClassAnnotation(consumerType, Entity.class));
		final Optional<EntityRef> consumerTypeEntityRefInstance = consumerType == null ? empty() : ofNullable(reflectionLookup.getClassAnnotation(consumerType, EntityRef.class));
		final Optional<Entity> returnTypeEntityInstance = returnType == null ? empty() : ofNullable(reflectionLookup.getClassAnnotation(returnType, Entity.class));
		final Optional<EntityRef> returnTypeEntityRefInstance = returnType == null ? empty() : ofNullable(reflectionLookup.getClassAnnotation(returnType, EntityRef.class));

		final Optional<String> referencedEntityType = Stream.of(
			parameterEntityInstance.map(Entity::name),
			parameterEntityRefInstance.map(EntityRef::value),
			consumerTypeEntityInstance.map(Entity::name),
			consumerTypeEntityRefInstance.map(EntityRef::value),
			returnTypeEntityInstance.map(Entity::name),
			returnTypeEntityRefInstance.map(EntityRef::value)
		)
			.filter(Optional::isPresent)
			.map(Optional::get)
			.findFirst();

		final EntityRecognizedIn recognizedIn;
		final ResolvedParameter entityContract;
		if (parameterEntityInstance.isPresent() || parameterEntityRefInstance.isPresent()) {
			entityContract = parameterType;
			recognizedIn = EntityRecognizedIn.PARAMETER;
		} else if (consumerTypeEntityInstance.isPresent() || consumerTypeEntityRefInstance.isPresent()) {
			entityContract = new ResolvedParameter(consumerType, consumerType);
			recognizedIn = EntityRecognizedIn.CONSUMER;
		} else if (returnTypeEntityInstance.isPresent() || returnTypeEntityRefInstance.isPresent()) {
			entityContract = new ResolvedParameter(returnType, returnType);
			recognizedIn = EntityRecognizedIn.RETURN_TYPE;
		} else {
			return empty();
		}

		if (referencedEntityType.map(it -> it.equals(referenceSchema.getReferencedEntityType())).orElse(false)) {
			return of(
				new RecognizedContext(
					recognizedIn, entityContract, referenceSchema.getReferencedEntityType(), false
				)
			);
		} else if (referencedEntityType.map(it -> it.equals(referenceSchema.getReferencedGroupType())).orElse(false)) {
			return of(
				new RecognizedContext(
					recognizedIn, entityContract, referenceSchema.getReferencedEntityType(), true
				)
			);
		} else if (referenceSchema.getReferencedGroupType() == null) {
			//noinspection rawtypes
			throw new EntityClassInvalidException(
				entityContract.resolvedType(),
				"Referenced class type `" + entityContract.resolvedType() + "` must represent " +
					"entity type `" + referenceSchema.getReferencedEntityType() + "`, " +
					"but " +
					(consumerType != null || parameterType != null ? "neither the parameter type `" + ofNullable((Class)consumerType).orElse(parameterType.resolvedType()).getName() + "` nor " : "") +
					"the return type `" + returnType.getName() + "` is annotated with @Entity referencing `" +
					referencedEntityType.orElse("N/A") + "` entity type!"
			);
		} else {
			//noinspection rawtypes
			throw new EntityClassInvalidException(
				entityContract.resolvedType(),
				"Referenced class type `" + entityContract.resolvedType() + "` must represent " +
					"either entity type `" + referenceSchema.getReferencedEntityType() + "` or " +
					"`" + referenceSchema.getReferencedGroupType() + "` (group), " +
					(consumerType != null || parameterType != null ? "neither the parameter type `" + ofNullable((Class)consumerType).orElse(parameterType.resolvedType()).getName() + "` nor " : "") +
					"the return type `" + returnType.getName() + "` is annotated with @Entity referencing `" +
					referencedEntityType.orElse("N/A") + "` entity type!"
			);
		}
	}

	public SetReferenceMethodClassifier() {
		super(
			"setReference",
			(method, proxyState) -> {
				final int parameterCount = method.getParameterCount();
				// now we need to identify reference schema that is being requested
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final EntitySchemaContract entitySchema = proxyState.getEntitySchema();
				final ReferenceSchemaContract referenceSchema = getReferenceSchema(
					method, reflectionLookup, entitySchema
				);

				if (referenceSchema == null) {
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

				OptionalInt referencedIdIndex = OptionalInt.empty();
				OptionalInt consumerIndex = OptionalInt.empty();
				Optional<ResolvedParameter> referencedType = empty();
				for (int i = 0; i < parameterCount; i++) {
					final Class<?> parameterType = method.getParameterTypes()[i];
					if (NumberUtils.isIntConvertibleNumber(parameterType)) {
						referencedIdIndex = OptionalInt.of(i);
					} else if (Consumer.class.isAssignableFrom(parameterType)) {
						consumerIndex = OptionalInt.of(i);
						final List<GenericBundle> genericType = GenericsUtils.getGenericType(proxyState.getProxyClass(), method.getGenericParameterTypes()[i]);
						referencedType = of(
							new ResolvedParameter(
								method.getParameterTypes()[i],
								genericType.get(0).getResolvedType()
							)
						);
					} else if (Collection.class.isAssignableFrom(parameterType)) {
						final List<GenericBundle> genericType = GenericsUtils.getGenericType(proxyState.getProxyClass(), method.getGenericParameterTypes()[i]);
						referencedType = of(
							new ResolvedParameter(
								method.getParameterTypes()[i],
								genericType.get(0).getResolvedType()
							)
						);
					} else if (parameterType.isArray()) {
						referencedType = of(
							new ResolvedParameter(
								method.getParameterTypes()[i],
								parameterType.getComponentType()
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

				final String methodName = method.getName();
				if (parameterCount == 1 && referencedIdIndex.isPresent() && entityRecognizedIn.isEmpty()) {
					if (REMOVAL_KEYWORDS.stream().anyMatch(methodName::startsWith)) {
						if (returnType.equals(proxyState.getProxyClass())) {
							return removeReferencedEntityIdWithBuilderResult(referenceSchema);
						} else {
							return removeReferencedEntityIdWithVoidResult(referenceSchema);
						}
					} else {
						if (returnType.equals(proxyState.getProxyClass())) {
							return setReferencedEntityIdWithBuilderResult(referenceSchema);
						} else {
							return setReferencedEntityIdWithVoidResult(referenceSchema);
						}
					}
				} else if (parameterCount == 1 && referencedType.map(ResolvedParameter::resolvedType).map(EntityClassifier.class::isAssignableFrom).orElse(false) && entityRecognizedIn.isEmpty()) {
					final ResolvedParameter referencedParameter = entityRecognizedIn.get().entityContract();
					if (referencedParameter.mainType().isArray()) {
						if (returnType.equals(proxyState.getProxyClass())) {
							return setReferencedEntityClassifierAsArrayWithBuilderResult(referenceSchema);
						} else {
							return setReferencedEntityClassifierAsArrayWithVoidResult(referenceSchema);
						}
					} else if (Collection.class.isAssignableFrom(referencedParameter.mainType)) {
						if (returnType.equals(proxyState.getProxyClass())) {
							return setReferencedEntityClassifierAsCollectionWithBuilderResult(referenceSchema);
						} else {
							return setReferencedEntityClassifierAsCollectionWithVoidResult(referenceSchema);
						}
					} else {
						if (returnType.equals(proxyState.getProxyClass())) {
							return setReferencedEntityClassifierWithBuilderResult(referenceSchema);
						} else {
							return setReferencedEntityClassifierWithVoidResult(referenceSchema);
						}
					}
				} else if (parameterCount == 1 && entityRecognizedIn.map(RecognizedContext::recognizedIn).map(EntityRecognizedIn.PARAMETER::equals).orElse(false)) {
					final ResolvedParameter referencedParameter = entityRecognizedIn.get().entityContract();
					if (referencedParameter.mainType().isArray()) {
						if (returnType.equals(proxyState.getProxyClass())) {
							return setReferencedEntityAsArrayWithBuilderResult(referenceSchema);
						} else {
							return setReferencedEntityAsArrayWithVoidResult(referenceSchema);
						}
					} else if (Collection.class.isAssignableFrom(referencedParameter.mainType)) {
						if (returnType.equals(proxyState.getProxyClass())) {
							return setReferencedEntityAsCollectionWithBuilderResult(referenceSchema);
						} else {
							return setReferencedEntityAsCollectionWithVoidResult(referenceSchema);
						}
					} else {
						if (returnType.equals(proxyState.getProxyClass())) {
							return setReferencedEntityWithBuilderResult(referenceSchema);
						} else {
							return setReferencedEntityWithVoidResult(referenceSchema);
						}
					}
				} else if (parameterCount == 1 && entityRecognizedIn.map(RecognizedContext::recognizedIn).map(EntityRecognizedIn.CONSUMER::equals).orElse(false)) {
					final int consumerParameterIndex = consumerIndex.getAsInt();
					return proxyState.getEntitySchema(referenceSchema.getReferencedEntityType())
						.map(referencedEntitySchema -> {
							if (Arrays.stream(method.getParameterAnnotations()[consumerParameterIndex]).anyMatch(CreateWhenNull.class::isInstance)) {
								if (returnType.equals(proxyState.getProxyClass())) {
									return createReferencedEntityWithEntityBuilderResult(
										referencedEntitySchema,
										referenceSchema,
										entityRecognizedIn.get().entityContract().resolvedType()
									);
								} else if (void.class.equals(returnType)) {
									return createReferencedEntityWithVoidResult(
										referencedEntitySchema,
										referenceSchema,
										entityRecognizedIn.get().entityContract().resolvedType()
									);
								} else {
									return null;
								}
							} else {
								if (returnType.equals(proxyState.getProxyClass())) {
									return updateReferencedEntityWithEntityBuilderResult(
										referenceSchema,
										entityRecognizedIn.get().entityContract().resolvedType()
									);
								} else if (void.class.equals(returnType)) {
									return updateReferencedEntityWithVoidResult(
										referenceSchema,
										entityRecognizedIn.get().entityContract().resolvedType()
									);
								} else {
									return null;
								}
							}
						})
						.orElse(null);

				} else if (parameterCount == 2 && entityRecognizedIn.isEmpty() && consumerIndex.isPresent() && referencedIdIndex.isPresent()) {
					if (returnType.equals(proxyState.getProxyClass())) {
						return createReferenceWithIdAndEntityBuilderResult(
							referenceSchema, referencedType.map(ResolvedParameter::resolvedType).orElseThrow(),
							referencedIdIndex.getAsInt(), consumerIndex.getAsInt()
						);
					} else {
						return createReferenceWithIdAndVoidResult(
							referenceSchema, referencedType.map(ResolvedParameter::resolvedType).orElseThrow(),
							referencedIdIndex.getAsInt(), consumerIndex.getAsInt()
						);
					}
				} else if (parameterCount == 2 && entityRecognizedIn.map(RecognizedContext::recognizedIn).map(EntityRecognizedIn.CONSUMER::equals).orElse(false) && referencedIdIndex.isPresent()) {
					if (returnType.equals(proxyState.getProxyClass())) {
						//noinspection OptionalGetWithoutIsPresent
						return createReferencedEntityWithIdAndEntityBuilderResult(
							referenceSchema, entityRecognizedIn.get().entityContract().resolvedType(),
							referencedIdIndex.getAsInt(), consumerIndex.getAsInt()
						);
					} else {
						//noinspection OptionalGetWithoutIsPresent
						return createReferencedEntityWithIdAndVoidResult(
							referenceSchema, entityRecognizedIn.get().entityContract().resolvedType(),
							referencedIdIndex.getAsInt(), consumerIndex.getAsInt()
						);
					}
				} else if (parameterCount == 0 && entityRecognizedIn.map(RecognizedContext::recognizedIn).map(EntityRecognizedIn.RETURN_TYPE::equals).orElse(false)) {
					return createReferencedEntityWithCreatedProxyResult(
						referenceSchema,
						entityRecognizedIn.get().entityContract().resolvedType()
					);
				} else if (parameterCount == 1 && entityRecognizedIn.map(RecognizedContext::recognizedIn).map(EntityRecognizedIn.RETURN_TYPE::equals).orElse(false) && referencedIdIndex.isPresent()) {
					return createReferencedEntityWithIdAndCreatedProxyResult(
						referenceSchema,
						entityRecognizedIn.get().entityContract().resolvedType()
					);
				}

				return null;
			}
		);
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * and returns the reference to the reference proxy to allow chaining (builder pattern).
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType   the expected type of the referenced entity proxy
	 * @param referenceIdLocation the location of the reference id in the method arguments
	 * @param consumerLocation the location of the consumer in the method arguments
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> createReferenceWithIdAndEntityBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int consumerLocation
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final int referencedId = EvitaDataTypes.toTargetType((Serializable) args[referenceIdLocation], int.class);
			final Object referencedEntityInstance = theState.createEntityReferenceBuilderProxy(
				theState.getEntitySchema(),
				referenceSchema,
				expectedType,
				ProxyType.REFERENCE_BUILDER,
				referencedId
			);
			entityBuilder.setReference(referenceSchema.getName(), referencedId);
			//noinspection unchecked
			final Consumer<Object> consumer = (Consumer<Object>) args[consumerLocation];
			consumer.accept(referencedEntityInstance);
			return proxy;
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * and returns no result.
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType   the expected type of the referenced entity proxy
	 * @param referenceIdLocation the location of the reference id in the method arguments
	 * @param consumerLocation the location of the consumer in the method arguments
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> createReferenceWithIdAndVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int consumerLocation
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final int referencedId = EvitaDataTypes.toTargetType((Serializable) args[referenceIdLocation], int.class);
			final Object referencedEntityInstance = theState.createEntityReferenceBuilderProxy(
				theState.getEntitySchema(),
				referenceSchema,
				expectedType,
				ProxyType.REFERENCE_BUILDER,
				referencedId
			);
			entityBuilder.setReference(referenceSchema.getName(), referencedId);
			//noinspection unchecked
			final Consumer<Object> consumer = (Consumer<Object>) args[consumerLocation];
			consumer.accept(referencedEntityInstance);
			return null;
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a referenced external entity
	 * and returns the reference to the entity proxy to allow chaining (builder pattern).
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType   the expected type of the referenced entity proxy
	 * @param referenceIdLocation the location of the reference id in the method arguments
	 * @param consumerLocation the location of the consumer in the method arguments
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> createReferencedEntityWithIdAndEntityBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int consumerLocation
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final int referencedId = EvitaDataTypes.toTargetType((Serializable) args[referenceIdLocation], int.class);
			final Object referencedEntityInstance = theState.createEntityReferenceBuilderProxy(
				theState.getEntitySchema(),
				referenceSchema,
				expectedType,
				ProxyType.REFERENCE_BUILDER,
				referencedId
			);
			entityBuilder.setReference(referenceSchema.getName(), referencedId);
			//noinspection unchecked
			final Consumer<Object> consumer = (Consumer<Object>) args[consumerLocation];
			consumer.accept(referencedEntityInstance);
			return proxy;
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a referenced external entity
	 * and returns no result.
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType   the expected type of the referenced entity proxy
	 * @param referenceIdLocation the location of the reference id in the method arguments
	 * @param consumerLocation the location of the consumer in the method arguments
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> createReferencedEntityWithIdAndVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int consumerLocation
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final int referencedId = EvitaDataTypes.toTargetType((Serializable) args[referenceIdLocation], int.class);
			final Object referencedEntityInstance = theState.createEntityReferenceBuilderProxy(
				theState.getEntitySchema(),
				referenceSchema,
				expectedType,
				ProxyType.REFERENCE_BUILDER,
				referencedId
			);
			entityBuilder.setReference(referenceSchema.getName(), referencedId);
			//noinspection unchecked
			final Consumer<Object> consumer = (Consumer<Object>) args[consumerLocation];
			consumer.accept(referencedEntityInstance);
			return null;
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * and returns the reference to the created proxy allowing to set reference properties on it.
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType   the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> createReferencedEntityWithIdAndCreatedProxyResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final int referencedId = EvitaDataTypes.toTargetType((Serializable) args[0], int.class);
			final Object referencedEntityInstance = theState.createEntityReferenceBuilderProxy(
				theState.getEntitySchema(),
				referenceSchema,
				expectedType,
				ProxyType.REFERENCE_BUILDER,
				referencedId
			);
			entityBuilder.setReference(referenceSchema.getName(), referencedId);
			return referencedEntityInstance;
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * (without knowing its primary key since it hasn't been assigned yet) and returns the reference to the entity proxy
	 * to allow chaining (builder pattern).
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType   the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> createReferencedEntityWithEntityBuilderResult(
		@Nonnull EntitySchemaContract referencedEntitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Object referencedEntityInstance = theState.createEntityBuilderProxyWithCallback(
				referencedEntitySchema,
				theState.getReferencedEntitySchemas(),
				expectedType,
				ProxyType.REFERENCED_ENTITY_BUILDER,
				entityReference -> entityBuilder.setReference(referenceSchema.getName(), entityReference.getPrimaryKey())
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
	 * @param referenceSchema the reference schema to use
	 * @param expectedType   the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> createReferencedEntityWithVoidResult(
		@Nonnull EntitySchemaContract referencedEntitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Object referencedEntityInstance = theState.createEntityBuilderProxyWithCallback(
				referencedEntitySchema,
				theState.getReferencedEntitySchemas(),
				expectedType,
				ProxyType.REFERENCED_ENTITY_BUILDER,
				entityReference -> entityBuilder.setReference(referenceSchema.getName(), entityReference.getPrimaryKey())
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
	 * @param referenceSchema the reference schema to use
	 * @param expectedType   the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> updateReferencedEntityWithEntityBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceSchema.getName());
			if (references.isEmpty()) {
				throw ContextMissingException.referenceContextMissing(referenceSchema.getName());
			} else {
				final Object referencedEntityInstance = theState.createEntityBuilderProxy(
					expectedType,
					references.iterator().next().getReferencedEntity()
						.orElseThrow(() -> ContextMissingException.referencedEntityContextMissing(entityBuilder.getType(), referenceSchema.getName())),
					theState.getReferencedEntitySchemas()
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
	 * @param referenceSchema the reference schema to use
	 * @param expectedType   the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> updateReferencedEntityWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceSchema.getName());
			if (references.isEmpty()) {
				throw ContextMissingException.referenceContextMissing(referenceSchema.getName());
			} else {
				final Object referencedEntityInstance = theState.createEntityBuilderProxy(
					expectedType,
					references.iterator().next().getReferencedEntity()
						.orElseThrow(() -> ContextMissingException.referencedEntityContextMissing(entityBuilder.getType(), referenceSchema.getName())),
					theState.getReferencedEntitySchemas()
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
	 * (without knowing its primary key since it hasn't been assigned yet) and returns the reference to the created
	 * proxy allowing to set reference properties on it.
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType   the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> createReferencedEntityWithCreatedProxyResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			return theState.createEntityReferenceBuilderProxyWithCallback(
				theState.getEntitySchema(),
				referenceSchema,
				expectedType,
				ProxyType.REFERENCE_BUILDER,
				entityReference -> entityBuilder.setReference(referenceSchema.getName(), entityReference.getPrimaryKey())
			);
		};
	}

	/**
	 * Returns method implementation that sets the referenced entity by extracting it from {@link EntityClassifier}
	 * and return no result.
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityClassifierWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = referenceSchema.getReferencedEntityType();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final EntityClassifier referencedClassifier = (EntityClassifier) args[0];
			Assert.isTrue(
				expectedEntityType.equals(referencedClassifier.getType()),
				"Entity type `" + referencedClassifier.getType() + "` in passed argument " +
					"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
			);
			entityBuilder.setReference(referenceName, referencedClassifier.getPrimaryKey());
			return proxy;
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityClassifierWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = referenceSchema.getReferencedEntityType();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final EntityClassifier referencedClassifier = (EntityClassifier) args[0];
			Assert.isTrue(
				expectedEntityType.equals(referencedClassifier.getType()),
				"Entity type `" + referencedClassifier.getType() + "` in passed argument " +
					"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
			);
			entityBuilder.setReference(referenceName, referencedClassifier.getPrimaryKey());
			return proxy;
		};
	}

	/**
	 * Returns method implementation that sets the referenced entity by extracting it from {@link EntityClassifier}
	 * and return no result.
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityClassifierAsArrayWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = referenceSchema.getReferencedEntityType();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Object[] referencedClassifierArray = (Object[]) args[0];
			for (Object referencedClassifierObject : referencedClassifierArray) {
				final EntityClassifier referencedClassifier = (EntityClassifier) referencedClassifierObject;
				Assert.isTrue(
					expectedEntityType.equals(referencedClassifier.getType()),
					"Entity type `" + referencedClassifier.getType() + "` in passed argument " +
						"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
				);
				entityBuilder.setReference(referenceName, referencedClassifier.getPrimaryKey());
			}
			return proxy;
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityClassifierAsArrayWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = referenceSchema.getReferencedEntityType();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Object[] referencedClassifierArray = (Object[]) args[0];
			for (Object referencedClassifierObject : referencedClassifierArray) {
				final EntityClassifier referencedClassifier = (EntityClassifier) referencedClassifierObject;
				Assert.isTrue(
					expectedEntityType.equals(referencedClassifier.getType()),
					"Entity type `" + referencedClassifier.getType() + "` in passed argument " +
						"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
				);
				entityBuilder.setReference(referenceName, referencedClassifier.getPrimaryKey());
			}
			return proxy;
		};
	}

	/**
	 * Returns method implementation that sets the referenced entity by extracting it from {@link EntityClassifier}
	 * and return no result.
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityClassifierAsCollectionWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = referenceSchema.getReferencedEntityType();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			//noinspection DataFlowIssue,unchecked
			final Collection<EntityClassifier> referencedClassifierArray = (Collection<EntityClassifier>) args[0];
			for (EntityClassifier referencedClassifier : referencedClassifierArray) {
				Assert.isTrue(
					expectedEntityType.equals(referencedClassifier.getType()),
					"Entity type `" + referencedClassifier.getType() + "` in passed argument " +
						"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
				);
				entityBuilder.setReference(referenceName, referencedClassifier.getPrimaryKey());
			}
			return proxy;
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityClassifierAsCollectionWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = referenceSchema.getReferencedEntityType();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			//noinspection DataFlowIssue,unchecked
			final Collection<EntityClassifier> referencedClassifierArray = (Collection<EntityClassifier>) args[0];
			for (EntityClassifier referencedClassifier : referencedClassifierArray) {
				Assert.isTrue(
					expectedEntityType.equals(referencedClassifier.getType()),
					"Entity type `" + referencedClassifier.getType() + "` in passed argument " +
						"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
				);
				entityBuilder.setReference(referenceName, referencedClassifier.getPrimaryKey());
			}
			return proxy;
		};
	}

	/**
	 * Returns method implementation that sets the referenced entity by extracting it from {@link SealedEntityProxy}
	 * and return no result.
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = referenceSchema.getReferencedEntityType();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final SealedEntityProxy referencedEntity = (SealedEntityProxy) args[0];
			final EntityContract sealedEntity = referencedEntity.getEntity();
			Assert.isTrue(
				expectedEntityType.equals(sealedEntity.getType()),
				"Entity type `" + sealedEntity.getType() + "` in passed argument " +
					"doesn't match the referencedEntity entity type: `" + expectedEntityType + "`!"
			);
			entityBuilder.setReference(referenceName, sealedEntity.getPrimaryKey());
			return proxy;
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = referenceSchema.getReferencedEntityType();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final SealedEntityProxy referencedEntity = (SealedEntityProxy) args[0];
			final EntityContract sealedEntity = referencedEntity.getEntity();
			Assert.isTrue(
				expectedEntityType.equals(sealedEntity.getType()),
				"Entity type `" + sealedEntity.getType() + "` in passed argument " +
					"doesn't match the referencedEntity entity type: `" + expectedEntityType + "`!"
			);
			final Integer primaryKey = sealedEntity.getPrimaryKey();
			entityBuilder.setReference(referenceName, primaryKey);
			theState.registerReferencedEntityObject(expectedEntityType, primaryKey, referencedEntity, ProxyType.ENTITY);
			return proxy;
		};
	}

	/**
	 * Returns method implementation that sets the referenced entity by extracting it from {@link SealedEntityProxy}
	 * and return no result.
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityAsArrayWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = referenceSchema.getReferencedEntityType();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Object[] referencedArray = (Object[]) args[0];
			for (Object referencedEntity : referencedArray) {
				final EntityContract sealedEntity = ((SealedEntityProxy)referencedEntity).getEntity();
				Assert.isTrue(
					expectedEntityType.equals(sealedEntity.getType()),
					"Entity type `" + sealedEntity.getType() + "` in passed argument " +
						"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
				);
				entityBuilder.setReference(referenceName, sealedEntity.getPrimaryKey());
			}
			return proxy;
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityAsArrayWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = referenceSchema.getReferencedEntityType();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Object[] referencedArray = (Object[]) args[0];
			for (Object referencedEntity : referencedArray) {
				final EntityContract sealedEntity = ((SealedEntityProxy)referencedEntity).getEntity();
				Assert.isTrue(
					expectedEntityType.equals(sealedEntity.getType()),
					"Entity type `" + sealedEntity.getType() + "` in passed argument " +
						"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
				);
				entityBuilder.setReference(referenceName, sealedEntity.getPrimaryKey());
			}
			return proxy;
		};
	}

	/**
	 * Returns method implementation that sets the referenced entity by extracting it from {@link SealedEntityProxy}
	 * and return no result.
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityAsCollectionWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = referenceSchema.getReferencedEntityType();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			//noinspection DataFlowIssue,unchecked
			final Collection<SealedEntityProxy> referencedArray = (Collection<SealedEntityProxy>) args[0];
			for (SealedEntityProxy referencedEntity : referencedArray) {
				final EntityContract sealedEntity = referencedEntity.getEntity();
				Assert.isTrue(
					expectedEntityType.equals(sealedEntity.getType()),
					"Entity type `" + sealedEntity.getType() + "` in passed argument " +
						"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
				);
				entityBuilder.setReference(referenceName, sealedEntity.getPrimaryKey());
			}
			return proxy;
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityAsCollectionWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = referenceSchema.getReferencedEntityType();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			//noinspection DataFlowIssue,unchecked
			final Collection<SealedEntityProxy> referencedArray = (Collection<SealedEntityProxy>) args[0];
			for (SealedEntityProxy referencedEntity : referencedArray) {
				final EntityContract sealedEntity = referencedEntity.getEntity();
				Assert.isTrue(
					expectedEntityType.equals(sealedEntity.getType()),
					"Entity type `" + sealedEntity.getType() + "` in passed argument " +
						"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
				);
				entityBuilder.setReference(referenceName, sealedEntity.getPrimaryKey());
			}
			return proxy;
		};
	}

	/**
	 * Returns method implementation that sets the referenced entity id and return no result.
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityIdWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Serializable referencedPrimaryKey = (Serializable) args[0];
			entityBuilder.setReference(
				referenceName,
				EvitaDataTypes.toTargetType(referencedPrimaryKey, int.class)
			);
			return null;
		};
	}

	/**
	 * Returns method implementation that sets the referenced entity id and return the reference to the proxy to allow
	 * chaining (builder pattern).
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityIdWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Serializable referencedPrimaryKey = (Serializable) args[0];
			entityBuilder.setReference(
				referenceName,
				EvitaDataTypes.toTargetType(referencedPrimaryKey, int.class)
			);
			return proxy;
		};
	}

	/**
	 * Returns method implementation that removes the referenced entity id and return no result.
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeReferencedEntityIdWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Serializable referencedPrimaryKey = (Serializable) args[0];
			entityBuilder.removeReference(
				referenceName,
				EvitaDataTypes.toTargetType(referencedPrimaryKey, int.class)
			);
			return null;
		};
	}

	/**
	 * Returns method implementation that removes the referenced entity id and return the reference to the proxy to allow
	 * chaining (builder pattern).
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeReferencedEntityIdWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.getEntityBuilder();
			final Serializable referencedPrimaryKey = (Serializable) args[0];
			entityBuilder.removeReference(
				referenceName,
				EvitaDataTypes.toTargetType(referencedPrimaryKey, int.class)
			);
			return proxy;
		};
	}

	/**
	 * Represents identification of the place which has been recognized as reference representation.
	 */
	private enum EntityRecognizedIn {
		PARAMETER,
		CONSUMER,
		RETURN_TYPE
	}

	/**
	 * Record containing information about the resolved generic parameter type along with the original class.
	 * @param mainType common parameter type (generic wrapper)
	 * @param resolvedType resolved generic parameter type
	 */
	private record ResolvedParameter(
		@Nonnull Class<?> mainType,
		@Nonnull Class<?> resolvedType
	) {}

	/**
	 * Record containing information about the context recognized from the method signature.
	 *
	 * @param recognizedIn the place where the entity type has been recognized
	 * @param entityContract the resolved entity contract
	 * @param entityType the resolved entity type
	 * @param isGroup whether the entity is a reference group or referenced entity itself
	 */
	private record RecognizedContext(
		@Nonnull EntityRecognizedIn recognizedIn,
		@Nonnull ResolvedParameter entityContract,
		@Nonnull String entityType,
		boolean isGroup
	) {

	}

}
