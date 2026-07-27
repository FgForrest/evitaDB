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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;

import static io.evitadb.dataType.Scope.DEFAULT_SCOPE;

/**
 * The ScopedReferenceIndexedComponents class encapsulates the relationship between
 * a reference's indexed components and the scope in which these indexing characteristics
 * are enforced.
 *
 * It makes use of two parameters:
 * - scope: Defines the context or domain (live or archived) where the reference resides.
 * - indexedComponents: Determines which reference components (entity, group entity, or both)
 *   are indexed in the given scope.
 *
 * The combination of these parameters allows for scoped indexed-component configuration
 * within reference schemas, providing fine-grained control over which parts of a reference
 * relationship are queryable in each scope.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ScopedReferenceIndexedComponents(
	@Nonnull Scope scope,
	@Nonnull ReferenceIndexedComponents[] indexedComponents
) implements Serializable {
	/**
	 * Reusable empty array constant to avoid repeated zero-length array allocations.
	 */
	public static final ScopedReferenceIndexedComponents[] EMPTY = new ScopedReferenceIndexedComponents[0];
	/**
	 * Default configuration for reference indexed components when no explicit configuration is provided.
	 * By default, references are indexed in the default scope with the default indexed components.
	 */
	public static final ScopedReferenceIndexedComponents[] DEFAULT = new ScopedReferenceIndexedComponents[] {
		new ScopedReferenceIndexedComponents(DEFAULT_SCOPE, ReferenceIndexedComponents.DEFAULT_INDEXED_COMPONENTS)
	};

	/**
	 * Compact constructor that validates neither `scope` nor `indexedComponents` is null.
	 */
	public ScopedReferenceIndexedComponents {
		Assert.notNull(scope, "Scope must not be null");
		Assert.notNull(indexedComponents, "Indexed components must not be null");
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ScopedReferenceIndexedComponents that)) return false;
		return this.scope == that.scope && Arrays.equals(this.indexedComponents, that.indexedComponents);
	}

	@Override
	public int hashCode() {
		int result = this.scope.hashCode();
		result = 31 * result + Arrays.hashCode(this.indexedComponents);
		return result;
	}

	@Nonnull
	@Override
	public String toString() {
		return "ScopedReferenceIndexedComponents[" +
			"scope=" + this.scope +
			", indexedComponents=" + Arrays.toString(this.indexedComponents) +
			']';
	}

}
