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

package io.evitadb.spi.store.catalog.persistence;

import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceDecodeCoverage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the save/restore discipline of {@link ReferenceDecodeCoverageContext} - the thread bound binding that tells
 * the Kryo deserializer which reference names a read may materialize.
 *
 * The binding is what decides whether a decoded {@link
 * io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart} is the entity's complete
 * reference set or a narrowed view of it, and a narrowed part must never reach a consumer entitled to the whole
 * record. Every failure mode of this class is therefore silent: a binding that outlives its read narrows the next,
 * unrelated read, and a restore that drops the outer binding widens it. That is why the tests below always assert
 * on what is bound **after** the call as well as during it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(REFERENCE)
@DisplayName("Reference name filter context")
class ReferenceDecodeCoverageContextTest {
	private static final String BRAND = "brand";
	private static final String CATEGORY = "category";
	private static final ReferenceDecodeCoverage OUTER_FILTER = ReferenceDecodeCoverage.ofNames(Set.of(BRAND));
	private static final ReferenceDecodeCoverage INNER_FILTER = ReferenceDecodeCoverage.ofNames(Set.of(CATEGORY));

	/**
	 * No test may leave a binding behind - the surefire fork reuses the thread for the next test class, and a leaked
	 * filter would narrow reads that never asked to be narrowed.
	 */
	@AfterEach
	void assertNothingLeaked() {
		assertNull(
			ReferenceDecodeCoverageContext.getDecodeCoverage(),
			"Reference name filter leaked out of the test that bound it!"
		);
	}

	@Nested
	@DisplayName("Binding and unbinding")
	class BindingTest {

		@Test
		@DisplayName("no filter is bound outside any read")
		void shouldReturnNullWhenNoFilterIsBound() {
			assertNull(ReferenceDecodeCoverageContext.getDecodeCoverage());
		}

		@Test
		@DisplayName("filter is visible inside the read and gone afterwards")
		void shouldBindAndUnbindFilter() {
			final ReferenceDecodeCoverage observed = ReferenceDecodeCoverageContext.executeWithCoverage(
				OUTER_FILTER,
				ReferenceDecodeCoverageContext::getDecodeCoverage
			);

			assertSame(OUTER_FILTER, observed);
			assertNull(ReferenceDecodeCoverageContext.getDecodeCoverage());
		}

		@Test
		@DisplayName("binding NULL expresses an unrestricted read")
		void shouldBindNullAsUnrestrictedRead() {
			final ReferenceDecodeCoverage observed = ReferenceDecodeCoverageContext.executeWithCoverage(
				null,
				ReferenceDecodeCoverageContext::getDecodeCoverage
			);

			assertNull(observed);
		}

		@Test
		@DisplayName("the lambda result is handed back untouched")
		void shouldReturnLambdaResult() {
			assertEquals(
				"decoded",
				ReferenceDecodeCoverageContext.executeWithCoverage(OUTER_FILTER, () -> "decoded")
			);
		}
	}

	@Nested
	@DisplayName("Nesting and restoration")
	class NestingTest {

		@Test
		@DisplayName("a nested narrowing puts the outer filter back")
		void shouldRestorePreviousFilterAfterNestedBinding() {
			final ReferenceDecodeCoverage outerAfterNesting = ReferenceDecodeCoverageContext.executeWithCoverage(
				OUTER_FILTER,
				() -> {
					final ReferenceDecodeCoverage inner = ReferenceDecodeCoverageContext.executeWithCoverage(
						INNER_FILTER,
						ReferenceDecodeCoverageContext::getDecodeCoverage
					);
					assertSame(INNER_FILTER, inner);
					return ReferenceDecodeCoverageContext.getDecodeCoverage();
				}
			);

			assertSame(OUTER_FILTER, outerAfterNesting);
			assertNull(ReferenceDecodeCoverageContext.getDecodeCoverage());
		}

