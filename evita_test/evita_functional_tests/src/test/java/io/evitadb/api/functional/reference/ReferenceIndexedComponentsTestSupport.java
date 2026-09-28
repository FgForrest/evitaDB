/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.functional.reference;

import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Shared helper interface for reference indexed-component tests. Provides a static utility method
 * commonly needed across tests that assert which components a reference indexes in which scope.
 *
 * Follows the same pattern as {@link io.evitadb.test.EvitaTestSupport}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface ReferenceIndexedComponentsTestSupport {

	/**
	 * Renders the reference's indexed components per indexed scope, scopes and components both in a stable order so
	 * the expectation can be written literally. Scopes the reference is not indexed in are omitted.
	 *
	 * @param reference the reference schema to describe
	 * @return one `SCOPE=[COMPONENT, ...]` group per indexed scope, space separated
	 */
	@Nonnull
	static String describe(@Nonnull ReferenceSchemaContract reference) {
		final StringBuilder result = new StringBuilder(64);
		for (Scope scope : Scope.values()) {
			if (!reference.isIndexedInScope(scope)) {
				continue;
			}
			if (!result.isEmpty()) {
				result.append(' ');
			}
			final Set<ReferenceIndexedComponents> components = reference.getIndexedComponents(scope);
			result.append(scope.name()).append('=').append(components.stream().map(Enum::name).sorted().toList());
		}
		return result.toString();
	}

}
