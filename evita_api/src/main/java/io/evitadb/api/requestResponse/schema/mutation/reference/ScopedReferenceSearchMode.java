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

import io.evitadb.api.requestResponse.schema.ReferenceSearchMode;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Declares a reference search mode in one scope.
 *
 * @param scope scope where the reference is searched through
 * @param mode how the referenced text participates in search
 */
public record ScopedReferenceSearchMode(
	@Nonnull Scope scope,
	@Nonnull ReferenceSearchMode mode
) implements Serializable {
	/** Empty declaration of searchable scopes. */
	public static final ScopedReferenceSearchMode[] EMPTY = new ScopedReferenceSearchMode[0];

	/** Validates the non-null record components. */
	public ScopedReferenceSearchMode {
		Assert.notNull(scope, "Scope must not be null");
		Assert.notNull(mode, "Search mode must not be null");
	}
}
