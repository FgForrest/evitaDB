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

package io.evitadb.api.functional.storage;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The corruption-level counterpart to the mock-level {@code TransactionManagerSuspensionTest}: it drives the same
 * merge-only flush the warm-up repro uses, but in the ALIVE (transactional) path, so a leaf merge is incorporated
 * through a real trunk-incorporation flush against real persistence and then cold-reloaded from disk.
 *
 * The warm-up bug (a merge-only flush whose freed set is empty ⇒ the root is skipped over a stale page list) is fixed
 * and covered. The remaining question this class exists to answer empirically is the transactional retry window: if a
 * merge-only incorporation flush fails transiently, does a retry — running against the page baseline the failed flush
 * left behind — reproduce the same overlapping-leaf-page corruption on disk, and does the suspend boundary close it?
 *
 * This first test pins the harness itself: a transactional leaf merge with NO injected failure must incorporate and
 * cold-reload cleanly. Later tests build the injection on top of exactly this recipe.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
@DisplayName("Transactional merge-only flush failure must suspend, never corrupt the persisted page list")
class TransactionalMergeFlushFailureSuspendTest implements EvitaTestSupport {

	/** Filterable near-unique timestamp attribute — near-unique values give single-record buckets that a delete drops
	 * outright, which is the shrink that forces a leaf merge (a low-cardinality attribute practically never merges). */
	private static final String ATTRIBUTE_TIMESTAMP = "published";
	/** Anchor for the strictly ascending timestamp stream a re-indexing job writes. */
	private static final OffsetDateTime BASE_TIMESTAMP = OffsetDateTime.of(
		2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC
	);
	/** 513 ascending values ⇒ four bucket-tree leaves [1..128], [129..256], [257..384], [385..513] at block size 256. */
	private static final int TIMESTAMP_COUNT = 513;
	/** The primary keys whose deletion forces leaf 0 to absorb leaf 1 via `mergeWithRight` (see the delete sequence). */
	private static final Set<Integer> DELETED_TIMESTAMP_PKS = Set.of(1, 2, 129);

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("TransactionalMergeFlushFailureSuspend");
		this.evita = new Evita(configuration());
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("a transactional leaf merge with no injected failure incorporates and cold-reloads cleanly")
	void shouldIncorporateAndReloadATransactionalLeafMergeCleanly() {
		warmUpToFourPagedLeavesAndGoLive();

		// ALIVE now: force leaf 0 to merge leaf 1 through a real transaction, incorporated by the trunk-incorporation
		// flush against real persistence (the warm-up repro does this same shrink, but on the warm-up path)
		mergeLeafZeroTransactionally();

		assertEquals(
			3, timestampIndexLeafPageCount(),
			"The transactional deletes must have merged a leaf away (four leaf pages -> three); if this still reports " +
				"four, no merge happened and the test is not exercising the freed-page path."
		);
		assertTrue(isTimestampIndexPaged(), "The index must stay PAGED after the merge, not collapse to SINGLE.");
		assertSurvivingTimestampsResolve();

		// cold reload: the merged, now-three-leaf tree must reassemble from disk with no overlapping page and every
		// surviving value resolving to its primary key
		reopenEvita();
		assertTrue(isTimestampIndexPaged(), "The reloaded index must still be PAGED after a cold load of the leaf pages.");
		assertSurvivingTimestampsResolve();
	}

