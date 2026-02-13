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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when concurrent schema modifications create a version conflict, similar
 * to optimistic locking conflicts in databases.
 *
 * evitaDB uses versioned schemas to detect concurrent modifications. Each schema change
 * increments the version number. When two sessions concurrently modify the same schema,
 * the first commit succeeds, but the second fails with this exception because its base
 * version is outdated.
 *
 * **Typical Causes:**
 * - Two sessions concurrently modifying catalog schema or entity type schema
 * - Schema cached in application code becomes stale during long-running operations
 * - Parallel threads or services attempting schema evolution simultaneously
 *
 * **Resolution:**
 * Retry the schema modification operation based on the current (latest) schema version.
 * Fetch the fresh schema, reapply your intended changes, and commit again. This follows
 * the optimistic locking pattern: read-modify-write with retry on conflict.
 *
 * **Design Note:**
 * Schema modifications are relatively rare compared to data operations, so optimistic
 * locking provides better overall throughput than pessimistic locking would. Most schema
 * conflicts resolve successfully on retry.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ConcurrentSchemaUpdateException extends SchemaAlteringException {
	@Serial private static final long serialVersionUID = 1176617132327079985L;

	/**
	 * Creates a new exception for a catalog schema version conflict.
	 *
	 * @param currentSchema the current (latest) catalog schema in the database
	 * @param newSchema     the schema version being submitted (now outdated)
	 */
	public ConcurrentSchemaUpdateException(@Nonnull CatalogSchemaContract currentSchema, @Nonnull CatalogSchemaContract newSchema) {
		super(
			"Cannot update catalog schema `" + currentSchema.getName() + "` - someone else altered the schema in the meanwhile (current version is " +
				currentSchema.version() + ", yours is " + newSchema.version() + ")."
		);
	}

	/**
	 * Creates a new exception for an entity schema version conflict.
	 *
	 * @param currentSchema the current (latest) entity schema in the database
	 * @param newSchema     the schema version being submitted (now outdated)
	 */
	public ConcurrentSchemaUpdateException(@Nonnull EntitySchemaContract currentSchema, @Nonnull EntitySchemaContract newSchema) {
		super(
			"Cannot update entity schema `" + currentSchema.getName() + "` - someone else altered the schema in the meanwhile (current version is " +
				currentSchema.version() + ", yours is " + newSchema.version() + ")."
		);
	}

}
