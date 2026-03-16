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
import io.evitadb.api.requestResponse.data.structure.EntityReferenceWithParent;
import io.evitadb.core.expression.proxy.EntityProxyState;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import one.edee.oss.proxycian.PredicateMethodClassification;

import java.util.Optional;

/**
 * Partial providing parent-related method implementations for entity expression proxies.
 *
 * Contains classifications for:
 * - `parentAvailable()` from {@link EntityContract} - returns `true`
 * - `getParentEntity()` from {@link EntityClassifierWithParent} - returns an {@link EntityReferenceWithParent}
 *   when the entity has a parent, or `Optional.empty()` otherwise
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
	 * Matches `getParentEntity()` declared on {@link EntityClassifierWithParent}. Returns the
	 * nested parent entity proxy when available (for expressions that access parent attributes),
	 * or falls back to a lightweight {@link EntityReferenceWithParent} for expressions that only
	 * check parent existence.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_PARENT_ENTITY =
		new PredicateMethodClassification<>(
			"getParentEntity",
			(method, proxyState) ->
				"getParentEntity".equals(method.getName())
					&& method.getParameterCount() == 0,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				// prefer nested parent entity proxy when available (supports attribute access)
				if (proxyState.parentEntity() != null) {
					return Optional.of(proxyState.parentEntity());
				}
				// fallback: lightweight reference for existence-only checks
				final EntityBodyStoragePart body = proxyState.bodyPartOrThrowException();
				final Integer parentPk = body.getParent();
				if (parentPk == null) {
					return Optional.empty();
				}
				return Optional.of(
					new EntityReferenceWithParent(
						proxyState.schema().getName(), parentPk, null
					)
				);
			}
		);

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private EntityParentPartial() {
		// utility class
	}
}