	@Test
	@DisplayName("a transient flush failure on a transactional merge incorporation suspends, then recovers on reload")
	void shouldSuspendThenRecoverWhenTransactionalMergeIncorporationFlushFailsTransiently() {
		warmUpToFourPagedLeavesAndGoLive();

		// arm the trunk-incorporation flush to fail exactly once at storeHeader: the point AFTER every collection has
		// staged its next page baseline (collectChangedPages runs inside the preceding flushTrappedUpdates) but BEFORE
		// the version is made durable - the "staged, not yet on disk" window the retry trace is about
		final AtomicInteger storeHeaderAttempts = injectTransientStoreHeaderFailure();

		// the merge transaction's incorporation now fails. Item 5 must suspend rather than retry, so the
		// WAIT_FOR_CHANGES_VISIBLE commit completes exceptionally instead of hanging or spinning
		assertThrows(
			Exception.class,
			this::mergeLeafZeroTransactionally,
			"the merge commit must surface the failed incorporation, not hang or silently swallow it"
		);
		assertEquals(
			1, storeHeaderAttempts.get(),
			"the injected storeHeader failure must have fired exactly once (a transient failure, not a deterministic one)"
		);

		// reads keep being served from the last good version: the failed merge left the in-memory catalog at N-1, so
		// every timestamp - including the ones the failed transaction tried to delete - still resolves
		assertAllTimestampsPresent();

		// reload: the delete transaction is durable in the WAL and replays on a fresh instance whose storeHeader is the
		// real one (the injected failure lived only on the suspended instance), so the merge finally lands - and the
		// persisted page list must be sound, NOT the overlapping-leaf-page corruption the retry trace warns about
		reopenEvita();
		assertEquals(3, timestampIndexLeafPageCount(), "the replayed merge must have dropped the tree to three leaves");
		assertTrue(isTimestampIndexPaged(), "the reloaded index must still be PAGED, not collapsed or corrupt");
		assertSurvivingTimestampsResolve();
	}

