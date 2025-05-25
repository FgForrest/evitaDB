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

package io.evitadb.api.proxy.impl.referenceBuilder;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.proxy.impl.SealedEntityReferenceProxyState;
import io.evitadb.api.requestResponse.data.BuilderContract;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.SealedInstance;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import one.edee.oss.proxycian.MethodClassification;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.recipe.Advice;
import one.edee.oss.proxycian.trait.ProxyStateAccessor;
import one.edee.oss.proxycian.util.ReflectionUtils;
import one.edee.oss.proxycian.utils.GenericsUtils;
import one.edee.oss.proxycian.utils.GenericsUtils.GenericBundle;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Advice allowing to implement all supported abstract methods on proxy wrapping {@link ReferenceContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class EntityReferenceBuilderAdvice implements Advice<SealedEntityReferenceProxy> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final EntityReferenceBuilderAdvice INSTANCE = new EntityReferenceBuilderAdvice();
	@Serial private static final long serialVersionUID = -5338309229187809879L;
	/**
	 * List of all method classifications supported by this advice.
	 */
	@SuppressWarnings("unchecked")
	private static final List<MethodClassification<?, SealedEntityReferenceProxy>> METHOD_CLASSIFICATION = Arrays.asList(
		new MethodClassification[]{
			getContractMethodClassification(),
			toMutationMethodClassification(),
			toInstanceMethodClassification(),
			upsertViaMethodClassification(),
			toMutationArrayMethodClassification(),
			toMutationCollectionMethodClassification(),
			openForWriteMethodClassification(),
			SetReferenceAttributeMethodClassifier.INSTANCE,
			SetReferenceGroupMethodClassifier.INSTANCE
		}
	);

	@Nonnull
	private static PredicateMethodClassification<Object, Class<?>, SealedEntityReferenceProxyState> openForWriteMethodClassification() {
		return new PredicateMethodClassification<>(
			"openForWrite",
			(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, SealedInstance.class) && "openForWrite".equals(method.getName()),
			(method, proxyState) -> {
				final List<GenericBundle> genericType = GenericsUtils.getGenericType(proxyState.getProxyClass(), method.getGenericReturnType());
				if (genericType.isEmpty()) {
					throw new IllegalStateException("Cannot determine the generic type of the method " + method);
				} else {
					return genericType.get(0).getResolvedType();
				}
			},
			(proxy, method, args, methodContext, proxyState, invokeSuper) ->
				proxyState.getOrCreateEntityReferenceProxy(
					methodContext, proxyState.getReference()
				)
		);
	}

	@Nonnull
	private static PredicateMethodClassification<Object, Class<?>, SealedEntityReferenceProxyState> toMutationArrayMethodClassification() {
		return new PredicateMethodClassification<>(
			"withMutationArray",
			(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, SealedInstance.class) &&
				"withMutations".equals(method.getName()) &&
				method.getParameterCount() == 1 &&
				method.getParameterTypes()[0].isArray() &&
				LocalMutation.class.equals(method.getParameterTypes()[0].getComponentType()),
			(method, proxyState) -> {
				final List<GenericBundle> genericType = GenericsUtils.getGenericType(proxyState.getProxyClass(), method.getGenericReturnType());
				if (genericType.isEmpty()) {
					throw new IllegalStateException("Cannot determine the generic type of the method " + method);
				} else {
					return genericType.get(0).getResolvedType();
				}
			},
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				final LocalMutation<?, ?>[] mutations = (LocalMutation<?, ?>[]) args[0];
				final Object theProxy = proxyState.getOrCreateEntityReferenceProxy(
					methodContext, proxyState.getReference()
				);
				final SealedEntityReferenceProxyState targetProxyState = (SealedEntityReferenceProxyState) ((ProxyStateAccessor) theProxy).getProxyState();
				targetProxyState.getReferenceBuilderWithMutations(Arrays.asList(mutations));
				return theProxy;
			}
		);
	}

	@Nonnull
	private static PredicateMethodClassification<Object, Class<?>, SealedEntityReferenceProxyState> toMutationCollectionMethodClassification() {
		return new PredicateMethodClassification<>(
			"withMutationArray",
			(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, SealedInstance.class) &&
				"withMutations".equals(method.getName()) &&
				method.getParameterCount() == 1 &&
				Collection.class.isAssignableFrom(method.getParameterTypes()[0]),
			(method, proxyState) -> {
				final List<GenericBundle> genericType = GenericsUtils.getGenericType(proxyState.getProxyClass(), method.getGenericReturnType());
				if (genericType.isEmpty()) {
					throw new IllegalStateException("Cannot determine the generic type of the method " + method);
				} else {
					return genericType.get(0).getResolvedType();
				}
			},
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				@SuppressWarnings("unchecked") final Collection<LocalMutation<?, ?>> mutations = (Collection<LocalMutation<?, ?>>) args[0];
				final Object theProxy = proxyState.getOrCreateEntityReferenceProxy(
					methodContext, proxyState.getReference()
				);
				final SealedEntityReferenceProxyState targetProxyState = (SealedEntityReferenceProxyState) ((ProxyStateAccessor) theProxy).getProxyState();
				targetProxyState.getReferenceBuilderWithMutations(mutations);
				return theProxy;
			}
		);
	}

	@Nonnull
	private static PredicateMethodClassification<Object, Method, SealedEntityReferenceProxyState> upsertViaMethodClassification() {
		return new PredicateMethodClassification<>(
			"upsertVia",
			(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, InstanceEditor.class) && "upsertVia".equals(method.getName()),
			(method, proxyState) -> method,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getReferenceBuilderIfPresent()
				.filter(ReferenceBuilder::hasChanges)
				.map(
					it -> new EntityUpsertMutation(
						proxyState.getType(),
						proxyState.getPrimaryKey(),
						EntityExistence.MUST_EXIST, it.buildChangeSet().collect(Collectors.toList())
					)
				)
				.map(it -> ((EvitaSessionContract) args[0]).upsertEntity(it))
				.orElseGet(() -> new EntityReference(proxyState.getType(), proxyState.getPrimaryKeyOrThrowException()))
		);
	}

	@Nonnull
	private static PredicateMethodClassification<Object, Method, SealedEntityReferenceProxyState> toInstanceMethodClassification() {
		return new PredicateMethodClassification<>(
			"toInstance",
			(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, InstanceEditor.class) && "toInstance".equals(method.getName()),
			(method, proxyState) -> method,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getReferenceBuilderIfPresent()
				.map(BuilderContract::build)
				.map(
					it -> (Object) proxyState.createNewReferenceProxy(
						proxyState.getEntityProxyClass(), proxyState.getProxyClass(), proxyState.getEntity(), it
					)
				)
				.orElse(proxy)
		);
	}

	@Nonnull
	private static PredicateMethodClassification<Object, Method, SealedEntityReferenceProxyState> toMutationMethodClassification() {
		return new PredicateMethodClassification<>(
			"toMutation",
			(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, InstanceEditor.class) && "toMutation".equals(method.getName()),
			(method, proxyState) -> method,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getReferenceBuilderIfPresent()
				.filter(ReferenceBuilder::hasChanges)
				.map(
					it -> new EntityUpsertMutation(
						proxyState.getType(),
						proxyState.getPrimaryKey(),
						EntityExistence.MUST_EXIST, it.buildChangeSet().collect(Collectors.toList())
					)
				)
		);
	}

	@Nonnull
	private static PredicateMethodClassification<Object, Method, SealedEntityReferenceProxyState> getContractMethodClassification() {
		return new PredicateMethodClassification<>(
			"getContract",
			(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, InstanceEditor.class) && "getContract".equals(method.getName()),
			(method, proxyState) -> method,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getProxyClass()
		);
	}

	@Override
	public Class<SealedEntityReferenceProxy> getRequestedStateContract() {
		return SealedEntityReferenceProxy.class;
	}

	@Override
	public List<MethodClassification<?, SealedEntityReferenceProxy>> getMethodClassification() {
		return METHOD_CLASSIFICATION;
	}

}
