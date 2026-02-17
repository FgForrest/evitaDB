/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.query.require;

import io.evitadb.dataType.SupportedEnum;

/**
 * Controls how {@link ReferenceContent} handles references that point to *managed* entity collections — i.e.
 * references whose target entity type is tracked within evitaDB itself (as opposed to references to external systems).
 *
 * In evitaDB it is valid to store a reference to an entity that does not yet exist in the database (e.g. during
 * bulk import, or when referencing entities from another collection that has not been fully populated). This enum
 * determines whether such "dangling" references are visible to the caller:
 *
 * - `ANY` — all references are returned regardless of whether the target entity currently exists in the database.
 *   This is the default behaviour and is appropriate during data ingestion or when the caller explicitly wants to
 *   enumerate both resolved and unresolved references.
 * - `EXISTING` — only references whose target entity is present in the database at query time are returned; dangling
 *   references are silently suppressed as if they did not exist. This is the correct choice for most read-side
 *   queries where incomplete data should not leak to the API consumer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@SupportedEnum
public enum ManagedReferencesBehaviour {

	/**
	 * The reference to managed entity will always be returned regardless of the target entity existence.
	 */
	ANY,
	/**
	 * The reference to managed entity will be returned only if the target entity exists in the database.
	 */
	EXISTING

}
