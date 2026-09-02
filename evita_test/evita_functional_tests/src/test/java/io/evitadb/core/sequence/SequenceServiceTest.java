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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies the two properties {@link SequenceService} has to hold for catalog folder generations: a counter is
 * only ever fast-forwarded, and a counter can be discarded once nothing depends on it any more.
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

}
