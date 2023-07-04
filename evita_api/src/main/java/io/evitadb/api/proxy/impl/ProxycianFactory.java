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
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.function.ExceptionRethrowingBiFunction;
import io.evitadb.function.ExceptionRethrowingIntTriFunction;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.ReflectionLookup;
import lombok.RequiredArgsConstructor;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import one.edee.oss.proxycian.recipe.Advice;
import one.edee.oss.proxycian.recipe.ProxyRecipe;
import one.edee.oss.proxycian.trait.delegate.DelegateCallsAdvice;

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
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class ProxycianFactory implements ProxyFactory {

	final static Function<ProxyRecipeCacheKey, ProxyRecipe> DEFAULT_ENTITY_RECIPE = cacheKey -> new ProxyRecipe(
		new Class<?>[]{cacheKey.type()},
		new Advice[]{
			new DelegateCallsAdvice<>(SealedEntityProxy.class, Function.identity(), true),
			EntityContractAdvice.INSTANCE
		}
	);

	final static Function<ProxyRecipeCacheKey, ProxyRecipe> DEFAULT_ENTITY_REFERENCE_RECIPE = cacheKey -> new ProxyRecipe(
		new Class<?>[]{cacheKey.type()},
		new Advice[]{
			new DelegateCallsAdvice<>(SealedEntityReferenceProxy.class, Function.identity(), true),
			EntityReferenceContractAdvice.INSTANCE
		}
	);

	private final static ConcurrentHashMap<BestConstructorCacheKey, BestMatchingConstructorWithExtractionLambda<?>> CONSTRUCTOR_CACHE = CollectionUtils.createConcurrentHashMap(256);
	private final Map<ProxyRecipeCacheKey, ProxyRecipe> recipes = new ConcurrentHashMap<>(64);
	private final Map<ProxyRecipeCacheKey, ProxyRecipe> collectedRecipes = new ConcurrentHashMap<>(64);
	private final ReflectionLookup reflectionLookup;

	static <T> T createProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull Map<ProxyRecipeCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyRecipeCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull SealedEntity sealedEntity,
		@Nonnull EvitaRequestContext context,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Function<ProxyRecipeCacheKey, ProxyRecipe> recipeLocator
	) {
		Assert.isTrue(
			EntityClassifier.class.isAssignableFrom(expectedType),
			() -> new EntityClassInvalidException(expectedType, "Proxied type `" + expectedType + "` must be a subclass of EntityClassifier!")
		);
		try {
			if (expectedType.isRecord()) {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				return bestMatchingConstructor.constructor().newInstance(
					bestMatchingConstructor.constructorArguments(sealedEntity, context)
				);
			} else if (expectedType.isInterface()) {
				final String entityName = sealedEntity.getSchema().getName();
				final ProxyRecipeCacheKey cacheKey = new ProxyRecipeCacheKey(expectedType, entityName, null);
				return ByteBuddyProxyGenerator.instantiate(
					recipeLocator.apply(cacheKey),
					new SealedEntityProxyState(context, sealedEntity, expectedType, recipes, collectedRecipes, reflectionLookup)
				);
			} else if (isAbstract(expectedType)) {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				final String entityName = sealedEntity.getSchema().getName();
				final ProxyRecipeCacheKey cacheKey = new ProxyRecipeCacheKey(expectedType, entityName, null);
				return ByteBuddyProxyGenerator.instantiate(
					recipeLocator.apply(cacheKey),
					new SealedEntityProxyState(context, sealedEntity, expectedType, recipes, collectedRecipes, reflectionLookup),
					bestMatchingConstructor.constructor().getParameterTypes(),
					bestMatchingConstructor.constructorArguments(sealedEntity, context)
				);
			} else {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				return bestMatchingConstructor.constructor().newInstance(
					bestMatchingConstructor.constructorArguments(sealedEntity, context)
				);
			}
		} catch (Exception e) {
			throw new EntityClassInvalidException(expectedType, e);
		}
	}

	static <T> T createProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull Map<ProxyRecipeCacheKey, ProxyRecipe> recipes,
		@Nonnull Map<ProxyRecipeCacheKey, ProxyRecipe> collectedRecipes,
		@Nonnull SealedEntity sealedEntity,
		@Nonnull ReferenceContract reference,
		@Nonnull EvitaRequestContext context,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Function<ProxyRecipeCacheKey, ProxyRecipe> recipeLocator
	) {
		try {
			if (expectedType.isRecord()) {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				return bestMatchingConstructor.constructor().newInstance(
					bestMatchingConstructor.constructorArguments(sealedEntity, context)
				);
			} else if (expectedType.isInterface()) {
				final String entityName = sealedEntity.getSchema().getName();
				final ProxyRecipeCacheKey cacheKey = new ProxyRecipeCacheKey(expectedType, entityName, reference.getReferenceName());
				return ByteBuddyProxyGenerator.instantiate(
					recipeLocator.apply(cacheKey),
					new SealedEntityReferenceProxyState(context, sealedEntity, reference, expectedType, recipes, collectedRecipes, reflectionLookup)
				);
			} else if (isAbstract(expectedType)) {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				final String entityName = sealedEntity.getSchema().getName();
				final ProxyRecipeCacheKey cacheKey = new ProxyRecipeCacheKey(expectedType, entityName, reference.getReferenceName());
				return ByteBuddyProxyGenerator.instantiate(
					recipeLocator.apply(cacheKey),
					new SealedEntityReferenceProxyState(context, sealedEntity, reference, expectedType, recipes, collectedRecipes, reflectionLookup),
					bestMatchingConstructor.constructor().getParameterTypes(),
					bestMatchingConstructor.constructorArguments(sealedEntity, context)
				);
			} else {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				return bestMatchingConstructor.constructor().newInstance(
					bestMatchingConstructor.constructorArguments(sealedEntity, context)
				);
			}
		} catch (Exception e) {
			throw new EntityClassInvalidException(expectedType, e);
		}
	}

	private static <T> BestMatchingConstructorWithExtractionLambda<T> findBestMatchingConstructor(
		@Nonnull Class<T> expectedType,
		@Nonnull EntitySchemaContract schema,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		final BestConstructorCacheKey cacheKey = new BestConstructorCacheKey(schema.getName(), null, expectedType);
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
				final ExceptionRethrowingBiFunction<SealedEntity, EvitaRequestContext, Object>[] argumentExtractors =
					new ExceptionRethrowingBiFunction[parameters.length];

				for (int i = 0; i < parameters.length; i++) {
					final String parameterName = parameters[i].getName();
					@SuppressWarnings("rawtypes") final Class parameterType = parameters[i].getType();
					if (parameterName.equals("primaryKey") || parameterName.equals("entityPrimaryKey") && (Integer.class.isAssignableFrom(parameterType) || int.class.isAssignableFrom(parameterType))) {
						argumentExtractors[i] = (entity, request) -> entity.getPrimaryKey();
						score++;
					} else if (parameterName.equals("type") || parameterName.equals("entityType") && String.class.isAssignableFrom(parameterType)) {
						argumentExtractors[i] = (entity, request) -> entity.getType();
						score++;
					} else if (EvitaDataTypes.isSupportedTypeOrItsArray(parameterType) || parameterType.isEnum()) {
						final Optional<AttributeSchemaContract> attribute = schema.getAttributeByName(parameterName, NamingConvention.CAMEL_CASE);
						final Optional<AssociatedDataSchemaContract> associatedData = schema.getAssociatedDataByName(parameterName, NamingConvention.CAMEL_CASE);
						if (attribute.isPresent()) {
							final String attributeName = attribute.get().getName();
							if (parameterType.isEnum()) {
								//noinspection unchecked
								argumentExtractors[i] = (entity, context) -> Enum.valueOf(
									parameterType,
									(String) entity.getAttribute(
										attributeName,
										context.locale(),
										String.class
									)
								);
							} else {
								//noinspection unchecked
								argumentExtractors[i] = (entity, context) -> entity.getAttribute(
									attributeName,
									context.locale(),
									parameterType
								);
							}
							score++;
						} else if (associatedData.isPresent()) {
							final String associatedDataName = associatedData.get().getName();
							if (parameterType.isEnum()) {
								//noinspection unchecked
								argumentExtractors[i] = (entity, context) -> Enum.valueOf(
									parameterType,
									(String) entity.getAssociatedData(
										associatedDataName,
										context.locale(),
										String.class,
										reflectionLookup
									)
								);
							} else {
								//noinspection unchecked
								argumentExtractors[i] = (entity, context) -> entity.getAssociatedData(
									associatedDataName,
									context.locale(),
									parameterType,
									reflectionLookup
								);
							}
							score++;
						} else {
							argumentExtractors[i] = (entity, request) -> null;
						}
					} else {
						final Optional<AssociatedDataSchemaContract> associatedData = schema.getAssociatedDataByName(parameterName, NamingConvention.CAMEL_CASE);
						if (associatedData.isPresent()) {
							final String associatedDataName = associatedData.get().getName();
							//noinspection unchecked
							argumentExtractors[i] = (entity, context) -> entity.getAssociatedData(
								associatedDataName,
								context.locale(),
								parameterType,
								reflectionLookup
							);
							score++;
						} else {
							argumentExtractors[i] = (entity, request) -> null;
						}
					}
					if (score > bestConstructorScore) {
						bestConstructorScore = score;
						//noinspection unchecked
						bestConstructor = new BestMatchingConstructorWithExtractionLambda<>(
							(Constructor<T>) declaredConstructor,
							(argumentIndex, sealedEntity, context) -> argumentExtractors[argumentIndex].apply(sealedEntity, context)
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
	 * TODO JNO - document me
	 */
	public <T extends EntityClassifier> void registerEntityRecipe(@Nonnull Class<T> type, @Nonnull String entityName, @Nonnull ProxyRecipe recipe) {
		final ProxyRecipe theRecipe = new ProxyRecipe(
			recipe.getInterfacesWith(SealedEntityProxy.class),
			ArrayUtils.mergeArrays(
				new Advice[]{
					new DelegateCallsAdvice<>(SealedEntityProxy.class, Function.identity(), true),
					EntityContractAdvice.INSTANCE
				},
				recipe.getAdvices()
			),
			recipe.getInstantiationCallback()
		);
		final ProxyRecipeCacheKey key = new ProxyRecipeCacheKey(type, entityName, null);
		recipes.put(key, theRecipe);
		collectedRecipes.put(key, theRecipe);
	}

	/**
	 * TODO JNO - document me
	 */
	public <T> void registerEntityReferenceRecipe(@Nonnull Class<T> type, @Nonnull String entityName, @Nonnull String referenceName, @Nonnull ProxyRecipe recipe) {
		final ProxyRecipe theRecipe = new ProxyRecipe(
			recipe.getInterfacesWith(SealedEntityReferenceProxy.class),
			ArrayUtils.mergeArrays(
				new Advice[]{
					new DelegateCallsAdvice<>(SealedEntityReferenceProxy.class, Function.identity(), true),
					EntityReferenceContractAdvice.INSTANCE
				},
				recipe.getAdvices()
			),
			recipe.getInstantiationCallback()
		);
		final ProxyRecipeCacheKey key = new ProxyRecipeCacheKey(type, entityName, referenceName);
		recipes.put(key, theRecipe);
		collectedRecipes.put(key, theRecipe);
	}

	@Nonnull
	@Override
	public <T> T createProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull SealedEntity sealedEntity,
		@Nonnull EvitaRequestContext context
	) {
		return createProxy(
			expectedType, recipes, collectedRecipes, sealedEntity, context, reflectionLookup,
			theType -> collectedRecipes.computeIfAbsent(theType, DEFAULT_ENTITY_RECIPE)
		);
	}

	private record BestMatchingConstructorWithExtractionLambda<T>(
		@Nonnull Constructor<T> constructor,
		@Nonnull ExceptionRethrowingIntTriFunction<SealedEntity, EvitaRequestContext, Object> extractionLambda
	) {

		@Nonnull
		public Object[] constructorArguments(@Nonnull SealedEntity sealedEntity, @Nonnull EvitaRequestContext context) throws Exception {
			final Class<?>[] parameterTypes = constructor.getParameterTypes();
			final Object[] parameterArguments = new Object[parameterTypes.length];
			for (int i = 0; i < parameterTypes.length; i++) {
				parameterArguments[i] = extractionLambda.apply(i, sealedEntity, context);
			}
			return parameterArguments;
		}
	}

	/**
	 * TODO JNO - document me
	 */
	public record ProxyRecipeCacheKey(
		@Nonnull Class<?> type,
		@Nonnull String entityName,
		@Nullable String referenceName
	) implements Serializable {
	}

	/**
	 * TODO JNO - document me
	 */
	private record BestConstructorCacheKey(
		@Nonnull String entityName,
		@Nullable String referenceName,
		@Nonnull Class<?> expectedType
	) {
	}

}
