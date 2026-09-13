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

import io.evitadb.spi.store.catalog.persistence.ReferenceNameFilterContext;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the per-execution storage record scope: what it serves from its own memory, what it refuses to serve, and
 * how it adds up the I/O the execution performed.
 *
 * Two of its rules decide correctness rather than speed. A record decoded under a reference name filter holds only
 * the references of those names, so it may only be handed back to a read asking for the same ones - which is why the
 * filter is part of the record's identity. And a record that this scope served rather than loaded must not be billed
 * as a second read, which is decided where the read happens rather than remembered afterwards - so the accounting
 * holds on to nothing and the ceiling on the records bounds the whole scope.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(QUERY)
@Tag(CACHE)
@DisplayName("Per-execution storage record scope")
class StorageAccessScopeTest {
	/**
	 * Mirrors the scope's own (private) ceiling on the number of records it is willing to hold.
	 */
	private static final int MAX_RECORDS = 65_536;
	/**
	 * Catalog version every fetch below is issued at - one execution pins a single version, and the key carries it
	 * so that a nested execution reading at another one cannot be served the wrong record.
	 */
	private static final long CATALOG_VERSION = 1L;
	private static final String BRAND = "brand";
	private static final String CATEGORY = "category";

	/**
	 * Loader that must never be consulted - handed to a fetch the scope is expected to answer on its own.
	 */
	@Nonnull
	private static Supplier<TestStoragePart> failingLoader() {
		return () -> {
			throw new IllegalStateException("The scope was expected to answer without consulting the loader!");
		};
	}

	/**
	 * Loader that counts how many times it was consulted and hands back a fresh record each time, the way a genuine
	 * storage read does.
	 */
	@Nonnull
	private static Supplier<TestStoragePart> countingLoader(@Nonnull AtomicInteger counter, long primaryKey) {
		return () -> {
			counter.incrementAndGet();
			return new TestStoragePart(primaryKey);
		};
	}

	/**
	 * No test may leave a scope bound to the thread - the surefire fork reuses it for the next test class, and
	 * a leaked scope would serve that class records it never read.
	 */
	@AfterEach
	void releaseLeakedScopes() {
		StorageAccessScope leaked = StorageAccessScope.getIfActive();
		while (leaked != null) {
			leaked.close();
			leaked = StorageAccessScope.getIfActive();
		}
	}

	/**
	 * Minimal storage part - the scope never looks inside a record, it only hands it back. Declared as a record so
	 * that two separately loaded instances of the same key are `equals` but not identical, which is what the
	 * accounting rules below are distinguished by.
	 *
	 * @param primaryKey numeric key the part would be stored under
	 */
	private record TestStoragePart(long primaryKey) implements StoragePart {
		@Serial private static final long serialVersionUID = -2_071_428_558_411_236_812L;

		@Nullable
		@Override
		public Long getStoragePartPK() {
			return this.primaryKey;
		}

