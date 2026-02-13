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

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Arrays;

/**
 * Exception thrown when an operation requires one or more entities that do not exist in the database.
 *
 * This exception is raised when evitaDB attempts to retrieve, modify, or reference entities by their primary keys,
 * but the specified entities cannot be found in the target entity collection. Common scenarios include:
 *
 * - **Entity mutations** attempting to update or delete non-existent entities
 * - **Reference validation** during entity persistence when referenced entities don't exist (especially for reflected
 *   references that require the main reference to already exist)
 * - **Storage layer operations** failing to retrieve entity data that indexes claim should exist (data consistency issue)
 * - **Scope changes** attempting to archive or restore entities that cannot be found in expected scope
 *
 * **When this exception occurs:**
 *
 * - Upserting entity mutations when the entity-to-update doesn't exist
 * - Deleting entities by primary key when they're not present in the collection
 * - Setting up reflected references when the main entity reference doesn't exist yet
 * - Storage persistence operations detecting missing entity data during read operations
 *
 * The exception may indicate either a client error (operating on wrong primary keys) or a data consistency issue
 * (index references entity that storage layer cannot retrieve).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityMissingException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 7500395432304631043L;

	/**
	 * Creates exception identifying the missing entity or entities.
	 *
	 * @param entityType the entity collection name
	 * @param entityPrimaryKey array of primary keys for the missing entities
	 * @param additionalMessage optional context explaining why the entity was expected to exist, or null
	 */
	public EntityMissingException(@Nonnull String entityType, int[] entityPrimaryKey, @Nullable String additionalMessage) {
		super(
			entityPrimaryKey.length == 1 ?
				"Entity `" + entityType + "` with primary key " + entityPrimaryKey[0] +
				" are not currently present in the database." +
				(additionalMessage != null ? " " + additionalMessage : "") :
				"Entities of type `" + entityType + "` with primary keys: " + Arrays.toString(entityPrimaryKey) +
				" are not currently present in the database." +
				(additionalMessage != null ? " " + additionalMessage : "")
		);
	}
}
