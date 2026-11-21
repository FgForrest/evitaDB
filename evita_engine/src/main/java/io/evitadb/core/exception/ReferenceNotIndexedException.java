/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.exception;

import io.evitadb.annotation.Internal;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Reports filtering or ordering by a reference that is missing its index in a requested scope.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceNotIndexedException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -5348222841886242557L;

	/**
	 * Reports a missing index for reference filtering.
	 *
	 * @param referenceName name of the reference
	 * @param entitySchema schema of the entity owning the reference
	 * @param scope scope lacking the index
	 */
	public ReferenceNotIndexedException(
		@Nonnull String referenceName, @Nonnull EntitySchemaContract entitySchema, @Nonnull Scope scope
	) {
		this(referenceName, entitySchema, scope, false);
	}

	/**
	 * Reports a missing index for the requested reference operation.
	 *
	 * @param referenceName name of the reference
	 * @param entitySchema schema of the entity owning the reference
	 * @param scope scope lacking the index
	 * @param ordering whether the index is required for ordering
	 */
	@Internal("used by reference attribute schema validation")
	public ReferenceNotIndexedException(
		@Nonnull String referenceName,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Scope scope,
		boolean ordering
	) {
		super(
			"Reference with name `" + referenceName + "` in entity `" + entitySchema.getName() + "` is not " +
				"indexed in scope `" + scope.name() + "` and cannot be " +
				(ordering
					? "ordered by. Ordering without an index would be slow."
					: "filtered by. Filtering by without index would be slow.")
		);
	}
}