		@Override
		public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
			return this.primaryKey;
		}
	}

	@Nested
	@DisplayName("Record de-duplication")
	class RecordDeduplicationTest {

		@Test
		@DisplayName("a record is loaded once and served from the scope afterwards")
		void shouldLoadOnceAndServeSubsequentReadsFromScope() {
			final Object owner = new Object();
			final AtomicInteger loads = new AtomicInteger();

			try (final StorageAccessScope scope = StorageAccessScope.install()) {
				final TestStoragePart first = scope.fetch(
					owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, countingLoader(loads, 1L)
				);
				final TestStoragePart second = scope.fetch(
					owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, countingLoader(loads, 1L)
				);

				assertEquals(1, loads.get());
				assertSame(first, second);
			}
		}

		@Test
		@DisplayName("a record addressed by a non-numeric key is de-duplicated by that key")
		void shouldDeduplicateRecordsAddressedByOriginalKey() {
			final Object owner = new Object();
			final AtomicInteger loads = new AtomicInteger();

			try (final StorageAccessScope scope = StorageAccessScope.install()) {
				scope.fetch(
					owner, CATALOG_VERSION, TestStoragePart.class, Long.MIN_VALUE, "code", countingLoader(loads, 1L)
				);
				scope.fetch(
					owner, CATALOG_VERSION, TestStoragePart.class, Long.MIN_VALUE, "code", countingLoader(loads, 1L)
				);
				scope.fetch(
					owner, CATALOG_VERSION, TestStoragePart.class, Long.MIN_VALUE, "name", countingLoader(loads, 2L)
				);

				assertEquals(2, loads.get());
			}
		}

		@Test
		@DisplayName("the same record read at two catalog versions is read twice")
		void shouldKeyRecordsByCatalogVersion() {
			// one execution pins a single version, but nothing enforces that and a nested execution joins the scope
			// the outer one opened - so the version belongs in the key rather than in a prose invariant
			final Object owner = new Object();
			final AtomicInteger loads = new AtomicInteger();

			try (final StorageAccessScope scope = StorageAccessScope.install()) {
				final TestStoragePart first = scope.fetch(
					owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, countingLoader(loads, 1L)
				);
				final TestStoragePart second = scope.fetch(
					owner, CATALOG_VERSION + 1, TestStoragePart.class, 1L, null, countingLoader(loads, 1L)
				);

				assertEquals(2, loads.get());
				assertNotSame(first, second);
			}
		}

		@Test
		@DisplayName("records of different owners with the same key do not collide")
		void shouldDistinguishRecordsOfDifferentOwners() {
			// readers define no equality of their own, so the key compares them by identity - two collections reading
			// the same container type and primary key must not see each other's records
			final Object firstOwner = new Object();
			final Object secondOwner = new Object();
			final AtomicInteger loads = new AtomicInteger();

			try (final StorageAccessScope scope = StorageAccessScope.install()) {
				final TestStoragePart first = scope.fetch(
					firstOwner, CATALOG_VERSION, TestStoragePart.class, 1L, null, countingLoader(loads, 1L)
				);
				final TestStoragePart second = scope.fetch(
					secondOwner, CATALOG_VERSION, TestStoragePart.class, 1L, null, countingLoader(loads, 1L)
				);

				assertEquals(2, loads.get());
				assertNotSame(first, second);
			}
		}

		@Test
		@DisplayName("a record known not to exist is not looked up twice")
		void shouldRememberMissingRecords() {
			final Object owner = new Object();
			final AtomicInteger loads = new AtomicInteger();

			try (final StorageAccessScope scope = StorageAccessScope.install()) {
				final TestStoragePart missing = scope.fetch(
					owner, CATALOG_VERSION, TestStoragePart.class, 1L, null,
					() -> {
						loads.incrementAndGet();
						return null;
					}
				);

				assertNull(missing);
				assertNull(scope.fetch(owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, failingLoader()));
				assertEquals(1, loads.get());
			}
		}
	}

	@Nested
	@DisplayName("Reference name filter as part of the record identity")
	class ReferenceNameFilterKeyTest {

		@Test
		@DisplayName("a narrowed record is never served to an unrestricted read")
		void shouldKeyRecordsByReferenceNameFilter() {
			final Object owner = new Object();
			final AtomicInteger loads = new AtomicInteger();

			try (final StorageAccessScope scope = StorageAccessScope.install()) {
				final TestStoragePart narrowed = ReferenceNameFilterContext.executeWithReferenceNameFilter(
					Set.of(BRAND),
					() -> scope.fetch(
						owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, countingLoader(loads, 1L)
					)
				);
				final TestStoragePart complete = scope.fetch(
					owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, countingLoader(loads, 1L)
				);

				assertEquals(2, loads.get());
				assertNotSame(narrowed, complete);
			}
		}

		@Test
		@DisplayName("records narrowed to different names are kept apart")
		void shouldKeepRecordsOfDifferentFiltersApart() {
			final Object owner = new Object();
			final AtomicInteger loads = new AtomicInteger();

			try (final StorageAccessScope scope = StorageAccessScope.install()) {
				ReferenceNameFilterContext.executeWithReferenceNameFilter(
					Set.of(BRAND),
					() -> scope.fetch(
						owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, countingLoader(loads, 1L)
					)
				);
				ReferenceNameFilterContext.executeWithReferenceNameFilter(
					Set.of(CATEGORY),
					() -> scope.fetch(
						owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, countingLoader(loads, 1L)
					)
				);

				assertEquals(2, loads.get());
			}
		}

		@Test
		@DisplayName("equal filter sets describe the same record")
		void shouldTreatEqualFilterSetsAsTheSameKey() {
			// the key compares the filter by Set equality, so an equivalent projection reuses what was already read
			// no matter which collection implementation or iteration order it arrived in
			final Object owner = new Object();
			final AtomicInteger loads = new AtomicInteger();
			final Set<String> reordered = new LinkedHashSet<>(List.of(CATEGORY, BRAND));

			try (final StorageAccessScope scope = StorageAccessScope.install()) {
				final TestStoragePart first = ReferenceNameFilterContext.executeWithReferenceNameFilter(
					Set.of(BRAND, CATEGORY),
					() -> scope.fetch(
						owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, countingLoader(loads, 1L)
					)
				);
				final TestStoragePart second = ReferenceNameFilterContext.executeWithReferenceNameFilter(
					reordered,
					() -> scope.fetch(owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, failingLoader())
				);

				assertEquals(1, loads.get());
				assertSame(first, second);
			}
		}
	}

	@Nested
	@DisplayName("Ceiling on the number of held records")
	class CeilingTest {

		@Test
		@DisplayName("above the ceiling the scope stops accepting records but keeps serving what it holds")
		void shouldDegradeToUncachedBehaviourAboveCeiling() {
			final Object owner = new Object();
			final AtomicInteger loads = new AtomicInteger();

			try (final StorageAccessScope scope = StorageAccessScope.install()) {
				for (int i = 0; i < MAX_RECORDS; i++) {
					scope.fetch(owner, CATALOG_VERSION, TestStoragePart.class, i, null, () -> null);
				}

				// what the scope already holds is still answered without a read
				assertNull(scope.fetch(owner, CATALOG_VERSION, TestStoragePart.class, 0L, null, failingLoader()));

				// a key it has no room for is re-read on every single ask
				scope.fetch(
					owner, CATALOG_VERSION, TestStoragePart.class, MAX_RECORDS, null, countingLoader(loads, MAX_RECORDS)
				);
				scope.fetch(
					owner, CATALOG_VERSION, TestStoragePart.class, MAX_RECORDS, null, countingLoader(loads, MAX_RECORDS)
				);

				assertEquals(2, loads.get());
			}
		}
	}

	@Nested
	@DisplayName("I/O accounting")
	class IoAccountingTest {

		@Test
		@DisplayName("a record the scope answered itself is not billed")
		void shouldNotBillRecordServedFromScope() {
			final Object owner = new Object();
			final TestStoragePart record = new TestStoragePart(1L);

			try (final StorageAccessScope scope = StorageAccessScope.install()) {
				// the first ask goes to the storage
				StorageAccessScope.noteRecordRead(
					scope.fetch(owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, () -> record), 100
				);
				// the second is answered from the scope, so no I/O happened and nothing may be billed for it
				StorageAccessScope.noteRecordRead(
					scope.fetch(owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, failingLoader()), 100
				);

				assertEquals(1, scope.getIoFetchCount());
				assertEquals(100, scope.getIoFetchedBytes());
			}
		}

		@Test
		@DisplayName("the answer tells the caller whether the read was physical")
		void shouldReportWhetherTheReadWasPhysical() {
			// the caller bills the very same read to the entity it is composing, so it has to learn what the scope
			// decided - otherwise one physical read is described twice, once per entity that reached it
			final Object owner = new Object();
			final TestStoragePart record = new TestStoragePart(1L);

			try (final StorageAccessScope scope = StorageAccessScope.install()) {
				assertTrue(
					StorageAccessScope.noteRecordRead(
						scope.fetch(owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, () -> record), 100
					),
					"The first ask went to the storage."
				);
				assertFalse(
					StorageAccessScope.noteRecordRead(
						scope.fetch(owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, failingLoader()), 100
					),
					"The second ask was answered by the scope and cost no I/O at all."
				);
			}
		}

		@Test
		@DisplayName("a read issued outside any execution is reported as physical")
		void shouldReportReadOutsideAnyScopeAsPhysical() {
			// nothing de-duplicates reads outside a scope, so a caller has to keep billing them
			assertNull(StorageAccessScope.getIfActive());

			assertTrue(StorageAccessScope.noteRecordRead(new TestStoragePart(1L), 100));
		}

		@Test
		@DisplayName("a read the scope never answered is billed as it comes")
		void shouldBillReadTheScopeNeverAnswered() {
			// binary fetches and reads issued with no scope in the way never pass through the scope at all, so they
			// are physical by construction - two of them are two reads even when they carry equal records
			try (final StorageAccessScope scope = StorageAccessScope.install()) {
				StorageAccessScope.noteRecordRead(new TestStoragePart(1L), 100);
				StorageAccessScope.noteRecordRead(new TestStoragePart(1L), 100);

				assertEquals(2, scope.getIoFetchCount());
				assertEquals(200, scope.getIoFetchedBytes());
			}
		}

		@Test
		@DisplayName("a read performed outside any execution is silently ignored")
		void shouldIgnoreRecordReadOutsideAnyScope() {
			assertNull(StorageAccessScope.getIfActive());

			// the write path reads storage records constantly from outside a query - noting those must be free and
			// must never fail
			StorageAccessScope.noteRecordRead(new TestStoragePart(1L), 100);

			assertNull(StorageAccessScope.getIfActive());
		}

		@Test
		@DisplayName("the accounting holds on to no record of its own")
		void shouldNotRetainRecordsItAccountedFor() {
			// whether a read was physical is decided by fetch(), which knows whether it had to call its loader, so
			// nothing has to be remembered afterwards. Past the ceiling the scope holds no record at all and each
			// ask is a genuine read that is billed again - the observable proof that the accounting is not keeping
			// one hard reference per record for the whole execution, which is the footprint the ceiling bounds.
			final Object owner = new Object();
			final TestStoragePart aboveTheCeiling = new TestStoragePart(MAX_RECORDS);

			try (final StorageAccessScope scope = StorageAccessScope.install()) {
				for (int i = 0; i < MAX_RECORDS; i++) {
					scope.fetch(owner, CATALOG_VERSION, TestStoragePart.class, i, null, () -> null);
				}

				StorageAccessScope.noteRecordRead(
					scope.fetch(
						owner, CATALOG_VERSION, TestStoragePart.class, MAX_RECORDS, null, () -> aboveTheCeiling
					),
					100
				);
				StorageAccessScope.noteRecordRead(
					scope.fetch(
						owner, CATALOG_VERSION, TestStoragePart.class, MAX_RECORDS, null, () -> aboveTheCeiling
					),
					100
				);

				assertEquals(2, scope.getIoFetchCount());
				assertEquals(200, scope.getIoFetchedBytes());
			}
		}
	}

	@Nested
	@DisplayName("Nesting and closing")
	class NestingTest {

		@Test
		@DisplayName("a nested execution joins the scope already bound to the thread")
		void shouldJoinScopeAlreadyBoundToThread() {
			try (final StorageAccessScope outer = StorageAccessScope.install()) {
				try (final StorageAccessScope inner = StorageAccessScope.install()) {
					assertSame(outer, inner);
				}
			}
		}

		@Test
		@DisplayName("only the outermost close releases the thread binding")
		void shouldReleaseThreadBindingOnlyWhenOutermostScopeCloses() {
			final Object owner = new Object();
			final AtomicInteger loads = new AtomicInteger();

			final StorageAccessScope outer = StorageAccessScope.install();
			final TestStoragePart loaded = outer.fetch(
				owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, countingLoader(loads, 1L)
			);
			final StorageAccessScope inner = StorageAccessScope.install();

			inner.close();

			assertSame(outer, StorageAccessScope.getIfActive());
			assertSame(loaded, outer.fetch(owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, failingLoader()));

			outer.close();

			assertNull(StorageAccessScope.getIfActive());
			assertEquals(1, loads.get());
		}

		@Test
		@DisplayName("a nested execution shares the outer execution's counters")
		void shouldShareCountersWithNestedScope() {
			// the consequence of joining rather than creating: a nested query plan reports the counters of the whole
			// thread bound scope, not only of what it read itself
			final StorageAccessScope outer = StorageAccessScope.install();
			StorageAccessScope.noteRecordRead(new TestStoragePart(1L), 100);

			final StorageAccessScope inner = StorageAccessScope.install();
			StorageAccessScope.noteRecordRead(new TestStoragePart(2L), 50);

			assertEquals(2, inner.getIoFetchCount());
			assertEquals(150, inner.getIoFetchedBytes());

			inner.close();
			outer.close();

			assertNull(StorageAccessScope.getIfActive());
		}

		@Test
		@DisplayName("closing the outermost scope discards everything it held")
		void shouldDiscardHeldRecordsWhenOutermostScopeCloses() {
			final Object owner = new Object();
			final AtomicInteger loads = new AtomicInteger();

			final StorageAccessScope scope = StorageAccessScope.install();
			scope.fetch(owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, countingLoader(loads, 1L));
			scope.close();

			// nothing survives the scope - the same key asked again goes back to the loader
			scope.fetch(owner, CATALOG_VERSION, TestStoragePart.class, 1L, null, countingLoader(loads, 1L));

			assertEquals(2, loads.get());
			assertNull(StorageAccessScope.getIfActive());
		}
	}

}
