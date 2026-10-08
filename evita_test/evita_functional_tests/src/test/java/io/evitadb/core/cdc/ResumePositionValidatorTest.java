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
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.LongSupplier;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the decisions {@link ResumePositionValidator} makes about a resume position, independently of an engine:
 * which positions an incarnation refuses, which it must still accept, and what the refusal tells the consumer.
 *
 * The engine-level tests in `CatalogChangeCaptureResumePositionTest` reach the same decisions through a real catalog;
 * this class pins the boundaries exactly - in particular both orderings of the session's and the change observer's
 * view of the live version, one of which an engine reaches only inside a window of a few instructions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Resume position validator")
@Tag(ENGINE)
@Tag(CDC)
class ResumePositionValidatorTest {
	/**
	 * Identity of the catalog incarnation the positions are checked against.
	 */
	private static final UUID CATALOG_ID = UUID.fromString("0b6c1f8e-2f4d-4c55-9a3e-6d7f1e2a9b10");
	/**
	 * Identity of another incarnation of the same catalog - the one a stale checkpoint was recorded on.
	 */
	private static final UUID OTHER_CATALOG_ID = UUID.fromString("e4a7d2c9-8b13-4f0e-a6c5-3b9d0f7e1c22");
	/**
	 * The version the catalog the checks run against is at.
	 */
	private static final long LIVE_VERSION = 10L;
	/**
	 * Lookup of the first replayable version for a WAL that has lost files up to version five.
	 */
	private static final LongSupplier FIRST_REPLAYABLE_VERSION = () -> 5L;

	/**
	 * Builds a request for the passed resume position.
	 *
	 * @param catalogId    the expected catalog identity, if any
	 * @param sinceVersion the version to resume from, if any
	 * @return the request
	 */
	@Nonnull
	private static ChangeCatalogCaptureRequest request(@Nullable UUID catalogId, @Nullable Long sinceVersion) {
		return new ChangeCatalogCaptureRequest(catalogId, sinceVersion, 3, null, ChangeCaptureContent.HEADER);
	}

	/**
	 * Checks a subscription position against a catalog whose session and observer agree on {@link #LIVE_VERSION}.
	 *
	 * @param request the request to check
	 */
	private static void assertSubscription(@Nonnull ChangeCatalogCaptureRequest request) {
		ResumePositionValidator.assertSubscriptionPosition(
			request, CATALOG_ID, LIVE_VERSION, OptionalLong.of(LIVE_VERSION), FIRST_REPLAYABLE_VERSION
		);
	}

	@Nested
	@DisplayName("Catalog identity")
	class CatalogIdentity {

		@Test
		@DisplayName("should refuse a subscription position recorded on a different incarnation")
		void shouldRefuseSubscriptionPositionOfDifferentIncarnation() {
			final ChangeCaptureResumePositionInvalidException refusal = assertThrows(
				ChangeCaptureResumePositionInvalidException.class,
				() -> assertSubscription(request(OTHER_CATALOG_ID, 7L))
			);

			assertEquals(Reason.DIFFERENT_INCARNATION, refusal.getReason());
			assertEquals(CATALOG_ID, refusal.getCatalogId());
			assertEquals(LIVE_VERSION, refusal.getCurrentCatalogVersion());
			assertEquals(5L, refusal.getCatalogVersion(), "The inherited getter keeps naming the oldest version.");
			assertEquals(OTHER_CATALOG_ID, refusal.getRequestedCatalogId());
			assertEquals(7L, refusal.getRequestedSinceVersion());
			assertEquals(3, refusal.getRequestedSinceIndex());
		}

		@Test
		@DisplayName("should refuse a different incarnation even when the position states no version")
		void shouldRefuseDifferentIncarnationWithoutVersion() {
			final ChangeCaptureResumePositionInvalidException refusal = assertThrows(
				ChangeCaptureResumePositionInvalidException.class,
				() -> assertSubscription(request(OTHER_CATALOG_ID, null))
			);

			assertEquals(Reason.DIFFERENT_INCARNATION, refusal.getReason());
			assertNull(refusal.getRequestedSinceVersion());
		}

		@Test
		@DisplayName("should accept a subscription position recorded on the same incarnation or stating none")
		void shouldAcceptPositionOfSameIncarnationOrWithoutIdentity() {
			assertDoesNotThrow(() -> assertSubscription(request(CATALOG_ID, 7L)));
			assertDoesNotThrow(() -> assertSubscription(request(CATALOG_ID, null)));
			assertDoesNotThrow(() -> assertSubscription(request(null, 7L)));
		}

		@Test
		@DisplayName("should refuse a history position of a different incarnation and accept the same one")
		void shouldCheckIdentityOfHistoryPosition() {
			final ChangeCaptureResumePositionInvalidException refusal = assertThrows(
				ChangeCaptureResumePositionInvalidException.class,
				() -> ResumePositionValidator.assertSameIncarnation(
					request(OTHER_CATALOG_ID, 7L), CATALOG_ID, LIVE_VERSION, FIRST_REPLAYABLE_VERSION
				)
			);
			assertEquals(Reason.DIFFERENT_INCARNATION, refusal.getReason());

			// the history checks only the identity - a future version is documented to read as an empty stream
			assertDoesNotThrow(
				() -> ResumePositionValidator.assertSameIncarnation(
					request(CATALOG_ID, LIVE_VERSION + 100), CATALOG_ID, LIVE_VERSION, FIRST_REPLAYABLE_VERSION
				)
			);
			assertDoesNotThrow(
				() -> ResumePositionValidator.assertSameIncarnation(
					request(null, LIVE_VERSION + 100), CATALOG_ID, LIVE_VERSION, FIRST_REPLAYABLE_VERSION
				)
			);
		}

	}

