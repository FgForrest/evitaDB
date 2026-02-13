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

import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when a schema object cannot be found in persistent storage, indicating
 * probable data corruption or incomplete catalog initialization.
 *
 * Unlike most evitaDB exceptions, this extends {@link io.evitadb.exception.EvitaInternalError}
 * rather than {@link io.evitadb.exception.EvitaInvalidUsageException} because missing schemas in
 * persistent storage represent an internal consistency violation, not a client error.
 *
 * evitaDB persists schemas alongside entity data in catalog storage files. Every catalog must
 * have a catalog schema, and every entity collection must have an entity schema. These schemas
 * are fundamental metadata required for reading and interpreting stored data. If a schema is
 * missing during catalog loading or entity collection access, the database is in an inconsistent
 * state.
 *
 * This exception can occur in two scenarios:
 *
 * **1. Catalog schema missing**: The catalog-level schema is absent from persistent storage,
 * preventing the entire catalog from being loaded. This is a critical error requiring manual
 * intervention or restoration from backup.
 *
 * **2. Entity collection schema missing**: A specific entity collection's schema is absent,
 * preventing access to entities of that type. The catalog may remain partially functional for
 * other entity types.
 *
 * **Causes**:
 * - File system corruption or incomplete write operations
 * - Interrupted catalog initialization or migration
 * - Manual deletion or modification of storage files
 * - Storage backend failures during schema persistence
 *
 * **Resolution**: This typically requires restoring from backup or reinitializing the catalog.
 * Data recovery may be difficult if schemas are permanently lost.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SchemaNotFoundException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = 244210093979306431L;

	/**
	 * Constructs an exception indicating that the catalog schema is missing from persistent
	 * storage.
	 *
	 * @param catalogName the name of the catalog whose schema could not be found
	 */
	public SchemaNotFoundException(@Nonnull String catalogName) {
		super(
			"No schema found for catalog with name `" + catalogName +
				"` in persistent storage. The data are probably corrupted!"
		);
	}

	/**
	 * Constructs an exception indicating that an entity collection schema is missing from
	 * persistent storage.
	 *
	 * @param catalogName the name of the catalog containing the entity collection
	 * @param entityType  the type name of the entity collection whose schema could not be found
	 */
	public SchemaNotFoundException(@Nonnull String catalogName, @Nonnull String entityType) {
		super(
			"No schema found " +
				"for entity collection `" + entityType + "` " +
				"in catalog with name `" + catalogName + "` in persistent storage. The data are probably corrupted!"
		);
	}
}
