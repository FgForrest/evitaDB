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

package io.evitadb.index.bitmap;

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.TransactionHandler;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.utils.JolHeapSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link Bitmap#getHeapSizeInBytes()} across every implementation, against JOL rather than against
 * arithmetic restated from the production code.
 *
 * # Why the expectations are measured rather than written down
 *
 * Every assertion below takes its expected value from a JOL walk of the real object graph. The alternative —
 * asserting the same formula the implementation computes — produces a test that only fails when someone
 * changes one of the two copies, and stays green while both are wrong together. That is exactly how the
 * estimates this work replaced came to be off by up to 6x inside a fully covered file.
 *
 * # The accounting rules being pinned
 *
 * - **Shared objects are not charged to their holders.** {@link EmptyBitmap#INSTANCE} is a JVM-wide
 *   singleton and must report zero, or every index holding one would be billed for the same object.
 * - **Structure aliased with a superseded version IS charged.** Verified for the roaring-backed bitmaps in
 *   `PersistentRoaringBitmapHeapSizeTest`; what this class pins is the near consequence — an *uncommitted*
 *   transactional diff is the opposite case, owned by the open transaction rather than by the bitmap, and
 *   must not move the figure.
 * - **Capacity, not cardinality.** A bitmap that grew large and shrank back still holds the allocation.
 *
 * @author Claude (bitmap heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(INDEXING)
@DisplayName("Bitmap heap-size reporting")
class BitmapHeapSizeTest {

	/**
	 * Produces `count` consecutive record ids starting at `from`.
	 *
	 * @param from  the first record id
	 * @param count how many ids to produce
	 * @return the ids in ascending order
	 */
	@Nonnull
	private static int[] contiguous(int from, int count) {
		final int[] ids = new int[count];
		for (int i = 0; i < count; i++) {
			ids[i] = from + i;
		}
		return ids;
	}

	/**
	 * Runs `body` inside a real transaction so a {@link TransactionalBitmap} builds an actual transactional
	 * layer, then closes it.
	 *
	 * @param body what to run; its result is returned
	 * @param <R>  the type `body` produces
	 * @return whatever `body` returned
	 */
	private static <R> R runInTransaction(@Nonnull Supplier<R> body) {
		final TransactionHandler noOpHandler = new TransactionHandler() {
			@Override
			public void registerMutation(@Nonnull Mutation mutation) {
				// this test never inspects the mutation stream, only the bitmap's own footprint
			}

			@Override
			public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
				// nothing is committed - the point is precisely to observe the UNcommitted state
			}

			@Override
			public void rollback(
				@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause
			) {
				// not exercised
			}
		};
		final Transaction transaction = new Transaction(UUID.randomUUID(), noOpHandler, false);
		final AtomicReference<R> result = new AtomicReference<>();
		Transaction.executeInTransactionIfProvided(
			transaction,
			() -> {
				try {
					result.set(body.get());
				} finally {
					transaction.close();
				}
			}
		);
		return result.get();
	}

	@Nested
	@DisplayName("matches the measured heap for every implementation")
	class MeasuredExactness {

		@Test
		void shouldMatchMeasuredHeapForBaseBitmap() {
			final BaseBitmap bitmap = new BaseBitmap(contiguous(1, 1_000));
			assertEquals(JolHeapSize.ownedSize(bitmap), bitmap.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForTransactionalBitmap() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(contiguous(1, 1_000));
			assertEquals(JolHeapSize.ownedSize(bitmap), bitmap.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForArrayBitmap() {
			final ArrayBitmap bitmap = new ArrayBitmap(contiguous(1, 1_000));
			assertEquals(JolHeapSize.ownedSize(bitmap), bitmap.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForSingleRecordBitmap() {
			final SingleRecordBitmap bitmap = new SingleRecordBitmap(42);
			assertEquals(JolHeapSize.ownedSize(bitmap), bitmap.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapForEmptyRoaringBitmap() {
			final BaseBitmap bitmap = new BaseBitmap();
			assertEquals(JolHeapSize.ownedSize(bitmap), bitmap.getHeapSizeInBytes());
		}

		@Test
		void shouldMatchMeasuredHeapAcrossTheChunkBoundary() {
			// spans many chunks, so the RoaringArray backbone and its per-container costs both matter
			final int[] ids = new int[50 * 500];
			int i = 0;
			for (int chunk = 0; chunk < 50; chunk++) {
				for (int offset = 0; offset < 500; offset++) {
					ids[i++] = chunk * (1 << 16) + offset;
				}
			}
			final BaseBitmap bitmap = new BaseBitmap(ids);
			assertEquals(JolHeapSize.ownedSize(bitmap), bitmap.getHeapSizeInBytes());
		}
	}

	@Nested
	@DisplayName("excludes structure it does not own")
	class Ownership {

		@Test
		void shouldReportZeroForTheSharedEmptySingleton() {
			// the singleton costs its holder nothing beyond the reference slot, which is already counted
			// inside the holder's own object. Charging its bytes to every holder would bill one object
			// once per index and break the property that per-index figures sum to something meaningful
			assertEquals(0L, EmptyBitmap.INSTANCE.getHeapSizeInBytes());
		}

		@Test
		void shouldNotChargeTheUncommittedTransactionalLayerToTheBitmap() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(contiguous(1, 1_000));
			final long committed = bitmap.getHeapSizeInBytes();

			final long duringTransaction = runInTransaction(() -> {
				// stage a large uncommitted change; the diff lives in the transaction's BitmapChanges layer
				for (int id = 100_000; id < 140_000; id++) {
					bitmap.add(id);
				}
				// the staged records really are visible through the bitmap's own read path...
				assertTrue(bitmap.size() > 1_000);
				// ...but the memory holding them belongs to the transaction, not to the index
				return bitmap.getHeapSizeInBytes();
			});

			assertEquals(committed, duringTransaction);
		}
	}

	@Nested
	@DisplayName("prices capacity rather than cardinality")
	class CapacitySlack {

		@Test
		void shouldKeepReportingRetainedCapacityAfterRemoval() {
			final BaseBitmap grown = new BaseBitmap(contiguous(1, 4_096));
			final long atPeak = grown.getHeapSizeInBytes();
			for (int id = 4_096; id > 100; id--) {
				grown.remove(id);
			}

			assertEquals(100, grown.size());
			assertEquals(atPeak, grown.getHeapSizeInBytes(), "roaring has no shrink path");
			assertEquals(JolHeapSize.ownedSize(grown), grown.getHeapSizeInBytes());

			// and a bitmap that never grew holds a small fraction of it, though both report 100 records
			final BaseBitmap fresh = new BaseBitmap(contiguous(1, 100));
			assertEquals(fresh.size(), grown.size());
			assertTrue(grown.getHeapSizeInBytes() > 10 * fresh.getHeapSizeInBytes());
		}
	}

	@Nested
	@DisplayName("prices the implementation choice")
	class ImplementationComparison {

		@Test
		void shouldShowArrayBitmapCostingMoreThanRoaringForTheSameRecords() {
			// the uncompressed composite array is markedly more expensive than roaring once the record
			// count grows - documented here so the trade-off is a measured fact rather than folklore
			final int[] ids = contiguous(1, 1_000);
			final ArrayBitmap arrayBitmap = new ArrayBitmap(ids);
			final BaseBitmap roaringBitmap = new BaseBitmap(ids);

			assertEquals(arrayBitmap.size(), roaringBitmap.size());
			assertTrue(
				arrayBitmap.getHeapSizeInBytes() > roaringBitmap.getHeapSizeInBytes(),
				"ArrayBitmap does not compress, so it must cost more than the roaring-backed bitmap here"
			);
			assertEquals(JolHeapSize.ownedSize(arrayBitmap), arrayBitmap.getHeapSizeInBytes());
			assertEquals(JolHeapSize.ownedSize(roaringBitmap), roaringBitmap.getHeapSizeInBytes());
		}

		@Test
		void shouldShowSingleRecordBitmapBeingTheLeanestForOneRecord() {
			final SingleRecordBitmap single = new SingleRecordBitmap(42);
			final BaseBitmap roaring = new BaseBitmap(42);

			assertTrue(
				single.getHeapSizeInBytes() * 5 < roaring.getHeapSizeInBytes(),
				"the single-record representation exists precisely because roaring's fixed overhead dwarfs "
					+ "one record"
			);
		}
	}
}
