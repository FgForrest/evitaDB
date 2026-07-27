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

import io.evitadb.api.requestResponse.data.ReferenceAvailabilityChecker;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferencesContract;
import io.evitadb.core.expression.proxy.EntityProxyState;
import one.edee.oss.proxycian.PredicateMethodClassification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Partial providing reference-related method implementations for entity expression proxies.
 *
 * Contains classifications for:
 * - `getReferences(String)` from {@link ReferencesContract} - filters references by name
 * - `getReference(String, int)` from {@link ReferencesContract} - finds reference by name and primary key
 * - `getReferences()` from {@link ReferencesContract} - returns all references
 * - `referencesAvailable()` from {@link ReferenceAvailabilityChecker} - returns `true`
 */
public final class EntityReferencesPartial {

	/**
	 * Matches `getReferences(String)` declared on {@link ReferencesContract} with exactly one String parameter.
	 * Returns collection of references for the given reference name using O(1) map lookup.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_REFERENCES_BY_NAME =
		new PredicateMethodClassification<>(
			"getReferences(String)",
			(method, proxyState) ->
				"getReferences".equals(method.getName())
					&& method.getParameterCount() == 1
					&& method.getParameterTypes()[0] == String.class,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				final String name = (String) args[0];
				final Map<String, List<ReferenceContract>> refsByName = proxyState.referencesByName();
				if (refsByName == null) {
					return Collections.emptyList();
				}
				return refsByName.getOrDefault(name, Collections.emptyList());
			}
		);

	/**
	 * Matches `getReference(String, int)` declared on {@link ReferencesContract}. Returns an Optional containing the
	 * reference matching the given name and primary key. Uses pre-indexed map for O(1) name lookup, then scans the
	 * (typically small) per-name list for the matching primary key.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_REFERENCE =
		new PredicateMethodClassification<>(
			"getReference(String, int)",
			(method, proxyState) ->
				"getReference".equals(method.getName())
					&& method.getParameterCount() == 2
					&& method.getParameterTypes()[0] == String.class
					&& method.getParameterTypes()[1] == int.class,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				final String name = (String) args[0];
				final int pk = (int) args[1];
				final Map<String, List<ReferenceContract>> refsByName = proxyState.referencesByName();
				if (refsByName == null) {
					return Optional.empty();
				}
				final List<ReferenceContract> refs = refsByName.get(name);
				if (refs == null) {
					return Optional.empty();
				}
				for (int i = 0; i < refs.size(); i++) {
					if (refs.get(i).getReferencedPrimaryKey() == pk) {
						return Optional.of(refs.get(i));
					}
				}
				return Optional.empty();
			}
		);

	/**
	 * Matches `getReferences()` declared on {@link ReferencesContract} with no parameters. Returns the full collection
	 * of all references by flattening the pre-indexed map values.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> GET_ALL_REFERENCES =
		new PredicateMethodClassification<>(
			"getReferences()",
			(method, proxyState) ->
				"getReferences".equals(method.getName())
					&& method.getParameterCount() == 0,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> {
				final Map<String, List<ReferenceContract>> refsByName = proxyState.referencesByName();
				if (refsByName == null || refsByName.isEmpty()) {
					return Collections.emptyList();
				}
				final Collection<List<ReferenceContract>> groups = refsByName.values();
				if (groups.size() == 1) {
					// fast path: single reference name — no copying needed
					return groups.iterator().next();
				}
				int totalSize = 0;
				for (final List<ReferenceContract> group : groups) {
					totalSize += group.size();
				}
				final ArrayList<ReferenceContract> result = new ArrayList<>(totalSize);
				for (final List<ReferenceContract> group : groups) {
					result.addAll(group);
				}
				return Collections.unmodifiableList(result);
			}
		);

	/**
	 * Matches `referencesAvailable()` declared on {@link ReferenceAvailabilityChecker} and always returns `true`
	 * since the proxy is constructed with the required reference data.
	 */
	public static final PredicateMethodClassification<Object, Void, EntityProxyState> REFERENCES_AVAILABLE =
		new PredicateMethodClassification<>(
			"referencesAvailable",
			(method, proxyState) ->
				"referencesAvailable".equals(method.getName())
					&& method.getParameterCount() == 0,
			(method, state) -> null,
			(proxy, method, args, methodContext, proxyState, invokeSuper) -> true
		);

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private EntityReferencesPartial() {
		// utility class
	}
}
