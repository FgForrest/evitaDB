/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.transaction.memory;

import com.carrotsearch.hppc.LongHashSet;
import io.evitadb.api.exception.IdentifierOverflowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the identifier sequence contract of {@link TransactionalObjectVersion}.
 *
 * The sequence hands out `1L` first, runs up through {@link Long#MAX_VALUE}, wraps in two's
 * complement to {@link Long#MIN_VALUE} and continues up to `-1L`. **`0L` is reserved and is never
 * emitted** - it denotes "no transactional layer", which lets an identifier-keyed registry represent
 * absence without being able to confuse it with a live creator.
 *
 * The wrap-around and exhaustion behaviour cannot be reached by counting (it takes 2^64 - 1
 * increments), so these tests drive a separately seeded sequence instance rather than the JVM-wide
 * {@link TransactionalObjectVersion#SEQUENCE} singleton.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
class TransactionalObjectVersionTest {

	@Nested
	@DisplayName("Identifier domain")
	class DomainTest {

		@Test
		@DisplayName("first identifier handed out is 1")
		void shouldStartAtOne() {
			final TransactionalObjectVersion sequence = new TransactionalObjectVersion();

			assertEquals(1L, sequence.nextId());
			assertEquals(2L, sequence.nextId());
			assertEquals(3L, sequence.nextId());
		}

		@Test
		@DisplayName("identifiers are unique across successive calls")
		void shouldHandOutDistinctIdentifiers() {
			final TransactionalObjectVersion sequence = new TransactionalObjectVersion();

			assertNotEquals(sequence.nextId(), sequence.nextId());
		}

		@Test
		@DisplayName("the shared singleton never emits the reserved zero identifier")
		void shouldNeverEmitZeroFromSharedSequence() {
			// the singleton is shared with the rest of the test suite, so only the reservation
			// invariant can be asserted on it - never a concrete value
			for (int i = 0; i < 1000; i++) {
				assertNotEquals(0L, TransactionalObjectVersion.SEQUENCE.nextId());
			}
		}
	}

	@Nested
	@DisplayName("Wrap-around")
	class WrapAroundTest {

		@Test
		@DisplayName("wrapping from Long.MAX_VALUE to Long.MIN_VALUE is legal, not an overflow")
		void shouldWrapFromMaxToMinWithoutThrowing() {
			// seeded so that the very next identifier is Long.MAX_VALUE
			final TransactionalObjectVersion sequence = new TransactionalObjectVersion(Long.MAX_VALUE - 1L);

			assertEquals(Long.MAX_VALUE, sequence.nextId());
			// two's complement wrap - the negative domain is the second half of the identifier space
			// and must keep being handed out rather than being mistaken for exhaustion
			assertEquals(Long.MIN_VALUE, sequence.nextId());
			assertEquals(Long.MIN_VALUE + 1L, sequence.nextId());
		}

		@Test
		@DisplayName("negative identifiers up to -1 are handed out normally")
		void shouldHandOutNegativeIdentifiers() {
			final TransactionalObjectVersion sequence = new TransactionalObjectVersion(-3L);

			assertEquals(-2L, sequence.nextId());
			assertEquals(-1L, sequence.nextId());
		}
	}

	@Nested
	@DisplayName("Exhaustion")
	class ExhaustionTest {

		@Test
		@DisplayName("returning to zero means the whole identifier space was consumed and throws")
		void shouldThrowWhenSequenceExhausted() {
			// -1L is the last identifier of the space; the following increment lands back on the
			// reserved zero, which is the definition of exhaustion
			final TransactionalObjectVersion sequence = new TransactionalObjectVersion(-2L);

			assertEquals(-1L, sequence.nextId());
			assertThrows(IdentifierOverflowException.class, sequence::nextId);
		}

		@Test
		@DisplayName("an exhausted sequence stays poisoned and never resumes at 1")
		void shouldRemainPoisonedAfterExhaustion() {
			final TransactionalObjectVersion sequence = new TransactionalObjectVersion(-1L);

			assertThrows(IdentifierOverflowException.class, sequence::nextId);
			// without poisoning, the counter would have advanced past zero and silently started
			// handing out 1, 2, 3... - identifiers that are already in use
			assertThrows(IdentifierOverflowException.class, sequence::nextId);
			assertThrows(IdentifierOverflowException.class, sequence::nextId);
		}

		@Test
		@DisplayName("concurrent callers keep receiving distinct identifiers away from the boundary")
		void shouldHandOutDistinctIdentifiersUnderConcurrentAccess() throws InterruptedException {
			// the exhaustion boundary itself is deliberately not asserted under concurrency - see the
			// class JavaDoc of TransactionalObjectVersion for why that corner is not guarded
			final int threadCount = 8;
			final int idsPerThread = 1000;
			final TransactionalObjectVersion sequence = new TransactionalObjectVersion();
			final AtomicReferenceArray<long[]> outcomes = new AtomicReferenceArray<>(threadCount);
			final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			try {
				for (int i = 0; i < threadCount; i++) {
					final int index = i;
					executor.submit(() -> {
						final long[] identifiers = new long[idsPerThread];
						for (int j = 0; j < idsPerThread; j++) {
							identifiers[j] = sequence.nextId();
						}
						outcomes.set(index, identifiers);
					});
				}
				executor.shutdown();
				assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
			} finally {
				executor.shutdownNow();
			}

			final LongHashSet seen = new LongHashSet(threadCount * idsPerThread);
			for (int i = 0; i < threadCount; i++) {
				final long[] identifiers = outcomes.get(i);
				assertNotNull(identifiers);
				for (final long identifier : identifiers) {
					assertNotEquals(0L, identifier);
					assertTrue(seen.add(identifier), "identifier " + identifier + " was handed out twice");
				}
			}
			assertEquals(threadCount * idsPerThread, seen.size());
		}
	}
}
