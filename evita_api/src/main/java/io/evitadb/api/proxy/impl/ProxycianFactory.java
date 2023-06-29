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
import io.evitadb.api.proxy.impl.method.GetAssociatedDataMethodClassifier;
import io.evitadb.api.proxy.impl.method.GetAttributeMethodClassifier;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.function.ExceptionRethrowingBiFunction;
import io.evitadb.function.ExceptionRethrowingIntTriFunction;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.ReflectionLookup;
import lombok.RequiredArgsConstructor;
import one.edee.oss.proxycian.MethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import one.edee.oss.proxycian.trait.StandardJavaMethods;
import one.edee.oss.proxycian.trait.delegate.DelegateCallsAdvice.DelegatingMethodClassification;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
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
	@SuppressWarnings("rawtypes")
	private static final MethodClassification[] METHOD_CLASSIFICATIONS = {
		StandardJavaMethods.equalsMethodInvoker(),
		StandardJavaMethods.hashCodeMethodInvoker(),
		StandardJavaMethods.toStringMethodInvoker(),
		StandardJavaMethods.realMethodInvoker(),
		new DelegatingMethodClassification<SealedEntityProxyState, EntityClassifier>(
			EntityClassifier.class, Function.identity()
		),
		new DelegatingMethodClassification<SealedEntityProxyState, SealedEntityProxy>(
			SealedEntityProxy.class, Function.identity()
		),
		GetAttributeMethodClassifier.INSTANCE,
		GetAssociatedDataMethodClassifier.INSTANCE
	};

	private final ReflectionLookup reflectionLookup;
	private final ConcurrentHashMap<BestConstructorCacheKey, BestMatchingConstructorWithExtractionLambda<?>> constructorCache = CollectionUtils.createConcurrentHashMap(256);

	@Override
	public <T extends EntityClassifier> T createProxy(
		@Nonnull Class<T> expectedType,
		@Nonnull SealedEntity sealedEntity,
		@Nonnull EvitaRequest request
	) {
		try {
			if (expectedType.isRecord()) {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				return bestMatchingConstructor.constructor().newInstance(
					bestMatchingConstructor.constructorArguments(sealedEntity, request)
				);
			} else if (expectedType.isInterface()) {
				return ByteBuddyProxyGenerator.instantiate(
					new ByteBuddyDispatcherInvocationHandler<>(
						new SealedEntityProxyState(sealedEntity, expectedType, reflectionLookup),
						METHOD_CLASSIFICATIONS
					),
					new Class<?>[]{expectedType, SealedEntityProxy.class}
				);
			} else if (isAbstract(expectedType)) {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				return ByteBuddyProxyGenerator.instantiate(
					new ByteBuddyDispatcherInvocationHandler<>(
						new SealedEntityProxyState(sealedEntity, expectedType, reflectionLookup),
						METHOD_CLASSIFICATIONS
					),
					new Class<?>[]{expectedType, SealedEntityProxy.class},
					bestMatchingConstructor.constructor().getParameterTypes(),
					bestMatchingConstructor.constructorArguments(sealedEntity, request)
				);
			} else {
				final BestMatchingConstructorWithExtractionLambda<T> bestMatchingConstructor = findBestMatchingConstructor(
					expectedType, sealedEntity.getSchema(), reflectionLookup
				);
				return bestMatchingConstructor.constructor().newInstance(
					bestMatchingConstructor.constructorArguments(sealedEntity, request)
				);
			}
		} catch (Exception e) {
			throw new EntityClassInvalidException(expectedType, e);
		}
	}

	private <T extends EntityClassifier> BestMatchingConstructorWithExtractionLambda<T> findBestMatchingConstructor(
		@Nonnull Class<T> expectedType,
		@Nonnull EntitySchemaContract schema,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		final BestConstructorCacheKey cacheKey = new BestConstructorCacheKey(schema.getName(), null, expectedType);
		if (constructorCache.containsKey(cacheKey)) {
			//noinspection unchecked
			return (BestMatchingConstructorWithExtractionLambda<T>) constructorCache.get(cacheKey);
		} else {
			int bestConstructorScore = Integer.MIN_VALUE;
			BestMatchingConstructorWithExtractionLambda<T> bestConstructor = null;
			for (Constructor<?> declaredConstructor : expectedType.getDeclaredConstructors()) {
				int score = 0;
				final Parameter[] parameters = declaredConstructor.getParameters();
				//noinspection unchecked
				final ExceptionRethrowingBiFunction<SealedEntity, EvitaRequest, Object>[] argumentExtractors =
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
								argumentExtractors[i] = (entity, request) -> Enum.valueOf(
									parameterType,
									(String) entity.getAttribute(
										attributeName,
										request.getRequiredOrImplicitLocale(),
										String.class
									)
								);
							} else {
								//noinspection unchecked
								argumentExtractors[i] = (entity, request) -> entity.getAttribute(
									attributeName,
									request.getRequiredOrImplicitLocale(),
									parameterType
								);
							}
							score++;
						} else if (associatedData.isPresent()) {
							final String associatedDataName = associatedData.get().getName();
							if (parameterType.isEnum()) {
								//noinspection unchecked
								argumentExtractors[i] = (entity, request) -> Enum.valueOf(
									parameterType,
									(String) entity.getAssociatedData(
										associatedDataName,
										request.getRequiredOrImplicitLocale(),
										String.class,
										reflectionLookup
									)
								);
							} else {
								//noinspection unchecked
								argumentExtractors[i] = (entity, request) -> entity.getAssociatedData(
									associatedDataName,
									request.getRequiredOrImplicitLocale(),
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
							argumentExtractors[i] = (entity, request) -> entity.getAssociatedData(
								associatedDataName,
								request.getRequiredOrImplicitLocale(),
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
							(argumentIndex, sealedEntity, request) -> argumentExtractors[argumentIndex].apply(sealedEntity, request)
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
				constructorCache.putIfAbsent(cacheKey, bestConstructor);
				return bestConstructor;
			}
		}
	}

	private record BestMatchingConstructorWithExtractionLambda<T>(
		@Nonnull Constructor<T> constructor,
		@Nonnull ExceptionRethrowingIntTriFunction<SealedEntity, EvitaRequest, Object> extractionLambda
	) {

		@Nonnull
		public Object[] constructorArguments(@Nonnull SealedEntity sealedEntity, @Nonnull EvitaRequest request) throws Exception {
			final Class<?>[] parameterTypes = constructor.getParameterTypes();
			final Object[] parameterArguments = new Object[parameterTypes.length];
			for (int i = 0; i < parameterTypes.length; i++) {
				parameterArguments[i] = extractionLambda.apply(i, sealedEntity, request);
			}
			return parameterArguments;
		}
	}

	private record BestConstructorCacheKey(
		@Nonnull String entityName,
		@Nullable String referenceName,
		@Nonnull Class<? extends EntityClassifier> expectedType
	) {
	}

}
