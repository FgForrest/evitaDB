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

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when a client attempts to entirely replace an existing
 * {@link io.evitadb.api.requestResponse.schema.EntitySchema} rather than incrementally modifying
 * it through the schema builder API.
 *
 * evitaDB does not support wholesale schema replacement or automatic schema difference analysis
 * for entity collections that already have a defined schema. Once a schema is established, all
 * modifications must be performed incrementally using the schema builder pattern.
 *
 * This exception typically occurs when:
 *
 * - A client calls a method intended for initial schema creation on a collection that already
 * has a schema
 * - Attempting to redefine a schema from scratch when an evolved version already exists
 *
 * **Resolution**: Instead of trying to replace the schema, use the
 * {@link io.evitadb.api.EvitaSessionContract#defineEntitySchema(String)} method to obtain a
 * schema builder, then apply incremental modifications through builder methods such as
 * `withAttribute()`, `withReference()`, etc. This approach ensures safe schema evolution without
 * data loss.
 *
 * This design prevents accidental schema overwrites and ensures that schema changes are explicit,
 * trackable, and compatible with existing data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SchemaAlreadyPresentException extends SchemaAlteringException {
	@Serial private static final long serialVersionUID = 3556383243388987300L;

	/**
	 * Constructs a new exception indicating that a schema already exists for the entity collection
	 * and cannot be replaced.
	 *
	 * @param entityType the type name of the entity collection that already has a schema defined
	 */
	public SchemaAlreadyPresentException(@Nonnull String entityType) {
		super(
			"Schema for entity collection `" + entityType + "` is already defined, use `defineSchema()` method and " +
				"alter it via returned builder."
		);
	}
}
