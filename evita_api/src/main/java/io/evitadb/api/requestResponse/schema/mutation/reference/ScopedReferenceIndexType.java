/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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


import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * The ScopedReferenceIndexType class encapsulates the relationship between a reference's
 * index type and the scope in which this indexing characteristic is enforced.
 *
 * It makes use of two parameters:
 * - scope: Defines the context or domain (live or archived) where the reference resides.
 * - indexType: Determines the indexing level (e.g., none, for filtering, or for filtering and partitioning).
 *
 * The combination of these parameters allows for scoped indexing configuration within reference schemas,
 * providing fine-grained control over reference indexing based on the entity's scope.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record ScopedReferenceIndexType(
	@Nonnull Scope scope,
	@Nonnull ReferenceIndexType indexType
) implements Serializable {
	public static final ScopedReferenceIndexType[] EMPTY = new ScopedReferenceIndexType[0];

	public ScopedReferenceIndexType {
		Assert.notNull(scope, "Scope must not be null");
		Assert.notNull(indexType, "Index type must not be null");
	}

}
