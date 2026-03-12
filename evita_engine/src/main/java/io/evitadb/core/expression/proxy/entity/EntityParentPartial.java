/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.expression.proxy.entity;

import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.core.expression.proxy.EntityProxyState;
import one.edee.oss.proxycian.PredicateMethodClassification;

import java.util.Optional;

/**
 * Partial providing parent-related method implementations for entity expression proxies.
 *
 * Contains classifications for:
 * - `parentAvailable()` from {@link EntityContract} - returns `true`
 * - `getParentEntity()` from {@link EntityClassifierWithParent} - returns `Optional.empty()` since parent entity fetch
 *   is not needed for expression evaluation
 */
public final class EntityParentPartial {

	/**
	 * Matches `parentAvailable()` declared on {@link EntityContract} and always returns `true` since the proxy is
	 * constructed with the required parent data.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> PARENT_AVAILABLE =
		new PredicateMethodClassification<>(
			"parentAvailable",
			(method, proxyState) ->
				"parentAvailable".equals(method.getName())
					&& method.getParameterCount() == 0,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> true
		);

	/**
	 * Matches `getParentEntity()` declared on {@link EntityClassifierWithParent} and returns `Optional.empty()` since
	 * expression evaluation does not need to traverse the parent entity hierarchy.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_PARENT_ENTITY =
		new PredicateMethodClassification<>(
			"getParentEntity",
			(method, proxyState) ->
				"getParentEntity".equals(method.getName())
					&& method.getParameterCount() == 0,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> Optional.empty()
		);

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private EntityParentPartial() {
		// utility class
	}
}
