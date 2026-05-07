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

package io.evitadb.core.cdc.predicate;

import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.cdc.SystemCaptureArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Predicate;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * White-box tests for {@link MutationPredicateFactory#createHostEventPredicate(ChangeSystemCaptureRequest)}.
 *
 * The predicate decides — at the publisher level — whether a particular
 * {@link HostSystemEvent} should reach a subscriber given the subscriber's request.
 * It encodes the deliberate **default-criteria divergence** documented on
 * {@link SystemCaptureArea}: a `null` criteria array means engine-only and rejects
 * every host event; explicit `HOST` (or `null`-area-inside-an-explicit-
 * criterion) opt-in is required to receive host events.
 *
 * Note: tests for the inner classes
 * `EngineSystemAreaPredicate` / `InfrastructureSystemAreaPredicate` / `FalsePredicate`
 * are intentionally omitted — Phase 1 simplifier flagged them as candidates for
 * removal, and locking in tests against soon-to-be-deleted code adds churn.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Host event predicate")
@Tag(ENGINE)
@Tag(CDC)
class SystemCapturePredicateTest {

	/**
	 * Sample host event reused across tests; the predicate output does not depend on the
	 * event's payload (only on the criteria), so a single shared instance suffices.
	 */
	private static final HostSystemEvent SAMPLE_EVENT =
		new HostSystemEvent.CatalogInstalledIntoLiveView("ctlg", CatalogState.ALIVE, 0L);

	/**
	 * Builds a {@link ChangeSystemCaptureRequest} whose only meaningful field for predicate
	 * construction is the criteria array. The remaining fields are filled with stable defaults
	 * so test reads stay focused.
	 *
	 * @param criteria the criteria array the request should carry; pass `null` to exercise the
	 *                 default-criteria divergence path
	 * @return a request carrying the supplied criteria, no version cursor, header content
	 */
	@Nonnull
	private static ChangeSystemCaptureRequest requestWithCriteria(
		@Nullable final ChangeSystemCaptureCriteria[] criteria
	) {
		return new ChangeSystemCaptureRequest(null, null, criteria, ChangeCaptureContent.HEADER);
	}

	@Nested
	@DisplayName("Default-criteria divergence (null / empty)")
	class DefaultDivergence {

		@Test
		@DisplayName("should reject every host event when criteria array is null")
		void shouldRejectAllWhenCriteriaIsNull() {
			// `null` criteria carries the legacy default — engine-only flow, no host events.
			// Locking this in protects the documented divergence rule on SystemCaptureArea.
			final Predicate<HostSystemEvent> predicate = MutationPredicateFactory.createHostEventPredicate(
				requestWithCriteria(null)
			);

			assertNotNull(predicate);
			assertFalse(predicate.test(SAMPLE_EVENT));
		}

		@Test
		@DisplayName("should reject every host event when criteria array is empty")
		void shouldRejectAllWhenCriteriaArrayIsEmpty() {
			// An explicit empty selection means "match nothing" — distinct from the null-criteria
			// default. The host-event predicate must be `false` for this case as well.
			final Predicate<HostSystemEvent> predicate = MutationPredicateFactory.createHostEventPredicate(
				requestWithCriteria(new ChangeSystemCaptureCriteria[0])
			);

			assertFalse(predicate.test(SAMPLE_EVENT));
		}
	}

	@Nested
	@DisplayName("Opt-in via explicit criteria")
	class ExplicitCriteria {

		@Test
		@DisplayName("should accept host events when criteria contains HOST")
		void shouldAcceptWhenCriteriaContainsInfrastructure() {
			final Predicate<HostSystemEvent> predicate = MutationPredicateFactory.createHostEventPredicate(
				requestWithCriteria(new ChangeSystemCaptureCriteria[] {
					new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST)
				})
			);

			assertTrue(predicate.test(SAMPLE_EVENT));
		}

		@Test
		@DisplayName("should accept host events when criteria contains null-area sentinel")
		void shouldAcceptWhenCriteriaContainsNullArea() {
			// `null` area inside an explicit criterion means match-any — host events included.
			final Predicate<HostSystemEvent> predicate = MutationPredicateFactory.createHostEventPredicate(
				requestWithCriteria(new ChangeSystemCaptureCriteria[] {
					new ChangeSystemCaptureCriteria(null)
				})
			);

			assertTrue(predicate.test(SAMPLE_EVENT));
		}

		@Test
		@DisplayName("should reject host events when criteria contains only ENGINE")
		void shouldRejectWhenCriteriaContainsOnlyEngine() {
			// ENGINE-only opt-in does NOT include host events; this is the symmetric counterpart
			// to the engine-mutation rejection on an HOST-only request.
			final Predicate<HostSystemEvent> predicate = MutationPredicateFactory.createHostEventPredicate(
				requestWithCriteria(new ChangeSystemCaptureCriteria[] {
					new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE)
				})
			);

			assertFalse(predicate.test(SAMPLE_EVENT));
		}

		@Test
		@DisplayName("should accept host events when criteria mixes ENGINE and HOST")
		void shouldAcceptWhenCriteriaIsMixed() {
			// OR semantics: as long as at least one criterion lets host events through, the
			// predicate is `true`.
			final Predicate<HostSystemEvent> predicate = MutationPredicateFactory.createHostEventPredicate(
				requestWithCriteria(new ChangeSystemCaptureCriteria[] {
					new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE),
					new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST)
				})
			);

			assertTrue(predicate.test(SAMPLE_EVENT));
		}
	}
}
