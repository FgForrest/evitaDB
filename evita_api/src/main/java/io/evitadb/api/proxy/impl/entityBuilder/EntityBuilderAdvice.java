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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.SealedInstance;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import one.edee.oss.proxycian.MethodClassification;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.recipe.Advice;
import one.edee.oss.proxycian.util.ReflectionUtils;
import one.edee.oss.proxycian.utils.GenericsUtils;
import one.edee.oss.proxycian.utils.GenericsUtils.GenericBundle;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Advice allowing to implement all supported abstract methods on proxy wrapping {@link InstanceEditor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class EntityBuilderAdvice implements Advice<SealedEntityProxy> {
	@Serial private static final long serialVersionUID = -4131476723105030817L;
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final EntityBuilderAdvice INSTANCE = new EntityBuilderAdvice();
	/**
	 * Contains set of method keywords which signalize the start of the removal operation.
	 */
	/* todo jno - toto odstranit a předělat na anotaci */
	public static final Set<String> REMOVAL_KEYWORDS = Set.of(
		"remove", "delete", "drop"
	);
	/**
	 * List of all method classifications supported by this advice.
	 */
	@SuppressWarnings("unchecked")
	private static final List<MethodClassification<?, SealedEntityProxy>> METHOD_CLASSIFICATION = Arrays.asList(
		new MethodClassification[]{
			getContractMethodClassification(),
			toMutationMethodClassification(),
			toInstanceMethodClassification(),
			upsertViaMethodClassification(),
			upsertDeeplyViaMethodClassification(),
			openForWriteMethodClassification(),
			SetAttributeMethodClassifier.INSTANCE,
			SetAssociatedDataMethodClassifier.INSTANCE,
			SetParentEntityMethodClassifier.INSTANCE,
			SetPriceMethodClassifier.INSTANCE,
			SetReferenceMethodClassifier.INSTANCE
		}
	);

	@Nonnull
	private static PredicateMethodClassification<Object, Class<?>, SealedEntityProxyState> openForWriteMethodClassification() {
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
				proxyState.createEntityBuilderProxy(
					methodContext, proxyState.getEntity(), proxyState.getReferencedEntitySchemas()
				)
		);
	}

	@Nonnull
	private static PredicateMethodClassification<Object, Method, SealedEntityProxyState> upsertViaMethodClassification() {
		return new PredicateMethodClassification<>(
			"upsertVia",
			(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, InstanceEditor.class) && "upsertVia".equals(method.getName()),
			(method, proxyState) -> method,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				proxyState.propagateReferenceMutations();
				final EntityReference entityReference = proxyState.getEntityBuilderIfPresent()
					.flatMap(InstanceEditor::toMutation)
					.map(it -> ((EvitaSessionContract) args[0]).upsertEntity(it))
					.orElseGet(() -> new EntityReference(proxyState.getType(), proxyState.getPrimaryKey()));
				proxyState.setEntityReference(entityReference);
				return entityReference;
			}
		);
	}

	@Nonnull
	private static PredicateMethodClassification<Object, Method, SealedEntityProxyState> upsertDeeplyViaMethodClassification() {
		return new PredicateMethodClassification<>(
			"upsertDeeplyVia",
			(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, InstanceEditor.class) && "upsertDeeplyVia".equals(method.getName()),
			(method, proxyState) -> method,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				proxyState.propagateReferenceMutations();
				return ((EvitaSessionContract)args[0]).upsertEntityDeeply((Serializable) proxy);
			}
		);
	}

	@Nonnull
	private static PredicateMethodClassification<Object, Method, SealedEntityProxyState> toInstanceMethodClassification() {
		return new PredicateMethodClassification<>(
			"toInstance",
			(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, InstanceEditor.class) && "toInstance".equals(method.getName()),
			(method, proxyState) -> method,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				proxyState.propagateReferenceMutations();
				return proxyState.getEntityBuilderIfPresent()
					.map(InstanceEditor::toInstance)
					.map(EntityContract.class::cast)
					.map(proxyState::createNewNonCachedClone)
					.orElse(proxy);
			}
		);
	}

	@Nonnull
	private static PredicateMethodClassification<Object, Method, SealedEntityProxyState> toMutationMethodClassification() {
		return new PredicateMethodClassification<>(
			"toMutation",
			(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, InstanceEditor.class) && "toMutation".equals(method.getName()),
			(method, proxyState) -> method,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				proxyState.propagateReferenceMutations();
				return proxyState.getEntityBuilderIfPresent()
					.flatMap(InstanceEditor::toMutation);
			}
		);
	}

	@Nonnull
	private static PredicateMethodClassification<Object, Method, SealedEntityProxyState> getContractMethodClassification() {
		return new PredicateMethodClassification<>(
			"getContract",
			(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, InstanceEditor.class) && "getContract".equals(method.getName()),
			(method, proxyState) -> method,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getProxyClass()
		);
	}

	@Override
	public Class<SealedEntityProxy> getRequestedStateContract() {
		return SealedEntityProxy.class;
	}

	@Override
	public List<MethodClassification<?, SealedEntityProxy>> getMethodClassification() {
		return METHOD_CLASSIFICATION;
	}

}
