/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core;


import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * This record encapsulates information related to a suspension operation
 * within the SessionRegistry.
 *
 * The suspension operation holds data regarding:
 * - The time at which the suspension occurred.
 * - A set of sessions (identified by their unique IDs) that were
 * forcefully terminated as part of the suspension process.
 */
public class SuspensionInformation {
	@Nonnull @Getter private final OffsetDateTime suspensionDateTime;
	@Nonnull private final Set<UUID> forcefullyClosedSessions;

	public SuspensionInformation(int expectedCount) {
		this.suspensionDateTime = OffsetDateTime.now();
		this.forcefullyClosedSessions = CollectionUtils.createHashSet(expectedCount);
	}

	/**
	 * Registers a session ID as forcefully closed during the suspension operation.
	 *
	 * @param sessionId the unique identifier of the session that was forcefully closed.
	 */
	void addForcefullyClosedSession(@Nonnull UUID sessionId) {
		this.forcefullyClosedSessions.add(sessionId);
	}

	/**
	 * Checks whether the specified session ID is present in the set of forcefully closed sessions.
	 *
	 * @param sessionId the unique identifier of the session to check. Must not be null.
	 * @return {@code true} if the session ID is present in the set of forcefully closed sessions;
	 * {@code false} otherwise.
	 */
	public boolean contains(@Nonnull UUID sessionId) {
		return this.forcefullyClosedSessions.contains(sessionId);
	}

}
