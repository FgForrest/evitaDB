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
import java.io.Serial;

/**
 * Exception thrown when attempting to enrich an entity reference that points to an entity
 * that has been deleted from the database.
 *
 * Entity enrichment is a lazy-loading mechanism where a lightweight entity reference (with
 * minimal data) is loaded first, then enriched on-demand with additional data like
 * attributes, references, or associated data. This exception occurs when the enrichment
 * operation discovers that the target entity has been deleted after the reference was
 * initially loaded.
 *
 * **Typical Causes:**
 * - Concurrent deletion: another session deleted the entity between loading the reference
 *   and attempting enrichment
 * - Stale reference: the application cached an entity reference, then the entity was
 *   deleted, and later the cached reference was enriched
 * - Long-running transaction: entity was deleted by a committed transaction while this
 *   transaction was still in progress
 *
 * **Resolution:**
 * - Handle this exception gracefully as a normal concurrency scenario
 * - Refresh your entity query to get current data
 * - Consider whether your application logic should treat deleted entities differently
 *   (e.g., skip enrichment or show "entity not found")
 *
 * **Design Note:**
 * This exception is specific to the enrichment flow and indicates a race condition rather
 * than a logic error. Unlike `CollectionNotFoundException` or `CatalogNotFoundException`,
 * this represents a timing issue rather than incorrect API usage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityAlreadyRemovedException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 5637899977815405714L;

	/**
	 * Creates a new exception for an entity that was deleted before enrichment could
	 * complete.
	 *
	 * @param entityType       type of the entity that was removed
	 * @param entityPrimaryKey primary key of the entity that was removed
	 */
	public EntityAlreadyRemovedException(@Nonnull String entityType, int entityPrimaryKey) {
		super(
			"Entity `" + entityType + "` with primary key `" + entityPrimaryKey +
				"` cannot be enriched because it has been already removed."
		);
	}
}
