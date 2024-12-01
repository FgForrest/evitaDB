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

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when there is attempt to filter by a hierarchy that is not marked as `indexed`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HierarchyNotIndexedException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -3219555612619986172L;

	public HierarchyNotIndexedException(@Nonnull EntitySchemaContract entitySchema) {
		super(
			"Entity `" + entitySchema.getName() + "` has not " +
				"indexed hierarchy and cannot be filtered by it. Filtering by without index would be slow."
		);
	}

	public HierarchyNotIndexedException(@Nonnull EntitySchemaContract entitySchema, @Nonnull Scope scope) {
		super(
			"Entity `" + entitySchema.getName() + "` has not " +
				"indexed hierarchy in scope `" + scope.name() + "` and cannot be filtered by it. Filtering by without index would be slow."
		);
	}

}