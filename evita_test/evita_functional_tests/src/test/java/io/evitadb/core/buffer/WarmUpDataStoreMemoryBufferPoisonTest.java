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

package io.evitadb.core.buffer;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serial;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that a {@link WarmUpDataStoreMemoryBuffer} whose flush failed refuses to serve any later flush.
 *
 * A warm-up collect is DESTRUCTIVE: {@code popTrappedChanges} hands the trapped parts out and simultaneously advances
 * every index's change-detection baseline, all BEFORE the write is attempted. If that write then fails, the popped
 * parts are gone — no later flush will re-collect them, because every baseline already says "already persisted". A
 * buffer that kept serving after such a failure would therefore silently write a catalog that is missing data it
 * believes it stored. Poisoning makes that state refuse loudly instead.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(INDEXING)
@DisplayName("Warm-up data-store buffer poisoning after a failed flush")
class WarmUpDataStoreMemoryBufferPoisonTest {

	/**
	 * Creates a throwaway persistence service — the trapped-change paths under test never reach it, and every method
	 * it does not implement throws rather than answering, so a test that starts reaching it says so loudly.
	 *
	 * @return an in-memory persistence service
	 */
	@Nonnull
	private static StoragePartPersistenceService<StorageDescriptor> persistenceService() {
		return new InMemoryStoragePartPersistenceService();
	}

	@Test
	@DisplayName("hands the trapped parts out exactly once, so a failed write can never re-collect them")
	void shouldPopTrappedChangesDestructively() {
		final WarmUpDataStoreMemoryBuffer buffer = new WarmUpDataStoreMemoryBuffer(persistenceService());
		buffer.trapUpdate(0L, new StubStoragePart(1L));

		assertEquals(1, buffer.popTrappedChanges().getTrappedChangesCount(), "the flush must collect the trapped part");
		assertEquals(
			0, buffer.popTrappedChanges().getTrappedChangesCount(),
			"pop is destructive — this is WHY a failed flush must poison the buffer: the parts it popped are gone, so " +
				"a second flush would happily persist a catalog that silently lost them"
		);
	}

	@Test
	@DisplayName("refuses every later collect once a failed flush has poisoned it")
	void shouldRefuseToPopOnceAFailedFlushHasPoisonedTheBuffer() {
		final WarmUpDataStoreMemoryBuffer buffer = new WarmUpDataStoreMemoryBuffer(persistenceService());
		buffer.trapUpdate(0L, new StubStoragePart(1L));

		// the flush collects the parts (destructively) and then its write fails
		buffer.popTrappedChanges();
		buffer.poison(new IOException("no space left on device"));

		// a later session traps more changes and closes: the collect must refuse rather than persist a catalog whose
		// baselines claim the lost parts were already written
		buffer.trapUpdate(0L, new StubStoragePart(2L));
		final GenericEvitaInternalError error = assertThrows(
			GenericEvitaInternalError.class,
			buffer::popTrappedChanges,
			"a poisoned buffer must refuse to collect, never silently proceed"
		);
		assertSame(
			IOException.class, error.getCause().getClass(),
			"the refusal must carry the original flush failure as its cause, so the operator sees what actually broke"
		);
	}

	/**
	 * Minimal {@link StoragePart} identified by its primary key — the trapped-change map stores it purely by reference
	 * and keys it by that pk, so nothing else about it is ever read.
	 *
	 * @param pk the storage-part primary key
	 */
	private record StubStoragePart(long pk) implements StoragePart {
		@Serial private static final long serialVersionUID = 1L;

		@Nonnull
		@Override
		public Long getStoragePartPK() {
			return this.pk;
		}

		@Override
		public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
			return this.pk;
		}
	}

	@Test
	@DisplayName("refuses deterministically — every subsequent collect fails the same way, never just the first")
	void shouldRefuseDeterministicallyOnEveryLaterCollect() {
		final WarmUpDataStoreMemoryBuffer buffer = new WarmUpDataStoreMemoryBuffer(persistenceService());
		buffer.poison(new IOException("no space left on device"));

		assertThrows(GenericEvitaInternalError.class, buffer::popTrappedChanges, "the first later collect must refuse");
		assertThrows(
			GenericEvitaInternalError.class, buffer::popTrappedChanges,
			"poisoning is terminal: a retry must never be allowed to slip through"
		);
	}
}
