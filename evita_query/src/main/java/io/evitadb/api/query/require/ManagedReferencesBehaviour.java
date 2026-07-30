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
 *   enumerate both resolved and unresolved references. Note that specifying a `filterBy` in
 *   `referenceContent` implies the existence check even in this mode — a target that is not
 *   present cannot be matched by it.
 * - `EXISTING` — only references whose target entity is present in the database at query time are returned; dangling
 *   references are silently suppressed as if they did not exist. This is the correct choice for most read-side
 *   queries where incomplete data should not leak to the API consumer.
 *
 * Under `EXISTING` mere presence is not enough — the target entity must also pass the checks
 * that apply to it. Both of the following sources are honoured:
 *
 * 1. the `filterBy` specified directly in the `referenceContent` requirement, and
 * 2. the required locale, which is taken from an `entityLocaleEquals` inside that `filterBy`
 *    when one is present, and is inherited from the enveloping query otherwise.
 *
 * A target entity that does not pass these checks is assumed to be non-existing for the sake of
 * this mode and its reference is suppressed exactly like a dangling one. In particular, an entity
 * that exists but holds no data in the required locale is never returned as a reference without
 * a body — it is omitted entirely. The suppression applies regardless of whether the referenced
 * body was requested, so reference visibility never depends on whether the caller asked for
 * a body. The exemption from the locale check is driven by the schema, not by the individual
 * entity: targets whose schema declares no localized data at all are never suppressed by it,
 * whereas within a localized schema even a target holding no localized data whatsoever is
 * suppressed, because it cannot be fetched in the required locale either.
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
	 * The reference to managed entity will be returned only if the target entity exists in the
	 * database and passes the checks that apply to it - the `filterBy` specified in
	 * `referenceContent` and the required locale, which is inherited from the enveloping query
	 * unless the `filterBy` states its own. A target that does not pass them is assumed to be
	 * non-existing - see the class-level documentation for the exact rules.
	 */
	EXISTING

}
