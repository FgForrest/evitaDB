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
import io.evitadb.api.proxy.ProxyReferenceFactory;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.proxy.impl.AbstractEntityProxyState.ProxyInstanceCacheKey;
import io.evitadb.api.proxy.impl.AbstractEntityProxyState.ProxyWithUpsertCallback;
import io.evitadb.api.proxy.impl.entity.EntityContractAdvice;
import io.evitadb.api.proxy.impl.entity.GetAssociatedDataMethodClassifier;
import io.evitadb.api.proxy.impl.entity.GetAttributeMethodClassifier;
import io.evitadb.api.proxy.impl.entity.GetEntityTypeMethodClassifier;
import io.evitadb.api.proxy.impl.entity.GetParentEntityMethodClassifier;
import io.evitadb.api.proxy.impl.entity.GetPriceMethodClassifier;
import io.evitadb.api.proxy.impl.entity.GetPrimaryKeyMethodClassifier;
import io.evitadb.api.proxy.impl.entity.GetReferenceMethodClassifier;
import io.evitadb.api.proxy.impl.entityBuilder.EntityBuilderAdvice;
import io.evitadb.api.proxy.impl.reference.EntityReferenceContractAdvice;
import io.evitadb.api.proxy.impl.reference.GetReferenceAttributeMethodClassifier;
import io.evitadb.api.proxy.impl.reference.GetReferencedEntityMethodClassifier;
import io.evitadb.api.proxy.impl.reference.GetReferencedEntityPrimaryKeyMethodClassifier;
import io.evitadb.api.proxy.impl.reference.GetReferencedGroupEntityPrimaryKeyMethodClassifier;
import io.evitadb.api.proxy.impl.referenceBuilder.EntityReferenceBuilderAdvice;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.function.ExceptionRethrowingBiFunction;
import io.evitadb.function.ExceptionRethrowingFunction;
import io.evitadb.function.ExceptionRethrowingIntBiFunction;
import io.evitadb.function.ExceptionRethrowingIntTriFunction;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.evitadb.utils.ClassUtils.isAbstract;
import static io.evitadb.utils.ClassUtils.isFinal;

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
			EntityContractAdvice.INSTANCE,
			EntityBuilderAdvice.INSTANCE
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
			EntityReferenceContractAdvice.INSTANCE,
			EntityReferenceBuilderAdvice.INSTANCE
		}
	);

	/**
	 * Cache for the identified best matching constructors to speed up proxy creation.
	 */
	private final static ConcurrentHashMap<ProxyEntityCacheKey, BestMatchingEntityConstructorWithExtractionLambda<?>> ENTITY_CONSTRUCTOR_CACHE = CollectionUtils.createConcurrentHashMap(256);
	private final static ConcurrentHashMap<ProxyEntityCacheKey, BestMatchingReferenceConstructorWithExtractionLambda<?>> REFERENCE_CONSTRUCTOR_CACHE = CollectionUtils.createConcurrentHashMap(256);
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
	 * Creates a new proxy instance for passed {@link EntityContract} instance.
	 */
	static <T> T createEntityProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull EntityContract entity,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		return createProxy(
			expectedType, recipes, collectedRecipes, entity, referencedEntitySchemas, reflectionLookup,
			theCacheKey -> collectedRecipes.computeIfAbsent(theCacheKey, DEFAULT_ENTITY_RECIPE),
			null
		);
	}

	/**
	 * Creates a new proxy instance for passed {@link EntityContract} instance.
	 */
	static <T> T createEntityProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull EntityContract entity,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nullable Consumer<SealedEntityProxyState> stateInitializer
	) {
		return createProxy(
			expectedType, recipes, collectedRecipes, entity, referencedEntitySchemas, reflectionLookup,
			theCacheKey -> collectedRecipes.computeIfAbsent(theCacheKey, DEFAULT_ENTITY_RECIPE),
			stateInitializer
		);
	}

	/**
	 * Creates a new proxy instance for passed {@link EntityContract} instance.
	 */
	private static <T> T createProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull EntityContract entity,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Function<ProxyEntityCacheKey, ProxyRecipe> recipeLocator,
		@Nullable Consumer<SealedEntityProxyState> stateInitializer
		) {
		try {
			if (expectedType.isRecord()) {
				final BestMatchingEntityConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, entity.getSchema(), referencedEntitySchemas, reflectionLookup,
					new DirectProxyFactory(recipes, collectedRecipes, reflectionLookup),
					new DirectProxyReferenceFactory(recipes, collectedRecipes, reflectionLookup)
				);
				return bestMatchingConstructor.constructor().newInstance(
					bestMatchingConstructor.constructorArguments(entity)
				);
			} else {
				final String entityName = entity.getSchema().getName();
				final ProxyEntityCacheKey cacheKey = new ProxyEntityCacheKey(expectedType, entityName, null);
				if (expectedType.isInterface()) {
					final SealedEntityProxyState proxyState = new SealedEntityProxyState(
						entity, referencedEntitySchemas, expectedType, recipes, collectedRecipes, reflectionLookup
					);
					if (stateInitializer != null) {
						stateInitializer.accept(proxyState);
					}
					return ByteBuddyProxyGenerator.instantiate(
						recipeLocator.apply(cacheKey),
						proxyState
					);
				} else if (!isFinal(expectedType)) {
					final BestMatchingEntityConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
						expectedType, entity.getSchema(), referencedEntitySchemas, reflectionLookup,
						new DirectProxyFactory(recipes, collectedRecipes, reflectionLookup),
						new DirectProxyReferenceFactory(recipes, collectedRecipes, reflectionLookup)
					);
					final SealedEntityProxyState proxyState = new SealedEntityProxyState(
						entity, referencedEntitySchemas, expectedType, recipes, collectedRecipes, reflectionLookup
					);
					if (stateInitializer != null) {
						stateInitializer.accept(proxyState);
					}
					return ByteBuddyProxyGenerator.instantiate(
						recipeLocator.apply(cacheKey),
						proxyState,
						bestMatchingConstructor.constructor().getParameterTypes(),
						bestMatchingConstructor.constructorArguments(entity)
					);
				} else {
					final BestMatchingEntityConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
						expectedType, entity.getSchema(), referencedEntitySchemas, reflectionLookup,
						new DirectProxyFactory(recipes, collectedRecipes, reflectionLookup),
						new DirectProxyReferenceFactory(recipes, collectedRecipes, reflectionLookup)
					);
					return bestMatchingConstructor.constructor().newInstance(
						bestMatchingConstructor.constructorArguments(entity)
					);
				}
			}
		} catch (Exception e) {
			throw new EntityClassInvalidException(expectedType, e);
		}
	}

	/**
	 * Creates a new proxy instance for passed {@link EntityContract} and {@link ReferenceContract} instance.
	 */
	static <T> T createEntityReferenceProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull EntityContract entity,
		@Nonnull Supplier<Integer> entityPrimaryKeySupplier,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull ReferenceContract reference,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nullable Map<ProxyInstanceCacheKey, ProxyWithUpsertCallback> instanceCache
	) {
		return createReferenceProxy(
			expectedType, recipes, collectedRecipes,
			entity, entityPrimaryKeySupplier,
			referencedEntitySchemas, reference, reflectionLookup,
			theCacheKey -> collectedRecipes.computeIfAbsent(theCacheKey, DEFAULT_ENTITY_REFERENCE_RECIPE),
			instanceCache
		);
	}

	/**
	 * Creates a new proxy instance for passed {@link EntityContract} and {@link ReferenceContract} instance.
	 */
	private static <T> T createReferenceProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull EntityContract entity,
		@Nonnull Supplier<Integer> entityPrimaryKeySupplier,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull ReferenceContract reference,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Function<ProxyEntityCacheKey, ProxyRecipe> recipeLocator,
		@Nullable Map<ProxyInstanceCacheKey, ProxyWithUpsertCallback> instanceCache
	) {
		try {
			if (expectedType.isRecord()) {
				final BestMatchingReferenceConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, entity.getSchema(), referencedEntitySchemas,
					reference.getReferenceSchemaOrThrow(), reflectionLookup,
					new DirectProxyFactory(recipes, collectedRecipes, reflectionLookup)
				);
				return bestMatchingConstructor.constructor().newInstance(
					bestMatchingConstructor.constructorArguments(entity, reference)
				);
			} else {
				if (expectedType.isInterface()) {
					final String entityName = entity.getSchema().getName();
					final ProxyEntityCacheKey cacheKey = new ProxyEntityCacheKey(expectedType, entityName, reference.getReferenceName());
					return ByteBuddyProxyGenerator.instantiate(
						recipeLocator.apply(cacheKey),
						new SealedEntityReferenceProxyState(
							entity, entityPrimaryKeySupplier,
							referencedEntitySchemas, reference, expectedType, recipes,
							collectedRecipes, reflectionLookup,
							instanceCache
						)
					);
				} else if (isAbstract(expectedType)) {
					final BestMatchingReferenceConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
						expectedType, entity.getSchema(), referencedEntitySchemas,
						reference.getReferenceSchemaOrThrow(), reflectionLookup,
						new DirectProxyFactory(recipes, collectedRecipes, reflectionLookup)
					);
					final String entityName = entity.getSchema().getName();
					final ProxyEntityCacheKey cacheKey = new ProxyEntityCacheKey(expectedType, entityName, reference.getReferenceName());
					return ByteBuddyProxyGenerator.instantiate(
						recipeLocator.apply(cacheKey),
						new SealedEntityReferenceProxyState(
							entity, entityPrimaryKeySupplier,
							referencedEntitySchemas, reference, expectedType,
							recipes, collectedRecipes, reflectionLookup,
							instanceCache
						),
						bestMatchingConstructor.constructor().getParameterTypes(),
						bestMatchingConstructor.constructorArguments(entity, reference)
					);
				} else {
					final BestMatchingReferenceConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
						expectedType, entity.getSchema(), referencedEntitySchemas,
						reference.getReferenceSchemaOrThrow(), reflectionLookup,
						new DirectProxyFactory(recipes, collectedRecipes, reflectionLookup)
					);
					return bestMatchingConstructor.constructor().newInstance(
						bestMatchingConstructor.constructorArguments(entity, reference)
					);
				}
			}
		} catch (Exception e) {
			throw new EntityClassInvalidException(expectedType, e);
		}
	}

	/**
	 * Method tries to identify the best matching constructor for passed {@link EntitySchemaContract} and {@link Class}
	 * type. It tries to find a constructor with most of the arguments matching the schema fields.
	 */
	private static <T> BestMatchingEntityConstructorWithExtractionLambda<T> findBestMatchingConstructor(
		@Nonnull Class<T> expectedType,
		@Nonnull EntitySchemaContract schema,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ProxyFactory proxyFactory,
		@Nonnull ProxyReferenceFactory proxyReferenceFactory
	) {
		final ProxyEntityCacheKey cacheKey = new ProxyEntityCacheKey(expectedType, schema.getName(), null);
		if (ENTITY_CONSTRUCTOR_CACHE.containsKey(cacheKey)) {
			//noinspection unchecked
			return (BestMatchingEntityConstructorWithExtractionLambda<T>) ENTITY_CONSTRUCTOR_CACHE.get(cacheKey);
		} else {
			int bestConstructorScore = Integer.MIN_VALUE;
			BestMatchingEntityConstructorWithExtractionLambda<T> bestConstructor = null;
			for (Constructor<?> declaredConstructor : expectedType.getDeclaredConstructors()) {
				int score = 0;
				final Parameter[] parameters = declaredConstructor.getParameters();
				//noinspection unchecked
				final ExceptionRethrowingFunction<EntityContract, Object>[] argumentExtractors =
					new ExceptionRethrowingFunction[parameters.length];

				for (int i = 0; i < parameters.length; i++) {
					final ExceptionRethrowingFunction<EntityContract, Object> pkFct =
						GetPrimaryKeyMethodClassifier.getExtractorIfPossible(
							expectedType, parameters[i], reflectionLookup
						);
					if (pkFct != null) {
						argumentExtractors[i] = pkFct;
						score++;
						continue;
					}
					final ExceptionRethrowingFunction<EntityContract, Object> entityTypeFct =
						GetEntityTypeMethodClassifier.getExtractorIfPossible(
							expectedType, parameters[i], reflectionLookup
						);
					if (entityTypeFct != null) {
						argumentExtractors[i] = entityTypeFct;
						score++;
						continue;
					}

					final ExceptionRethrowingFunction<EntityContract, Object> attributeFct =
						GetAttributeMethodClassifier.getExtractorIfPossible(
							expectedType, parameters[i], reflectionLookup, schema
						);
					if (attributeFct != null) {
						argumentExtractors[i] = attributeFct;
						score++;
						continue;
					}

					final ExceptionRethrowingFunction<EntityContract, Object> priceFct =
						GetPriceMethodClassifier.getExtractorIfPossible(
							expectedType, parameters[i], reflectionLookup, schema
						);
					if (priceFct != null) {
						argumentExtractors[i] = priceFct;
						score++;
						continue;
					}

					final ExceptionRethrowingFunction<EntityContract, Object> parentFct =
						GetParentEntityMethodClassifier.getExtractorIfPossible(
							referencedEntitySchemas, expectedType, parameters[i], reflectionLookup, proxyFactory
						);
					if (parentFct != null) {
						argumentExtractors[i] = parentFct;
						score++;
						continue;
					}

					final ExceptionRethrowingFunction<EntityContract, Object> referenceFct =
						GetReferenceMethodClassifier.getExtractorIfPossible(
							schema, referencedEntitySchemas, expectedType, parameters[i], reflectionLookup,
							(itemType, entity) -> proxyFactory.createEntityProxy(itemType, entity, referencedEntitySchemas),
							proxyReferenceFactory
						);
					if (referenceFct != null) {
						argumentExtractors[i] = referenceFct;
						score++;
						continue;
					}

					final ExceptionRethrowingFunction<EntityContract, Object> associatedDataFct =
						GetAssociatedDataMethodClassifier.getExtractorIfPossible(
							expectedType, parameters[i], reflectionLookup, schema
						);
					if (associatedDataFct != null) {
						argumentExtractors[i] = associatedDataFct;
						score++;
						continue;
					}

					argumentExtractors[i] = entity -> null;
				}

				if (score > bestConstructorScore) {
					bestConstructorScore = score;
					//noinspection unchecked
					bestConstructor = new BestMatchingEntityConstructorWithExtractionLambda<>(
						(Constructor<T>) declaredConstructor,
						(argumentIndex, entity) -> argumentExtractors[argumentIndex].apply(entity)
					);
				}
			}

			if (bestConstructor == null) {
				throw new EntityClassInvalidException(
					expectedType,
					"Cannot find any constructor with matching arguments in class: `" + expectedType.getName() + "`"
				);
			} else {
				ENTITY_CONSTRUCTOR_CACHE.putIfAbsent(cacheKey, bestConstructor);
				return bestConstructor;
			}
		}
	}

	/**
	 * Method tries to identify the best matching constructor for passed {@link EntitySchemaContract} and {@link Class}
	 * type. It tries to find a constructor with most of the arguments matching the schema fields.
	 */
	private static <T> BestMatchingReferenceConstructorWithExtractionLambda<T> findBestMatchingConstructor(
		@Nonnull Class<T> expectedType,
		@Nonnull EntitySchemaContract schema,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ProxyFactory proxyFactory
	) {
		final ProxyEntityCacheKey cacheKey = new ProxyEntityCacheKey(expectedType, schema.getName(), null);
		if (REFERENCE_CONSTRUCTOR_CACHE.containsKey(cacheKey)) {
			//noinspection unchecked
			return (BestMatchingReferenceConstructorWithExtractionLambda<T>) REFERENCE_CONSTRUCTOR_CACHE.get(cacheKey);
		} else {
			int bestConstructorScore = Integer.MIN_VALUE;
			BestMatchingReferenceConstructorWithExtractionLambda<T> bestConstructor = null;
			for (Constructor<?> declaredConstructor : expectedType.getDeclaredConstructors()) {
				int score = 0;
				final Parameter[] parameters = declaredConstructor.getParameters();
				//noinspection unchecked
				final ExceptionRethrowingBiFunction<EntityContract, ReferenceContract, Object>[] argumentExtractors =
					new ExceptionRethrowingBiFunction[parameters.length];

				for (int i = 0; i < parameters.length; i++) {
					final ExceptionRethrowingBiFunction<EntityContract, ReferenceContract, Object> refEntityPkFct =
						GetReferencedEntityPrimaryKeyMethodClassifier.getExtractorIfPossible(
							expectedType, parameters[i], reflectionLookup
						);
					if (refEntityPkFct != null) {
						argumentExtractors[i] = refEntityPkFct;
						score++;
						continue;
					}
					final ExceptionRethrowingBiFunction<EntityContract, ReferenceContract, Object> refEntityGroupPkFct =
						GetReferencedGroupEntityPrimaryKeyMethodClassifier.getExtractorIfPossible(
							expectedType, parameters[i], reflectionLookup
						);
					if (refEntityGroupPkFct != null) {
						argumentExtractors[i] = refEntityGroupPkFct;
						score++;
						continue;
					}

					final ExceptionRethrowingBiFunction<EntityContract, ReferenceContract, Object> attributeFct =
						GetReferenceAttributeMethodClassifier.getExtractorIfPossible(
							expectedType, parameters[i], reflectionLookup, schema, referenceSchema
						);
					if (attributeFct != null) {
						argumentExtractors[i] = attributeFct;
						score++;
						continue;
					}

					final ExceptionRethrowingBiFunction<EntityContract, ReferenceContract, Object> referenceFct =
						GetReferencedEntityMethodClassifier.getExtractorIfPossible(
							referencedEntitySchemas, expectedType, parameters[i],
							reflectionLookup, referenceSchema, proxyFactory
						);
					if (referenceFct != null) {
						argumentExtractors[i] = referenceFct;
						score++;
						continue;
					}

					argumentExtractors[i] = (entity, reference) -> null;
				}

				if (score > bestConstructorScore) {
					bestConstructorScore = score;
					//noinspection unchecked
					bestConstructor = new BestMatchingReferenceConstructorWithExtractionLambda<>(
						(Constructor<T>) declaredConstructor,
						(argumentIndex, EntityContract, reference) -> argumentExtractors[argumentIndex]
							.apply(EntityContract, reference)
					);
				}
			}
			if (bestConstructor == null) {
				throw new EntityClassInvalidException(
					expectedType,
					"Cannot find any constructor with matching arguments in class: `" + expectedType.getName() + "`"
				);
			} else {
				REFERENCE_CONSTRUCTOR_CACHE.putIfAbsent(cacheKey, bestConstructor);
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
		@Nonnull EntityContract entity,
		@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas
	) throws EntityClassInvalidException {
		return createEntityProxy(expectedType, recipes, collectedRecipes, entity, referencedEntitySchemas, reflectionLookup);
	}

	/**
	 * DTO for storing constructor and constructor argument value extraction lambda.
	 *
	 * @param constructor      proxy class constructor
	 * @param extractionLambda lambda for extracting constructor argument value from sealed entity for specific
	 *                         index of the argument in the constructor
	 */
	private record BestMatchingEntityConstructorWithExtractionLambda<T>(
		@Nonnull Constructor<T> constructor,
		@Nonnull ExceptionRethrowingIntBiFunction<EntityContract, Object> extractionLambda
	) {

		/**
		 * Extracts constructor arguments from sealed entity for particular constructor method.
		 */
		@Nonnull
		public Object[] constructorArguments(@Nonnull EntityContract entity) throws Exception {
			final Class<?>[] parameterTypes = constructor.getParameterTypes();
			final Object[] parameterArguments = new Object[parameterTypes.length];
			for (int i = 0; i < parameterTypes.length; i++) {
				parameterArguments[i] = extractionLambda.apply(i, entity);
			}
			return parameterArguments;
		}
	}

	/**
	 * DTO for storing constructor and constructor argument value extraction lambda.
	 *
	 * @param constructor      proxy class constructor
	 * @param extractionLambda lambda for extracting constructor argument value from sealed entity for specific
	 *                         index of the argument in the constructor
	 */
	private record BestMatchingReferenceConstructorWithExtractionLambda<T>(
		@Nonnull Constructor<T> constructor,
		@Nonnull ExceptionRethrowingIntTriFunction<EntityContract, ReferenceContract, Object> extractionLambda
	) {

		/**
		 * Extracts constructor arguments from sealed entity for particular constructor method.
		 */
		@Nonnull
		public Object[] constructorArguments(@Nonnull EntityContract EntityContract, @Nonnull ReferenceContract reference) throws Exception {
			final Class<?>[] parameterTypes = constructor.getParameterTypes();
			final Object[] parameterArguments = new Object[parameterTypes.length];
			for (int i = 0; i < parameterTypes.length; i++) {
				parameterArguments[i] = extractionLambda.apply(i, EntityContract, reference);
			}
			return parameterArguments;
		}
	}

	/**
	 * Cache key for particular type/entity/reference combination.
	 *
	 * @param type          the proxy class
	 * @param entityName    the name of the entity {@link EntitySchemaContract#getName()}
	 * @param referenceName the name of the entity reference schema {@link ReferenceSchemaContract#getName()}
	 */
	public record ProxyEntityCacheKey(
		@Nonnull Class<?> type,
		@Nonnull String entityName,
		@Nullable String referenceName
	) implements Serializable {
	}


	/**
	 * Direct implementation of the {@link ProxyFactory} interface that uses the provided maps of recipes and avoids
	 * going through {@link AbstractEntityProxyState}.
	 */
	private record DirectProxyFactory(
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull ReflectionLookup reflectionLookup
	) implements ProxyFactory {

		@Nonnull
		@Override
		public <T> T createEntityProxy(
			@Nonnull Class<T> expectedType,
			@Nonnull EntityContract entityContract,
			@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas
		) throws EntityClassInvalidException {
			return ProxycianFactory.createEntityProxy(expectedType, recipes, collectedRecipes, entityContract, referencedEntitySchemas, reflectionLookup);
		}

	}

	/**
	 * Direct implementation of the {@link ProxyReferenceFactory} interface that uses the provided maps of recipes and avoids
	 * going through {@link AbstractEntityProxyState}.
	 */
	private record DirectProxyReferenceFactory(
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyEntityCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull ReflectionLookup reflectionLookup
	) implements ProxyReferenceFactory {
		@Nonnull
		@Override
		public <T> T createEntityReferenceProxy(
			@Nonnull Class<T> expectedType,
			@Nonnull EntityContract entity,
			@Nonnull Map<String, EntitySchemaContract> referencedEntitySchemas,
			@Nonnull ReferenceContract reference
		) throws EntityClassInvalidException {
			return ProxycianFactory.createEntityReferenceProxy(
				expectedType, recipes, collectedRecipes,
				entity, () -> null,
				referencedEntitySchemas, reference, reflectionLookup,
				null
			);
		}

	}
}
