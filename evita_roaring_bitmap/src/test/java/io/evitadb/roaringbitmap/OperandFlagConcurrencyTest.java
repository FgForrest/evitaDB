package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static io.evitadb.roaringbitmap.SharedContainerLockstepFuzzTest.assertLockstep;

/**
 * Concurrent borrowers leave the published operand's flag array unchanged and flag surviving aliases.
 * The array snapshots deterministically reject operand flag writes; the stress phase cannot force the
 * old lost-write interleaving, and later wholesale marking could repair a lost flag before the audit.
 */
class OperandFlagConcurrencyTest {

	/** Races eight readers, joins all of them, checks the flag-array snapshots, and audits surviving aliases. */
	@Test
	@Timeout(value = 2, unit = TimeUnit.SECONDS)
	void concurrentBorrowersPreserveEffectiveFlags() throws Exception {
		final PersistentRoaringBitmap published = OperandFlagOwnershipTest.operand(0, false);
		final boolean[] sharedReference = published.shared;
		final boolean[] sharedContents = published.shared.clone();
		final CyclicBarrier start = new CyclicBarrier(8);
		final ExecutorService executor = Executors.newFixedThreadPool(8);
		try {
			final List<Future<List<PersistentRoaringBitmap>>> tasks = new ArrayList<>();
			for (int thread = 0; thread < 8; thread++) {
				tasks.add(executor.submit(() -> {
					start.await();
					final List<PersistentRoaringBitmap> results = new ArrayList<>();
					for (int iteration = 0; iteration < 250; iteration++) {
						final PersistentRoaringBitmap privateBitmap = PersistentRoaringBitmap.bitmapOf(63 << 16);
						final PersistentRoaringBitmap result;
						switch (iteration % 5) {
							case 0 -> { privateBitmap.or(published); result = privateBitmap; }
							case 1 -> result = PersistentRoaringBitmap.or(published, privateBitmap);
							case 2 -> result = PersistentRoaringBitmap.xor(published, privateBitmap);
							case 3 -> result =
								PersistentRoaringBitmap.and(List.of(published).iterator(), 0L, 64L << 16);
							default -> result = published.clone();
						}
						results.add(result);
					}
					return results;
				}));
			}
			final List<PersistentRoaringBitmap> results = new ArrayList<>();
			for (final Future<List<PersistentRoaringBitmap>> task : tasks) {
				results.addAll(task.get(1500, TimeUnit.MILLISECONDS));
			}
			assertSame(sharedReference, published.shared, "concurrent borrowers replaced the operand flag array");
			assertArrayEquals(sharedContents, published.shared, "concurrent borrowers changed operand flag contents");
			for (final PersistentRoaringBitmap result : results) {
				assertLockstep(result, "concurrent result");
				for (int i = 0; i < result.highLowContainer.size(); i++) {
					final int source = published.highLowContainer.getIndex(result.getKeyAtIndex(i));
					if (source >= 0 && published.getContainerAtIndex(source) == result.getContainerAtIndex(i)) {
						assertTrue(published.isShared(source), "published alias is unflagged at slot " + source);
					}
				}
			}
			assertTrue(published.lent, "concurrent borrowers must lend the source");
			assertLockstep(published, "published operand");
		} finally {
			executor.shutdownNow();
		}
	}
}
