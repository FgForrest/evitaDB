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

package io.evitadb.store.traffic.serializer;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Provides a context for binding a session sequence order to the current thread
 * using a thread-local variable. This enables thread-safe access to a session-specific sequence order
 * across different parts of the application.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class CurrentSessionRecordContext {
	private static final ThreadLocal<SessionRecordContext> SESSION_SEQUENCE_ORDER = new ThreadLocal<>();

	/**
	 * Executes a provided fetch operation within the context of a thread-local session sequence order.
	 * The session sequence order is set for the current thread before executing the fetch function,
	 * and is removed once the operation completes.
	 *
	 * @param sessionSequenceOrder the session sequence order to associate with the current thread context
	 * @param sessionRecordsCount the total number of records associated with the current session
	 * @param fetcher the supplier function representing the operation to execute in the context
	 * @param <V> the type of the value returned by the supplier function
	 * @return the result of the supplier function execution
	 */
	@Nonnull
	public static <V> V fetch(long sessionSequenceOrder, int sessionRecordsCount, @Nonnull Supplier<V> fetcher) {
		try {
			SESSION_SEQUENCE_ORDER.set(new SessionRecordContext(sessionSequenceOrder, sessionRecordsCount));
			return fetcher.get();
		} finally {
			SESSION_SEQUENCE_ORDER.remove();
		}
	}

	/**
	 * Retrieves the session sequence order associated with the current thread context.
	 *
	 * @return the session sequence order associated with the current thread context
	 */
	@Nullable
	public static SessionRecordContext get() {
		return SESSION_SEQUENCE_ORDER.get();
	}

	/**
	 * Represents the context of session-related metadata, encapsulating information about
	 * the order of a session sequence and the count of records associated with the session.
	 * This record can be used as a data holder for session-specific operations and metadata tracking.
	 *
	 * @param sessionSequenceOrder the sequence order of the session, which is a long value used to
	 *                              uniquely identify the sequence within the session.
	 * @param sessionRecordsCount the total number of records associated with the current session,
	 *                             represented as an integer.
	 */
	public record SessionRecordContext(
		long sessionSequenceOrder,
		int sessionRecordsCount
	) {

	}

}
