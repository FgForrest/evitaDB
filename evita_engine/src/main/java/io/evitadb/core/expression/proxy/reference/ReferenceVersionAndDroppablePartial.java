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

package io.evitadb.core.expression.proxy.reference;

import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.core.expression.proxy.ReferenceProxyState;
import one.edee.oss.proxycian.PredicateMethodClassification;

/**
 * Always-included partial providing version and droppable method implementations for reference expression proxies.
 *
 * Contains classifications for:
 * - `version()` from {@link Versioned} - returns the version from the proxy state
 * - `dropped()` from {@link Droppable} - always returns `false` since expression evaluation only works with active
 *   references
 */
public final class ReferenceVersionAndDroppablePartial {

	/**
	 * Matches `version()` declared on {@link Versioned} and returns the version from the proxy state.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState> VERSION =
		new PredicateMethodClassification<>(
			"version",
			(method, proxyState) ->
				"version".equals(method.getName())
					&& method.getParameterCount() == 0,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.version()
		);

	/**
	 * Matches `dropped()` declared on {@link Droppable} and always returns `false` since expression evaluation only
	 * works with active (non-dropped) references.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState> DROPPED =
		new PredicateMethodClassification<>(
			"dropped",
			(method, proxyState) ->
				"dropped".equals(method.getName())
					&& method.getParameterCount() == 0,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> false
		);

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private ReferenceVersionAndDroppablePartial() {
		// utility class
	}
}