		@Test
		@DisplayName("a nested unrestricted read puts the outer filter back")
		void shouldRestorePreviousFilterAfterNestedNullBinding() {
			// the asymmetric branch: binding NULL removes the thread local rather than setting it, so the restore
			// has to notice that the previous value was a set and put it back explicitly - a regression here hands
			// the following read an unrestricted view (or, in the mirror case, a stale narrow one)
			final ReferenceDecodeCoverage outerAfterNesting = ReferenceDecodeCoverageContext.executeWithCoverage(
				OUTER_FILTER,
				() -> {
					final ReferenceDecodeCoverage inner = ReferenceDecodeCoverageContext.executeWithCoverage(
						null,
						ReferenceDecodeCoverageContext::getDecodeCoverage
					);
					assertNull(inner);
					return ReferenceDecodeCoverageContext.getDecodeCoverage();
				}
			);

			assertSame(OUTER_FILTER, outerAfterNesting);
			assertNull(ReferenceDecodeCoverageContext.getDecodeCoverage());
		}

		@Test
		@DisplayName("an unrestricted read nesting a narrowing stays unrestricted")
		void shouldRestoreUnrestrictedReadAfterNestedNarrowing() {
			final ReferenceDecodeCoverage outerAfterNesting = ReferenceDecodeCoverageContext.executeWithCoverage(
				null,
				() -> {
					ReferenceDecodeCoverageContext.executeWithCoverage(INNER_FILTER, () -> null);
					return ReferenceDecodeCoverageContext.getDecodeCoverage();
				}
			);

			assertNull(outerAfterNesting);
		}

		@Test
		@DisplayName("a failing read still puts the outer filter back")
		void shouldRestoreFilterWhenLambdaThrows() {
			final ReferenceDecodeCoverage outerAfterFailure = ReferenceDecodeCoverageContext.executeWithCoverage(
				OUTER_FILTER,
				() -> {
					assertThrows(
						IllegalStateException.class,
						() -> ReferenceDecodeCoverageContext.executeWithCoverage(
							INNER_FILTER,
							() -> {
								throw new IllegalStateException("decoding failed");
							}
						)
					);
					return ReferenceDecodeCoverageContext.getDecodeCoverage();
				}
			);

			assertSame(OUTER_FILTER, outerAfterFailure);
		}

		@Test
		@DisplayName("a failing top level read leaves no binding behind")
		void shouldLeaveNoBindingWhenTopLevelLambdaThrows() {
			assertThrows(
				IllegalStateException.class,
				() -> ReferenceDecodeCoverageContext.executeWithCoverage(
					OUTER_FILTER,
					() -> {
						throw new IllegalStateException("decoding failed");
					}
				)
			);

			assertNull(ReferenceDecodeCoverageContext.getDecodeCoverage());
		}
	}

	@Nested
	@DisplayName("Thread confinement")
	class ThreadConfinementTest {

		@Test
		@DisplayName("a filter bound on one thread is invisible on another")
		void shouldNotLeakFilterAcrossThreads() {
			final CountDownLatch observed = new CountDownLatch(1);
			final AtomicReference<ReferenceDecodeCoverage> seenByOtherThread = new AtomicReference<>(INNER_FILTER);

			ReferenceDecodeCoverageContext.executeWithCoverage(
				OUTER_FILTER,
				() -> {
					final Thread other = new Thread(
						() -> {
							seenByOtherThread.set(ReferenceDecodeCoverageContext.getDecodeCoverage());
							observed.countDown();
						},
						"reference-name-filter-confinement-probe"
					);
					other.setDaemon(true);
					other.start();
					try {
						// positive wait - the probe must run, so the bound is generous and costs nothing when it does
						assertTrue(
							observed.await(30, TimeUnit.SECONDS),
							"The probing thread did not observe the filter within 30 seconds!"
						);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(ex);
					}
					return null;
				}
			);

			assertNull(seenByOtherThread.get());
		}
	}

}
