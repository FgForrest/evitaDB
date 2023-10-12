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

package io.evitadb.api.proxy.impl;

import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.ProxyFactory;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.proxy.impl.entity.EntityContractAdvice;
import io.evitadb.api.proxy.impl.reference.EntityReferenceContractAdvice;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKeyRef;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.function.ExceptionRethrowingFunction;
import io.evitadb.function.ExceptionRethrowingIntBiFunction;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.ReflectionLookup;
import lombok.RequiredArgsConstructor;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import one.edee.oss.proxycian.recipe.Advice;
import one.edee.oss.proxycian.recipe.ProxyRecipe;
import one.edee.oss.proxycian.trait.delegate.DelegateCallsAdvice;
import one.edee.oss.proxycian.trait.localDataStore.LocalDataStoreAdvice;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static io.evitadb.utils.ClassUtils.isAbstract;

/**
 * Implementation of the {@link ProxyFactory} interface based on Proxycian (ByteBuddy) library.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class ProxycianFactory implements ProxyFactory {
	/**
	 * Function that creates a default {@link ProxyRecipe} for an entity.
	 */
	final static Function<ProxyEntityCacheKey, ProxyRecipe> DEFAULT_ENTITY_RECIPE = cacheKey -> new ProxyRecipe(
		new Class<?>[]{cacheKey.type()},
		new Advice[]{
			new DelegateCallsAdvice<>(SealedEntityProxy.class, Function.identity(), true),
			LocalDataStoreAdvice.INSTANCE,
			EntityContractAdvice.INSTANCE
		}
	);

	/**
	 * Function that creates a default {@link ProxyRecipe} for an entity reference.
	 */
	final static Function<ProxyEntityCacheKey, ProxyRecipe> DEFAULT_ENTITY_REFERENCE_RECIPE = cacheKey -> new ProxyRecipe(
		new Class<?>[]{cacheKey.type()},
		new Advice[]{
			new DelegateCallsAdvice<>(SealedEntityReferenceProxy.class, Function.identity(), true),
			LocalDataStoreAdvice.INSTANCE,
			EntityReferenceContractAdvice.INSTANCE
		}
	);

	/**
	 * Cache for the identified best matching constructors to speed up proxy creation.
	 */
	private final static ConcurrentHashMap<ProxyEntityCacheKey, BestMatchingConstructorWithExtractionLambda<?>> CONSTRUCTOR_CACHE = CollectionUtils.createConcurrentHashMap(256);
	/**
	 * The map of recipes provided from outside that are used to build the proxy.
	 */
	private final Map<ProxyEntityCacheKey, ProxyRecipe> recipes = new ConcurrentHashMap<>(64);
	/**
	 * The merged map all recipes - the ones provided from outside and the ones created with default configuration on
	 * the fly during the proxy building.
	 */
	private final Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes = new ConcurrentHashMap<>(64);
	/**
	 * The reflection lookup instance used to access the reflection data in a memoized fashion.
	 */
	private final ReflectionLookup reflectionLookup;

	/**
	 * Creates a new proxy instance for passed {@link SealedEntity} instance.
	 */
	static <T> T createProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull SealedEntity sealedEntity,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Function<ProxyEntityCacheKey, ProxyRecipe> recipeLocator
	) {
		try {
			if (expectedType.isRecord()) {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				return bestMatchingConstructor.constructor().newInstance(
					bestMatchingConstructor.constructorArguments(sealedEntity)
				);
			} else if (expectedType.isInterface()) {
				final String entityName = sealedEntity.getSchema().getName();
				final ProxyEntityCacheKey cacheKey = new ProxyEntityCacheKey(expectedType, entityName, null);
				return ByteBuddyProxyGenerator.instantiate(
					recipeLocator.apply(cacheKey),
					new SealedEntityProxyState(sealedEntity, expectedType, recipes, collectedRecipes, reflectionLookup)
				);
			} else if (isAbstract(expectedType)) {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				final String entityName = sealedEntity.getSchema().getName();
				final ProxyEntityCacheKey cacheKey = new ProxyEntityCacheKey(expectedType, entityName, null);
				return ByteBuddyProxyGenerator.instantiate(
					recipeLocator.apply(cacheKey),
					new SealedEntityProxyState(sealedEntity, expectedType, recipes, collectedRecipes, reflectionLookup),
					bestMatchingConstructor.constructor().getParameterTypes(),
					bestMatchingConstructor.constructorArguments(sealedEntity)
				);
			} else {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				return bestMatchingConstructor.constructor().newInstance(
					bestMatchingConstructor.constructorArguments(sealedEntity)
				);
			}
		} catch (Exception e) {
			throw new EntityClassInvalidException(expectedType, e);
		}
	}

	/**
	 * Creates a new proxy instance for passed {@link SealedEntity} and {@link ReferenceContract} instance.
	 */
	static <T> T createProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull SealedEntity sealedEntity,
		@Nonnull ReferenceContract reference,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Function<ProxyEntityCacheKey, ProxyRecipe> recipeLocator
	) {
		try {
			if (expectedType.isRecord()) {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				return bestMatchingConstructor.constructor().newInstance(
					bestMatchingConstructor.constructorArguments(sealedEntity)
				);
			} else if (expectedType.isInterface()) {
				final String entityName = sealedEntity.getSchema().getName();
				final ProxyEntityCacheKey cacheKey = new ProxyEntityCacheKey(expectedType, entityName, reference.getReferenceName());
				return ByteBuddyProxyGenerator.instantiate(
					recipeLocator.apply(cacheKey),
					new SealedEntityReferenceProxyState(sealedEntity, reference, expectedType, recipes, collectedRecipes, reflectionLookup)
				);
			} else if (isAbstract(expectedType)) {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				final String entityName = sealedEntity.getSchema().getName();
				final ProxyEntityCacheKey cacheKey = new ProxyEntityCacheKey(expectedType, entityName, reference.getReferenceName());
				return ByteBuddyProxyGenerator.instantiate(
					recipeLocator.apply(cacheKey),
					new SealedEntityReferenceProxyState(sealedEntity, reference, expectedType, recipes, collectedRecipes, reflectionLookup),
					bestMatchingConstructor.constructor().getParameterTypes(),
					bestMatchingConstructor.constructorArguments(sealedEntity)
				);
			} else {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				return bestMatchingConstructor.constructor().newInstance(
					bestMatchingConstructor.constructorArguments(sealedEntity)
				);
			}
		} catch (Exception e) {
			throw new EntityClassInvalidException(expectedType, e);
		}
	}

	/**
	 * Method tries to identify the best matching constructor for passed {@link EntitySchemaContract} and {@link Class}
	 * type. It tries to find a constructor with most of the arguments matching the schema fields.
	 *
	 * TODO JNO - write some test!
	 */
	private static <T> BestMatchingConstructorWithExtractionLambda<T> findBestMatchingConstructor(
		@Nonnull Class<T> expectedType,
		@Nonnull EntitySchemaContract schema,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		final ProxyEntityCacheKey cacheKey = new ProxyEntityCacheKey(expectedType, schema.getName(), null);
		if (CONSTRUCTOR_CACHE.containsKey(cacheKey)) {
			//noinspection unchecked
			return (BestMatchingConstructorWithExtractionLambda<T>) CONSTRUCTOR_CACHE.get(cacheKey);
		} else {
			int bestConstructorScore = Integer.MIN_VALUE;
			BestMatchingConstructorWithExtractionLambda<T> bestConstructor = null;
			for (Constructor<?> declaredConstructor : expectedType.getDeclaredConstructors()) {
				int score = 0;
				final Parameter[] parameters = declaredConstructor.getParameters();
				//noinspection unchecked
				final ExceptionRethrowingFunction<SealedEntity, Object>[] argumentExtractors =
					new ExceptionRethrowingFunction[parameters.length];

				for (int i = 0; i < parameters.length; i++) {
					final String parameterName = parameters[i].getName();
					@SuppressWarnings("rawtypes") final Class parameterType = parameters[i].getType();
					if (PrimaryKeyRef.POSSIBLE_ARGUMENT_NAMES.contains(parameterName) && (Integer.class.isAssignableFrom(parameterType) || int.class.isAssignableFrom(parameterType))) {
						argumentExtractors[i] = EntityContract::getPrimaryKey;
						score++;
					} else if (EntityRef.POSSIBLE_ARGUMENT_NAMES.contains(parameterName) && String.class.isAssignableFrom(parameterType)) {
						argumentExtractors[i] = EntityClassifier::getType;
						score++;
					} else if (EvitaDataTypes.isSupportedTypeOrItsArray(parameterType) || parameterType.isEnum()) {
						final Optional<EntityAttributeSchemaContract> attribute = schema.getAttributeByName(parameterName, NamingConvention.CAMEL_CASE);
						final Optional<AssociatedDataSchemaContract> associatedData = schema.getAssociatedDataByName(parameterName, NamingConvention.CAMEL_CASE);
						if (attribute.isPresent()) {
							final String attributeName = attribute.get().getName();
							if (parameterType.isEnum()) {
								//noinspection unchecked
								argumentExtractors[i] = entity -> Enum.valueOf(
									parameterType,
									(String) entity.getAttribute(
										attributeName,
										String.class
									)
								);
							} else {
								//noinspection unchecked
								argumentExtractors[i] = entity -> entity.getAttribute(
									attributeName,
									parameterType
								);
							}
							score++;
						} else if (associatedData.isPresent()) {
							final String associatedDataName = associatedData.get().getName();
							if (parameterType.isEnum()) {
								//noinspection unchecked
								argumentExtractors[i] = entity -> Enum.valueOf(
									parameterType,
									(String) entity.getAssociatedData(
										associatedDataName,
										String.class,
										reflectionLookup
									)
								);
							} else {
								//noinspection unchecked
								argumentExtractors[i] = entity -> entity.getAssociatedData(
									associatedDataName,
									parameterType,
									reflectionLookup
								);
							}
							score++;
						} else {
							argumentExtractors[i] = entity -> null;
						}
					} else {
						final Optional<AssociatedDataSchemaContract> associatedData = schema.getAssociatedDataByName(parameterName, NamingConvention.CAMEL_CASE);
						if (associatedData.isPresent()) {
							final String associatedDataName = associatedData.get().getName();
							//noinspection unchecked
							argumentExtractors[i] = entity -> entity.getAssociatedData(
								associatedDataName,
								parameterType,
								reflectionLookup
							);
							score++;
						} else {
							argumentExtractors[i] = entity -> null;
						}
					}
					if (score > bestConstructorScore) {
						bestConstructorScore = score;
						//noinspection unchecked
						bestConstructor = new BestMatchingConstructorWithExtractionLambda<>(
							(Constructor<T>) declaredConstructor,
							(argumentIndex, sealedEntity) -> argumentExtractors[argumentIndex].apply(sealedEntity)
						);
					}
				}
			}
			if (bestConstructor == null) {
				throw new EntityClassInvalidException(
					expectedType,
					"Cannot find any constructor with matching arguments in class: `" + expectedType.getName() + "`"
				);
			} else {
				CONSTRUCTOR_CACHE.putIfAbsent(cacheKey, bestConstructor);
				return bestConstructor;
			}
		}
	}

	/**
	 * Method allows to provide explicit recipe for passed entity and output contract type.
	 *
	 * @param type       the proxy class for which the recipe should be used (combines with entityName)
	 * @param entityName the name of the entity for which the recipe should be used (combines with type)
	 * @param recipe     the Proxycian recipe to be used
	 */
	public <T> void registerEntityRecipe(
		@Nonnull Class<T> type,
		@Nonnull String entityName,
		@Nonnull ProxyRecipe recipe
	) {
		final ProxyRecipe theRecipe = new ProxyRecipe(
			recipe.getInterfaces(),
			ArrayUtils.mergeArrays(
				new Advice[]{
					new DelegateCallsAdvice<>(SealedEntityProxy.class, Function.identity(), true),
					LocalDataStoreAdvice.INSTANCE,
					EntityContractAdvice.INSTANCE
				},
				recipe.getAdvices()
			),
			recipe.getInstantiationCallback()
		);
		final ProxyEntityCacheKey key = new ProxyEntityCacheKey(type, entityName, null);
		recipes.put(key, theRecipe);
		collectedRecipes.put(key, theRecipe);
	}

	/**
	 * Method allows to provide explicit recipe for passed entity reference and output contract type.
	 *
	 * @param type          the proxy class for which the recipe should be used (combines with entityName and referenceName)
	 * @param entityName    the name of the entity for which the recipe should be used (combines with type and referenceName)
	 * @param referenceName the name of the entity reference schema for which the recipe should be used (combines with entityName and type)
	 * @param recipe        the Proxycian recipe to be used
	 */
	public <T> void registerEntityReferenceRecipe(
		@Nonnull Class<T> type,
		@Nonnull String entityName,
		@Nonnull String referenceName,
		@Nonnull ProxyRecipe recipe
	) {
		final ProxyRecipe theRecipe = new ProxyRecipe(
			recipe.getInterfaces(),
			ArrayUtils.mergeArrays(
				new Advice[]{
					new DelegateCallsAdvice<>(SealedEntityReferenceProxy.class, Function.identity(), true),
					LocalDataStoreAdvice.INSTANCE,
					EntityReferenceContractAdvice.INSTANCE
				},
				recipe.getAdvices()
			),
			recipe.getInstantiationCallback()
		);
		final ProxyEntityCacheKey key = new ProxyEntityCacheKey(type, entityName, referenceName);
		recipes.put(key, theRecipe);
		collectedRecipes.put(key, theRecipe);
	}

	@Nonnull
	@Override
	public <T> T createEntityProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull SealedEntity sealedEntity
	) {
		return createProxy(
			expectedType, recipes, collectedRecipes, sealedEntity, reflectionLookup,
			theType -> collectedRecipes.computeIfAbsent(theType, DEFAULT_ENTITY_RECIPE)
		);
	}

	/**
	 * DTO for storing constructor and constructor argument value extraction lambda.
	 *
	 * @param constructor      proxy class constructor
	 * @param extractionLambda lambda for extracting constructor argument value from sealed entity for specific
	 *                         index of the argument in the constructor
	 */
	private record BestMatchingConstructorWithExtractionLambda<T>(
		@Nonnull Constructor<T> constructor,
		@Nonnull ExceptionRethrowingIntBiFunction<SealedEntity, Object> extractionLambda
	) {

		/**
		 * Extracts constructor arguments from sealed entity for particular constructor method.
		 */
		@Nonnull
		public Object[] constructorArguments(@Nonnull SealedEntity sealedEntity) throws Exception {
			final Class<?>[] parameterTypes = constructor.getParameterTypes();
			final Object[] parameterArguments = new Object[parameterTypes.length];
			for (int i = 0; i < parameterTypes.length; i++) {
				parameterArguments[i] = extractionLambda.apply(i, sealedEntity);
			}
			return parameterArguments;
		}
	}

	/**
	 * Cache key for particular type/entity/reference combination.
	 *
	 * @param type          the proxy class
	 * @param entityName	the name of the entity {@link EntitySchemaContract#getName()}
	 * @param referenceName the name of the entity reference schema {@link ReferenceSchemaContract#getName()}
	 */
	public record ProxyEntityCacheKey(
		@Nonnull Class<?> type,
		@Nonnull String entityName,
		@Nullable String referenceName
	) implements Serializable {}


}
