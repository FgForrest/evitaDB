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

package io.evitadb.api.proxy.impl.entityBuilder;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.SealedEntityProxy.ProxyType;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.EvitaInvalidUsageException;
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
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.proxy.impl.entity.GetReferenceMethodClassifier.getReferenceSchema;
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
	public static Optional<RecognizedContext> recognizeCallContext(
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

		final String expectedReferencedEntityType = referenceSchema.getReferencedEntityType();
		if (referencedEntityType.map(it -> it.equals(expectedReferencedEntityType)).orElse(false)) {
			return of(
				new RecognizedContext(
					recognizedIn, entityContract, expectedReferencedEntityType, false
				)
			);
		} else if (referencedEntityType.map(it -> it.equals(referenceSchema.getReferencedGroupType())).orElse(false)) {
			return of(
				new RecognizedContext(
					recognizedIn, entityContract, expectedReferencedEntityType, true
				)
			);
		} else if (referenceSchema.getReferencedGroupType() == null) {
			//noinspection rawtypes
			throw new EntityClassInvalidException(
				entityContract.resolvedType(),
				"Referenced class type `" + entityContract.resolvedType() + "` must represent " +
					"entity type `" + expectedReferencedEntityType + "`, " +
					"but " +
					(consumerType != null || parameterType != null ? "neither the parameter type `" + ofNullable((Class) consumerType).orElse(parameterType.resolvedType()).getName() + "` nor " : "") +
					"the return type `" + returnType.getName() + "` is annotated with @Entity referencing `" +
					referencedEntityType.orElse("N/A") + "` entity type!"
			);
		} else {
			//noinspection rawtypes
			throw new EntityClassInvalidException(
				entityContract.resolvedType(),
				"Referenced class type `" + entityContract.resolvedType() + "` must represent " +
					"either entity type `" + expectedReferencedEntityType + "` or " +
					"`" + referenceSchema.getReferencedGroupType() + "` (group), " +
					(consumerType != null || parameterType != null ? "neither the parameter type `" + ofNullable((Class) consumerType).orElse(parameterType.resolvedType()).getName() + "` nor " : "") +
					"the return type `" + returnType.getName() + "` is annotated with @Entity referencing `" +
					referencedEntityType.orElse("N/A") + "` entity type!"
			);
		}
	}

	/**
	 * Returns true if the entity annotation is recognized in the given scope.
	 *
	 * @param entityRecognizedIn the entity recognized in
	 * @param scope              the scope to check
	 * @return true if the entity annotation is recognized in the given scope
	 */
	public static boolean isEntityRecognizedIn(
		@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
		@Nonnull Optional<RecognizedContext> entityRecognizedIn,
		@Nonnull EntityRecognizedIn scope
	) {
		return entityRecognizedIn.map(RecognizedContext::recognizedIn).map(scope::equals).orElse(false);
	}

	/**
	 * Returns true if referenced type is assignable from the given class.
	 *
	 * @param referencedType the referenced type
	 * @param aClass         the class to check
	 * @return true if referenced type is assignable from the given class
	 */
	@Nonnull
	public static Boolean resolvedTypeIs(
		@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
		@Nonnull Optional<ResolvedParameter> referencedType,
		@Nonnull Class<?> aClass
	) {
		return referencedType.map(ResolvedParameter::resolvedType).map(aClass::isAssignableFrom).orElse(false);
	}

	/**
	 * Returns true if referenced type is assignable from the given class.
	 *
	 * @param referencedType the referenced type
	 * @return true if referenced type is assignable from the given class
	 */
	@Nonnull
	public static Boolean resolvedTypeIsNumber(
		@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
		@Nonnull Optional<ResolvedParameter> referencedType
	) {
		return referencedType.map(ResolvedParameter::resolvedType).map(NumberUtils::isIntConvertibleNumber).orElse(false);
	}

	/**
	 * Method returns the referenced entity type and verifies that it is managed by evitaDB.
	 *
	 * @param referenceSchema the reference schema
	 * @return the referenced entity type
	 */
	@Nonnull
	private static String getReferencedType(@Nonnull ReferenceSchemaContract referenceSchema, boolean requireManagedOnly) {
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		Assert.isTrue(
			!requireManagedOnly || referenceSchema.isReferencedEntityTypeManaged(),
			"Referenced entity group type `" + referencedEntityType + "` is not managed " +
				"by evitaDB and cannot be created by method call!"
		);
		return referencedEntityType;
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * and returns the reference to the reference proxy to allow chaining (builder pattern).
	 *
	 * @param referenceSchema     the reference schema to use
	 * @param expectedType        the expected type of the referenced entity proxy
	 * @param referenceIdLocation the location of the reference id in the method arguments
	 * @param consumerLocation    the location of the consumer in the method arguments
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getOrCreateReferenceWithIdAndEntityBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int consumerLocation
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			getOrCreateReferenceWithId(referenceSchema, expectedType, referenceIdLocation, consumerLocation, args, theState);
			return proxy;
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * and returns no result.
	 *
	 * @param referenceSchema     the reference schema to use
	 * @param expectedType        the expected type of the referenced entity proxy
	 * @param referenceIdLocation the location of the reference id in the method arguments
	 * @param consumerLocation    the location of the consumer in the method arguments
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getOrCreateReferenceWithIdAndVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int consumerLocation
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			getOrCreateReferenceWithId(referenceSchema, expectedType, referenceIdLocation, consumerLocation, args, theState);
			return proxy;
		};
	}

	/**
	 * Create or retrieve a reference instance to an external entity with a specified ID.
	 *
	 * @param referenceSchema     The reference schema to use.
	 * @param expectedType        The expected type of the referenced entity proxy.
	 * @param referenceIdLocation The index of the reference ID in the method arguments.
	 * @param consumerLocation    The index of the consumer in the method arguments.
	 * @param args                The method arguments.
	 * @param theState            The proxy state.
	 */
	private static void getOrCreateReferenceWithId(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int consumerLocation,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final String referenceName = referenceSchema.getName();
		final int referencedId = Objects.requireNonNull(EvitaDataTypes.toTargetType((Serializable) args[referenceIdLocation], int.class));
		final Optional<ReferenceContract> reference = theState.entityBuilder()
			.getReference(referenceName, referencedId);
		//noinspection unchecked
		final Object referenceProxy = reference
			.map(referenceContract -> theState.getOrCreateEntityReferenceProxy((Class<Object>) expectedType, referenceContract))
			.orElseGet(
				() -> {
					theState.entityBuilder().setReference(referenceSchema.getName(), referencedId);
					//noinspection unchecked
					return theState.createEntityReferenceProxy(
						theState.getEntitySchema(), referenceSchema, (Class<Object>) expectedType, ProxyType.REFERENCE,
						referencedId
					);
				}
			);
		//noinspection unchecked
		final Consumer<Object> consumer = (Consumer<Object>) args[consumerLocation];
		consumer.accept(referenceProxy);
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * and returns the reference to the reference proxy to allow chaining (builder pattern).
	 *
	 * @param referenceSchema     the reference schema to use
	 * @param expectedType        the expected type of the referenced entity proxy
	 * @param referenceIdLocation the location of the reference id in the method arguments
	 * @param consumerLocation    the location of the consumer in the method arguments
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getAndUpdateReferenceWithIdAndEntityBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int consumerLocation
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			getAndUpdateReferenceWithId(referenceSchema, expectedType, referenceIdLocation, consumerLocation, args, theState);
			return proxy;
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * and returns no result.
	 *
	 * @param referenceSchema     the reference schema to use
	 * @param expectedType        the expected type of the referenced entity proxy
	 * @param referenceIdLocation the location of the reference id in the method arguments
	 * @param consumerLocation    the location of the consumer in the method arguments
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getAndUpdateReferenceWithIdAndVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int consumerLocation
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			getAndUpdateReferenceWithId(referenceSchema, expectedType, referenceIdLocation, consumerLocation, args, theState);
			return null;
		};
	}

	/**
	 * Performs a get and update operation on a reference with the given ID.
	 *
	 * @param referenceSchema     the reference schema to use
	 * @param expectedType        the expected type of the referenced entity proxy
	 * @param referenceIdLocation the location of the reference ID in the method arguments
	 * @param consumerLocation    the location of the consumer in the method arguments
	 * @param args                the method arguments
	 * @param theState            the sealed entity proxy state
	 */
	private static void getAndUpdateReferenceWithId(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int consumerLocation,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final String referenceName = referenceSchema.getName();
		final int referencedId = Objects.requireNonNull(EvitaDataTypes.toTargetType((Serializable) args[referenceIdLocation], int.class));
		final Optional<ReferenceContract> reference = theState.entityBuilder()
			.getReference(referenceName, referencedId);
		final Object referenceProxy;
		if (reference.isEmpty()) {
			throw new ReferenceNotFoundException(referenceName, referencedId, theState.entity());
		} else {
			//noinspection unchecked
			referenceProxy = theState.getOrCreateEntityReferenceProxy((Class<Object>) expectedType, reference.get());
		}
		//noinspection unchecked
		final Consumer<Object> consumer = (Consumer<Object>) args[consumerLocation];
		consumer.accept(referenceProxy);
	}

	/**
	 * Return a method implementation that creates new proxy object representing a referenced external entity
	 * and returns the reference to the entity proxy to allow chaining (builder pattern).
	 *
	 * @param referenceSchema     the reference schema to use
	 * @param expectedType        the expected type of the referenced entity proxy
	 * @param referenceIdLocation the location of the reference id in the method arguments
	 * @param consumerLocation    the location of the consumer in the method arguments
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
			createReferencedEntityWithId(referenceSchema, expectedType, referenceIdLocation, consumerLocation, args, theState);
			return proxy;
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a referenced external entity
	 * and returns no result.
	 *
	 * @param referenceSchema     the reference schema to use
	 * @param expectedType        the expected type of the referenced entity proxy
	 * @param referenceIdLocation the location of the reference id in the method arguments
	 * @param consumerLocation    the location of the consumer in the method arguments
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
			createReferencedEntityWithId(referenceSchema, expectedType, referenceIdLocation, consumerLocation, args, theState);
			return null;
		};
	}

	/**
	 * Create a new proxy object representing a referenced external entity, using the provided reference schema,
	 * expected type, reference ID location, consumer location, method arguments, and proxy state.
	 *
	 * @param referenceSchema     The reference schema to use
	 * @param expectedType        The expected type of the referenced entity proxy
	 * @param referenceIdLocation The location of the reference ID in the method arguments
	 * @param consumerLocation    The location of the consumer in the method arguments
	 * @param args                The method arguments
	 * @param theState            The proxy state
	 */
	private static void createReferencedEntityWithId(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int consumerLocation,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final int referencedId = Objects.requireNonNull(EvitaDataTypes.toTargetType((Serializable) args[referenceIdLocation], int.class));
		entityBuilder.setReference(referenceSchema.getName(), referencedId);
		final Object referencedEntityInstance = theState.createEntityReferenceProxy(
			theState.getEntitySchema(),
			referenceSchema,
			expectedType,
			ProxyType.REFERENCE,
			referencedId
		);
		//noinspection unchecked
		final Consumer<Object> consumer = (Consumer<Object>) args[consumerLocation];
		consumer.accept(referencedEntityInstance);
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * (without knowing its primary key since it hasn't been assigned yet) and returns the reference to the entity proxy
	 * to allow chaining (builder pattern).
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType    the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> createReferencedEntityWithEntityBuilderResult(
		@Nonnull EntitySchemaContract referencedEntitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			createReferencedEntity(referencedEntitySchema, referenceSchema, expectedType, args, theState);
			return proxy;
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * (without knowing its primary key since it hasn't been assigned yet) and returns no result.
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType    the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> createReferencedEntityWithVoidResult(
		@Nonnull EntitySchemaContract referencedEntitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			createReferencedEntity(referencedEntitySchema, referenceSchema, expectedType, args, theState);
			return null;
		};
	}

	/**
	 * Create a new proxy object representing a reference to an external entity
	 * without knowing its primary key since it hasn't been assigned yet.
	 *
	 * @param referencedEntitySchema the schema of the referenced entity
	 * @param referenceSchema        the reference schema to use
	 * @param expectedType           the expected type of the referenced entity proxy
	 * @param args                   the arguments passed to the method
	 * @param theState               the state of the proxy
	 */
	private static void createReferencedEntity(
		@Nonnull EntitySchemaContract referencedEntitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final Object referencedEntityInstance = theState.createReferencedEntityProxyWithCallback(
			referencedEntitySchema,
			expectedType,
			ProxyType.REFERENCED_ENTITY,
			entityReference -> entityBuilder.setReference(referenceSchema.getName(), entityReference.getPrimaryKey())
		);
		//noinspection unchecked
		final Consumer<Object> consumer = (Consumer<Object>) args[0];
		consumer.accept(referencedEntityInstance);
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * (without knowing its primary key since it hasn't been assigned yet) and returns the reference to the entity proxy
	 * to allow chaining (builder pattern).
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType    the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> updateReferencedEntityWithEntityBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			updateReferencedEntity(referenceSchema, expectedType, args, theState);
			return proxy;
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * (without knowing its primary key since it hasn't been assigned yet) and returns no result.
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType    the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> updateReferencedEntityWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			updateReferencedEntity(referenceSchema, expectedType, args, theState);
			return null;
		};
	}

	/**
	 * Updates the referenced entity using consumer logic.
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType    the expected type of the referenced entity proxy
	 * @param args            the arguments to pass to the updateReferencedEntity method
	 * @param theState        the sealed entity proxy state
	 */
	private static void updateReferencedEntity(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceSchema.getName());
		if (references.isEmpty()) {
			throw ContextMissingException.referenceContextMissing(referenceSchema.getName());
		} else {
			final Object referencedEntityInstance = theState.getOrCreateReferencedEntityProxy(
				expectedType,
				references.iterator().next().getReferencedEntity()
					.orElseThrow(() -> ContextMissingException.referencedEntityContextMissing(entityBuilder.getType(), referenceSchema.getName())),
				ProxyType.REFERENCED_ENTITY
			);
			//noinspection unchecked
			final Consumer<Object> consumer = (Consumer<Object>) args[0];
			consumer.accept(referencedEntityInstance);
		}
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * and returns the reference to the created proxy allowing to set reference properties on it.
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType    the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getOrCreateByIdWithReferenceResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class expectedType
	) {
		final String referenceName = referenceSchema.getName();
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final int referencedId = Objects.requireNonNull(EvitaDataTypes.toTargetType((Serializable) args[0], int.class));
			final Collection<ReferenceContract> references = theState.entityBuilder()
				.getReferences(referenceName);
			if (references.isEmpty()) {
				theState.entityBuilder().setReference(referenceSchema.getName(), referencedId);
				return theState.createEntityReferenceProxy(
					theState.getEntitySchema(), referenceSchema, expectedType, ProxyType.REFERENCE,
					referencedId
				);
			} else {
				final ReferenceContract firstReference = references.iterator().next();
				return theState.getOrCreateEntityReferenceProxy(expectedType, firstReference);
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getOrCreateWithReferencedEntityResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class expectedType
	) {
		final String referencedEntityType = getReferencedType(referenceSchema, true);
		final String referenceName = referenceSchema.getName();
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.entityBuilder();
			final Collection<ReferenceContract> references = entityBuilder
				.getReferences(referenceName);
			if (references.isEmpty()) {
				return theState.createReferencedEntityProxyWithCallback(
					theState.getEntitySchemaOrThrow(referencedEntityType), expectedType, ProxyType.REFERENCED_ENTITY,
					entityReference -> entityBuilder.setReference(referenceName, entityReference.primaryKey())
				);
			} else {
				final ReferenceContract firstReference = references.iterator().next();
				final Optional<?> referencedInstance = theState.getReferencedEntityObjectIfPresent(
					referencedEntityType, firstReference.getReferencedPrimaryKey(),
					expectedType, ProxyType.REFERENCED_ENTITY
				);
				if (referencedInstance.isPresent()) {
					return referencedInstance.get();
				} else {
					Assert.isTrue(
						firstReference.getReferencedEntity().isPresent(),
						() -> ContextMissingException.referencedEntityContextMissing(theState.getType(), referenceName)
					);
					return firstReference.getReferencedEntity()
						.map(it -> theState.getOrCreateReferencedEntityProxy(expectedType, it, ProxyType.REFERENCED_ENTITY))
						.orElse(null);
				}
			}
		};
	}

	/**
	 * Return a method implementation that removes the single reference if exists.
	 *
	 * @param referenceSchema the reference schema to use
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeReference(
		@Nonnull SealedEntityProxyState proxyState,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ResolvedParameter resolvedParameter,
		boolean entityRecognizedInReturnType
	) {
		final Class<?> returnType = resolvedParameter.resolvedType();
		if (Collection.class.equals(resolvedParameter.mainType()) || List.class.equals(resolvedParameter.mainType())) {
			if (Number.class.isAssignableFrom(returnType)) {
				return (proxy, theMethod, args, theState, invokeSuper) -> {
					final EntityBuilder entityBuilder = theState.entityBuilder();
					final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceSchema.getName());
					references.forEach(it -> {
						entityBuilder.removeReference(referenceSchema.getName(), it.getReferencedPrimaryKey());
						theState.unregisterReferencedEntityObject(referenceSchema.getName(), it.getReferencedPrimaryKey(), ProxyType.REFERENCE);
						theState.unregisterReferencedEntityObject(referenceSchema.getName(), it.getReferencedPrimaryKey(), ProxyType.REFERENCED_ENTITY);
					});
					return references.stream()
						.map(it -> it.getReferenceKey().primaryKey())
						.toList();
				};
			} else {
				return removeReferencesAndReturnTheirProxies(referenceSchema, returnType, entityRecognizedInReturnType);
			}
		} else if (returnType.equals(void.class)) {
			return (proxy, theMethod, args, theState, invokeSuper) -> {
				final EntityBuilder entityBuilder = theState.entityBuilder();
				final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceSchema.getName());
				references.forEach(it -> {
					entityBuilder.removeReference(referenceSchema.getName(), it.getReferencedPrimaryKey());
					theState.unregisterReferencedEntityObject(referenceSchema.getName(), it.getReferencedPrimaryKey(), ProxyType.REFERENCE);
					theState.unregisterReferencedEntityObject(referenceSchema.getName(), it.getReferencedPrimaryKey(), ProxyType.REFERENCED_ENTITY);
				});
				return null;
			};
		} else if (Boolean.class.isAssignableFrom(returnType)) {
			return (proxy, theMethod, args, theState, invokeSuper) -> {
				final EntityBuilder entityBuilder = theState.entityBuilder();
				final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceSchema.getName());
				references.forEach(it -> {
					entityBuilder.removeReference(referenceSchema.getName(), it.getReferencedPrimaryKey());
					theState.unregisterReferencedEntityObject(referenceSchema.getName(), it.getReferencedPrimaryKey(), ProxyType.REFERENCE);
					theState.unregisterReferencedEntityObject(referenceSchema.getName(), it.getReferencedPrimaryKey(), ProxyType.REFERENCED_ENTITY);
				});
				return !references.isEmpty();
			};
		} else {
			if (returnType.equals(proxyState.getProxyClass())) {
				return (proxy, theMethod, args, theState, invokeSuper) -> {
					final EntityBuilder entityBuilder = theState.entityBuilder();
					final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceSchema.getName());
					if (references.isEmpty()) {
						// do nothing
					} else if (references.size() == 1) {
						final int referencedPrimaryKey = references.iterator().next().getReferencedPrimaryKey();
						entityBuilder.removeReference(
							referenceSchema.getName(),
							referencedPrimaryKey
						);
						theState.unregisterReferencedEntityObject(referenceSchema.getName(), referencedPrimaryKey, ProxyType.REFERENCE);
						theState.unregisterReferencedEntityObject(referenceSchema.getName(), referencedPrimaryKey, ProxyType.REFERENCED_ENTITY);
					} else {
						throw new EvitaInvalidUsageException(
							"Cannot remove reference `" + referenceSchema.getName() +
								"` from entity `" + theState.getEntitySchema().getName() + "` " +
								"because there is more than single reference!"
						);
					}
					return proxy;
				};
			} else if (Number.class.isAssignableFrom(returnType)) {
				return (proxy, theMethod, args, theState, invokeSuper) -> {
					final EntityBuilder entityBuilder = theState.entityBuilder();
					final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceSchema.getName());
					if (references.isEmpty()) {
						// do nothing
						return null;
					} else if (references.size() == 1) {
						final int referencedPrimaryKey = references.iterator().next().getReferencedPrimaryKey();
						entityBuilder.removeReference(
							referenceSchema.getName(),
							referencedPrimaryKey
						);
						theState.unregisterReferencedEntityObject(referenceSchema.getName(), referencedPrimaryKey, ProxyType.REFERENCE);
						theState.unregisterReferencedEntityObject(referenceSchema.getName(), referencedPrimaryKey, ProxyType.REFERENCED_ENTITY);
						//noinspection unchecked,rawtypes
						return EvitaDataTypes.toTargetType(referencedPrimaryKey, (Class) returnType);
					} else {
						throw new EvitaInvalidUsageException(
							"Cannot remove reference `" + referenceSchema.getName() +
								"` from entity `" + theState.getEntitySchema().getName() + "` " +
								"because there is more than single reference!"
						);
					}
				};
			} else {
				return removeReferenceAndReturnItsProxy(referenceSchema, returnType, entityRecognizedInReturnType);
			}
		}
	}

	/**
	 * Method implementation that removes the single reference if exists and returns the removed reference proxy.
	 *
	 * @param referenceSchema              the reference schema to use
	 * @param returnType                   the return type
	 * @param entityRecognizedInReturnType true if the entity annotation is recognized in the return type
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeReferenceAndReturnItsProxy(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> returnType,
		boolean entityRecognizedInReturnType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.entityBuilder();
			final String referenceName = referenceSchema.getName();
			final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
			if (references.isEmpty()) {
				// do nothing
				return null;
			} else if (references.size() == 1) {
				final int referencedPrimaryKey = references.iterator().next().getReferencedPrimaryKey();
				final Optional<ReferenceContract> reference = entityBuilder.getReference(referenceName, referencedPrimaryKey);
				if (reference.isPresent()) {
					entityBuilder.removeReference(referenceName, referencedPrimaryKey);
					theState.unregisterReferencedEntityObject(referenceSchema.getName(), referencedPrimaryKey, ProxyType.REFERENCE);
					theState.unregisterReferencedEntityObject(referenceSchema.getName(), referencedPrimaryKey, ProxyType.REFERENCED_ENTITY);
					if (entityRecognizedInReturnType) {
						if (reference.get().getReferencedEntity().isPresent()) {
							final SealedEntity referencedEntity = reference.get().getReferencedEntity().get();
							return theState.getOrCreateReferencedEntityProxy(
								returnType, referencedEntity, ProxyType.REFERENCED_ENTITY
							);
						} else {
							throw ContextMissingException.referencedEntityContextMissing(theState.getType(), referenceName);
						}
					} else {
						return theState.getOrCreateEntityReferenceProxy(returnType, reference.get());
					}
				} else {
					return null;
				}
			} else {
				throw new EvitaInvalidUsageException(
					"Cannot remove reference `" + referenceSchema.getName() +
						"` from entity `" + theState.getEntitySchema().getName() + "` " +
						"because there is more than single reference!"
				);
			}
		};
	}

	/**
	 * Method implementation that removes the multiple references if they exist and returns all the removed reference
	 * proxies.
	 *
	 * @param referenceSchema              the reference schema to use
	 * @param returnType                   the return type
	 * @param entityRecognizedInReturnType true if the entity annotation is recognized in the return type
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeReferencesAndReturnTheirProxies(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> returnType,
		boolean entityRecognizedInReturnType
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.entityBuilder();
			final String referenceName = referenceSchema.getName();
			final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
			if (references.isEmpty()) {
				// do nothing
				return Collections.emptyList();
			} else {
				final List<Object> removedReferences = new ArrayList<>(references.size());
				for (ReferenceContract referenceToRemove : references) {
					if (entityRecognizedInReturnType) {
						if (referenceToRemove.getReferencedEntity().isPresent()) {
							final SealedEntity referencedEntity = referenceToRemove.getReferencedEntity().get();
							removedReferences.add(
								theState.getOrCreateReferencedEntityProxy(
									returnType, referencedEntity, ProxyType.REFERENCED_ENTITY
								)
							);
						} else {
							throw ContextMissingException.referencedEntityContextMissing(theState.getType(), referenceName);
						}
					} else {
						removedReferences.add(
							theState.getOrCreateEntityReferenceProxy(returnType, referenceToRemove)
						);
					}
					final int referencedPrimaryKey = referenceToRemove.getReferencedPrimaryKey();
					entityBuilder.removeReference(referenceName, referencedPrimaryKey);
					theState.unregisterReferencedEntityObject(referenceName, referencedPrimaryKey, ProxyType.REFERENCE);
					theState.unregisterReferencedEntityObject(referenceName, referencedPrimaryKey, ProxyType.REFERENCED_ENTITY);
				}
				return removedReferences;
			}
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
		final String expectedEntityType = getReferencedType(referenceSchema, false);
		final Cardinality cardinality = referenceSchema.getCardinality();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityClassifier(referenceName, expectedEntityType, cardinality, args, theState);
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityClassifierWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = getReferencedType(referenceSchema, false);
		final Cardinality cardinality = referenceSchema.getCardinality();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityClassifier(referenceName, expectedEntityType, cardinality, args, theState);
			return proxy;
		};
	}

	/**
	 * Sets the referenced entity classifier by extracting it from the EntityClassifier object.
	 *
	 * @param referenceName      the name of the reference
	 * @param expectedEntityType the expected type of the referenced entity
	 * @param cardinality        the cardinality of the reference
	 * @param args               the arguments passed to the method
	 * @param theState           the state of the sealed entity proxy
	 */
	private static void setReferencedEntityClassifier(
		@Nonnull String referenceName,
		@Nonnull String expectedEntityType,
		@Nonnull Cardinality cardinality,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final EntityClassifier referencedClassifier = (EntityClassifier) args[0];
		if (referencedClassifier == null && cardinality.getMax() == 1) {
			final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
			Assert.isPremiseValid(references.size() < 2, "Cardinality is `" + cardinality + "` but there are more than one reference!");
			references.forEach(it -> {
				entityBuilder.removeReference(referenceName, it.getReferencedPrimaryKey());
				theState.unregisterReferencedEntityObject(referenceName, it.getReferencedPrimaryKey(), ProxyType.REFERENCE);
				theState.unregisterReferencedEntityObject(referenceName, it.getReferencedPrimaryKey(), ProxyType.REFERENCED_ENTITY);
			});
		} else if (referencedClassifier != null) {
			Assert.isTrue(
				expectedEntityType.equals(referencedClassifier.getType()),
				"Entity type `" + referencedClassifier.getType() + "` in passed argument " +
					"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
			);

			if (cardinality.getMax() == 1) {
				final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
				Assert.isPremiseValid(references.size() < 2, "Cardinality is `" + cardinality + "` but there are more than one reference!");
				if (!references.isEmpty()) {
					final ReferenceContract singleReference = references.iterator().next();
					if (singleReference.getReferencedPrimaryKey() == referencedClassifier.getPrimaryKeyOrThrowException()) {
						// do nothing the reference is already set
						return;
					} else {
						// remove existing reference and registered object
						entityBuilder.removeReference(referenceName, singleReference.getReferencedPrimaryKey());
						theState.unregisterReferencedEntityObject(referenceName, singleReference.getReferencedPrimaryKey(), ProxyType.REFERENCE);
						theState.unregisterReferencedEntityObject(referenceName, singleReference.getReferencedPrimaryKey(), ProxyType.REFERENCED_ENTITY);
					}
				}
			}

			entityBuilder.setReference(referenceName, referencedClassifier.getPrimaryKeyOrThrowException());
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityClassifierAsArrayWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = getReferencedType(referenceSchema, false);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityClassifierAsArray(referenceName, expectedEntityType, args, theState);
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
		final String expectedEntityType = getReferencedType(referenceSchema, false);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityClassifierAsArray(referenceName, expectedEntityType, args, theState);
			return proxy;
		};
	}

	/**
	 * Sets the referenced entity classifier as an array of {@link EntityClassifier}.
	 *
	 * @param referenceName      the name of the reference
	 * @param expectedEntityType the expected entity type
	 * @param args               the array of referenced entity classifiers
	 * @param theState           the sealed entity proxy state
	 */
	private static void setReferencedEntityClassifierAsArray(
		@Nonnull String referenceName,
		@Nonnull String expectedEntityType,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final Object[] referencedClassifierArray = (Object[]) args[0];
		for (Object referencedClassifierObject : referencedClassifierArray) {
			final EntityClassifier referencedClassifier = Objects.requireNonNull((EntityClassifier) referencedClassifierObject);
			Assert.isTrue(
				expectedEntityType.equals(referencedClassifier.getType()),
				"Entity type `" + referencedClassifier.getType() + "` in passed argument " +
					"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
			);
			entityBuilder.setReference(referenceName, Objects.requireNonNull(referencedClassifier.getPrimaryKey()));
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityClassifierAsCollectionWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = getReferencedType(referenceSchema, false);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityClassifierAsCollection(referenceName, expectedEntityType, args, theState);
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
		final String expectedEntityType = getReferencedType(referenceSchema, false);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityClassifierAsCollection(referenceName, expectedEntityType, args, theState);
			return proxy;
		};
	}

	/**
	 * Sets the referenced entity classifier as a collection by extracting it from {@link EntityClassifier}.
	 *
	 * @param referenceName      the name of the reference
	 * @param expectedEntityType the expected entity type of the referenced classifier
	 * @param args               the arguments passed to the method
	 * @param theState           the state of the sealed entity proxy
	 */
	private static void setReferencedEntityClassifierAsCollection(
		@Nonnull String referenceName,
		@Nonnull String expectedEntityType,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		// noinspection unchecked
		final Collection<EntityClassifier> referencedClassifierArray = (Collection<EntityClassifier>) args[0];
		for (EntityClassifier referencedClassifier : referencedClassifierArray) {
			Assert.isTrue(
				expectedEntityType.equals(referencedClassifier.getType()),
				"Entity type `" + referencedClassifier.getType() + "` in passed argument " +
					"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
			);
			entityBuilder.setReference(referenceName, Objects.requireNonNull(referencedClassifier.getPrimaryKey()));
		}
	}

	/**
	 * Returns method implementation that sets the referenced entity by primary key and return no result.
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityPrimaryKeyWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final Cardinality cardinality = referenceSchema.getCardinality();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityPrimaryKey(referenceName, cardinality, args, theState);
			return null;
		};
	}

	/**
	 * Returns method implementation that sets the referenced entity primary key and return the reference to the proxy
	 * to allow chaining (builder pattern).
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityPrimaryKeyWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final Cardinality cardinality = referenceSchema.getCardinality();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityPrimaryKey(referenceName, cardinality, args, theState);
			return proxy;
		};
	}

	/**
	 * Sets the referenced entity primary key.
	 *
	 * @param referenceName the name of the reference
	 * @param cardinality   the cardinality of the reference
	 * @param args          the arguments passed to the method
	 * @param theState      the state of the sealed entity proxy
	 */
	private static void setReferencedEntityPrimaryKey(
		@Nonnull String referenceName,
		@Nonnull Cardinality cardinality,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final Serializable referencedClassifier = (Serializable) args[0];
		if (referencedClassifier == null && cardinality.getMax() == 1) {
			final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
			Assert.isPremiseValid(references.size() < 2, "Cardinality is `" + cardinality + "` but there are more than one reference!");
			references.forEach(it -> {
				entityBuilder.removeReference(referenceName, it.getReferencedPrimaryKey());
				theState.unregisterReferencedEntityObject(referenceName, it.getReferencedPrimaryKey(), ProxyType.REFERENCE);
				theState.unregisterReferencedEntityObject(referenceName, it.getReferencedPrimaryKey(), ProxyType.REFERENCED_ENTITY);
			});
		} else {
			final int newReferencedPrimaryKey = Objects.requireNonNull(EvitaDataTypes.toTargetType(referencedClassifier, int.class));
			if (cardinality.getMax() == 1) {
				final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
				Assert.isPremiseValid(references.size() < 2, "Cardinality is `" + cardinality + "` but there are more than one reference!");
				if (!references.isEmpty()) {
					final ReferenceContract singleReference = references.iterator().next();
					if (singleReference.getReferencedPrimaryKey() == newReferencedPrimaryKey) {
						// do nothing the reference is already set
						return;
					} else {
						// remove existing reference and registered object
						entityBuilder.removeReference(referenceName, singleReference.getReferencedPrimaryKey());
						theState.unregisterReferencedEntityObject(referenceName, singleReference.getReferencedPrimaryKey(), ProxyType.REFERENCE);
						theState.unregisterReferencedEntityObject(referenceName, singleReference.getReferencedPrimaryKey(), ProxyType.REFERENCED_ENTITY);
					}
				}
			}

			entityBuilder.setReference(
				referenceName,
				newReferencedPrimaryKey
			);
		}
	}

	/**
	 * Returns method implementation that sets the referenced entity primary key and return no result.
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityPrimaryKeyAsArrayWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityPrimaryKeyAsArray(referenceName, args, theState);
			return proxy;
		};
	}

	/**
	 * Returns method implementation that sets the referenced entity primary key and return the reference to the proxy
	 * to allow chaining (builder pattern).
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityPrimaryKeyAsArrayWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityPrimaryKeyAsArray(referenceName, args, theState);
			return proxy;
		};
	}

	/**
	 * Sets the referenced entity primary key as an array.
	 *
	 * @param referenceName the name of the reference
	 * @param args          an array of objects containing the primary key values
	 * @param theState      the state of the sealed entity proxy
	 */
	private static void setReferencedEntityPrimaryKeyAsArray(
		@Nonnull String referenceName,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final int length = Array.getLength(args[0]);
		for (int i = 0; i < length; i++) {
			final Serializable primaryKey = (Serializable) Array.get(args[0], i);
			entityBuilder.setReference(
				referenceName, Objects.requireNonNull(EvitaDataTypes.toTargetType(primaryKey, int.class))
			);
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityPrimaryKeyAsCollectionWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityPrimaryKeyAsCollection(referenceName, args, theState);
			return proxy;
		};
	}

	/**
	 * Returns method implementation that sets the referenced entity primary key and return the reference to the proxy
	 * to allow chaining (builder pattern).
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityPrimaryKeyAsCollectionWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityPrimaryKeyAsCollection(referenceName, args, theState);
			return proxy;
		};
	}

	/**
	 * Sets the referenced entity primary key as a collection.
	 *
	 * @param referenceName the name of the reference schema
	 * @param args          the arguments passed to the method
	 * @param theState      the state of the sealed entity proxy
	 */
	private static void setReferencedEntityPrimaryKeyAsCollection(
		@Nonnull String referenceName,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		// noinspection unchecked
		final Collection<Serializable> primaryKeys = (Collection<Serializable>) args[0];
		for (Serializable primaryKey : primaryKeys) {
			entityBuilder.setReference(referenceName, Objects.requireNonNull(EvitaDataTypes.toTargetType(primaryKey, int.class)));
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityWithVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = getReferencedType(referenceSchema, true);
		final Cardinality cardinality = referenceSchema.getCardinality();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntity(referenceName, expectedEntityType, cardinality, args, theState);
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = getReferencedType(referenceSchema, true);
		final Cardinality cardinality = referenceSchema.getCardinality();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntity(referenceName, expectedEntityType, cardinality, args, theState);
			return proxy;
		};
	}

	/**
	 * Sets the referenced entity by extracting it from {@link SealedEntityProxy} and returns the reference to the proxy
	 * to allow chaining (builder pattern).
	 *
	 * @param referenceName      the name of the reference
	 * @param expectedEntityType the expected entity type
	 * @param cardinality        the cardinality of the reference
	 * @param args               the arguments passed to the method
	 * @param theState           the state of the SealedEntityProxy
	 */
	private static void setReferencedEntity(
		@Nonnull String referenceName,
		@Nonnull String expectedEntityType,
		@Nonnull Cardinality cardinality,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final SealedEntityProxy referencedEntity = (SealedEntityProxy) args[0];
		if (referencedEntity == null && cardinality.getMax() == 1) {
			final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
			Assert.isPremiseValid(references.size() < 2, "Cardinality is `" + cardinality + "` but there are more than one reference!");
			references.forEach(it -> {
				entityBuilder.removeReference(referenceName, it.getReferencedPrimaryKey());
				theState.unregisterReferencedEntityObject(referenceName, it.getReferencedPrimaryKey(), ProxyType.REFERENCE);
				theState.unregisterReferencedEntityObject(referenceName, it.getReferencedPrimaryKey(), ProxyType.REFERENCED_ENTITY);
			});
		} else {
			final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
			if (cardinality.getMax() == 1) {
				Assert.isPremiseValid(references.size() < 2, "Cardinality is `" + cardinality + "` but there are more than one reference!");
				if (!references.isEmpty()) {
					final ReferenceContract singleReference = references.iterator().next();
					if (singleReference.getReferencedPrimaryKey() == referencedEntity.getPrimaryKeyOrThrowException()) {
						// just exchange registered object - the set entity and existing reference share same primary key
						theState.registerReferencedEntityObject(expectedEntityType, singleReference.getReferencedPrimaryKey(), referencedEntity, ProxyType.REFERENCE);
						return;
					} else {
						// remove existing reference and registered object
						entityBuilder.removeReference(referenceName, singleReference.getReferencedPrimaryKey());
						theState.unregisterReferencedEntityObject(expectedEntityType, singleReference.getReferencedPrimaryKey(), ProxyType.REFERENCE);
						theState.unregisterReferencedEntityObject(expectedEntityType, singleReference.getReferencedPrimaryKey(), ProxyType.REFERENCED_ENTITY);
					}
				}
			}

			if (referencedEntity != null) {
				final EntityContract sealedEntity = referencedEntity.entity();
				Assert.isTrue(
					expectedEntityType.equals(sealedEntity.getType()),
					"Entity type `" + sealedEntity.getType() + "` in passed argument " +
						"doesn't match the referencedEntity entity type: `" + expectedEntityType + "`!"
				);
				final int primaryKey = sealedEntity.getPrimaryKeyOrThrowException();
				entityBuilder.setReference(referenceName, primaryKey);
				theState.registerReferencedEntityObject(
					expectedEntityType, primaryKey, referencedEntity, ProxyType.REFERENCED_ENTITY);
			}
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityAsArrayWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = getReferencedType(referenceSchema, true);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityAsArray(referenceName, expectedEntityType, args, theState);
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
		final String expectedEntityType = getReferencedType(referenceSchema, true);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityAsArray(referenceName, expectedEntityType, args, theState);
			return proxy;
		};
	}

	/**
	 * Sets the referenced entity as an array by extracting it from the SealedEntityProxy.
	 *
	 * @param referenceName      the name of the reference
	 * @param expectedEntityType the expected type of the referenced entity
	 * @param args               the arguments passed to the method
	 * @param theState           the SealedEntityProxy state
	 */
	private static void setReferencedEntityAsArray(
		@Nonnull String referenceName,
		@Nonnull String expectedEntityType,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final Object[] referencedArray = (Object[]) args[0];
		for (Object referencedEntity : referencedArray) {
			final EntityContract sealedEntity = ((SealedEntityProxy) referencedEntity).entity();
			Assert.isTrue(
				expectedEntityType.equals(sealedEntity.getType()),
				"Entity type `" + sealedEntity.getType() + "` in passed argument " +
					"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
			);
			entityBuilder.setReference(referenceName, sealedEntity.getPrimaryKeyOrThrowException());
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferencedEntityAsCollectionWithBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		final String expectedEntityType = getReferencedType(referenceSchema, true);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityAsCollection(referenceName, expectedEntityType, args, theState);
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
		final String expectedEntityType = getReferencedType(referenceSchema, true);
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			setReferencedEntityAsCollection(referenceName, expectedEntityType, args, theState);
			return proxy;
		};
	}

	/**
	 * Sets the referenced entity as a collection by extracting it from {@link SealedEntityProxy}.
	 *
	 * @param referenceName      the name of the reference
	 * @param expectedEntityType the expected entity type
	 * @param args               the arguments passed to the method
	 * @param theState           the state of the sealed entity proxy
	 */
	private static void setReferencedEntityAsCollection(
		@Nonnull String referenceName,
		@Nonnull String expectedEntityType,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		//noinspection unchecked
		final Collection<SealedEntityProxy> referencedArray = (Collection<SealedEntityProxy>) args[0];
		for (SealedEntityProxy referencedEntity : referencedArray) {
			final EntityContract sealedEntity = referencedEntity.entity();
			Assert.isTrue(
				expectedEntityType.equals(sealedEntity.getType()),
				"Entity type `" + sealedEntity.getType() + "` in passed argument " +
					"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
			);
			entityBuilder.setReference(referenceName, sealedEntity.getPrimaryKeyOrThrowException());
		}
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
			removeReferencedEntityId(referenceName, args, theState);
			return null;
		};
	}

	/**
	 * Returns method implementation that removes the referenced entity id and return TRUE if the reference was removed.
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeReferencedEntityIdWithBooleanResult(
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String referenceName = referenceSchema.getName();
		return (proxy, theMethod, args, theState, invokeSuper) -> removeReferencedEntityId(referenceName, args, theState);
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
			removeReferencedEntityId(referenceName, args, theState);
			return proxy;
		};
	}

	/**
	 * Removes the referenced entity id from the entity builder stored in the state.
	 *
	 * @param referenceName the name of the reference
	 * @param args          the arguments passed to the method
	 * @param theState      the state of the entity proxy
	 */
	private static boolean removeReferencedEntityId(
		@Nonnull String referenceName,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final Serializable referencedPrimaryKey = (Serializable) args[0];
		final int referenceId = Objects.requireNonNull(EvitaDataTypes.toTargetType(referencedPrimaryKey, int.class));
		final Optional<ReferenceContract> reference = entityBuilder.getReference(referenceName, referenceId);
		if (reference.isPresent()) {
			entityBuilder.removeReference(
				referenceName,
				referenceId
			);
			theState.unregisterReferencedEntityObject(referenceName, referenceId, ProxyType.REFERENCE);
			theState.unregisterReferencedEntityObject(referenceName, referenceId, ProxyType.REFERENCED_ENTITY);
		}
		return reference.isPresent();
	}

	/**
	 * Returns method implementation that removes the referenced entity id and return the reference to the removed
	 * reference proxy.
	 *
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> removeReferencedEntityIdWithReferenceResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		boolean entityRecognizedInReturnType
	) {
		final String referenceName = referenceSchema.getName();
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.entityBuilder();
			final int referencedPrimaryKey = Objects.requireNonNull(EvitaDataTypes.toTargetType((Serializable) args[0], int.class));
			final Optional<ReferenceContract> reference = entityBuilder.getReference(referenceName, referencedPrimaryKey);
			if (reference.isPresent()) {
				entityBuilder.removeReference(referenceName, referencedPrimaryKey);
				theState.unregisterReferencedEntityObject(referenceName, referencedPrimaryKey, ProxyType.REFERENCE);
				theState.unregisterReferencedEntityObject(referenceName, referencedPrimaryKey, ProxyType.REFERENCED_ENTITY);
				if (entityRecognizedInReturnType) {
					if (reference.get().getReferencedEntity().isPresent()) {
						final SealedEntity referencedEntity = reference.get().getReferencedEntity().get();
						return theState.getOrCreateReferencedEntityProxy(
							expectedType, referencedEntity, ProxyType.REFERENCED_ENTITY
						);
					} else {
						throw ContextMissingException.referencedEntityContextMissing(theState.getType(), referenceName);
					}
				} else {
					return theState.getOrCreateEntityReferenceProxy(expectedType, reference.get());
				}
			} else {
				return null;
			}
		};
	}

	/**
	 * Returns method implementation that creates or updates reference by passing integer id and a consumer lambda, that
	 * could immediately set attributes on the reference.
	 *
	 * @param proxyState        the proxy state
	 * @param method            the method
	 * @param returnType        the return type
	 * @param referenceSchema   the reference schema
	 * @param referencedIdIndex the index of the referenced id parameter
	 * @param consumerIndex     the index of the consumer parameter
	 * @param expectedType      the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferenceByIdAndReferenceConsumer(
		@Nonnull SealedEntityProxyState proxyState,
		@Nonnull Method method,
		@Nonnull Class<?> returnType,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int referencedIdIndex,
		int consumerIndex,
		@Nonnull Class<?> expectedType
	) {
		if (method.isAnnotationPresent(CreateWhenMissing.class) ||
			Arrays.stream(method.getParameterAnnotations()[consumerIndex]).anyMatch(CreateWhenMissing.class::isInstance)) {
			if (returnType.equals(proxyState.getProxyClass())) {
				return getOrCreateReferenceWithIdAndEntityBuilderResult(
					referenceSchema, expectedType,
					referencedIdIndex, consumerIndex
				);
			} else {
				return getOrCreateReferenceWithIdAndVoidResult(
					referenceSchema, expectedType,
					referencedIdIndex, consumerIndex
				);
			}
		} else {
			if (returnType.equals(proxyState.getProxyClass())) {
				return getAndUpdateReferenceWithIdAndEntityBuilderResult(
					referenceSchema, expectedType,
					referencedIdIndex, consumerIndex
				);
			} else {
				return getAndUpdateReferenceWithIdAndVoidResult(
					referenceSchema, expectedType,
					referencedIdIndex, consumerIndex
				);
			}
		}
	}

	/**
	 * Returns method implementation that creates or updates reference by passing integer id and a consumer lambda, that
	 * could immediately modify referenced entity. This method creates the reference without possibility to set
	 * attributes on the reference and works directly with the referenced entity.
	 *
	 * @param proxyState        the proxy state
	 * @param returnType        the return type
	 * @param referenceSchema   the reference schema
	 * @param referencedIdIndex the index of the referenced id parameter
	 * @param consumerIndex     the index of the consumer parameter
	 * @param expectedType      the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferenceByIdAndEntityConsumer(
		@Nonnull SealedEntityProxyState proxyState,
		@Nonnull Class<?> returnType,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int referencedIdIndex,
		int consumerIndex,
		@Nonnull Class<?> expectedType
	) {
		if (returnType.equals(proxyState.getProxyClass())) {
			return createReferencedEntityWithIdAndEntityBuilderResult(
				referenceSchema, expectedType,
				referencedIdIndex, consumerIndex
			);
		} else {
			return createReferencedEntityWithIdAndVoidResult(
				referenceSchema, expectedType,
				referencedIdIndex, consumerIndex
			);
		}
	}

	/**
	 * Return a method implementation that creates new proxy object representing a reference to and external entity
	 * and returns the reference to the created proxy allowing to set reference properties on it.
	 *
	 * @param referenceSchema the reference schema to use
	 * @param expectedType    the expected type of the referenced entity proxy
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOrRemoveReferenceByEntityReturnType(
		@Nonnull Method method,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType
	) {
		if (method.isAnnotationPresent(RemoveWhenExists.class)) {
			return removeReferencedEntityIdWithReferenceResult(
				referenceSchema, expectedType, true
			);
		} else {
			return (proxy, theMethod, args, theState, invokeSuper) -> {
				final EntityBuilder entityBuilder = theState.entityBuilder();
				final int referencedId = Objects.requireNonNull(EvitaDataTypes.toTargetType((Serializable) args[0], int.class));
				entityBuilder.setReference(referenceSchema.getName(), referencedId);
				return theState.createEntityReferenceProxy(
					theState.getEntitySchema(),
					referenceSchema,
					expectedType,
					ProxyType.REFERENCE,
					referencedId
				);
			};
		}
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferenceByEntityConsumer(
		@Nonnull Method method,
		@Nonnull SealedEntityProxyState proxyState,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> returnType,
		@Nonnull Class<?> expectedType
	) {
		final String referencedEntityType = getReferencedType(referenceSchema, true);
		return proxyState.getEntitySchema(referencedEntityType)
			.map(referencedEntitySchema -> {
				if (method.isAnnotationPresent(CreateWhenMissing.class) ||
					Arrays.stream(method.getParameterAnnotations()[0]).anyMatch(CreateWhenMissing.class::isInstance)) {
					if (returnType.equals(proxyState.getProxyClass())) {
						return createReferencedEntityWithEntityBuilderResult(
							referencedEntitySchema,
							referenceSchema,
							expectedType
						);
					} else if (void.class.equals(returnType)) {
						return createReferencedEntityWithVoidResult(
							referencedEntitySchema,
							referenceSchema,
							expectedType
						);
					} else {
						return null;
					}
				} else {
					if (returnType.equals(proxyState.getProxyClass())) {
						return updateReferencedEntityWithEntityBuilderResult(
							referenceSchema,
							expectedType
						);
					} else if (void.class.equals(returnType)) {
						return updateReferencedEntityWithVoidResult(
							referenceSchema,
							expectedType
						);
					} else {
						return null;
					}
				}
			})
			.orElse(null);
	}

	/**
	 * Returns method implementation that creates or updates reference by passing directly the entities fetched from
	 * other sources. This implementation doesn't allow to set attributes on the reference.
	 *
	 * @param proxyState         the proxy state
	 * @param entityRecognizedIn the entity recognized in
	 * @param returnType         the return type
	 * @param referenceSchema    the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferenceByEntity(
		@Nonnull SealedEntityProxyState proxyState,
		@Nonnull RecognizedContext entityRecognizedIn,
		@Nonnull Class<?> returnType,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final ResolvedParameter referencedParameter = entityRecognizedIn.entityContract();
		if (referencedParameter.mainType().isArray()) {
			if (returnType.equals(proxyState.getProxyClass())) {
				return setReferencedEntityAsArrayWithBuilderResult(referenceSchema);
			} else {
				return setReferencedEntityAsArrayWithVoidResult(referenceSchema);
			}
		} else if (Collection.class.isAssignableFrom(referencedParameter.mainType())) {
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
	}

	/**
	 * Returns method implementation that creates or updates reference by passing {@link EntityClassifier} instances
	 * to the method parameter. This implementation doesn't allow to set attributes on the reference.
	 *
	 * @param proxyState         the proxy state
	 * @param entityRecognizedIn the entity recognized in
	 * @param returnType         the return type
	 * @param referenceSchema    the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferenceByEntityClassifier(
		@Nonnull SealedEntityProxyState proxyState,
		@Nonnull RecognizedContext entityRecognizedIn,
		@Nonnull Class<?> returnType,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final ResolvedParameter referencedParameter = entityRecognizedIn.entityContract();
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
	}

	/**
	 * Returns method implementation that creates or updates reference by passing primary keys
	 * to the method parameter. This implementation doesn't allow to set attributes on the reference.
	 *
	 * @param proxyState          the proxy state
	 * @param referencedParameter the entity recognized in
	 * @param returnType          the return type
	 * @param referenceSchema     the reference schema
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferenceById(
		@Nonnull SealedEntityProxyState proxyState,
		@Nonnull ResolvedParameter referencedParameter,
		@Nonnull Class<?> returnType,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		if (referencedParameter.mainType().isArray()) {
			if (returnType.equals(proxyState.getProxyClass())) {
				return setReferencedEntityPrimaryKeyAsArrayWithBuilderResult(referenceSchema);
			} else {
				return setReferencedEntityPrimaryKeyAsArrayWithVoidResult(referenceSchema);
			}
		} else if (Collection.class.isAssignableFrom(referencedParameter.mainType)) {
			if (returnType.equals(proxyState.getProxyClass())) {
				return setReferencedEntityPrimaryKeyAsCollectionWithBuilderResult(referenceSchema);
			} else {
				return setReferencedEntityPrimaryKeyAsCollectionWithVoidResult(referenceSchema);
			}
		} else {
			if (returnType.equals(proxyState.getProxyClass())) {
				return setReferencedEntityPrimaryKeyWithBuilderResult(referenceSchema);
			} else {
				return setReferencedEntityPrimaryKeyWithVoidResult(referenceSchema);
			}
		}
	}

	/**
	 * Returns method implementation that creates, updates or removes reference by passing referenced entity ids.
	 * This implementation doesn't allow to set attributes on the reference.
	 *
	 * @param proxyState      the proxy state
	 * @param method          the method
	 * @param returnType      the return type
	 * @param referenceSchema the reference schema
	 * @return the method implementation
	 */
	@Nullable
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setOrRemoveReferenceById(
		@Nonnull SealedEntityProxyState proxyState,
		@Nonnull Method method,
		@Nonnull Class<?> returnType,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		if (method.isAnnotationPresent(RemoveWhenExists.class)) {
			if (returnType.equals(proxyState.getProxyClass())) {
				return removeReferencedEntityIdWithBuilderResult(referenceSchema);
			} else if (void.class.equals(returnType)) {
				return removeReferencedEntityIdWithVoidResult(referenceSchema);
			} else if (boolean.class.equals(returnType)) {
				return removeReferencedEntityIdWithBooleanResult(referenceSchema);
			} else {
				return removeReferencedEntityIdWithReferenceResult(
					referenceSchema, returnType, false
				);
			}
		} else {
			if (returnType.equals(proxyState.getProxyClass())) {
				return setReferencedEntityPrimaryKeyWithBuilderResult(referenceSchema);
			} else if (void.class.equals(returnType)) {
				return setReferencedEntityPrimaryKeyWithVoidResult(referenceSchema);
			} else if (method.isAnnotationPresent(CreateWhenMissing.class)) {
				return getOrCreateByIdWithReferenceResult(referenceSchema, returnType);
			} else {
				return null;
			}
		}
	}

	/**
	 * Returns parsed arguments for a method that creates, updates or removes a reference by passing referenced entity ids.
	 *
	 * @param method           the method to be invoked
	 * @param proxyState       the proxy state of the entity
	 * @param reflectionLookup the reflection lookup utility
	 * @param referenceSchema  the reference schema for the entity
	 * @param firstParameter   the first parameter of the method
	 * @return the parsed arguments
	 */
	@Nonnull
	private static ParsedArguments getParsedArguments(
		@Nonnull Method method,
		@Nonnull SealedEntityProxyState proxyState,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable ResolvedParameter firstParameter
	) {

		OptionalInt referencedIdIndex = OptionalInt.empty();
		OptionalInt consumerIndex = OptionalInt.empty();
		Optional<ResolvedParameter> referencedType = empty();
		for (int i = 0; i < method.getParameterCount(); i++) {
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
		return new ParsedArguments(referencedIdIndex, consumerIndex, referencedType, returnType, entityRecognizedIn);
	}

	/**
	 * Resolves the first parameter of a method.
	 *
	 * @param method     the method to be examined
	 * @param proxyClass the proxy class
	 * @return the resolved first parameter, or null if there are no parameters
	 */
	@Nullable
	public static ResolvedParameter resolveFirstParameter(@Nonnull Method method, @Nonnull Class<?> proxyClass) {
		final ResolvedParameter firstParameter;
		if (method.getParameterCount() > 0) {
			if (Collection.class.isAssignableFrom(method.getParameterTypes()[0])) {
				final List<GenericBundle> genericType = GenericsUtils.getGenericType(proxyClass, method.getGenericParameterTypes()[0]);
				firstParameter = new ResolvedParameter(method.getParameterTypes()[0], genericType.get(0).getResolvedType());
			} else if (method.getParameterTypes()[0].isArray()) {
				firstParameter = new ResolvedParameter(method.getParameterTypes()[0], method.getParameterTypes()[0].getComponentType());
			} else {
				firstParameter = new ResolvedParameter(method.getParameterTypes()[0], method.getParameterTypes()[0]);
			}
		} else {
			firstParameter = null;
		}
		return firstParameter;
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

				final ResolvedParameter firstParameter = resolveFirstParameter(method, proxyState.getProxyClass());
				final ParsedArguments result = getParsedArguments(method, proxyState, reflectionLookup, referenceSchema, firstParameter);

				final boolean noDirectlyReferencedEntityRecognized = result.entityRecognizedIn().isEmpty();
				final Class<?> expectedType = result.referencedType().map(ResolvedParameter::resolvedType).orElse(null);

				if (parameterCount == 0) {
					if (method.isAnnotationPresent(RemoveWhenExists.class)) {
						return removeReference(
							proxyState, referenceSchema,
							result.entityRecognizedIn()
								.map(RecognizedContext::entityContract)
								.orElseGet(() -> {
									final Class<?> methodReturnType = GenericsUtils.getMethodReturnType(proxyState.getProxyClass(), method);
									return new ResolvedParameter(method.getReturnType(), methodReturnType);
								}),
							isEntityRecognizedIn(result.entityRecognizedIn(), EntityRecognizedIn.RETURN_TYPE)
						);
					} else if (isEntityRecognizedIn(result.entityRecognizedIn(), EntityRecognizedIn.RETURN_TYPE)) {
						//noinspection rawtypes
						return getOrCreateWithReferencedEntityResult(
							referenceSchema,
							result.entityRecognizedIn()
								.map(it -> (Class)it.entityContract().resolvedType())
								.orElse(result.returnType())
						);
					} else {
						return null;
					}
				} else if (parameterCount == 1) {
					if (result.referencedIdIndex().isPresent() && noDirectlyReferencedEntityRecognized) {
						return setOrRemoveReferenceById(proxyState, method, result.returnType(), referenceSchema);
					} else if (resolvedTypeIs(result.referencedType(), EntityClassifier.class) && noDirectlyReferencedEntityRecognized) {
						return setReferenceByEntityClassifier(proxyState, result.entityRecognizedIn().orElseThrow(), result.returnType(), referenceSchema);
					} else if (resolvedTypeIsNumber(result.referencedType()) && noDirectlyReferencedEntityRecognized) {
						return setReferenceById(proxyState, result.referencedType().orElseThrow(), result.returnType(), referenceSchema);
					} else if (isEntityRecognizedIn(result.entityRecognizedIn(), EntityRecognizedIn.PARAMETER)) {
						return setReferenceByEntity(proxyState, result.entityRecognizedIn().orElseThrow(), result.returnType(), referenceSchema);
					} else if (isEntityRecognizedIn(result.entityRecognizedIn(), EntityRecognizedIn.CONSUMER)) {
						return setReferenceByEntityConsumer(method, proxyState, referenceSchema, result.returnType(), Objects.requireNonNull(expectedType));
					} else if (isEntityRecognizedIn(result.entityRecognizedIn(), EntityRecognizedIn.RETURN_TYPE) && result.referencedIdIndex().isPresent()) {
						return setOrRemoveReferenceByEntityReturnType(method, referenceSchema, result.entityRecognizedIn().get().entityContract().resolvedType());
					}
				} else if (parameterCount == 2 && result.consumerIndex().isPresent() && result.referencedIdIndex().isPresent()) {
					if (isEntityRecognizedIn(result.entityRecognizedIn(), EntityRecognizedIn.CONSUMER)) {
						return setReferenceByIdAndEntityConsumer(
							proxyState, result.returnType(), referenceSchema,
							result.referencedIdIndex().getAsInt(),
							result.consumerIndex().getAsInt(),
							Objects.requireNonNull(expectedType)
						);
					} else if (noDirectlyReferencedEntityRecognized) {
						return setReferenceByIdAndReferenceConsumer(
							proxyState, method, result.returnType(), referenceSchema,
							result.referencedIdIndex().getAsInt(),
							result.consumerIndex().getAsInt(),
							Objects.requireNonNull(expectedType)
						);
					}
				}

				return null;
			}
		);
	}

	/**
	 * Represents identification of the place which has been recognized as reference representation.
	 */
	public enum EntityRecognizedIn {
		PARAMETER,
		CONSUMER,
		RETURN_TYPE
	}

	/**
	 * ParsedArguments is a record class that represents the parsed arguments for a method call.
	 * It contains optional indexes for the referencedId and consumer, optional resolved type for the referencedType,
	 * a Class object for the returnType, and an optional RecognizedContext object for the entityRecognizedIn.
	 *
	 * @param referencedIdIndex  the index of the referenced id parameter
	 * @param consumerIndex      the index of the consumer parameter
	 * @param referencedType     the resolved type of the referenced entity
	 * @param returnType         the return type
	 * @param entityRecognizedIn the context in which the entity is recognized
	 */
	private record ParsedArguments(
		@Nonnull OptionalInt referencedIdIndex,
		@Nonnull OptionalInt consumerIndex,
		@Nonnull Optional<ResolvedParameter> referencedType,
		@Nonnull Class<?> returnType,
		@Nonnull Optional<RecognizedContext> entityRecognizedIn
	) {
	}

	/**
	 * Record containing information about the resolved generic parameter type along with the original class.
	 *
	 * @param mainType     common parameter type (generic wrapper)
	 * @param resolvedType resolved generic parameter type
	 */
	public record ResolvedParameter(
		@Nonnull Class<?> mainType,
		@Nonnull Class<?> resolvedType
	) {
	}

	/**
	 * Record containing information about the context recognized from the method signature.
	 *
	 * @param recognizedIn   the place where the entity type has been recognized
	 * @param entityContract the resolved entity contract
	 * @param entityType     the resolved entity type
	 * @param isGroup        whether the entity is a reference group or referenced entity itself
	 */
	public record RecognizedContext(
		@Nonnull EntityRecognizedIn recognizedIn,
		@Nonnull ResolvedParameter entityContract,
		@Nonnull String entityType,
		boolean isGroup
	) {

	}

}
