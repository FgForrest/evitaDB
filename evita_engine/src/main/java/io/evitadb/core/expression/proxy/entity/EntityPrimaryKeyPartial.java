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

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.core.expression.proxy.EntityProxyState;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.util.ReflectionUtils;

/**
 * Partial providing primary key method implementation for entity expression proxies.
 *
 * Contains classification for:
 * - `getPrimaryKey()` from {@link EntityContract} - returns the primary key from the entity body storage part
 */
public final class EntityPrimaryKeyPartial {

	/**
	 * Matches `getPrimaryKey()` declared on {@link EntityContract} and returns the primary key from the entity body
	 * storage part.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_PRIMARY_KEY =
		new PredicateMethodClassification<>(
			"getPrimaryKey",
			(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(
				method, EntityContract.class, "getPrimaryKey"
			),
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) ->
				proxyState.bodyPartOrThrowException().getPrimaryKey()
		);

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private EntityPrimaryKeyPartial() {
		// utility class
	}
}
