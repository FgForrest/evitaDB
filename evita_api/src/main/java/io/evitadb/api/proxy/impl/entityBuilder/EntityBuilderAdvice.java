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
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import one.edee.oss.proxycian.MethodClassification;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.recipe.Advice;
import one.edee.oss.proxycian.util.ReflectionUtils;

import java.io.Serial;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

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
	 * List of all method classifications supported by this advice.
	 */
	@SuppressWarnings("unchecked")
	private static final List<MethodClassification<?, SealedEntityProxy>> METHOD_CLASSIFICATION = Arrays.asList(
		new MethodClassification[]{
			new PredicateMethodClassification<Object, Method, SealedEntityProxyState>(
				"getContract",
				(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, InstanceEditor.class) && "getContract".equals(method.getName()),
				(method, proxyState) -> method,
				(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getProxyClass()
			),
			new PredicateMethodClassification<Object, Method, SealedEntityProxyState>(
				"toMutation",
				(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, InstanceEditor.class) && "toMutation".equals(method.getName()),
				(method, proxyState) -> method,
				(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getEntityBuilderIfPresent()
					.flatMap(InstanceEditor::toMutation)
			),
			new PredicateMethodClassification<Object, Method, SealedEntityProxyState>(
				"toInstance",
				(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, InstanceEditor.class) && "toInstance".equals(method.getName()),
				(method, proxyState) -> method,
				(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getEntityBuilderIfPresent()
					.map(InstanceEditor::toInstance)
					.map(EntityContract.class::cast)
					.map(it -> (Object) proxyState.createNewNonCachedEntityProxy(proxyState.getProxyClass(), it))
					.orElse(proxy)
			),
			new PredicateMethodClassification<Object, Method, SealedEntityProxyState>(
				"upsertVia",
				(method, proxyState) -> ReflectionUtils.isMatchingMethodPresentOn(method, InstanceEditor.class) && "upsertVia".equals(method.getName()),
				(method, proxyState) -> method,
				(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.getEntityBuilderIfPresent()
					.flatMap(InstanceEditor::toMutation)
					.map(it -> ((EvitaSessionContract)args[0]).upsertEntity(it))
					.orElseGet(() -> new EntityReference(proxyState.getType(), proxyState.getPrimaryKey()))
			),
			SetAttributeMethodClassifier.INSTANCE,
			SetAssociatedDataMethodClassifier.INSTANCE,
			SetParentEntityMethodClassifier.INSTANCE/*,
			SetReferenceMethodClassifier.INSTANCE,
			SetPriceMethodClassifier.INSTANCE*/
		}
	);

	@Override
	public Class<SealedEntityProxy> getRequestedStateContract() {
		return SealedEntityProxy.class;
	}

	@Override
	public List<MethodClassification<?, SealedEntityProxy>> getMethodClassification() {
		return METHOD_CLASSIFICATION;
	}

}
