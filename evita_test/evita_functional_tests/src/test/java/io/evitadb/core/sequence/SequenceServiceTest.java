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

package io.evitadb.core.sequence;

import io.evitadb.api.exception.IdentifierOverflowException;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.BitSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the two properties {@link SequenceService} has to hold for catalog folder generations: a counter is
 * only ever fast-forwarded, and a counter can be discarded once nothing depends on it any more. It also verifies that
 * the primary key sequences of entity collections and catalogs refuse to wrap once they have handed out their last
 * value, and that the refusal tells the operator what helps.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("SequenceService")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class SequenceServiceTest {

	@Nested
	@DisplayName("Fast-forward seeding")
	class FastForwardSeeding {

		@Test
		@DisplayName("Raises an existing counter to the seed but never lowers it")
		void shouldOnlyEverFastForwardAnExistingSequence() {
			final SequenceService sequenceService = new SequenceService();

			final AtomicInteger sequence = sequenceService.getOrCreateSequence(
				"products", SequenceType.CATALOG_GENERATION, 3
			);
			assertEquals(3, sequence.get());

			// re-seeding with a higher peak - as a boot does after the peak advanced - moves the counter up …
			assertSame(sequence, sequenceService.getOrCreateSequence("products", SequenceType.CATALOG_GENERATION, 7));
			assertEquals(7, sequence.get());

			// … while a lower peak must be ignored: walking back would hand out a number a live folder holds
			assertSame(sequence, sequenceService.getOrCreateSequence("products", SequenceType.CATALOG_GENERATION, 2));
			assertEquals(7, sequence.get());
		}

		@Test
		@DisplayName("Burns a number per call, so a failed attempt cannot hand its number to the retry")
		void shouldBurnANumberPerAttempt() {
			// this is why the generation is drawn from a sequence rather than from a counter advanced inside the
			// engine mutation: an attempt that fails still consumed its number, and the folder it may have left
			// behind on disk is exactly what the retry must not collide with
			final SequenceService sequenceService = new SequenceService();
			final AtomicInteger sequence = sequenceService.getOrCreateSequence(
				"products", SequenceType.CATALOG_GENERATION, 0
			);

			assertEquals(1, sequence.incrementAndGet());
			assertEquals(2, sequence.incrementAndGet());
		}

	}

	@Nested
	@DisplayName("Retirement")
	class Retirement {

		@Test
		@DisplayName("Discards every sequence of one catalog and leaves the others alone")
		void shouldRemoveOnlyTheNamedCatalogsSequences() {
			final SequenceService sequenceService = new SequenceService();
			sequenceService.getOrCreateSequence("products", SequenceType.CATALOG_GENERATION, 5);
			sequenceService.getOrCreateSequence("products", SequenceType.ENTITY, "Product", 11);
			sequenceService.getOrCreateSequence("orders", SequenceType.CATALOG_GENERATION, 2);

			assertEquals(2, sequenceService.removeSequences("products"));

			// the retired catalog restarts from whatever seed it is next given …
			assertEquals(
				0, sequenceService.getOrCreateSequence("products", SequenceType.CATALOG_GENERATION, 0).get()
			);
			// … while an unrelated catalog keeps its counter, which is what bounds the map without ever
			// resetting a sequence that is still protecting a live folder
			assertEquals(
				2, sequenceService.getOrCreateSequence("orders", SequenceType.CATALOG_GENERATION, 0).get()
			);
		}

		@Test
		@DisplayName("Reports nothing removed for a catalog that holds no sequences")
		void shouldReportZeroForUnknownCatalog() {
			final SequenceService sequenceService = new SequenceService();
			sequenceService.getOrCreateSequence("products", SequenceType.CATALOG_GENERATION, 5);

			assertEquals(0, sequenceService.removeSequences("orders"));
			assertEquals(1, sequenceService.removeSequences("products"));
			// removal is idempotent - the tombstone drain that triggers it may run again on a later boot
			assertEquals(0, sequenceService.removeSequences("products"));
		}

	}

	@Nested
	@DisplayName("Exhaustion")
	class Exhaustion {
		private static final String CATALOG = "products";
		private static final String ENTITY_TYPE = "Product";

		/**
		 * Restores a sequence the way an entity collection or a catalog does on load - seeded with the last value its
		 * header says was handed out.
		 *
		 * @param sequenceType      type of the sequence
		 * @param lastValueHandedOut the persisted last value
		 * @return the restored sequence
		 */
		@Nonnull
		private static AtomicInteger restore(@Nonnull SequenceType sequenceType, int lastValueHandedOut) {
			final SequenceService sequenceService = new SequenceService();
			return sequenceType == SequenceType.ENTITY_COLLECTION ?
				sequenceService.getOrCreateSequence(CATALOG, sequenceType, lastValueHandedOut) :
				sequenceService.getOrCreateSequence(CATALOG, sequenceType, ENTITY_TYPE, lastValueHandedOut);
		}

		/**
		 * Returns the entity type a refusal of the given sequence type names - none for the catalog-wide one.
		 *
		 * @param sequenceType type of the sequence
		 * @return the entity type, or `null`
		 */
		@Nullable
		private static String entityTypeOf(@Nonnull SequenceType sequenceType) {
			return sequenceType == SequenceType.ENTITY_COLLECTION ? null : ENTITY_TYPE;
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = SequenceType.class, names = "CATALOG_GENERATION", mode = Mode.EXCLUDE)
		@DisplayName("Hands out the last value, then refuses without moving and says what helps")
		void shouldHandOutTheLastValueAndRefuseTheNext(@Nonnull SequenceType sequenceType) {
			final AtomicInteger sequence = restore(sequenceType, Integer.MAX_VALUE - 1);

			assertEquals(Integer.MAX_VALUE, SequenceService.nextValueOrExhausted(sequence));
			assertEquals(SequenceService.EXHAUSTED, SequenceService.nextValueOrExhausted(sequence));
			assertEquals(SequenceService.EXHAUSTED, SequenceService.nextValueOrExhausted(sequence));
			// the refusal leaves the sequence where it was, so the header persists the last value actually handed out
			assertEquals(Integer.MAX_VALUE, sequence.get());

			final IdentifierOverflowException exception = SequenceService.exhausted(
				sequenceType, CATALOG, entityTypeOf(sequenceType)
			);
			final String publicMessage = exception.getPublicMessage();
			assertTrue(
				publicMessage.contains("catalog `" + CATALOG + "`"),
				"The public message must name the catalog: " + publicMessage
			);
			if (sequenceType == SequenceType.ENTITY_COLLECTION) {
				assertTrue(
					publicMessage.contains("primary keys of the entity collections") &&
						publicMessage.contains("rebuild the catalog") &&
						publicMessage.contains("`replaceCatalog`"),
					"The public message must name the id space and how to rebuild the catalog: " + publicMessage
				);
			} else {
				assertTrue(
					publicMessage.contains("entity collection `" + ENTITY_TYPE + "`") &&
						publicMessage.contains("rebuild the collection") &&
						publicMessage.contains("`replaceCollection`"),
					"The public message must name the collection and how to rebuild it: " + publicMessage
				);
			}
			// every one of these sequences is persisted, so suggesting a restart would send the operator nowhere
			assertTrue(
				publicMessage.contains("a restart does not help"),
				"The public message must say a restart does not help: " + publicMessage
			);
			assertTrue(
				exception.getPrivateMessage().contains(String.valueOf(Integer.MAX_VALUE)),
				"The private message must carry the position of the sequence: " + exception.getPrivateMessage()
			);
		}

		@Test
		@DisplayName("Restored at the last value, refuses the very first draw")
		void shouldRefuseTheFirstDrawOfASequenceRestoredAtTheLimit() {
			final AtomicInteger sequence = restore(SequenceType.ENTITY, Integer.MAX_VALUE);

			assertEquals(SequenceService.EXHAUSTED, SequenceService.nextValueOrExhausted(sequence));
			assertEquals(Integer.MAX_VALUE, sequence.get());
		}

		@Test
		@DisplayName("Concurrent draws near the limit hand out every last value exactly once and never wrap")
		void shouldHandOutEveryLastValueExactlyOnceUnderConcurrentDraws() throws Exception {
			final int valuesLeft = 10_000;
			final int threadCount = 8;
			final AtomicInteger sequence = restore(SequenceType.PRICE, Integer.MAX_VALUE - valuesLeft);
			final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			try {
				final CountDownLatch start = new CountDownLatch(1);
				@SuppressWarnings("unchecked") final Future<BitSet>[] futures = new Future[threadCount];
				for (int i = 0; i < threadCount; i++) {
					futures[i] = executor.submit(() -> {
						start.await();
						final BitSet drawn = new BitSet(valuesLeft);
						int value;
						while ((value = SequenceService.nextValueOrExhausted(sequence)) != SequenceService.EXHAUSTED) {
							assertTrue(value > Integer.MAX_VALUE - valuesLeft, "Drawn value out of range: " + value);
							drawn.set(Integer.MAX_VALUE - value);
						}
						return drawn;
					});
				}
				start.countDown();
				final BitSet all = new BitSet(valuesLeft);
				int total = 0;
				for (final Future<BitSet> future : futures) {
					final BitSet drawn = future.get(1, TimeUnit.MINUTES);
					assertFalse(all.intersects(drawn), "Two callers must never receive the same value");
					all.or(drawn);
					total += drawn.cardinality();
				}
				assertEquals(valuesLeft, total);
				assertEquals(valuesLeft, all.cardinality());
				assertEquals(Integer.MAX_VALUE, sequence.get());
			} finally {
				executor.shutdownNow();
			}
		}

		@Test
		@DisplayName("Builds no refusal for catalog folder generations, which are not drawn this way")
		void shouldRefuseToBuildARefusalForCatalogGenerations() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> SequenceService.exhausted(SequenceType.CATALOG_GENERATION, CATALOG, null)
			);
		}

	}

}
