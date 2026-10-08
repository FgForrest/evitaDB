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

import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException.Reason;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.core.cdc.WalReadResult.RetentionFailureFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies how a failed read of the write-ahead log on behalf of a lagging change capture subscriber is classified
 * against the WAL retention - the part both shared publishers delegate to {@link WalReadResult}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("WAL read result: classifying a failed catch-up read")
@Tag(ENGINE)
@Tag(CDC)
class WalReadResultTest {
	/**
	 * The position every read in these tests started at.
	 */
	private static final WalPointer READ_START = new WalPointer(10L, 0);

	@Test
	@DisplayName("should report a position the retention removed during the read as temporal data not available")
	void shouldReportAPositionRemovedDuringTheReadAsTemporalDataNotAvailable() {
		final RuntimeException readFailure = new IllegalStateException("the read failed");

		final RuntimeException classified = WalReadResult.classifyReadFailure(
			readFailure, READ_START, () -> 11L, RetentionFailureFactory.PLAIN
		);

		final TemporalDataNotAvailableException notAvailable = assertInstanceOf(
			TemporalDataNotAvailableException.class, classified
		);
		assertEquals(11L, notAvailable.getCatalogVersion());
		assertSame(readFailure, notAvailable.getCause());
	}

	@Test
	@DisplayName("should rethrow the read failure when the position is still retained")
	void shouldRethrowTheReadFailureWhenThePositionIsStillRetained() {
		final RuntimeException readFailure = new IllegalStateException("the read failed");

		assertSame(
			readFailure,
			WalReadResult.classifyReadFailure(readFailure, READ_START, () -> 10L, RetentionFailureFactory.PLAIN)
		);
		assertSame(
			readFailure,
			WalReadResult.classifyReadFailure(readFailure, READ_START, () -> -1L, RetentionFailureFactory.PLAIN)
		);
	}

	@Test
	@DisplayName("should keep the read failure and attach a failing retention re-check to it")
	void shouldKeepTheReadFailureAndAttachAFailingRetentionRecheck() {
		final RuntimeException readFailure = new IllegalStateException("the read failed");
		final RuntimeException recheckFailure = new IllegalStateException("the WAL folder could not be listed");

		final RuntimeException classified = WalReadResult.classifyReadFailure(
			readFailure, READ_START, () -> {
				throw recheckFailure;
			},
			RetentionFailureFactory.PLAIN
		);

		assertSame(
			readFailure,
			classified,
			"The read failure is the only account of what went wrong with the read - a failure of the retention " +
				"re-check that follows it must not take its place."
		);
		assertArrayEquals(new Throwable[]{recheckFailure}, readFailure.getSuppressed());
	}

	@Test
	@DisplayName("should report a removed position through the factory the publisher passed, before and after a read")
	void shouldReportARemovedPositionThroughThePassedFactory() {
		final UUID catalogId = UUID.randomUUID();
		final RetentionFailureFactory catalogFactory = (position, firstReplayableVersion, cause) -> cause == null ?
			new ChangeCaptureResumePositionInvalidException(
				Reason.OUTSIDE_RETENTION, catalogId, 20L, firstReplayableVersion,
				null, position.version(), position.index()
			) :
			new ChangeCaptureResumePositionInvalidException(
				Reason.OUTSIDE_RETENTION, catalogId, 20L, firstReplayableVersion,
				null, position.version(), position.index(), cause
			);

		final ChangeCaptureResumePositionInvalidException beforeRead = assertThrows(
			ChangeCaptureResumePositionInvalidException.class,
			() -> WalReadResult.assertPositionRetained(READ_START, 11L, catalogFactory)
		);
		assertEquals(11L, beforeRead.getCatalogVersion());
		assertEquals(10L, beforeRead.getRequestedSinceVersion());
		assertEquals(catalogId, beforeRead.getCatalogId());

		final RuntimeException readFailure = new IllegalStateException("the read failed");
		readFailure.addSuppressed(new IllegalStateException("closing the reader failed"));
		final ChangeCaptureResumePositionInvalidException afterRead = assertInstanceOf(
			ChangeCaptureResumePositionInvalidException.class,
			WalReadResult.classifyReadFailure(readFailure, READ_START, () -> 11L, catalogFactory)
		);
		assertEquals(11L, afterRead.getCatalogVersion());
		assertSame(readFailure, afterRead.getCause(), "The read failure and what it carries must stay attached.");
		assertEquals(1, afterRead.getCause().getSuppressed().length);
	}

	@Test
	@DisplayName("should let a retained position pass and report a removed one as the plain exception by default")
	void shouldLetARetainedPositionPassAndReportARemovedOneAsThePlainException() {
		WalReadResult.assertPositionRetained(READ_START, 10L, RetentionFailureFactory.PLAIN);
		WalReadResult.assertPositionRetained(READ_START, -1L, RetentionFailureFactory.PLAIN);

		final TemporalDataNotAvailableException notAvailable = assertThrows(
			TemporalDataNotAvailableException.class,
			() -> WalReadResult.assertPositionRetained(READ_START, 11L, RetentionFailureFactory.PLAIN)
		);
		assertEquals(
			TemporalDataNotAvailableException.class, notAvailable.getClass(),
			"A stream that belongs to no catalog incarnation has no identity to report."
		);
		assertEquals(11L, notAvailable.getCatalogVersion());
	}

	@Test
	@DisplayName("should never report a position below the sentinel of a WAL that has not lost a file as removed")
	void shouldNeverReportAPositionBelowTheNeverLostAFileSentinelAsRemoved() {
		// `-1` is not a version but a WAL that never lost a file - nothing can have been removed below it
		final WalPointer belowSentinel = new WalPointer(-5L, 0);
		WalReadResult.assertPositionRetained(belowSentinel, -1L, RetentionFailureFactory.PLAIN);

		final RuntimeException readFailure = new IllegalStateException("the read failed");
		assertSame(
			readFailure,
			WalReadResult.classifyReadFailure(readFailure, belowSentinel, () -> -1L, RetentionFailureFactory.PLAIN),
			"A WAL that never lost a file cannot have removed the position - the read failure is the answer."
		);

		// a real retention floor still refuses the very same position
		assertThrows(
			TemporalDataNotAvailableException.class,
			() -> WalReadResult.assertPositionRetained(belowSentinel, 0L, RetentionFailureFactory.PLAIN)
		);
	}

}
