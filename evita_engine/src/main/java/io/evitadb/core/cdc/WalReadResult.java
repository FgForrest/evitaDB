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

package io.evitadb.core.cdc;


import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.requestResponse.cdc.ChangeCapture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.LongSupplier;

/**
 * Outcome of one read of change captures from the write-ahead log on behalf of a lagging subscriber.
 *
 * The catalog and the system shared publishers read their logs the same way, so the two ways such a read can fail
 * on the WAL retention are classified here, once for both of them. What they report differs: each publisher passes a
 * {@link RetentionFailureFactory} - the catalog one reports the identity of the catalog incarnation along with the
 * retention floor, the system one, which serves no catalog, reports the floor alone.
 *
 * @param lastCapture            the last capture the read offered to the subscriber's queue, or `null` when it
 *                               offered none
 * @param examinedThroughVersion the newest version whose every capture the read examined, provided it was not cut
 *                               short by a full queue; lower than the version the read started at when there was
 *                               nothing to examine
 * @param <T>                    the type of the change capture
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
record WalReadResult<T extends ChangeCapture>(
	@Nullable T lastCapture,
	long examinedThroughVersion
) {

	/**
	 * Verifies that the WAL retention has not removed the position a lagging subscriber has to continue from.
	 *
	 * @param walPointer             the position the subscriber continues from
	 * @param firstReplayableVersion the first version the WAL can still replay, `-1` when it never lost a file
	 * @param failureFactory         creates the exception reporting a removed position
	 * @throws TemporalDataNotAvailableException if the position lies below the first replayable version
	 */
	static void assertPositionRetained(
		@Nonnull WalPointer walPointer,
		long firstReplayableVersion,
		@Nonnull RetentionFailureFactory failureFactory
	) {
		if (isRemovedByRetention(walPointer, firstReplayableVersion)) {
			throw failureFactory.create(walPointer, firstReplayableVersion, null);
		}
	}

	/**
	 * Returns the exception a failed read of the WAL has to end with. The retention may have removed the position
	 * between the check that preceded the read and the read itself, so the check runs again: a position that is gone
	 * is reported as what it is, {@link TemporalDataNotAvailableException} caused by the read failure, rather than as
	 * the WAL damage the reader had to assume. Otherwise the read failure itself is the answer.
	 *
	 * The repeated check lists the WAL folder and can fail in its own right. Such a failure is attached to the read
	 * failure as suppressed instead of replacing it - the read failure is the only account of what went wrong.
	 *
	 * @param readFailure            the failure of the read
	 * @param walPointer             the position the read started at
	 * @param firstReplayableVersion looks up the first version the WAL can still replay, `-1` when it never lost a
	 *                               file
	 * @param failureFactory         creates the exception reporting a removed position, caused by the read failure
	 * @return the exception to throw
	 */
	@Nonnull
	static RuntimeException classifyReadFailure(
		@Nonnull RuntimeException readFailure,
		@Nonnull WalPointer walPointer,
		@Nonnull LongSupplier firstReplayableVersion,
		@Nonnull RetentionFailureFactory failureFactory
	) {
		final long theFirstReplayableVersion;
		try {
			theFirstReplayableVersion = firstReplayableVersion.getAsLong();
		} catch (RuntimeException recheckFailure) {
			readFailure.addSuppressed(recheckFailure);
			return readFailure;
		}
		return isRemovedByRetention(walPointer, theFirstReplayableVersion) ?
			failureFactory.create(walPointer, theFirstReplayableVersion, readFailure) : readFailure;
	}

	/**
	 * Decides whether the WAL retention has removed the position. Only a WAL that has actually lost a file can have
	 * removed anything: its first replayable version is then a real, non-negative version. The `-1` a WAL that never
	 * lost a file answers is a sentinel, not a version - compared as a number it would declare every position below
	 * it removed, although nothing was.
	 *
	 * @param walPointer             the position the subscriber continues from
	 * @param firstReplayableVersion the first version the WAL can still replay, `-1` when it never lost a file
	 * @return `true` when the position lies below the first version the WAL can still replay
	 */
	private static boolean isRemovedByRetention(@Nonnull WalPointer walPointer, long firstReplayableVersion) {
		return firstReplayableVersion >= 0L && firstReplayableVersion > walPointer.version();
	}

	/**
	 * Creates the exception reporting that the WAL retention has removed the position a lagging subscriber has to
	 * continue from.
	 */
	@FunctionalInterface
	interface RetentionFailureFactory {

		/**
		 * Reports the removed position as a plain {@link TemporalDataNotAvailableException} naming the retention
		 * floor - for a stream that does not belong to any catalog incarnation.
		 */
		RetentionFailureFactory PLAIN = (walPointer, firstReplayableVersion, cause) -> cause == null ?
			new TemporalDataNotAvailableException(firstReplayableVersion) :
			new TemporalDataNotAvailableException(firstReplayableVersion, cause);

		/**
		 * Creates the exception.
		 *
		 * @param walPointer             the position the subscriber has to continue from
		 * @param firstReplayableVersion the first version the WAL can still replay - the oldest version still
		 *                               available
		 * @param cause                  the failure of the read that ran into the removed position, `null` when the
		 *                               removal was recognized before reading
		 * @return the exception to throw, whose {@link TemporalDataNotAvailableException#getCatalogVersion()} is the
		 *         first replayable version
		 */
		@Nonnull
		TemporalDataNotAvailableException create(
			@Nonnull WalPointer walPointer,
			long firstReplayableVersion,
			@Nullable Throwable cause
		);

	}

}