	@Nested
	@DisplayName("Position ahead of the catalog")
	class AheadOfCatalog {

		@Test
		@DisplayName("should refuse a position more than one version past the live catalog, with or without identity")
		void shouldRefusePositionMoreThanOneVersionAhead() {
			for (UUID catalogId : new UUID[]{null, CATALOG_ID}) {
				final ChangeCaptureResumePositionInvalidException refusal = assertThrows(
					ChangeCaptureResumePositionInvalidException.class,
					() -> assertSubscription(request(catalogId, LIVE_VERSION + 2))
				);
				assertEquals(Reason.AHEAD_OF_CATALOG, refusal.getReason());
				assertEquals(LIVE_VERSION, refusal.getCurrentCatalogVersion());
				assertEquals(LIVE_VERSION + 2, refusal.getRequestedSinceVersion());
			}
		}

		@Test
		@DisplayName("should accept the version the next change will carry and every version up to it")
		void shouldAcceptNextVersionAndBelow() {
			assertDoesNotThrow(() -> assertSubscription(request(null, LIVE_VERSION + 1)));
			assertDoesNotThrow(() -> assertSubscription(request(null, LIVE_VERSION)));
			assertDoesNotThrow(() -> assertSubscription(request(null, 0L)));
		}

		@Test
		@DisplayName("should accept the next version of a session that is already ahead of the change observer")
		void shouldAcceptNextVersionOfSessionAheadOfObserver() {
			// a new version is published to sessions before the observer is told about it
			assertDoesNotThrow(
				() -> ResumePositionValidator.assertSubscriptionPosition(
					request(null, LIVE_VERSION + 2), CATALOG_ID, LIVE_VERSION + 1, OptionalLong.of(LIVE_VERSION),
					FIRST_REPLAYABLE_VERSION
				)
			);
		}

		@Test
		@DisplayName("should accept the next version of an observer that is already ahead of the session")
		void shouldAcceptNextVersionOfObserverAheadOfSession() {
			// a session opened before the latest commit still reads the version before it
			assertDoesNotThrow(
				() -> ResumePositionValidator.assertSubscriptionPosition(
					request(null, LIVE_VERSION + 2), CATALOG_ID, LIVE_VERSION, OptionalLong.of(LIVE_VERSION + 1),
					FIRST_REPLAYABLE_VERSION
				)
			);
		}

		@Test
		@DisplayName("should judge by the session alone when the catalog has no change observer")
		void shouldJudgeBySessionWithoutObserver() {
			final ChangeCaptureResumePositionInvalidException refusal = assertThrows(
				ChangeCaptureResumePositionInvalidException.class,
				() -> ResumePositionValidator.assertSubscriptionPosition(
					request(null, LIVE_VERSION + 2), CATALOG_ID, LIVE_VERSION, OptionalLong.empty(),
					FIRST_REPLAYABLE_VERSION
				)
			);
			assertEquals(LIVE_VERSION, refusal.getCurrentCatalogVersion());
		}

	}

	@Nested
	@DisplayName("Oldest available version")
	class OldestAvailableVersion {

		@Test
		@DisplayName("should report version zero when the write-ahead log has never lost a file")
		void shouldReportZeroWhenWalNeverLostAFile() {
			final ChangeCaptureResumePositionInvalidException refusal = assertThrows(
				ChangeCaptureResumePositionInvalidException.class,
				() -> ResumePositionValidator.assertSameIncarnation(
					request(OTHER_CATALOG_ID, 7L), CATALOG_ID, LIVE_VERSION, () -> -1L
				)
			);

			assertEquals(0L, refusal.getCatalogVersion());
		}

		@Test
		@DisplayName("should keep the refusal and attach a failed lookup of the oldest version to it")
		void shouldKeepRefusalWhenOldestVersionLookupFails() {
			final RuntimeException lookupFailure = new IllegalStateException("the WAL folder could not be listed");

			final ChangeCaptureResumePositionInvalidException refusal = assertThrows(
				ChangeCaptureResumePositionInvalidException.class,
				() -> ResumePositionValidator.assertSameIncarnation(
					request(OTHER_CATALOG_ID, 7L), CATALOG_ID, LIVE_VERSION, () -> {
						throw lookupFailure;
					}
				)
			);

			assertEquals(Reason.DIFFERENT_INCARNATION, refusal.getReason());
			assertNull(refusal.getCatalogVersion(), "The oldest version is unknown and must not be made up.");
			assertArrayEquals(new Throwable[]{lookupFailure}, refusal.getSuppressed());
			assertTrue(refusal.getMessage().contains(OTHER_CATALOG_ID.toString()));
		}

	}

}
