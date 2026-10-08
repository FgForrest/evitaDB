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

import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.CONTRACT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the contract of {@link ChangeCaptureResumePositionInvalidException}: it is caught by every handler of
 * {@link TemporalDataNotAvailableException}, keeps the inherited getter meaning the oldest available version, and
 * tells the consumer what happened and what to do about it - for every reason.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Invalid change capture resume position exception")
@Tag(CONTRACT)
@Tag(CDC)
class ChangeCaptureResumePositionInvalidExceptionTest {
	/**
	 * Identity of the catalog incarnation the position was checked against.
	 */
	private static final UUID CATALOG_ID = UUID.fromString("5c2e9a71-3d4b-4e8f-b1a6-9f0c7d2e4a33");
	/**
	 * Identity of the incarnation the consumer recorded its position on.
	 */
	private static final UUID REQUESTED_CATALOG_ID = UUID.fromString("a81f4c06-7b2d-4d93-8e5a-1c6b3f9d0e44");

	@ParameterizedTest(name = "{0}")
	@EnumSource(Reason.class)
	@DisplayName("should describe the position, the incarnation and the remedy for every reason")
	void shouldDescribePositionIncarnationAndRemedy(Reason reason) {
		final ChangeCaptureResumePositionInvalidException exception = new ChangeCaptureResumePositionInvalidException(
			reason, CATALOG_ID, 12L, 4L, REQUESTED_CATALOG_ID, 1878L, 2
		);

		final String message = exception.getMessage();
		assertTrue(message.contains("catalogId=" + REQUESTED_CATALOG_ID), message);
		assertTrue(message.contains("sinceVersion=1878"), message);
		assertTrue(message.contains("sinceIndex=2"), message);
		assertTrue(message.contains(CATALOG_ID.toString()), message);
		assertTrue(message.contains("version 12"), message);
		assertTrue(
			message.contains("subscribe again from the head of the stream (without `sinceVersion`)"),
			"The consumer must be told what to do: " + message
		);
		assertTrue(message.contains("Drop every state derived from the change stream"), message);
		assertEquals(message, exception.getPublicMessage());
	}

	@Test
	@DisplayName("should be a temporal data not available exception reporting the oldest available version")
	void shouldBeTemporalDataNotAvailableReportingOldestVersion() {
		final ChangeCaptureResumePositionInvalidException exception = new ChangeCaptureResumePositionInvalidException(
			Reason.DIFFERENT_INCARNATION, CATALOG_ID, 12L, 4L, REQUESTED_CATALOG_ID, 1878L, null
		);

		final TemporalDataNotAvailableException asParent = assertInstanceOf(
			TemporalDataNotAvailableException.class, exception
		);
		assertEquals(4L, asParent.getCatalogVersion());
		assertNull(asParent.getOffsetDateTime());
		assertEquals(12L, exception.getCurrentCatalogVersion());
		assertEquals(CATALOG_ID, exception.getCatalogId());
		assertEquals(REQUESTED_CATALOG_ID, exception.getRequestedCatalogId());
		assertEquals(1878L, exception.getRequestedSinceVersion());
		assertNull(exception.getRequestedSinceIndex());
		assertFalse(exception.getMessage().contains("sinceIndex"));
	}

	@Test
	@DisplayName("should name the next acceptable version of a position ahead of the catalog")
	void shouldNameNextAcceptableVersionOfPositionAhead() {
		final ChangeCaptureResumePositionInvalidException exception = new ChangeCaptureResumePositionInvalidException(
			Reason.AHEAD_OF_CATALOG, CATALOG_ID, 12L, 4L, null, 1878L, null
		);

		assertTrue(exception.getMessage().contains("start at version 13 at the latest"), exception.getMessage());
		assertFalse(exception.getMessage().contains("catalogId="), exception.getMessage());
	}

	@Test
	@DisplayName("should keep the read failure as the cause of a position outside the retention")
	void shouldKeepReadFailureAsCause() {
		final RuntimeException readFailure = new IllegalStateException("the read failed");
		final ChangeCaptureResumePositionInvalidException exception = new ChangeCaptureResumePositionInvalidException(
			Reason.OUTSIDE_RETENTION, CATALOG_ID, 12L, 4L, null, 2L, 0, readFailure
		);

		assertSame(readFailure, exception.getCause());
		assertTrue(
			exception.getMessage().contains("the oldest catalog version still available is 4"),
			exception.getMessage()
		);
	}

	@Test
	@DisplayName("should leave the oldest available version out when it is unknown")
	void shouldLeaveOldestVersionOutWhenUnknown() {
		final ChangeCaptureResumePositionInvalidException exception = new ChangeCaptureResumePositionInvalidException(
			Reason.OUTSIDE_RETENTION, CATALOG_ID, 12L, null, null, 2L, 0
		);

		assertNull(exception.getCatalogVersion());
		assertFalse(exception.getMessage().contains("oldest catalog version"), exception.getMessage());
		assertTrue(exception.getMessage().contains("sinceVersion=2"), exception.getMessage());
	}

}