	/**
	 * Warm-up bulk insert of {@link #TIMESTAMP_COUNT} ascending timestamps, flushed by go-live, leaving the timestamp
	 * bucket tree laid out as four leaf pages before the catalog turns ALIVE.
	 */
	private void warmUpToFourPagedLeavesAndGoLive() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_TIMESTAMP, OffsetDateTime.class, AttributeSchemaEditor::filterable)
					.updateVia(session);
				for (int pk = 1; pk <= TIMESTAMP_COUNT; pk++) {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, pk)
							.setAttribute(ATTRIBUTE_TIMESTAMP, timestamp(pk))
					);
				}
				session.goLiveAndClose();
			}
		);
		assertTrue(isTimestampIndexPaged(), "The timestamp index must be PAGED before the merge.");
		assertEquals(
			4, timestampIndexLeafPageCount(),
			"Go-live must lay the bucket tree out as four leaf pages — the layout the deletes are calibrated against."
		);
	}

	/**
	 * Deletes exactly the three primary keys that force leaf 0 to underflow and absorb leaf 1 in place
	 * (`mergeWithRight`) inside a single transaction, so the merge is incorporated by the trunk-incorporation flush.
	 * The sequence mirrors the warm-up repro: bring the right sibling to the minimum so it cannot donate, then push
	 * leaf 0 one below the minimum.
	 */
	private void mergeLeafZeroTransactionally() {
		try (final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE))) {
			session.deleteEntity(Entities.PRODUCT, 129); // leaf 1: 128 -> 127 (== minimum, can no longer donate)
			session.deleteEntity(Entities.PRODUCT, 1);   // leaf 0: 128 -> 127 (still at the minimum)
			session.deleteEntity(Entities.PRODUCT, 2);   // leaf 0: 127 -> 126 -> underflow -> mergeWithRight
		}
	}

	/**
	 * Closes and reopens the whole Evita instance, forcing a cold load of the PAGED leaf pages from disk.
	 */
	private void reopenEvita() {
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Builds the per-test Evita configuration wired to the (stable across restarts) test path triplet.
	 *
	 * @return the configuration; never null
	 */
	@Nonnull
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

	/**
	 * Produces a distinct, strictly ascending timestamp for the given primary key.
	 *
	 * @param pk the primary key (1-based)
	 * @return a distinct timestamp; never null
	 */
	@Nonnull
	private static OffsetDateTime timestamp(int pk) {
		return BASE_TIMESTAMP.plusSeconds(pk);
	}

	/**
	 * Reports whether the {@link InvertedIndex} backing {@link #ATTRIBUTE_TIMESTAMP} currently uses the PAGED
	 * representation.
	 *
	 * @return {@code true} when the backing bucket tree is multi-leaf (PAGED)
	 */
	private boolean isTimestampIndexPaged() {
		return timestampInvertedIndex().isPaged();
	}

	/**
	 * Returns how many leaf pages the {@link #ATTRIBUTE_TIMESTAMP} bucket tree currently occupies (the set the last
	 * flush staged). A drop across a flush is the observable fingerprint of a leaf merge.
	 *
	 * @return the current live leaf-page count
	 */
	private int timestampIndexLeafPageCount() {
		return timestampInvertedIndex().currentLeafPageSequences().length;
	}

	/**
	 * Resolves the {@link InvertedIndex} backing {@link #ATTRIBUTE_TIMESTAMP} in the global entity index.
	 *
	 * @return the backing inverted index; never null
	 */
	@Nonnull
	private InvertedIndex timestampInvertedIndex() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection =
			(EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		final EntityIndex globalIndex = collection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
		assertNotNull(globalIndex, "Global entity index must exist!");
		final FilterIndex filterIndex = globalIndex.getFilterIndex(new AttributeIndexKey(null, ATTRIBUTE_TIMESTAMP, null));
		assertNotNull(filterIndex, "Filter index for the timestamp attribute must exist!");
		return filterIndex.getInvertedIndex();
	}

	/**
	 * Asserts that every timestamp that was not deleted still resolves to exactly the primary key it was written with,
	 * and that the deleted ones resolve to nothing — the round-trip check across the merged leaf boundary.
	 */
	private void assertSurvivingTimestampsResolve() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int pk = 1; pk <= TIMESTAMP_COUNT; pk++) {
					final List<EntityReferenceContract> matches = session.queryListOfEntityReferences(
						query(
							collection(Entities.PRODUCT),
							filterBy(attributeEquals(ATTRIBUTE_TIMESTAMP, timestamp(pk)))
						)
					);
					if (DELETED_TIMESTAMP_PKS.contains(pk)) {
						assertEquals(0, matches.size(), "Deleted timestamp of pk " + pk + " must match nothing!");
					} else {
						assertEquals(1, matches.size(), "Exactly one entity should match timestamp of pk " + pk);
						assertEquals(pk, matches.get(0).getPrimaryKey(), "Wrong primary key for timestamp of pk " + pk);
					}
				}
				return null;
			}
		);
	}

	/**
	 * Asserts that every timestamp still resolves to its primary key — the read-side check that a suspended catalog
	 * keeps serving the last good version, with the failed transaction's deletes never applied.
	 */
	private void assertAllTimestampsPresent() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int pk = 1; pk <= TIMESTAMP_COUNT; pk++) {
					final List<EntityReferenceContract> matches = session.queryListOfEntityReferences(
						query(
							collection(Entities.PRODUCT),
							filterBy(attributeEquals(ATTRIBUTE_TIMESTAMP, timestamp(pk)))
						)
					);
					assertEquals(
						1, matches.size(),
						"A suspended catalog must still serve pk " + pk + " from the last good version."
					);
					assertEquals(pk, matches.get(0).getPrimaryKey(), "Wrong primary key for timestamp of pk " + pk);
				}
				return null;
			}
		);
	}

	/**
	 * Wraps the live catalog's {@link CatalogPersistenceService} in a delegating proxy that throws once from
	 * {@code storeHeader} — the trunk-incorporation write that follows every collection's page-baseline staging — and
	 * delegates every other call to the real service. The proxy rides the persistence service by reference into the
	 * next transactional catalog copy, so it is in force for the merge transaction incorporated next.
	 *
	 * @return the counter of {@code storeHeader} attempts that reached the proxy (one failure, then pass-through)
	 */
	@Nonnull
	private AtomicInteger injectTransientStoreHeaderFailure() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final AtomicInteger attempts = new AtomicInteger();
		try {
			final Field field = Catalog.class.getDeclaredField("persistenceService");
			field.setAccessible(true);
			final CatalogPersistenceService<?, ?, ?> real = (CatalogPersistenceService<?, ?, ?>) field.get(catalog);
			final CatalogPersistenceService<?, ?, ?> poisoned = (CatalogPersistenceService<?, ?, ?>) Proxy.newProxyInstance(
				CatalogPersistenceService.class.getClassLoader(),
				new Class<?>[]{CatalogPersistenceService.class},
				(proxy, method, args) -> {
					if ("storeHeader".equals(method.getName()) && attempts.getAndIncrement() == 0) {
						throw new UnexpectedIOException(
							"Injected transient store-header failure", "The catalog header could not be written."
						);
					}
					try {
						return method.invoke(real, args);
					} catch (InvocationTargetException ex) {
						throw ex.getCause();
					}
				}
			);
			field.set(catalog, poisoned);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Failed to inject the transient storeHeader failure", ex);
		}
		return attempts;
	}
}
