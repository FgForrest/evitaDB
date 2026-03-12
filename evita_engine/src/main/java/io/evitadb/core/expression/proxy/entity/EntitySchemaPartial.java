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

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.WithEntitySchema;
import io.evitadb.core.expression.proxy.EntityProxyState;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.util.ReflectionUtils;

/**
 * Partial providing schema-related method implementations for entity expression proxies.
 *
 * Contains classifications for:
 * - `getSchema()` from {@link WithEntitySchema} - returns the entity schema from the proxy state
 * - `getType()` from {@link EntityClassifier} - returns the schema name
 */
public final class EntitySchemaPartial {

	/**
	 * Matches `getSchema()` declared on {@link WithEntitySchema} and returns the entity schema from the proxy state.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_SCHEMA =
		new PredicateMethodClassification<>(
			"getSchema",
			(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(
				method, WithEntitySchema.class, "getSchema"
			),
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.schema()
		);

	/**
	 * Matches `getType()` declared on {@link EntityClassifier} and returns the entity schema name.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_TYPE =
		new PredicateMethodClassification<>(
			"getType",
			(method, proxyState) -> ReflectionUtils.isMethodDeclaredOn(
				method, EntityClassifier.class, "getType"
			),
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> proxyState.schema().getName()
		);

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private EntitySchemaPartial() {
		// utility class
	}
}
