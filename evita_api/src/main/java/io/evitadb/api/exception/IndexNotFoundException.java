/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import javax.annotation.Nullable;
import java.io.Serial;

/**
 * Raised when the requested owner holds no index under the requested primary key.
 *
 * **This is the ordinary outcome of a race, not necessarily a client mistake.** An index primary key is obtained from
 * an index browse, and an owner's index set moves with its data: the index a page described can be gone by the time
 * the caller drills into it - a reference index is reclaimed once nothing references its target any more. Answering
 * with an empty response instead would be indistinguishable from an index that holds nothing, which is the one reading
 * an operator investigating index growth must not be given wrongly.
 *
 * **What it can never mean is "you reached a different index".** A collection assigns index primary keys from a
 * forward-only sequence whose high-water mark is persisted, so a key belonging to a removed index is never handed to
 * a later one. The catalog derives its own handles from the index's scope, so they denote one logical index for the
 * life of the catalog whether or not it currently exists - the `ARCHIVED` catalog index is created lazily, so its
 * handle can fail here and start resolving later. Either way a stale key fails rather than silently resolving to
 * something else.
 *
 * **Remote callers see the message, not this type.** The gRPC driver rebuilds every business failure as a plain
 * {@link EvitaInvalidUsageException} carrying the server's error code, so a client of `EvitaClient` catches the base
 * type and reads the message - as it does for every other exception declared on the management contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class IndexNotFoundException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 3095473827441055831L;

	/**
	 * Creates a new exception for an index primary key its owner does not hold.
	 *
	 * @param entityType      name of the entity collection that was asked, or null when the catalog itself was
	 * @param indexPrimaryKey primary key of the index that was not found
	 */
	public IndexNotFoundException(@Nullable String entityType, int indexPrimaryKey) {
		super(
			entityType == null ?
				"The catalog holds no index with primary key `" + indexPrimaryKey + "`. A catalog index is created " +
					"lazily per scope, so this handle may simply denote a scope nothing globally unique has been " +
					"written into yet; it will not come to denote another index." :
				"Entity collection `" + entityType + "` holds no index with primary key `" + indexPrimaryKey +
					"`. It may have been removed since it was browsed; index primary keys are never reused, so this " +
					"key will not come to denote another index."
		);
	}

}
