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

import io.evitadb.api.exception.AmbiguousReferenceException;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.exception.ReferenceAllowsDuplicatesException;
import io.evitadb.api.exception.ReferenceAllowsDuplicatesException.Operation;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.proxy.impl.ReferencedObjectType;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.proxy.impl.SealedEntityProxyState.ProxyInput;
import io.evitadb.api.proxy.impl.SealedEntityReferenceProxyState;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.api.requestResponse.data.annotation.ResetWhenExists;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.InternalEntityBuilder;
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
import one.edee.oss.proxycian.trait.ProxyStateAccessor;
import one.edee.oss.proxycian.utils.GenericsUtils;
import one.edee.oss.proxycian.utils.GenericsUtils.GenericBundle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
		final Optional<Entity> parameterEntityInstance = parameterType == null ? empty() : ofNullable(
			reflectionLookup.getClassAnnotation(parameterType.resolvedType(), Entity.class));
		final Optional<EntityRef> parameterEntityRefInstance = parameterType == null ? empty() : ofNullable(
			reflectionLookup.getClassAnnotation(parameterType.resolvedType(), EntityRef.class));
		final Optional<Entity> consumerTypeEntityInstance = consumerType == null ? empty() : ofNullable(
			reflectionLookup.getClassAnnotation(consumerType, Entity.class));
		final Optional<EntityRef> consumerTypeEntityRefInstance = consumerType == null ? empty() : ofNullable(
			reflectionLookup.getClassAnnotation(consumerType, EntityRef.class));
		final Optional<Entity> returnTypeEntityInstance = returnType == null ? empty() : ofNullable(
			reflectionLookup.getClassAnnotation(returnType, Entity.class));
		final Optional<EntityRef> returnTypeEntityRefInstance = returnType == null ? empty() : ofNullable(
			reflectionLookup.getClassAnnotation(returnType, EntityRef.class));

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
					(consumerType != null || parameterType != null ? "neither the parameter type `" + ofNullable(
						(Class) consumerType).orElse(parameterType.resolvedType()).getName() + "` nor " : "") +
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
					(consumerType != null || parameterType != null ? "neither the parameter type `" + ofNullable(
						(Class) consumerType).orElse(parameterType.resolvedType()).getName() + "` nor " : "") +
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
		return referencedType.map(ResolvedParameter::resolvedType).map(NumberUtils::isIntConvertibleNumber).orElse(
			false);
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
				final List<GenericBundle> genericType = GenericsUtils.getGenericType(
					proxyClass, method.getGenericParameterTypes()[0]);
				firstParameter = new ResolvedParameter(
					method.getParameterTypes()[0], genericType.get(0).getResolvedType());
			} else if (method.getParameterTypes()[0].isArray()) {
				firstParameter = new ResolvedParameter(
					method.getParameterTypes()[0], method.getParameterTypes()[0].getComponentType());
			} else {
				firstParameter = new ResolvedParameter(method.getParameterTypes()[0], method.getParameterTypes()[0]);
			}
		} else {
			firstParameter = null;
		}
		return firstParameter;
	}

	/**
	 * Method returns the referenced entity type and verifies that it is managed by evitaDB.
	 *
	 * @param referenceSchema the reference schema
	 * @return the referenced entity type
	 */
	@Nonnull
	private static String getReferencedType(
		@Nonnull ReferenceSchemaContract referenceSchema, boolean requireManagedOnly) {
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
		int consumerLocation,
		@Nonnull ProxyInput proxyInput
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			createReferenceWithIdIfRequestedByInput(
				referenceSchema, expectedType, referenceIdLocation, consumerLocation, proxyInput, args, theState);
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
		int consumerLocation,
		@Nonnull ProxyInput proxyInput
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			createReferenceWithIdIfRequestedByInput(
				referenceSchema, expectedType, referenceIdLocation, consumerLocation, proxyInput, args, theState);
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
	private static void createReferenceWithIdIfRequestedByInput(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int consumerLocation,
		@Nonnull ProxyInput proxyInput,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final String referenceName = referenceSchema.getName();
		final int referencedId = Objects.requireNonNull(
			EvitaDataTypes.toTargetType((Serializable) args[referenceIdLocation], int.class));
		final Optional<ReferenceContract> reference = theState
			.entityBuilder()
		    .getReference(referenceName, referencedId);
		if (proxyInput == ProxyInput.READ_ONLY_REFERENCE && reference.isEmpty()) {
			// no reference found and proxy is not instructed to be created
			// execute no update
			return;
		}

		//noinspection unchecked
		final Object referenceProxy = reference
			.map(ref -> theState.getOrCreateEntityReferenceProxy(
				referenceSchema,
				(Class<Object>) expectedType,
				ref,
				proxyInput
			))
			.orElseGet(
				() -> {
					theState.entityBuilder().setReference(referenceSchema.getName(), referencedId);
					//noinspection unchecked
					return theState.getOrCreateEntityReferenceProxy(
						referenceSchema,
						(Class<Object>) expectedType,
						new ReferenceKey(referenceName, referencedId),
						proxyInput
					);
				}
			);
		//noinspection unchecked
		final Consumer<Object> consumer = (Consumer<Object>) args[consumerLocation];
		consumer.accept(referenceProxy);
		propagateReferenceMutationsToMainEntity(
			theState, (SealedEntityReferenceProxy) referenceProxy, false
		);
	}

	/**
	 * Propagates mutations from a reference proxy to the main entity within the provided state.
	 *
	 * @param theState       the state of the sealed entity proxy which contains the entity builder
	 * @param referenceProxy the sealed entity reference proxy containing the reference builder to be added or replaced
	 */
	private static void propagateReferenceMutationsToMainEntity(
		@Nonnull SealedEntityProxyState theState,
		@Nonnull SealedEntityReferenceProxy referenceProxy,
		boolean methodAllowsDuplicates
	) {
		theState.entityBuilder().addOrReplaceReferenceMutations(
			referenceProxy.getReferenceBuilder(),
			methodAllowsDuplicates
		);
	}

	/**
	 * Creates or retrieves a CurriedMethodContextInvocationHandler that processes a reference
	 * schema with a predicate, reference ID, and entity builder result. This method is used
	 * to facilitate handling method calls in a dynamic proxy context while ensuring references
	 * adhere to the given schema and constraints.
	 *
	 * @param referenceSchema     the schema of the reference being processed, defining its structure
	 *                            and constraints
	 * @param expectedType        the expected type of the processed references
	 * @param referenceIdLocation the index in the method argument array where the reference ID is
	 *                            located
	 * @param predicateLocation   the index in the method argument array where the predicate is located
	 * @param consumerLocation    the index in the method argument array where the consumer is located
	 * @param constantPredicate   an optional constant predicate used to validate references, or null
	 *                            if no constant predicate is needed
	 * @return a configured CurriedMethodContextInvocationHandler for dynamically handling method
	 * calls related to the reference schema
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getOrCreateReferenceWithPredicateAndIdAndEntityBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int predicateLocation,
		@Nullable Class<?> predicateType,
		int consumerLocation,
		@Nullable ConstantPredicate constantPredicate,
		@Nonnull ProxyInput proxyInput
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			getOrCreateReferenceWithPredicateAndId(
				referenceSchema, expectedType,
				referenceIdLocation, predicateLocation, predicateType, consumerLocation,
				proxyInput, args, theState, constantPredicate
			);
			return proxy;
		};
	}

	/**
	 * Creates or retrieves a reference with the specified predicate, ID location, and consumer location,
	 * and produces a void result. This method ensures that the reference is handled properly within the
	 * context, ensuring proper interaction with the proxy and state.
	 *
	 * @param referenceSchema     the schema definition for the reference being processed
	 * @param expectedType        the expected class type of the reference
	 * @param referenceIdLocation the position in the arguments array where the reference ID is located
	 * @param predicateLocation   the position in the arguments array where the predicate is located
	 * @param consumerLocation    the position in the arguments array where the consumer is located
	 * @param constantPredicate   an optional constant predicate used for additional reference filtering
	 * @return a curried method context invocation handler configured to process the reference appropriately
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getOrCreateReferenceWithPredicateAndIdAndVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int predicateLocation,
		@Nullable Class<?> predicateType,
		int consumerLocation,
		@Nullable ConstantPredicate constantPredicate,
		@Nonnull ProxyInput proxyInput
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			getOrCreateReferenceWithPredicateAndId(
				referenceSchema, expectedType,
				referenceIdLocation, predicateLocation, predicateType, consumerLocation,
				proxyInput, args, theState, constantPredicate
			);
			return proxy;
		};
	}

	/**
	 * Retrieves or creates a reference entity based on the specified predicate and identifier.
	 * If a matching reference based on the predicate exists, it processes it using the provided consumer.
	 * Otherwise, it creates a new reference and processes it using the consumer.
	 *
	 * @param referenceSchema the schema contract that defines details about the reference
	 * @param expectedType    the class type that is expected for the reference entities
	 */
	@SuppressWarnings("unchecked")
	private static void getOrCreateReferenceWithPredicateAndId(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> expectedType,
		int referenceIdLocation,
		int predicateLocation,
		@Nullable Class<?> predicateType,
		int consumerLocation,
		@Nonnull ProxyInput proxyInput,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState,
		@Nullable ConstantPredicate constantPredicate
	) {
		final String referenceName = referenceSchema.getName();
		final int referencedId = Objects.requireNonNull(
			EvitaDataTypes.toTargetType((Serializable) args[referenceIdLocation], int.class));
		final Predicate<Object> predicate = predicateLocation >= 0 ?
			(Predicate<Object>) Objects.requireNonNull(args[predicateLocation]) : null;
		final Predicate<Object> composedPredicate = combinePredicates(predicate, constantPredicate, args);
		final Consumer<Object> consumer = (Consumer<Object>) args[consumerLocation];
		final List<ReferenceContract> allReferences = theState
			.entityBuilder()
			.getReferences(referenceName, referencedId);

		boolean foundMatch = false;
		for (ReferenceContract reference : allReferences) {
			final Object objectToTest = predicateType == null ?
				reference :
				theState.getOrCreateEntityReferenceProxy(
					referenceSchema,
					(Class<Object>) predicateType,
					reference,
					proxyInput
				);
			if (composedPredicate.test(objectToTest)) {
				foundMatch = true;
				final Object referenceProxy = Objects.equals(predicateType, expectedType) ?
					objectToTest :
					theState.getOrCreateEntityReferenceProxy(
						referenceSchema,
						(Class<Object>) expectedType,
						reference,
						proxyInput
					);
				consumer.accept(referenceProxy);
				propagateReferenceMutationsToMainEntity(
					theState, (SealedEntityReferenceProxy) referenceProxy, true
				);
			}
		}
		if (!foundMatch) {
			final ReferenceKey referenceKey = theState.entityBuilder().createReference(
				referenceSchema.getName(), referencedId
			);
			final Object newReference = theState.getOrCreateEntityReferenceProxy(
				referenceSchema, (Class<Object>) expectedType, referenceKey, proxyInput
			);
			if (constantPredicate != null) {
				constantPredicate.onCreate(args, newReference);
			}
			consumer.accept(newReference);
			propagateReferenceMutationsToMainEntity(
				theState, (SealedEntityReferenceProxy) newReference, true
			);
		}
	}

	/**
	 * Return a method implementation that creates new proxy object representing a referenced external entity
	 * and returns the reference to the entity proxy to allow chaining (builder pattern).
	 *
	 * @param referenceSchema   the reference schema to use
	 * @param predicateLocation the location of the reference id in the method arguments
	 * @param consumerLocation  the location of the consumer in the method arguments
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> updateReferencedEntityWithPredicateAndEntityBuilderResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		int predicateLocation,
		@Nullable Class<?> predicateType,
		int consumerLocation,
		@Nullable Class<?> expectedType,
		@Nullable ConstantPredicate constantPredicate,
		@Nonnull ProxyInput proxyInput
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			updateReferencedEntityWithPredicate(
				referenceSchema, predicateLocation,
				predicateType, consumerLocation, expectedType,
				constantPredicate, proxyInput, args, theState
			);
			return proxy;
		};
	}

	/**
	 * Return a method implementation that creates new proxy object representing a referenced external entity
	 * and returns no result.
	 *
	 * @param referenceSchema   the reference schema to use
	 * @param predicateLocation the location of the reference id in the method arguments
	 * @param consumerLocation  the location of the consumer in the method arguments
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> updateReferencedEntityWithPredicateAndVoidResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		int predicateLocation,
		@Nullable Class<?> predicateType,
		int consumerLocation,
		@Nullable Class<?> expectedType,
		@Nullable ConstantPredicate constantPredicate,
		@Nonnull ProxyInput proxyInput
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			updateReferencedEntityWithPredicate(
				referenceSchema, predicateLocation,
				predicateType, consumerLocation, expectedType,
				constantPredicate, proxyInput, args, theState
			);
			return null;
		};
	}

	/**
	 * Combines a given constant bi-predicate and a predicate into a single predicate.
	 * The combined predicate is constructed based on the provided inputs.
	 *
	 * @param predicate         an additional predicate to be combined with the constant bi-predicate; can be null
	 * @param constantPredicate a bi-predicate that performs a comparison between a constant argument and an input value; can be null
	 * @param args              an array of arguments, one of which is used by the constant bi-predicate
	 * @return a combined predicate that applies the logic of the constant bi-predicate and the input predicate
	 */
	@Nonnull
	private static Predicate<Object> combinePredicates(
		@Nullable Predicate<Object> predicate,
		@Nullable ConstantPredicate constantPredicate,
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

	/**
	 * Create a new proxy object representing a referenced external entity, using the provided reference schema,
	 * expected type, reference ID location, consumer location, method arguments, and proxy state.
	 *
	 * @param referenceSchema   The reference schema to use
	 * @param predicateLocation The location of the reference ID in the method arguments
	 * @param predicateType     The type of the predicate to be used for filtering references
	 * @param consumerLocation  The location of the consumer in the method arguments
	 * @param args              The method arguments
	 * @param theState          The proxy state
	 */
	@SuppressWarnings({"unchecked"})
	private static void updateReferencedEntityWithPredicate(
		@Nonnull ReferenceSchemaContract referenceSchema,
		int predicateLocation,
		@Nullable Class<?> predicateType,
		int consumerLocation,
		@Nullable Class<?> expectedType,
		@Nullable ConstantPredicate constantPredicate,
		@Nonnull ProxyInput proxyInput,
		@Nonnull Object[] args,
		@Nonnull SealedEntityProxyState theState
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final Predicate<Object> predicate = predicateLocation >= 0 ? (Predicate<Object>) Objects.requireNonNull(
			args[predicateLocation]) : null;
		final Consumer<Object> consumer = (Consumer<Object>) args[consumerLocation];

		updateReferencedEntityWithPredicate(
			referenceSchema, theState, entityBuilder,
			combinePredicates(predicate, constantPredicate, args),
			predicateType,
			consumer,
			expectedType,
			proxyInput
		);
	}

	/**
	 * Updates referenced entities that match a given predicate and applies a consumer to those entities.
	 *
	 * @param referenceSchema the schema contract for the reference.
	 * @param theState        the sealed state of the entity proxy.
	 * @param entityBuilder   the builder for creating or modifying entities.
	 * @param predicate       the predicate used to test each referenced entity.
	 * @param consumer        the consumer to apply to each referenced entity that matches the predicate.
	 * @return true if any referenced entities matched the predicate and were processed, otherwise false.
	 */
	private static boolean updateReferencedEntityWithPredicate(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull SealedEntityProxyState theState,
		@Nonnull EntityBuilder entityBuilder,
		@Nonnull Predicate<Object> predicate,
		@Nullable Class<?> predicateType,
		@Nonnull Consumer<Object> consumer,
		@Nullable Class<?> expectedType,
		@Nonnull ProxyInput proxyInput
	) {
		final Collection<ReferenceContract> existingReferences = entityBuilder.getReferences(referenceSchema.getName());
		boolean foundMatch = false;
		for (ReferenceContract existingReference : existingReferences) {
			final Object objectToTest;
			if (predicateType == null) {
				objectToTest = existingReference;
			} else {
				objectToTest = theState.getOrCreateEntityReferenceProxy(
					referenceSchema,
					predicateType,
					existingReference,
					proxyInput
				);
			}
			if (predicate.test(objectToTest)) {
				foundMatch = true;
				final Object objectForConsumer = Objects.equals(predicateType, expectedType) ?
					objectToTest :
					expectedType == null ?
						existingReference :
						theState.getOrCreateEntityReferenceProxy(
							referenceSchema,
							expectedType,
							existingReference,
							proxyInput
						);
				consumer.accept(objectForConsumer);
				propagateReferenceMutationsToMainEntity(
					theState, (SealedEntityReferenceProxy) objectForConsumer, true
				);
			}
		}
		return foundMatch;
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
		@Nonnull Class<?> expectedType,
		int consumerIndex,
		int referencedIdIndex
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			createReferencedEntity(
				referencedEntitySchema,
				referenceSchema,
				expectedType,
				args,
				theState,
				consumerIndex,
				referencedIdIndex
			);
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
		@Nonnull Class<?> expectedType,
		int consumerIndex,
		int referencedIdIndex
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			createReferencedEntity(
				referencedEntitySchema,
				referenceSchema,
				expectedType,
				args,
				theState,
				consumerIndex,
				referencedIdIndex
			);
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
		@Nonnull SealedEntityProxyState theState,
		int consumerIndex,
		int referencedIdIndex
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final Object referenceProxy = theState.getOrCreateReferencedEntityProxyWithCallback(
			referenceSchema.getName(),
			referencedIdIndex >= 0 ?
				Objects.requireNonNull(EvitaDataTypes.toTargetType((Serializable) args[referencedIdIndex], int.class)) :
				null,
			referencedEntitySchema,
			expectedType,
			ReferencedObjectType.TARGET,
			entityReference -> entityBuilder.setReference(referenceSchema.getName(), entityReference.getPrimaryKey())
		);
		//noinspection unchecked
		final Consumer<Object> consumer = (Consumer<Object>) args[consumerIndex];
		consumer.accept(referenceProxy);
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
		@Nonnull Class<?> expectedType,
		int consumerIndex,
		int referencedIdIndex
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			updateReferencedEntity(referenceSchema, expectedType, args, theState, consumerIndex, referencedIdIndex);
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
		@Nonnull Class<?> expectedType,
		int consumerIndex,
		int referencedIdIndex
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			updateReferencedEntity(referenceSchema, expectedType, args, theState, consumerIndex, referencedIdIndex);
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
		@Nonnull SealedEntityProxyState theState,
		int consumerIndex,
		int referencedIdIndex
	) {
		final EntityBuilder entityBuilder = theState.entityBuilder();
		final String referenceName = referenceSchema.getName();
		final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
		if (references.isEmpty()) {
			if (!entityBuilder.referencesAvailable(referenceName)) {
				throw ContextMissingException.referenceContextMissing(referenceName);
			}
		} else {
			final Cardinality schemaCardinality = referenceSchema.getCardinality();
			final Optional<SealedEntity> referencedEntity;
			if (referencedIdIndex < 0) {
				if (schemaCardinality.getMax() <= 1) {
					referencedEntity = references.iterator()
						.next()
						.getReferencedEntity();
				} else {
					throw new AmbiguousReferenceException(referenceName, schemaCardinality);
				}
			} else {
				if (schemaCardinality.allowsDuplicates()) {
					throw new ReferenceAllowsDuplicatesException(referenceName, theState.getEntitySchema(), Operation.WRITE);
				} else {
					final int requestedPrimaryKey = Objects.requireNonNull(
						EvitaDataTypes.toTargetType((Serializable) args[referencedIdIndex], int.class)
					);
					referencedEntity = references.stream()
						.filter(it -> it.getReferencedPrimaryKey() == requestedPrimaryKey)
						.findFirst()
						.flatMap(ReferenceContract::getReferencedEntity);
				}
			}
			final Object referenceProxy = theState.getOrCreateReferencedEntityProxy(
				referenceName,
				expectedType,
				referencedEntity
					.orElseThrow(
						() -> ContextMissingException.referencedEntityContextMissing(
							entityBuilder.getType(),
							referenceName
						)
					),
				ReferencedObjectType.TARGET
			);
			//noinspection unchecked
			final Consumer<Object> consumer = (Consumer<Object>) args[consumerIndex];
			consumer.accept(referenceProxy);
			if (referenceProxy instanceof SealedEntityReferenceProxy sealedEntityReferenceProxy) {
				propagateReferenceMutationsToMainEntity(
					theState, sealedEntityReferenceProxy, false
				);
			}
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
		@Nonnull Class expectedType,
		@Nonnull ProxyInput proxyInput
	) {
		final String referenceName = referenceSchema.getName();
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final int referencedId = Objects.requireNonNull(
				EvitaDataTypes.toTargetType((Serializable) args[0], int.class));
			final Optional<ReferenceContract> reference = theState
				.entityBuilder()
			    .getReference(referenceName, referencedId);
			if (reference.isEmpty()) {
				final ReferenceKey referenceKey = theState.entityBuilder().createReference(
					referenceSchema.getName(), referencedId
				);
				return theState.getOrCreateEntityReferenceProxy(
					referenceSchema, expectedType, referenceKey, proxyInput
				);
			} else {
				return theState.getOrCreateEntityReferenceProxy(
					referenceSchema,
					expectedType,
					reference.get(),
					proxyInput
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> getOrCreateWithReferencedEntityResult(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class expectedType
	) {
		final Cardinality schemaCardinality = referenceSchema.getCardinality();
		final String referenceName = referenceSchema.getName();
		final String referencedEntityType = getReferencedType(referenceSchema, true);
		if (schemaCardinality.getMax() > 1) {
			throw new AmbiguousReferenceException(referenceName, schemaCardinality);
		}
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.entityBuilder();
			final Collection<ReferenceContract> reference = entityBuilder
				.getReferences(referenceName);
			if (reference.isEmpty()) {
				return theState.getOrCreateReferencedEntityProxyWithCallback(
					referenceName,
					null,
					theState.getEntitySchemaOrThrow(referencedEntityType), expectedType,
					ReferencedObjectType.TARGET,
					entityReference -> entityBuilder.setReference(referenceName, entityReference.getPrimaryKeyOrThrowException())
				);
			} else {
				final ReferenceContract firstReference = reference.iterator().next();
				final Optional<?> referencedInstance = theState.getReferencedEntityObjectIfPresent(
					referenceName, firstReference.getReferencedPrimaryKey(), expectedType, ReferencedObjectType.TARGET
				);
				if (referencedInstance.isPresent()) {
					return referencedInstance.get();
				} else {
					Assert.isTrue(
						firstReference.getReferencedEntity().isPresent(),
						() -> ContextMissingException.referencedEntityContextMissing(theState.getType(), referenceName)
					);
					return firstReference
						.getReferencedEntity()
						.map(
							it -> theState.getOrCreateReferencedEntityProxy(
								referenceName, expectedType, it, ReferencedObjectType.TARGET
							)
						)
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
		boolean entityRecognizedInReturnType,
		int predicateIndex,
		@Nullable Class<?> predicateType,
		@Nullable ConstantPredicate constantPredicate
	) {
		final Class<?> returnType = resolvedParameter.resolvedType();
		final String referenceName = referenceSchema.getName();
		if (Collection.class.equals(resolvedParameter.mainType()) || List.class.equals(resolvedParameter.mainType())) {
			if (Number.class.isAssignableFrom(returnType)) {
				return (proxy, theMethod, args, theState, invokeSuper) -> {
					final EntityBuilder entityBuilder = theState.entityBuilder();
					final Collection<ReferenceContract> references = entityBuilder.getReferences(
						referenceName);
					//noinspection unchecked
					final Predicate<Object> predicate = predicateIndex >= 0 ?
						(Predicate<Object>) Objects.requireNonNull(args[predicateIndex]) :
						null;
					final Predicate<Object> composedPredicate = combinePredicates(predicate, constantPredicate, args);
					final List<Integer> removedKeys = new ArrayList<>(8);
					references
						.stream()
						.filter(ref -> applyPredicateOnReadOnlyProxy(theState, referenceSchema, predicateType, ref, composedPredicate))
						.forEach(it -> {
							final ReferenceKey referenceKey = it.getReferenceKey();
							entityBuilder.removeReference(referenceKey);
							theState.unregisterReferenceObject(referenceKey);
							theState.unregisterReferencedEntityObject(referenceName, referenceKey.primaryKey(), ReferencedObjectType.TARGET);
							removedKeys.add(referenceKey.primaryKey());
						});
					return removedKeys;
				};
			} else {
				return removeReferencesAndReturnTheirProxies(
					referenceSchema,
					returnType,
					entityRecognizedInReturnType,
					predicateIndex,
					predicateType,
					constantPredicate
				);
			}
		} else if (returnType.equals(void.class)) {
			return (proxy, theMethod, args, theState, invokeSuper) -> {
				final EntityBuilder entityBuilder = theState.entityBuilder();
				final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
				//noinspection unchecked
				final Predicate<Object> predicate = predicateIndex >= 0 ? (Predicate<Object>) Objects.requireNonNull(
					args[predicateIndex]) : null;
				final Predicate<Object> composedPredicate = combinePredicates(predicate, constantPredicate, args);
				references
					.stream()
					.filter(ref -> applyPredicateOnReadOnlyProxy(theState, referenceSchema, predicateType, ref, composedPredicate))
					.forEach(it -> {
						final ReferenceKey referenceKey = it.getReferenceKey();
						entityBuilder.removeReference(referenceKey);
						theState.unregisterReferenceObject(referenceKey);
						theState.unregisterReferencedEntityObject(referenceName, referenceKey.primaryKey(), ReferencedObjectType.TARGET);
					});
				return null;
			};
		} else if (Boolean.class.isAssignableFrom(returnType)) {
			return (proxy, theMethod, args, theState, invokeSuper) -> {
				final EntityBuilder entityBuilder = theState.entityBuilder();
				final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
				//noinspection unchecked
				final Predicate<Object> predicate = predicateIndex >= 0 ? (Predicate<Object>) Objects.requireNonNull(
					args[predicateIndex]) : null;
				final Predicate<Object> composedPredicate = combinePredicates(predicate, constantPredicate, args);
				final AtomicBoolean removedAny = new AtomicBoolean(false);
				references
					.stream()
					.filter(ref -> applyPredicateOnReadOnlyProxy(theState, referenceSchema, predicateType, ref, composedPredicate))
					.forEach(it -> {
						final ReferenceKey referenceKey = it.getReferenceKey();
						entityBuilder.removeReference(referenceKey);
						theState.unregisterReferenceObject(referenceKey);
						theState.unregisterReferencedEntityObject(referenceName, referenceKey.primaryKey(), ReferencedObjectType.TARGET);
						removedAny.set(true);
					});
				return removedAny.get();
			};
		} else {
			if (returnType.equals(proxyState.getProxyClass())) {
				return (proxy, theMethod, args, theState, invokeSuper) -> {
					final EntityBuilder entityBuilder = theState.entityBuilder();
					final Collection<ReferenceContract> references = entityBuilder.getReferences(
						referenceName);
					if (references.isEmpty()) {
						// do nothing
					} else {
						//noinspection unchecked
						final Predicate<Object> predicate = predicateIndex >= 0 ?
							(Predicate<Object>) Objects.requireNonNull(args[predicateIndex]) :
							null;
						final Predicate<Object> composedPredicate = combinePredicates(
							predicate, constantPredicate, args);
						for (ReferenceContract reference : references) {
							if (applyPredicateOnReadOnlyProxy(theState, referenceSchema, predicateType, reference, composedPredicate)) {
								final ReferenceKey referenceKey = reference.getReferenceKey();
								entityBuilder.removeReference(referenceKey);
								theState.unregisterReferenceObject(referenceKey);
								theState.unregisterReferencedEntityObject(referenceName, referenceKey.primaryKey(), ReferencedObjectType.TARGET);
							}
						}
					}
					return proxy;
				};
			} else if (Number.class.isAssignableFrom(returnType) || (returnType.isPrimitive() && (int.class.equals(returnType) || long.class.equals(returnType)))) {
				return (proxy, theMethod, args, theState, invokeSuper) -> {
					final EntityBuilder entityBuilder = theState.entityBuilder();
					final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
					if (references.isEmpty()) {
						// do nothing
						return 0;
					} else {
						//noinspection unchecked
						final Predicate<Object> predicate = predicateIndex >= 0 ?
							(Predicate<Object>) Objects.requireNonNull(args[predicateIndex]) : null;
						final Predicate<Object> composedPredicate = combinePredicates(predicate, constantPredicate, args);
						int counter = 0;
						for (ReferenceContract reference : references) {
							if (applyPredicateOnReadOnlyProxy(theState, referenceSchema, predicateType, reference, composedPredicate)) {
								counter++;
								final ReferenceKey referenceKey = reference.getReferenceKey();
								entityBuilder.removeReference(referenceKey);
								theState.unregisterReferenceObject(referenceKey);
								theState.unregisterReferencedEntityObject(referenceName, referenceKey.primaryKey(), ReferencedObjectType.TARGET);
							}
						}
						//noinspection unchecked
						return EvitaDataTypes.toTargetType(counter, (Class<Serializable>) returnType);
					}
				};
			} else {
				return removeReferenceAndReturnItsProxy(
					referenceSchema,
					returnType,
					entityRecognizedInReturnType,
					predicateIndex,
					predicateType,
					constantPredicate
				);
			}
		}
	}

	/**
	 * Applies a given predicate to a reference or an entity reference proxy based on the predicate type.
	 *
	 * @param theState          the current proxy state of the sealed entity, used to create an entity reference proxy if needed
	 * @param referenceSchema   the schema of the reference to be validated
	 * @param predicateType     the class type of the predicate; if null, the reference is directly tested by the predicate
	 * @param ref               the reference to be tested by the provided predicate
	 * @param composedPredicate the predicate to be applied, which defines the testing logic
	 * @return true if the predicate evaluates to true for the provided reference or entity reference proxy, otherwise false
	 */
	private static boolean applyPredicateOnReadOnlyProxy(
		@Nonnull SealedEntityProxyState theState,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Class<?> predicateType,
		@Nonnull ReferenceContract ref,
		@Nonnull Predicate<Object> composedPredicate
	) {
		return composedPredicate.test(
			predicateType == null ?
				ref :
				theState.getOrCreateEntityReferenceProxy(
					referenceSchema,
					predicateType,
					ref,
					ProxyInput.READ_ONLY_REFERENCE
				)
		);
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
		boolean entityRecognizedInReturnType,
		int predicateIndex,
		@Nullable Class<?> predicateType,
		@Nullable ConstantPredicate constantPredicate
	) {
		return (proxy, theMethod, args, theState, invokeSuper) -> {
			final EntityBuilder entityBuilder = theState.entityBuilder();
			final String referenceName = referenceSchema.getName();
			final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
			if (references.isEmpty()) {
				// do nothing
				return null;
			} else {
				//noinspection unchecked
				final Predicate<Object> predicate = predicateIndex >= 0 ? (Predicate<Object>) Objects.requireNonNull(args[predicateIndex]) : null;
				final Predicate<Object> composedPredicate = combinePredicates(predicate, constantPredicate, args);
				final List<ReferenceContract> referencesToRemove = references
					.stream()
					.filter(
						ref -> applyPredicateOnReadOnlyProxy(
							theState, referenceSchema, predicateType, ref, composedPredicate
						)
					)
					.toList();
				if (referencesToRemove.size() > 1) {
					throw new EvitaInvalidUsageException(
						"Cannot remove reference `" + referenceName +
							"` from entity `" + theState.getEntitySchema().getName() + "` " +
							"because there is more than single reference!"
					);
				} else if (referencesToRemove.isEmpty()) {
					// do nothing
					return null;
				} else {
					final ReferenceContract reference = referencesToRemove.get(0);
					final ReferenceKey referenceKey = reference.getReferenceKey();
					entityBuilder.removeReference(referenceKey);
					theState.unregisterReferenceObject(referenceKey);
					theState.unregisterReferencedEntityObject(referenceName, referenceKey.primaryKey(), ReferencedObjectType.TARGET);
					if (entityRecognizedInReturnType) {
						if (reference.getReferencedEntity().isPresent()) {
							final SealedEntity referencedEntity = reference.getReferencedEntity().get();
							return theState.getOrCreateReferencedEntityProxy(referenceName, returnType, referencedEntity, ReferencedObjectType.TARGET);
						} else {
							throw ContextMissingException.referencedEntityContextMissing(
								theState.getType(), referenceName);
						}
					} else {
						return theState.getOrCreateEntityReferenceProxy(
							referenceSchema,
							returnType,
							reference,
							ProxyInput.EXISTING_REFERENCE_BUILDER
						);
					}
				}
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
		boolean entityRecognizedInReturnType,
		int predicateIndex,
		@Nullable Class<?> predicateType,
		@Nullable ConstantPredicate constantPredicate
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
				//noinspection unchecked
				final Predicate<Object> predicate = predicateIndex >= 0 ? (Predicate<Object>) Objects.requireNonNull(args[predicateIndex]) : null;
				final Predicate<Object> composedPredicate = combinePredicates(predicate, constantPredicate, args);
				for (ReferenceContract reference : references) {
					if (applyPredicateOnReadOnlyProxy(theState, referenceSchema, predicateType, reference, composedPredicate)) {
						if (entityRecognizedInReturnType) {
							if (reference.getReferencedEntity().isPresent()) {
								final SealedEntity referencedEntity = reference.getReferencedEntity().get();
								removedReferences.add(
									theState.getOrCreateReferencedEntityProxy(referenceName, returnType, referencedEntity, ReferencedObjectType.TARGET)
								);
							} else {
								throw ContextMissingException.referencedEntityContextMissing(
									theState.getType(), referenceName);
							}
						} else {
							removedReferences.add(
								theState.getOrCreateEntityReferenceProxy(
									referenceSchema,
									returnType,
									reference,
									ProxyInput.READ_ONLY_REFERENCE
								)
							);
						}
						final ReferenceKey referenceKey = reference.getReferenceKey();
						entityBuilder.removeReference(referenceKey);
						theState.unregisterReferenceObject(referenceKey);
						theState.unregisterReferencedEntityObject(referenceName, referenceKey.primaryKey(), ReferencedObjectType.TARGET);
					}
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
			Assert.isPremiseValid(
				references.size() < 2, "Cardinality is `" + cardinality + "` but there are more than one reference!");
			references.forEach(it -> {
				final ReferenceKey referenceKey = it.getReferenceKey();
				entityBuilder.removeReference(referenceKey);
				theState.unregisterReferenceObject(referenceKey);
				theState.unregisterReferencedEntityObject(referenceName, referenceKey.primaryKey(), ReferencedObjectType.TARGET);
			});
		} else if (referencedClassifier != null) {
			Assert.isTrue(
				expectedEntityType.equals(referencedClassifier.getType()),
				"Entity type `" + referencedClassifier.getType() + "` in passed argument " +
					"doesn't match the referenced entity type: `" + expectedEntityType + "`!"
			);

			if (cardinality.getMax() == 1) {
				final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
				Assert.isPremiseValid(
					references.size() < 2,
					"Cardinality is `" + cardinality + "` but there are more than one reference!"
				);
				if (!references.isEmpty()) {
					final ReferenceContract singleReference = references.iterator().next();
					if (singleReference.getReferencedPrimaryKey() == referencedClassifier.getPrimaryKeyOrThrowException()) {
						// do nothing the reference is already set
						return;
					} else {
						// remove existing reference and registered object
						final ReferenceKey referenceKey = singleReference.getReferenceKey();
						entityBuilder.removeReference(referenceKey);
						theState.unregisterReferenceObject(referenceKey);
						theState.unregisterReferencedEntityObject(referenceName, referenceKey.primaryKey(), ReferencedObjectType.TARGET);
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
			final EntityClassifier referencedClassifier = Objects.requireNonNull(
				(EntityClassifier) referencedClassifierObject);
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
			Assert.isPremiseValid(
				references.size() < 2, "Cardinality is `" + cardinality + "` but there are more than one reference!");
			references.forEach(it -> {
				final ReferenceKey referenceKey = it.getReferenceKey();
				entityBuilder.removeReference(referenceKey);
				theState.unregisterReferenceObject(referenceKey);
				theState.unregisterReferencedEntityObject(referenceName, referenceKey.primaryKey(), ReferencedObjectType.TARGET);
			});
		} else {
			final int newReferencedPrimaryKey = Objects.requireNonNull(
				EvitaDataTypes.toTargetType(referencedClassifier, int.class));
			if (cardinality.getMax() == 1) {
				final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
				Assert.isPremiseValid(
					references.size() < 2,
					"Cardinality is `" + cardinality + "` but there are more than one reference!"
				);
				if (!references.isEmpty()) {
					final ReferenceContract singleReference = references.iterator().next();
					if (singleReference.getReferencedPrimaryKey() == newReferencedPrimaryKey) {
						// do nothing the reference is already set
						return;
					} else {
						// remove existing reference and registered object
						final ReferenceKey referenceKey = singleReference.getReferenceKey();
						entityBuilder.removeReference(referenceKey);
						theState.unregisterReferenceObject(referenceKey);
						theState.unregisterReferencedEntityObject(referenceName, referenceKey.primaryKey(), ReferencedObjectType.TARGET);
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
			entityBuilder.setReference(
				referenceName, Objects.requireNonNull(EvitaDataTypes.toTargetType(primaryKey, int.class)));
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
		final Collection<ReferenceContract> references = entityBuilder.getReferences(referenceName);
		if (referencedEntity == null && cardinality.getMax() == 1) {
			Assert.isPremiseValid(
				references.size() < 2, "Cardinality is `" + cardinality + "` but there are more than one reference!");
			references.forEach(it -> {
				final ReferenceKey referenceKey = it.getReferenceKey();
				entityBuilder.removeReference(referenceKey);
				theState.unregisterReferenceObject(referenceKey);
				theState.unregisterReferencedEntityObject(referenceName, referenceKey.primaryKey(), ReferencedObjectType.TARGET);
			});
		} else {
			if (cardinality.getMax() == 1) {
				Assert.isPremiseValid(
					references.size() < 2,
					"Cardinality is `" + cardinality + "` but there are more than one reference!"
				);
				if (!references.isEmpty()) {
					final ReferenceContract singleReference = references.iterator().next();
					final ReferenceKey referenceKey = singleReference.getReferenceKey();
					if (singleReference.getReferencedPrimaryKey() == referencedEntity.getPrimaryKeyOrThrowException()) {
						// just exchange registered object - the set entity and existing reference share same primary key
						theState.registerReferenceObject(referenceKey, referencedEntity);
						return;
					} else {
						// remove existing reference and registered object
						entityBuilder.removeReference(referenceKey);
						theState.unregisterReferenceObject(referenceKey);
						theState.unregisterReferencedEntityObject(referenceName, referenceKey.primaryKey(), ReferencedObjectType.TARGET);
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
				theState.registerReferencedEntityObject(referenceName, primaryKey, ReferencedObjectType.TARGET, referencedEntity);
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
		return (proxy, theMethod, args, theState, invokeSuper) -> removeReferencedEntityId(
			referenceName, args, theState);
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
			final ReferenceKey referenceKey = reference.get().getReferenceKey();
			entityBuilder.removeReference(referenceKey);
			theState.unregisterReferenceObject(referenceKey);
			theState.unregisterReferencedEntityObject(referenceName, referenceId, ReferencedObjectType.TARGET);
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
			final int referencedPrimaryKey = Objects.requireNonNull(
				EvitaDataTypes.toTargetType((Serializable) args[0], int.class));
			final Optional<ReferenceContract> reference = entityBuilder.getReference(
				referenceName, referencedPrimaryKey
			);
			if (reference.isPresent()) {
				final ReferenceKey referenceKey = reference.get().getReferenceKey();
				entityBuilder.removeReference(referenceKey);
				theState.unregisterReferenceObject(referenceKey);
				theState.unregisterReferencedEntityObject(referenceName, referencedPrimaryKey, ReferencedObjectType.TARGET);
				if (entityRecognizedInReturnType) {
					if (reference.get().getReferencedEntity().isPresent()) {
						final SealedEntity referencedEntity = reference.get().getReferencedEntity().get();
						return theState.getOrCreateReferencedEntityProxy(
							referenceName, expectedType, referencedEntity, ReferencedObjectType.TARGET
						);
					} else {
						throw ContextMissingException.referencedEntityContextMissing(theState.getType(), referenceName);
					}
				} else {
					return theState.getOrCreateEntityReferenceProxy(
						referenceSchema,
						expectedType,
						reference.get(),
						ProxyInput.READ_ONLY_REFERENCE
					);
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
		@Nonnull Class<?> returnType,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int referencedIdIndex,
		int consumerIndex,
		@Nonnull Class<?> expectedType,
		@Nonnull ProxyInput proxyInput
	) {
		if (returnType.equals(proxyState.getProxyClass())) {
			return getOrCreateReferenceWithIdAndEntityBuilderResult(
				referenceSchema, expectedType,
				referencedIdIndex, consumerIndex,
				proxyInput
			);
		} else {
			return getOrCreateReferenceWithIdAndVoidResult(
				referenceSchema, expectedType,
				referencedIdIndex, consumerIndex,
				proxyInput
			);
		}
	}

	/**
	 * Returns method implementation that creates or updates reference by passing integer id and a consumer lambda, that
	 * could immediately set attributes on the reference.
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
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferenceByPredicateAndIdAndReferenceConsumer(
		@Nonnull SealedEntityProxyState proxyState,
		@Nonnull Class<?> returnType,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int referencedIdIndex,
		int predicateIndex,
		@Nullable Class<?> predicateType,
		int consumerIndex,
		@Nonnull Class<?> expectedType,
		@Nullable ConstantPredicate constantPredicate,
		@Nonnull ProxyInput proxyInput
	) {
		if (returnType.equals(proxyState.getProxyClass())) {
			return getOrCreateReferenceWithPredicateAndIdAndEntityBuilderResult(
				referenceSchema, expectedType,
				referencedIdIndex, predicateIndex, predicateType, consumerIndex,
				constantPredicate, proxyInput
			);
		} else {
			return getOrCreateReferenceWithPredicateAndIdAndVoidResult(
				referenceSchema, expectedType,
				referencedIdIndex, predicateIndex, predicateType, consumerIndex,
				constantPredicate, proxyInput
			);
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
	 * @param predicateIndex    the index of the predicate parameter
	 * @param predicateType     the type of the predicate parameter
	 * @param consumerIndex     the index of the consumer parameter
	 * @param constantPredicate optional constant predicate to be always applied
	 * @return the method implementation
	 */
	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferenceByPredicateAndEntityConsumer(
		@Nonnull SealedEntityProxyState proxyState,
		@Nonnull Class<?> returnType,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int predicateIndex,
		@Nullable Class<?> predicateType,
		int consumerIndex,
		@Nullable Class<?> expectedType,
		@Nullable ConstantPredicate constantPredicate,
		@Nonnull ProxyInput proxyInput
	) {
		// update only variant
		if (returnType.equals(proxyState.getProxyClass())) {
			return updateReferencedEntityWithPredicateAndEntityBuilderResult(
				referenceSchema,
				predicateIndex,
				predicateType,
				consumerIndex,
				expectedType,
				constantPredicate,
				proxyInput
			);
		} else {
			return updateReferencedEntityWithPredicateAndVoidResult(
				referenceSchema,
				predicateIndex,
				predicateType,
				consumerIndex,
				expectedType,
				constantPredicate,
				proxyInput
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
		@Nonnull Class<?> expectedType,
		@Nonnull ProxyInput proxyInput
	) {
		if (method.isAnnotationPresent(RemoveWhenExists.class)) {
			return removeReferencedEntityIdWithReferenceResult(
				referenceSchema, expectedType, true
			);
		} else {
			return (proxy, theMethod, args, theState, invokeSuper) -> {
				final InternalEntityBuilder entityBuilder = theState.entityBuilder();
				final int referencedId = Objects.requireNonNull(
					EvitaDataTypes.toTargetType((Serializable) args[0], int.class));
				final ReferenceKey referenceKey = entityBuilder.createReference(
					referenceSchema.getName(), referencedId
				);
				return theState.getOrCreateEntityReferenceProxy(
					referenceSchema,
					expectedType,
					referenceKey,
					proxyInput
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
	 * @param referencedIdIndex    the index of the referenced entity id parameter
	 * @param consumerIndex   the index of the consumer parameter
	 * @return the method implementation
	 */
	@Nullable
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityProxyState> setReferenceByEntityConsumer(
		@Nonnull Method method,
		@Nonnull SealedEntityProxyState proxyState,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Class<?> returnType,
		@Nonnull Class<?> expectedType,
		int referencedIdIndex,
		int consumerIndex
	) {
		final String referencedEntityType = getReferencedType(referenceSchema, true);
		return proxyState
			.getEntitySchema(referencedEntityType)
			.map(referencedEntitySchema -> {
				if (method.isAnnotationPresent(CreateWhenMissing.class) ||
					Arrays.stream(method.getParameterAnnotations()[consumerIndex]).anyMatch(CreateWhenMissing.class::isInstance)) {
					if (returnType.equals(proxyState.getProxyClass())) {
						return createReferencedEntityWithEntityBuilderResult(
							referencedEntitySchema,
							referenceSchema,
							expectedType,
							consumerIndex,
							referencedIdIndex
						);
					} else if (void.class.equals(returnType)) {
						return createReferencedEntityWithVoidResult(
							referencedEntitySchema,
							referenceSchema,
							expectedType,
							consumerIndex,
							referencedIdIndex
						);
					} else {
						return null;
					}
				} else {
					if (returnType.equals(proxyState.getProxyClass())) {
						return updateReferencedEntityWithEntityBuilderResult(
							referenceSchema,
							expectedType,
							consumerIndex,
							referencedIdIndex
						);
					} else if (void.class.equals(returnType)) {
						return updateReferencedEntityWithVoidResult(
							referenceSchema,
							expectedType,
							consumerIndex,
							referencedIdIndex
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
		@Nullable RecognizedContext entityRecognizedIn,
		@Nonnull Class<?> returnType,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final ResolvedParameter referencedParameter = entityRecognizedIn == null ? null : entityRecognizedIn.entityContract();
		if (referencedParameter != null && referencedParameter.mainType().isArray()) {
			if (returnType.equals(proxyState.getProxyClass())) {
				return setReferencedEntityClassifierAsArrayWithBuilderResult(referenceSchema);
			} else {
				return setReferencedEntityClassifierAsArrayWithVoidResult(referenceSchema);
			}
		} else if (referencedParameter != null && Collection.class.isAssignableFrom(referencedParameter.mainType())) {
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
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ProxyInput proxyInput
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
			} else if (proxyInput != ProxyInput.READ_ONLY_REFERENCE) {
				return getOrCreateByIdWithReferenceResult(referenceSchema, returnType, proxyInput);
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
	@SuppressWarnings({"rawtypes"})
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
		OptionalInt predicateIndex = OptionalInt.empty();
		Optional<ResolvedParameter> predicate = empty();
		Optional<ConstantPredicate> constantPredicate = empty();
		ProxyInput proxyInput;
		if (method.getAnnotation(CreateWhenMissing.class) != null) {
			proxyInput = ProxyInput.EXISTING_REFERENCE_BUILDER;
		} else if (method.getAnnotation(ResetWhenExists.class) != null) {
			proxyInput = ProxyInput.INITIAL_REFERENCE_BUILDER;
		} else {
			proxyInput = ProxyInput.READ_ONLY_REFERENCE;
		}
		for (int i = 0; i < method.getParameterCount(); i++) {
			final int parameterIndex = i;
			final Class<?> parameterType = method.getParameterTypes()[i];
			final Optional<AttributeRef> attributeRef = Arrays
				.stream(method.getParameterAnnotations()[i])
				.filter(AttributeRef.class::isInstance)
				.map(AttributeRef.class::cast)
				.findFirst();
			final Parameter parameter = method.getParameters()[i];
			final String argumentName = attributeRef
				.map(AttributeRef::value)
				.orElseGet(parameter::getName);

			if (NumberUtils.isIntConvertibleNumber(parameterType)) {
				referencedIdIndex = OptionalInt.of(i);
			} else if (Consumer.class.isAssignableFrom(parameterType)) {
				consumerIndex = OptionalInt.of(i);
				final List<GenericBundle> genericType = GenericsUtils.getGenericType(
					proxyState.getProxyClass(), method.getGenericParameterTypes()[i]);
				referencedType = of(
					new ResolvedParameter(
						method.getParameterTypes()[i],
						genericType.get(0).getResolvedType()
					)
				);
				if (parameter.getAnnotation(CreateWhenMissing.class) != null) {
					Assert.isTrue(
						proxyInput == ProxyInput.READ_ONLY_REFERENCE || proxyInput == ProxyInput.EXISTING_REFERENCE_BUILDER,
						"Cannot use both CreateWhenMissing and ResetWhenExists annotations on the same method!"
					);
					proxyInput = ProxyInput.EXISTING_REFERENCE_BUILDER;
				} else if (parameter.getAnnotation(ResetWhenExists.class) != null) {
					Assert.isTrue(
						proxyInput == ProxyInput.READ_ONLY_REFERENCE || proxyInput == ProxyInput.INITIAL_REFERENCE_BUILDER,
						"Cannot use both CreateWhenMissing and ResetWhenExists annotations on the same method!"
					);
					proxyInput = ProxyInput.INITIAL_REFERENCE_BUILDER;
				}
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
				final ConstantPredicate argumentPredicate;
				if (EvitaDataTypes.isSupportedType(parameterType)) {
					argumentPredicate = new ConstantPredicate(
						(args, reference) -> {
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
						},
						(args, reference) -> {
							if (reference instanceof ProxyStateAccessor psa && psa.getProxyState() instanceof SealedEntityReferenceProxyState serps) {
								serps.getReferenceBuilder().setAttribute(
									argumentName, (Serializable) args[parameterIndex]);
							} else if (reference instanceof ReferenceEditor rc) {
								//noinspection unchecked
								rc.setAttribute(argumentName, (Serializable) args[parameterIndex]);
							} else {
								throw new EvitaInvalidUsageException(
									"Unsupported argument type in automatic reference attribute predicate implementation! " +
										"Supported are only `SealedEntityReferenceProxy` and `ReferenceContract`. " +
										"Offending method: " + method
								);
							}
						}
					);
				} else if (parameterType.isEnum()) {
					argumentPredicate = new ConstantPredicate(
						(args, reference) -> {
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
						},
						(args, reference) -> {
							if (reference instanceof ProxyStateAccessor psa && psa.getProxyState() instanceof SealedEntityReferenceProxyState serps) {
								serps.getReferenceBuilder().setAttribute(
									argumentName, ((Enum<?>) args[parameterIndex]).name());
							} else if (reference instanceof ReferenceEditor rc) {
								//noinspection unchecked
								rc.setAttribute(argumentName, ((Enum<?>) args[parameterIndex]).name());
							} else {
								throw new EvitaInvalidUsageException(
									"Unsupported argument type in automatic reference attribute predicate implementation! " +
										"Supported are only `SealedEntityReferenceProxy` and `ReferenceContract`. " +
										"Offending method: " + method
								);
							}
						}
					);
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
			} else if (Collection.class.isAssignableFrom(parameterType)) {
				final List<GenericBundle> genericType = GenericsUtils.getGenericType(
					proxyState.getProxyClass(), method.getGenericParameterTypes()[i]);
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
		return new ParsedArguments(
			referencedIdIndex,
			consumerIndex,
			referencedType,
			predicateIndex,
			predicate,
			constantPredicate,
			returnType,
			entityRecognizedIn,
			proxyInput
		);
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
				final ParsedArguments parsedArguments = getParsedArguments(
					method, proxyState, reflectionLookup, referenceSchema, firstParameter);

				final boolean noDirectlyReferencedEntityRecognized = parsedArguments.entityRecognizedIn().isEmpty();
				final RecognizedContext entityRecognizedIn = parsedArguments.entityRecognizedIn().orElse(null);
				final Class<?> expectedType = parsedArguments.referencedType().map(ResolvedParameter::resolvedType).orElse(null);

				if (parameterCount == 0) {
					if (method.isAnnotationPresent(RemoveWhenExists.class)) {
						return removeReference(
							proxyState, referenceSchema,
							parsedArguments.entityRecognizedIn()
							      .map(RecognizedContext::entityContract)
							      .orElseGet(() -> {
								      final Class<?> methodReturnType = GenericsUtils.getMethodReturnType(
									      proxyState.getProxyClass(), method);
								      return new ResolvedParameter(method.getReturnType(), methodReturnType);
							      }),
							isEntityRecognizedIn(parsedArguments.entityRecognizedIn(), EntityRecognizedIn.RETURN_TYPE),
							-1,
							null,
							null
						);
					} else if (isEntityRecognizedIn(parsedArguments.entityRecognizedIn(), EntityRecognizedIn.RETURN_TYPE)) {
						//noinspection rawtypes
						return getOrCreateWithReferencedEntityResult(
							referenceSchema,
							parsedArguments.entityRecognizedIn()
							      .map(it -> (Class) it.entityContract().resolvedType())
							      .orElse(parsedArguments.returnType())
						);
					} else {
						return null;
					}
				} else {
					if (parameterCount == 1) {
						if (parsedArguments.referencedIdIndex().isPresent() && noDirectlyReferencedEntityRecognized) {
							return setOrRemoveReferenceById(
								proxyState,
								method,
								parsedArguments.returnType(),
								referenceSchema,
								parsedArguments.proxyInput()
							);
						} else if (parsedArguments.predicateIndex.isPresent() || parsedArguments.constantPredicate.isPresent()) {
							return removeReference(
								proxyState, referenceSchema,
								parsedArguments.entityRecognizedIn()
									.map(RecognizedContext::entityContract)
									.orElseGet(() -> {
										final Class<?> methodReturnType = GenericsUtils.getMethodReturnType(
											proxyState.getProxyClass(), method);
										return new ResolvedParameter(method.getReturnType(), methodReturnType);
									}),
								isEntityRecognizedIn(parsedArguments.entityRecognizedIn(), EntityRecognizedIn.RETURN_TYPE),
								parsedArguments.predicateIndex().orElse(-1),
								parsedArguments.predicateType().map(ResolvedParameter::resolvedType).orElse(null),
								parsedArguments.constantPredicate().orElse(null)
							);
						} else if (resolvedTypeIs(parsedArguments.referencedType(), EntityClassifier.class) && noDirectlyReferencedEntityRecognized) {
							return setReferenceByEntityClassifier(
								proxyState,
								entityRecognizedIn,
								parsedArguments.returnType(),
								referenceSchema
							);
						} else if (resolvedTypeIsNumber(parsedArguments.referencedType()) && noDirectlyReferencedEntityRecognized) {
							return setReferenceById(
								proxyState,
								parsedArguments.referencedType().orElseThrow(),
								parsedArguments.returnType(),
								referenceSchema
							);
						} else if (isEntityRecognizedIn(parsedArguments.entityRecognizedIn(), EntityRecognizedIn.PARAMETER)) {
							return setReferenceByEntity(
								proxyState,
								parsedArguments.entityRecognizedIn().orElseThrow(),
								parsedArguments.returnType(),
								referenceSchema
							);
						} else if (isEntityRecognizedIn(parsedArguments.entityRecognizedIn(), EntityRecognizedIn.CONSUMER)) {
							return setReferenceByEntityConsumer(
								method,
								proxyState,
								referenceSchema,
								parsedArguments.returnType(),
								Objects.requireNonNull(expectedType),
								-1,
								0
							);
						} else if (
							isEntityRecognizedIn(parsedArguments.entityRecognizedIn(), EntityRecognizedIn.RETURN_TYPE) &&
								parsedArguments.referencedIdIndex().isPresent()
						) {
							return setOrRemoveReferenceByEntityReturnType(
								method, referenceSchema,
								entityRecognizedIn
								      .entityContract()
								      .resolvedType(),
								parsedArguments.proxyInput()
							);
						}
					} else if (parameterCount == 2) {
						if (method.isAnnotationPresent(RemoveWhenExists.class)) {
							return removeReference(
								proxyState,
								referenceSchema,
								parsedArguments.entityRecognizedIn()
								      .map(RecognizedContext::entityContract)
								      .orElseGet(() -> {
									      final Class<?> methodReturnType = GenericsUtils.getMethodReturnType(
										      proxyState.getProxyClass(), method);
									      return new ResolvedParameter(method.getReturnType(), methodReturnType);
								      }),
								isEntityRecognizedIn(parsedArguments.entityRecognizedIn(), EntityRecognizedIn.RETURN_TYPE),
								parsedArguments.predicateIndex().orElse(-1),
								parsedArguments.predicateType().map(ResolvedParameter::resolvedType).orElse(null),
								parsedArguments.constantPredicate().orElse(null)
							);
						} else if (parsedArguments.consumerIndex().isPresent() && parsedArguments.referencedIdIndex().isPresent()) {
							if (isEntityRecognizedIn(parsedArguments.entityRecognizedIn(), EntityRecognizedIn.CONSUMER)) {
								return setReferenceByEntityConsumer(
									method,
									proxyState,
									referenceSchema,
									parsedArguments.returnType(),
									Objects.requireNonNull(expectedType),
									parsedArguments.referencedIdIndex().getAsInt(),
									parsedArguments.consumerIndex().getAsInt()
								);
							} else {
								return setReferenceByIdAndReferenceConsumer(
									proxyState,
									parsedArguments.returnType(),
									referenceSchema,
									parsedArguments.referencedIdIndex().getAsInt(),
									parsedArguments.consumerIndex().getAsInt(),
									Objects.requireNonNull(expectedType),
									parsedArguments.proxyInput()
								);
							}
						} else if (
							(parsedArguments.predicateIndex().isPresent() || parsedArguments.constantPredicate.isPresent()) &&
								parsedArguments.consumerIndex().isPresent()
						) {
							return setReferenceByPredicateAndEntityConsumer(
								proxyState,
								parsedArguments.returnType(), referenceSchema,
								parsedArguments.predicateIndex().orElse(-1),
								parsedArguments.predicateType().map(ResolvedParameter::resolvedType).orElse(null),
								parsedArguments.consumerIndex().getAsInt(),
								parsedArguments.referencedType().map(ResolvedParameter::resolvedType).orElse(null),
								parsedArguments.constantPredicate().orElse(null),
								parsedArguments.proxyInput()
							);
						}
					} else if (parameterCount == 3) {
						if (
							(parsedArguments.predicateIndex().isPresent() || parsedArguments.constantPredicate.isPresent()) &&
								parsedArguments.consumerIndex().isPresent() && parsedArguments.referencedIdIndex().isPresent()
						) {
							return setReferenceByPredicateAndIdAndReferenceConsumer(
								proxyState, parsedArguments.returnType(), referenceSchema,
								parsedArguments.referencedIdIndex().getAsInt(),
								parsedArguments.predicateIndex().orElse(-1),
								parsedArguments.predicateType().map(ResolvedParameter::resolvedType).orElse(null),
								parsedArguments.consumerIndex().getAsInt(),
								Objects.requireNonNull(expectedType),
								parsedArguments.constantPredicate().orElse(null),
								parsedArguments.proxyInput()
							);
						}
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
	 * a Class object for the return type, and an optional RecognizedContext object for the entityRecognizedIn.
	 *
	 * @param referencedIdIndex  the index of the referenced id parameter
	 * @param consumerIndex      the index of the consumer parameter
	 * @param referencedType     the resolved type of the referenced entity
	 * @param predicateIndex     the index of the predicate parameter
	 * @param predicateType      the resolved type of the predicate
	 * @param constantPredicate  the constant predicate if any
	 * @param returnType         the return type
	 * @param entityRecognizedIn the context in which the entity is recognized
	 * @param proxyInput 	     the proxy input setting for creating proxy object
	 */
	private record ParsedArguments(
		@Nonnull OptionalInt referencedIdIndex,
		@Nonnull OptionalInt consumerIndex,
		@Nonnull Optional<ResolvedParameter> referencedType,
		@Nonnull OptionalInt predicateIndex,
		@Nonnull Optional<ResolvedParameter> predicateType,
		@Nonnull Optional<ConstantPredicate> constantPredicate,
		@Nonnull Class<?> returnType,
		@Nonnull Optional<RecognizedContext> entityRecognizedIn,
		@Nonnull ProxyInput proxyInput
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

	/**
	 * Composite predicate with an optional side-effect for reference creation.
	 *
	 * This helper is used while classifying and executing reference-setting methods. It evaluates a
	 * predicate against the original method arguments and a tested object (for example a
	 * ReferenceEditor or an EntityBuilder). It can be chained with another ConstantPredicate and also
	 * carries a side-effect that is executed when a new object is created as a result of the method
	 * call. The evaluation short-circuits: if the first predicate fails, the rest of the chain is not
	 * evaluated.
	 */
	private static class ConstantPredicate implements BiPredicate<Object[], Object> {
		/**
		 * Head predicate evaluated first against the provided arguments and tested object.
		 */
		private final BiPredicate<Object[], Object> first;
		/**
		 * Optional chained predicate evaluated only when {@link #first} succeeds. May be {@code null}.
		 */
		private final ConstantPredicate other;
		/**
		 * Side-effect executed when a new object is created; invoked for each predicate in the chain.
		 */
		private final BiConsumer<Object[], Object> onCreate;

		/**
		 * Creates a ConstantPredicate with a single predicate and a creation side-effect.
		 *
		 * @param first predicate evaluated against the original method arguments and the tested object
		 * @param onCreate side-effect invoked when a new object is created
		 */
		public ConstantPredicate(@Nonnull BiPredicate<Object[], Object> first, @Nonnull BiConsumer<Object[], Object> onCreate) {
			this.first = first;
			this.other = null;
			this.onCreate = onCreate;
		}

		/**
		 * Creates a ConstantPredicate that chains another ConstantPredicate to be evaluated afterwards.
		 *
		 * @param first predicate evaluated against the original method arguments and the tested object
		 * @param onCreate side-effect invoked when a new object is created
		 * @param other the next predicate to be evaluated if {@code first} succeeds
		 */
		public ConstantPredicate(
			@Nonnull BiPredicate<Object[], Object> first,
			@Nonnull BiConsumer<Object[], Object> onCreate,
			@Nonnull ConstantPredicate other
		) {
			this.first = first;
			this.other = other;
			this.onCreate = onCreate;
		}

		/**
		 * Evaluates this predicate chain.
		 *
		 * First evaluates {@link #first}. If it is {@code true} and a chained predicate exists, the
		 * evaluation continues with the chained predicate; otherwise the result of {@code first} is
		 * returned.
		 *
		 * @param args original method arguments used to compute the predicate
		 * @param tested object being tested (e.g., a ReferenceEditor or EntityBuilder)
		 * @return {@code true} if all predicates in the chain pass, otherwise {@code false}
		 */
		@Override
		public boolean test(Object[] args, Object tested) {
			if (this.first.test(args, tested)) {
				if (this.other != null) {
					return this.other.test(args, tested);
				} else {
					return true;
				}
			} else {
				return false;
			}
		}

		/**
		 * Executes the creation side-effect for this predicate and any chained predicates.
		 *
		 * @param args original method arguments available during creation
		 * @param created the object that has been created
		 */
		public void onCreate(@Nonnull Object[] args, @Nonnull Object created) {
			this.onCreate.accept(args, created);
			if (this.other != null) {
				this.other.onCreate(args, created);
			}
		}

		/**
		 * Returns a new ConstantPredicate that represents logical AND with the provided predicate.
		 *
		 * The current instance remains unchanged; the returned instance evaluates {@link #first}, and if
		 * it succeeds, delegates to the provided {@code other} predicate.
		 *
		 * @param other the predicate to chain after this one
		 * @return new combined ConstantPredicate
		 */
		@Nonnull
		public ConstantPredicate and(@Nonnull ConstantPredicate other) {
			return new ConstantPredicate(this.first, this.onCreate, other);
		}
	}

}
