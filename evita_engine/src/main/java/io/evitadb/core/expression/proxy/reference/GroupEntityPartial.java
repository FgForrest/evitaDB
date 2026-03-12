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

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.core.expression.proxy.ReferenceProxyState;
import one.edee.oss.proxycian.PredicateMethodClassification;

import java.util.Optional;

/**
 * Partial providing the group entity method implementation for reference expression proxies.
 *
 * Contains a single classification for `getGroupEntity()` from {@link ReferenceContract} that returns
 * `Optional.ofNullable(proxyState.groupEntity())`.
 */
public final class GroupEntityPartial {

	/**
	 * Matches `getGroupEntity()` declared on {@link ReferenceContract} and returns the group entity wrapped in
	 * an {@link Optional}.
	 */
	public static final PredicateMethodClassification<Object, Void, ReferenceProxyState> GET_GROUP_ENTITY =
		new PredicateMethodClassification<>(
			"getGroupEntity",
			(method, proxyState) ->
				"getGroupEntity".equals(method.getName())
					&& method.getParameterCount() == 0,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) ->
				Optional.ofNullable(proxyState.groupEntity())
		);

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private GroupEntityPartial() {
		// utility class
	}
}
