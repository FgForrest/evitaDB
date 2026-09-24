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

package io.evitadb.core.session;


import lombok.Getter;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
	/**
	 * Concurrent, and it has to be: three different threads reach this census. The drain's forced-close lambda
	 * ({@code SessionRegistry#closeAllActiveSessionsAndSuspend}) is postponed by {@code EvitaSessionProxy} and then
	 * runs **on the session's own thread** when its in-flight method returns, so quiescing a catalog holding many
	 * sessions - shutdown, deactivate, drop, rename - adds from several threads at once;
	 * {@code EvitaSession#goLiveAndCloseWithProgress} adds from the go-live caller's thread; and
	 * {@code Evita#wasSessionForcefullyClosedForCatalog} reads it from request threads. An unsynchronised
	 * {@link java.util.HashSet} loses ids under that - and a lost id makes a client whose session was taken away
	 * hear "you closed it yourself" - as well as being able to wedge a writer inside a resizing map outright.
	 *
	 * **CALIBRATION - `LongRunningSuspensionInformationConcurrencyTest` is what proves this**, and its
	 * counterfactual is a plain {@link java.util.HashSet} here: with one, 8 threads adding 200 distinct ids each
	 * lost at least one of them in 436 rounds out of 1 000, the first loss landing in round 0. Making an
	 * {@link #addForcefullyClosedSession(java.util.UUID)} CHEAPER narrows that window as effectively as making it
	 * safer widens it, so a sweep that stops observing losses on its counterfactual has gone blunt rather than
	 * been fixed - re-measure before believing it. Run it with:
	 *
	 * ```
	 * mvn -pl evita_test/evita_functional_tests,evita_test/evita_long_running_tests test -P longRunning \
	 *     -Dtest=LongRunningSuspensionInformationConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false
	 * ```
	 */
	@Nonnull private final Set<UUID> forcefullyClosedSessions;

	public SuspensionInformation(int expectedCount) {
		this.suspensionDateTime = OffsetDateTime.now();
		this.forcefullyClosedSessions = ConcurrentHashMap.newKeySet(expectedCount);
	}

	/**
	 * Registers a session ID as forcefully closed during the suspension operation.
	 *
	 * @param sessionId the unique identifier of the session that was forcefully closed.
	 */
	public void addForcefullyClosedSession(@Nonnull UUID sessionId) {
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
