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

package io.evitadb.index;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.DataStoreChanges.RemovedStoragePart;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.price.VoidPriceIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramCardinalityStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramCardinalityStoragePartRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramLeafStreamKey.StreamKind;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramRangeIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramRootRemoval;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Component-level lifecycle of {@link GroupHistogramEntityIndex}, the histogram-only store of one referenced group:
 * its key, its histogram content and emptiness, the writes it refuses, its commit copy and rollback, and what each
 * flush of it emits.
 *
 * The flush cells pin what the code does in each state it really has, not an idealised list. Production never resets
 * an index's dirty flags after a flush ({@link io.evitadb.core.buffer.DataStoreChanges#popTrappedUpdates()} only calls
 * {@link EntityIndex#notifyFlushed()}), so a warm-up instance re-collected after a flush is a different state from a
 * committed copy, which starts clean. The cell that carries the decision to reuse the inherited dirty flag is the one
 * where a store is created and emptied before its first flush: with an own flag it would emit no manifest at all.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Group histogram store lifecycle")
@Tag(INDEXING)
@Tag(HISTOGRAM)
class GroupHistogramEntityIndexTest {

	/**
	 * The owner entity type of every store in this class.
	 */
	private static final String ENTITY_TYPE = "Product";
	/**
	 * The grouped reference whose histograms the store holds.
	 */
	private static final String REFERENCE_NAME = "parameterValues";
	/**
	 * The primary key of the group whose store every test builds.
	 */
	private static final int GROUP_PK = 100;
	/**
	 * The primary key of the store as an entity index.
	 */
	private static final int INDEX_PK = 7;
	/**
	 * The histogram most tests write.
	 */
	private static final String HISTOGRAM_NAME = "priceHistogram";
	/**
	 * A second histogram, for tests that need two names in one store.
	 */
	private static final String OTHER_HISTOGRAM_NAME = "widthHistogram";
	/**
	 * Distinct values large enough to page a histogram's bucket axis out across several leaf pages.
	 */
	private static final int PAGED_VALUE_COUNT = 3200;
	/**
	 * The key of the store every test builds.
	 */
	private static final EntityIndexKey STORE_KEY =
		GroupHistogramEntityIndex.keyOf(REFERENCE_NAME, GROUP_PK, Scope.LIVE);
	/**
	 * The manifest key of the non-localized {@link #HISTOGRAM_NAME} histogram of the store.
	 */
	private static final HistogramIndexStorageKey HISTOGRAM_KEY =
		new HistogramIndexStorageKey(STORE_KEY, HISTOGRAM_NAME, null);

	/**
	 * Creates a fresh, empty store of the fixture's group.
	 *
	 * @return the store
	 */
	@Nonnull
	private static GroupHistogramEntityIndex newStore() {
		return new GroupHistogramEntityIndex(INDEX_PK, ENTITY_TYPE, STORE_KEY, true);
	}

	/**
	 * Collects what a flush of the index would write, without advancing anything.
	 *
	 * @param index the index to collect
	 * @return the emitted parts in emission order
	 */
	@Nonnull
	private static List<StoragePart> collect(@Nonnull EntityIndex index) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		index.getModifiedStorageParts(trappedChanges);
		final List<StoragePart> parts = new ArrayList<>(8);
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			parts.add(iterator.next());
		}
		return parts;
	}

	/**
	 * Flushes the index the way {@link io.evitadb.core.buffer.DataStoreChanges#popTrappedUpdates()} does: collects its
	 * parts, then advances its baseline with {@link EntityIndex#notifyFlushed()}. No dirty flag is reset, because the
	 * production flush resets none.
	 *
	 * @param index the index to flush
	 * @return the emitted parts in emission order
	 */
	@Nonnull
	private static List<StoragePart> flushLikeTheBuffer(@Nonnull EntityIndex index) {
		final List<StoragePart> parts = collect(index);
		index.notifyFlushed();
		return parts;
	}

	/**
	 * Names every emitted part by its role, sorted, so that a whole emission is compared as one list. A removal of
	 * anything but the store's own entity-id part is named by its target class, which no expectation lists.
	 *
	 * @param parts the emitted parts
	 * @return the sorted role names
	 */
	@Nonnull
	private static List<String> rolesOf(@Nonnull List<StoragePart> parts) {
		final List<String> roles = new ArrayList<>(parts.size());
		for (final StoragePart part : parts) {
			if (part instanceof EntityIndexStoragePart) {
				roles.add("manifest");
			} else if (part instanceof EntityIdsStoragePart) {
				roles.add("membership");
			} else if (part instanceof RemovedStoragePart removed) {
				roles.add(
					removed.containerType() == EntityIdsStoragePart.class && removed.storagePartPK() == INDEX_PK ?
						"membership removal" : "removal of " + removed.containerType().getSimpleName()
				);
			} else if (part instanceof HistogramIndexStoragePart) {
				roles.add("histogram root");
			} else if (part instanceof HistogramCardinalityStoragePart) {
				roles.add("histogram cardinality");
			} else if (part instanceof HistogramIndexLeafPagePart) {
				roles.add("histogram bucket page");
			} else if (part instanceof HistogramRangeIndexLeafPagePart) {
				roles.add("histogram range page");
			} else if (part instanceof HistogramRootRemoval) {
				roles.add("histogram root removal");
			} else if (part instanceof HistogramCardinalityStoragePartRemoval) {
				roles.add("histogram cardinality removal");
			} else if (part instanceof HistogramIndexLeafPageRemoval) {
				roles.add("histogram page removal");
			} else {
				roles.add(part.getClass().getSimpleName());
			}
		}
		Collections.sort(roles);
		return roles;
	}

	/**
	 * Sorts the expected role names the way {@link #rolesOf(List)} sorts the emitted ones.
	 *
	 * @param roles the expected role names in any order
	 * @return the sorted list
	 */
	@Nonnull
	private static List<String> sorted(@Nonnull String... roles) {
		final List<String> result = new ArrayList<>(List.of(roles));
		Collections.sort(result);
		return result;
	}

	/**
	 * Returns the manifest of an emission and asserts that it names nothing but histograms.
	 *
	 * @param parts the emitted parts
	 * @return the manifest
	 */
	@Nonnull
	private static EntityIndexStoragePart histogramOnlyManifestOf(@Nonnull List<StoragePart> parts) {
		EntityIndexStoragePart manifest = null;
		for (final StoragePart part : parts) {
			if (part instanceof EntityIndexStoragePart manifestPart) {
				assertNull(manifest, "A flush must emit at most one manifest");
				manifest = manifestPart;
			}
		}
		assertNotNull(manifest, "The emission carries no manifest");
		assertEquals(INDEX_PK, manifest.getPrimaryKey());
		assertEquals(STORE_KEY, manifest.getEntityIndexKey());
		assertTrue(manifest.getAttributeIndexes().isEmpty(), "A store manifest names no attribute sub-index");
		assertTrue(manifest.getPriceIndexes().isEmpty(), "A store manifest names no price sub-index");
		assertTrue(manifest.getFacetIndexes().isEmpty(), "A store manifest names no facet sub-index");
		assertFalse(manifest.isHierarchyIndex(), "A store manifest names no hierarchy");
		assertNull(manifest.getEntityIds(), "A current manifest carries no inline membership");
		return manifest;
	}

	/**
	 * Commits one transactional change of the store and returns the committed copy.
	 *
	 * @param store  the store to change
	 * @param change the change made inside the transaction
	 * @return the committed copy
	 */
	@Nonnull
	private static GroupHistogramEntityIndex commit(
		@Nonnull GroupHistogramEntityIndex store,
		@Nonnull Consumer<GroupHistogramEntityIndex> change
	) {
		final GroupHistogramEntityIndex[] committed = new GroupHistogramEntityIndex[1];
		assertStateAfterCommit(store, change, (original, copy) -> committed[0] = copy);
		return Objects.requireNonNull(committed[0], "The commit produced no copy");
	}

	/**
	 * Makes one change of the store inside a transaction and collects what a flush would write while the transaction's
	 * layer is still bound - the view of the store the transaction itself has - then commits.
	 *
	 * @param store  the store to change
	 * @param change the change made inside the transaction before the collect
	 * @return the parts collected inside the transaction, in emission order
	 */
	@Nonnull
	private static List<StoragePart> collectWithinTransaction(
		@Nonnull GroupHistogramEntityIndex store,
		@Nonnull Consumer<GroupHistogramEntityIndex> change
	) {
		final List<List<StoragePart>> collected = new ArrayList<>(1);
		assertStateAfterCommit(
			store,
			transactional -> {
				change.accept(transactional);
				collected.add(collect(transactional));
			},
			(original, copy) -> assertNotNull(copy, "The commit produced no copy")
		);
		return collected.get(0);
	}

	/**
	 * The store's key and a freshly created store: what {@link GroupHistogramEntityIndex#keyOf} builds, the only key
	 * kind a store accepts, and the state a new store starts in.
	 */
	@Nested
	@DisplayName("Key and creation")
	class KeyAndCreationTest {

		/**
		 * Verifies that the key names the reference, the scope and the group, carries no representative attribute
		 * values, and tells apart the stores of different groups and scopes.
		 */
		@Test
		@DisplayName("should key the store by reference, scope and group, without representative values")
		void shouldKeyTheStoreByReferenceScopeAndGroup() {
			final EntityIndexKey key = GroupHistogramEntityIndex.keyOf(REFERENCE_NAME, GROUP_PK, Scope.ARCHIVED);

			assertEquals(EntityIndexType.REFERENCED_GROUP_HISTOGRAM, key.type());
			assertEquals(Scope.ARCHIVED, key.scope());
			final RepresentativeReferenceKey discriminator =
				assertInstanceOf(RepresentativeReferenceKey.class, key.discriminator());
			assertEquals(new ReferenceKey(REFERENCE_NAME, GROUP_PK), discriminator.referenceKey());
			assertEquals(0, discriminator.representativeAttributeValues().length);
			assertEquals(REFERENCE_NAME, key.referenceName());
			// one store per group: the key of another group, or of the same group in another scope, is another store
			assertNotEquals(key, GroupHistogramEntityIndex.keyOf(REFERENCE_NAME, GROUP_PK + 1, Scope.ARCHIVED));
			assertNotEquals(key, GroupHistogramEntityIndex.keyOf(REFERENCE_NAME, GROUP_PK, Scope.LIVE));
			assertEquals(key, GroupHistogramEntityIndex.keyOf(REFERENCE_NAME, GROUP_PK, Scope.ARCHIVED));
		}

		/**
		 * Verifies that a store refuses to be created under the key of another index kind.
		 */
		@Test
		@DisplayName("should refuse to be created under the key of another index kind")
		void shouldThrowExceptionWhenCreatedUnderAnotherKind() {
			final EntityIndexKey groupIndexKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_NAME, GROUP_PK))
			);

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> new GroupHistogramEntityIndex(INDEX_PK, ENTITY_TYPE, groupIndexKey, true)
			);
			assertTrue(error.getMessage().contains(EntityIndexType.REFERENCED_GROUP_ENTITY.name()), error.getMessage());
		}

		/**
		 * Verifies that a fresh store is empty, at version one, holds the void price index and no histogram, and
		 * allocates an activity holder only when usage statistics are tracked.
		 */
		@Test
		@DisplayName("should start empty, at version one, with the void price index and no histogram")
		void shouldStartEmpty() {
			final GroupHistogramEntityIndex store = newStore();

			assertTrue(store.isEmpty());
			assertEquals(1, store.version());
			assertEquals(INDEX_PK, store.getPrimaryKey());
			assertEquals(STORE_KEY, store.getIndexKey());
			assertTrue(store.getAllPrimaryKeys().isEmpty());
			assertTrue(store.getLanguages().isEmpty());
			assertSame(VoidPriceIndex.INSTANCE, store.getPriceIndex());
			assertNull(store.getHistogramIndex(HISTOGRAM_NAME));
			assertNull(store.getHistogramFilterIndex(HISTOGRAM_NAME, null));
			assertNotNull(store.getActivity(), "A tracked store must count its own usage");
			assertNull(
				new GroupHistogramEntityIndex(INDEX_PK, ENTITY_TYPE, STORE_KEY, false).getActivity(),
				"An untracked store must not allocate an activity holder"
			);
		}
	}

	/**
	 * What the store holds and when it counts as empty: histogram values per owner and locale, an emptied histogram
	 * leaving the map, and the per (value, owner) counter that decides when a value leaves.
	 */
	@Nested
	@DisplayName("Histogram content and emptiness")
	class HistogramContentTest {

		/**
		 * Verifies that an inserted value is found for its owner through the histogram filter index.
		 */
		@Test
		@DisplayName("should index a value for its owner and hand it out through the histogram filter index")
		void shouldIndexAValueForItsOwner() {
			final GroupHistogramEntityIndex store = newStore();
			store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0);

			final FilterIndex filter = store.getHistogramFilterIndex(HISTOGRAM_NAME, null);
			assertNotNull(filter);
			assertTrue(filter.getRecordsEqualTo(42).contains(10));
			assertNotNull(store.getHistogramIndex(HISTOGRAM_NAME));
			assertNull(store.getHistogramFilterIndex(OTHER_HISTOGRAM_NAME, null));
		}

		/**
		 * Verifies that a store holding a histogram value is not empty although it holds no rows - the cell that turns
		 * red if the emptiness override is lost.
		 */
		@Test
		@DisplayName("should not be empty while it holds a histogram value, though it holds no rows")
		void shouldNotBeEmptyWhileItHoldsAValue() {
			final GroupHistogramEntityIndex store = newStore();
			store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0);

			// the inherited emptiness reads rows, languages, attributes, facets and hierarchy only - all of them empty
			// in a store - so this is the cell that turns red if the override is lost
			assertTrue(store.getAllPrimaryKeys().isEmpty());
			assertFalse(store.isEmpty(), "A store holding a histogram value must not be empty");
		}

		/**
		 * Verifies that the values of one locale never show up in the histogram of another.
		 */
		@Test
		@DisplayName("should keep the histograms of each locale apart")
		void shouldKeepTheHistogramsOfEachLocaleApart() {
			final GroupHistogramEntityIndex store = newStore();
			store.insertHistogramValue(HISTOGRAM_NAME, Locale.ENGLISH, 42, 10, Integer.class, 0);
			store.insertHistogramValue(HISTOGRAM_NAME, Locale.GERMAN, 43, 11, Integer.class, 0);

			final FilterIndex english = store.getHistogramFilterIndex(HISTOGRAM_NAME, Locale.ENGLISH);
			final FilterIndex german = store.getHistogramFilterIndex(HISTOGRAM_NAME, Locale.GERMAN);
			assertNotNull(english);
			assertNotNull(german);
			assertTrue(english.getRecordsEqualTo(42).contains(10));
			assertFalse(english.getRecordsEqualTo(43).contains(11), "English must not carry German values");
			assertTrue(german.getRecordsEqualTo(43).contains(11));
			assertFalse(german.getRecordsEqualTo(42).contains(10), "German must not carry English values");
		}

		/**
		 * Verifies that a histogram whose last value is withdrawn leaves the map, and that the store is empty again
		 * then.
		 */
		@Test
		@DisplayName("should drop an emptied histogram from the map and become empty again")
		void shouldDropAnEmptiedHistogram() {
			final GroupHistogramEntityIndex store = newStore();
			store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0);
			store.insertHistogramValue(HISTOGRAM_NAME, null, 43, 11, Integer.class, 0);

			store.removeHistogramValue(HISTOGRAM_NAME, null, 42, 10, 0);
			assertNotNull(store.getHistogramIndex(HISTOGRAM_NAME), "One owner still contributes");
			assertFalse(store.isEmpty());

			store.removeHistogramValue(HISTOGRAM_NAME, null, 43, 11, 0);
			assertNull(store.getHistogramIndex(HISTOGRAM_NAME), "An emptied histogram must leave the map");
			assertTrue(store.isEmpty(), "A store whose last histogram left must be empty");
		}

		/**
		 * Verifies that a value inserted twice for one owner stays until both units are withdrawn - the known limit
		 * where one unit stands in for a sibling's missing one.
		 */
		@Test
		@DisplayName("should count one unit per reference, so a value stays until the last unit of its owner leaves")
		void shouldKeepAValueUntilTheLastUnitOfItsOwnerLeaves() {
			// two same-valued references of one owner in one group share one (value, owner) counter: this is the known
			// limit of the store, where one unit can stand in for a sibling's missing one
			final GroupHistogramEntityIndex store = newStore();
			store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0);
			store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0);

			store.removeHistogramValue(HISTOGRAM_NAME, null, 42, 10, 0);
			final FilterIndex filter = store.getHistogramFilterIndex(HISTOGRAM_NAME, null);
			assertNotNull(filter, "The second unit must keep the histogram alive");
			assertTrue(filter.getRecordsEqualTo(42).contains(10));

			store.removeHistogramValue(HISTOGRAM_NAME, null, 42, 10, 0);
			assertTrue(store.isEmpty());
		}

		/**
		 * Verifies that withdrawing a value from a histogram the store does not hold fails loudly.
		 */
		@Test
		@DisplayName("should refuse to withdraw a value from a histogram it does not hold")
		void shouldThrowExceptionWhenWithdrawingFromAnAbsentHistogram() {
			final GroupHistogramEntityIndex store = newStore();

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> store.removeHistogramValue(HISTOGRAM_NAME, null, 42, 10, 0)
			);
			assertTrue(error.getMessage().contains(HISTOGRAM_NAME), error.getMessage());
		}
	}

	/**
	 * The writes of an ordinary entity index a store refuses, and the emptiness check that refuses to answer once a
	 * base structure has been filled anyway.
	 */
	@Nested
	@DisplayName("Writes a store never receives")
	class RefusedWritesTest {

		/**
		 * Verifies that inserting an entity primary key is refused, naming the store, and leaves it untouched.
		 */
		@Test
		@DisplayName("should refuse to insert an entity primary key")
		void shouldThrowExceptionWhenInsertingAPrimaryKey() {
			final GroupHistogramEntityIndex store = newStore();

			final GenericEvitaInternalError error =
				assertThrows(GenericEvitaInternalError.class, () -> store.insertPrimaryKeyIfMissing(10));
			assertTrue(error.getMessage().contains("primary key " + INDEX_PK), error.getMessage());
			assertTrue(store.isEmpty(), "A refused write must leave the store untouched");
		}

		/**
		 * Verifies that removing an entity primary key is refused, naming the store.
		 */
		@Test
		@DisplayName("should refuse to remove an entity primary key")
		void shouldThrowExceptionWhenRemovingAPrimaryKey() {
			final GroupHistogramEntityIndex store = newStore();

			final GenericEvitaInternalError error =
				assertThrows(GenericEvitaInternalError.class, () -> store.removePrimaryKey(10));
			assertTrue(error.getMessage().contains("primary key " + INDEX_PK), error.getMessage());
		}

		/**
		 * Verifies that adding a language is refused, naming the store, and leaves it untouched.
		 */
		@Test
		@DisplayName("should refuse to add a language")
		void shouldThrowExceptionWhenAddingALanguage() {
			final GroupHistogramEntityIndex store = newStore();

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> store.upsertLanguage(Locale.ENGLISH, 10, EntitySchema._internalBuild(ENTITY_TYPE))
			);
			assertTrue(error.getMessage().contains("primary key " + INDEX_PK), error.getMessage());
			assertTrue(store.isEmpty(), "A refused write must leave the store untouched");
		}

		/**
		 * Verifies that removing a language is refused, naming the store.
		 */
		@Test
		@DisplayName("should refuse to remove a language")
		void shouldThrowExceptionWhenRemovingALanguage() {
			final GroupHistogramEntityIndex store = newStore();

			final GenericEvitaInternalError error =
				assertThrows(GenericEvitaInternalError.class, () -> store.removeLanguage(Locale.ENGLISH, 10));
			assertTrue(error.getMessage().contains("primary key " + INDEX_PK), error.getMessage());
		}

		/**
		 * Verifies that the emptiness check fails, naming the store, once a facet has been written into it.
		 */
		@Test
		@DisplayName("should refuse to answer whether it is empty once a base structure has been filled anyway")
		void shouldThrowExceptionWhenABaseStructureIsNotEmpty() {
			// a facet reaches the store only through a walker that must never address it - the answer must not hide it
			final GroupHistogramEntityIndex store = newStore();
			store.addFacet(null, new ReferenceKey(REFERENCE_NAME, 1), GROUP_PK, 10);

			final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class, store::isEmpty);
			assertTrue(error.getMessage().contains("primary key " + INDEX_PK), error.getMessage());
		}

		/**
		 * Verifies that the store refuses to name a price root part type, since it never persists prices.
		 */
		@Test
		@DisplayName("should refuse to name a price root part type, since it never persists prices")
		void shouldThrowExceptionWhenAskedForThePriceRootType() {
			final GroupHistogramEntityIndex store = newStore();

			final GenericEvitaInternalError error =
				assertThrows(GenericEvitaInternalError.class, store::getPriceRootStoragePartType);
			assertTrue(error.getMessage().contains("price sub-index"), error.getMessage());
		}
	}

	/**
	 * The commit-time copy and the rollback of a store under a real transactional layer.
	 */
	@Nested
	@DisplayName("Commit copy and rollback")
	@Tag(TRANSACTION)
	class TransactionalCopyTest {

		/**
		 * Verifies that the commit copy carries the histograms, bumps the version of a dirty store and keeps counting
		 * into the same activity holder.
		 */
		@Test
		@DisplayName("should carry its histograms, a bumped version and the same activity holder into the commit copy")
		void shouldCarryItsHistogramsIntoTheCommitCopy() {
			final GroupHistogramEntityIndex store = newStore();
			final IndexActivity activity = store.getActivity();

			assertStateAfterCommit(
				store,
				original -> {
					original.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0);
					original.insertHistogramValue(OTHER_HISTOGRAM_NAME, Locale.ENGLISH, 7, 11, Integer.class, 0);
				},
				(original, committed) -> {
					assertNotNull(committed);
					assertNotSame(original, committed, "The commit must have produced a copy");
					assertEquals(INDEX_PK, committed.getPrimaryKey());
					assertEquals(STORE_KEY, committed.getIndexKey());
					assertEquals(2, committed.version(), "A dirty store must bump its version on commit");
					assertSame(activity, committed.getActivity(), "The copy must keep counting into the same holder");
					final FilterIndex filter = committed.getHistogramFilterIndex(HISTOGRAM_NAME, null);
					assertNotNull(filter);
					assertTrue(filter.getRecordsEqualTo(42).contains(10));
					final FilterIndex other = committed.getHistogramFilterIndex(OTHER_HISTOGRAM_NAME, Locale.ENGLISH);
					assertNotNull(other);
					assertTrue(other.getRecordsEqualTo(7).contains(11));
					assertFalse(committed.isEmpty());
					assertTrue(committed.getAllPrimaryKeys().isEmpty(), "The copy must hold no rows either");
					// the original was not touched outside the transaction
					assertNull(original.getHistogramIndex(HISTOGRAM_NAME));
				}
			);
		}

		/**
		 * Verifies that a transaction that changed nothing in the store leaves its version as it was.
		 */
		@Test
		@DisplayName("should keep its version when a transaction changed nothing in it")
		void shouldKeepItsVersionWhenNothingChanged() {
			final GroupHistogramEntityIndex store = newStore();

			assertStateAfterCommit(
				store,
				original -> {
				},
				(original, committed) -> {
					assertNotNull(committed);
					assertEquals(1, committed.version(), "A clean store must keep its version on commit");
					assertTrue(committed.isEmpty());
				}
			);
		}

		/**
		 * Verifies that withdrawing the last value inside a transaction commits an empty copy and leaves the original
		 * as it was.
		 */
		@Test
		@DisplayName("should commit an emptying withdrawal as an empty copy")
		void shouldCommitAnEmptyingWithdrawal() {
			final GroupHistogramEntityIndex store = newStore();
			store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0);

			assertStateAfterCommit(
				store,
				original -> original.removeHistogramValue(HISTOGRAM_NAME, null, 42, 10, 0),
				(original, committed) -> {
					assertNotNull(committed);
					assertTrue(committed.isEmpty(), "The committed copy of an emptied store must be empty");
					assertNull(committed.getHistogramIndex(HISTOGRAM_NAME));
					// the original keeps its pre-transaction value
					assertNotNull(original.getHistogramFilterIndex(HISTOGRAM_NAME, null));
				}
			);
		}

		/**
		 * Verifies that a base structure filled anyway is carried into the commit copy, which then refuses to answer
		 * whether it is empty, naming the store, rather than having the structure dropped by the copy.
		 */
		@Test
		@DisplayName("should carry a filled base structure into the commit copy, to be refused there, not dropped")
		void shouldCarryAFilledBaseStructureIntoTheCommitCopy() {
			// a facet reaches the store only through a walker that must never address it - the copy must keep it
			// visible to the emptiness check instead of erasing it unseen
			final GroupHistogramEntityIndex committed = commit(
				newStore(), store -> store.addFacet(null, new ReferenceKey(REFERENCE_NAME, 1), GROUP_PK, 10)
			);

			final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class, committed::isEmpty);
			assertTrue(error.getMessage().contains("primary key " + INDEX_PK), error.getMessage());
		}

		/**
		 * Verifies that a rollback reverts every histogram insert and withdrawal across names and locales.
		 */
		@Test
		@DisplayName("should revert every histogram write across names and locales on rollback")
		void shouldRevertEveryHistogramWriteOnRollback() {
			final GroupHistogramEntityIndex store = newStore();
			store.insertHistogramValue(HISTOGRAM_NAME, null, 1, 1_000, Integer.class, 0);

			assertStateAfterRollback(
				store,
				original -> {
					original.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0);
					original.removeHistogramValue(HISTOGRAM_NAME, null, 1, 1_000, 0);
					original.insertHistogramValue(OTHER_HISTOGRAM_NAME, Locale.ENGLISH, 99, 12, Integer.class, 0);
				},
				(original, committed) -> {
					assertNull(committed, "Rollback must leave no committed state");
					final FilterIndex filter = original.getHistogramFilterIndex(HISTOGRAM_NAME, null);
					assertNotNull(filter, "The pre-transaction value must survive the rollback");
					assertTrue(filter.getRecordsEqualTo(1).contains(1_000));
					assertFalse(filter.getRecordsEqualTo(42).contains(10), "The rolled back insert must be gone");
					assertNull(original.getHistogramIndex(OTHER_HISTOGRAM_NAME));
				}
			);
		}
	}

	/**
	 * What a flush of the store emits in each state the code really has - see the class JavaDoc for why a reused
	 * warm-up instance, a committed copy and the view through a transaction's layer are told apart.
	 */
	@Nested
	@DisplayName("Flush emission")
	@Tag(STORAGE)
	class FlushEmissionTest {

		/**
		 * Verifies that a fresh store nothing wrote to emits nothing at all.
		 */
		@Test
		@DisplayName("a fresh store nobody wrote to emits nothing")
		void shouldEmitNothingWhenFreshAndUntouched() {
			assertEquals(List.of(), rolesOf(collect(newStore())));
		}

		/**
		 * Verifies that a store created with values emits its manifest, the no-op removal of its never-written
		 * entity-id part, the histogram root and its cardinality sibling.
		 */
		@Test
		@DisplayName("a store created with values emits its manifest, the no-op membership removal and its histogram")
		void shouldEmitManifestMembershipRemovalAndHistogramWhenCreatedWithValues() {
			final GroupHistogramEntityIndex store = newStore();
			store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0);
			store.insertHistogramValue(HISTOGRAM_NAME, null, 43, 11, Integer.class, 0);

			final List<StoragePart> parts = flushLikeTheBuffer(store);

			assertEquals(
				sorted("manifest", "membership removal", "histogram root", "histogram cardinality"),
				rolesOf(parts)
			);
			assertEquals(Set.of(HISTOGRAM_KEY), histogramOnlyManifestOf(parts).getHistogramIndexes());
		}

		/**
		 * Verifies that a store created and emptied before its first flush still emits its manifest - the cell that
		 * turns red if the histogram writes stop setting the inherited dirty flag.
		 */
		@Test
		@DisplayName("a store created and emptied before its first flush still emits its manifest")
		void shouldEmitManifestWhenCreatedAndEmptiedBeforeTheFirstFlush() {
			// the collection header lists every registered index, so a store that existed at the flush must leave a
			// manifest behind or the next load finds none to read - this is the cell the inherited dirty flag carries
			final GroupHistogramEntityIndex store = newStore();
			store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0);
			store.removeHistogramValue(HISTOGRAM_NAME, null, 42, 10, 0);
			assertTrue(store.isEmpty());

			final List<StoragePart> parts = flushLikeTheBuffer(store);

			assertEquals(sorted("manifest", "membership removal"), rolesOf(parts));
			assertEquals(Set.of(), histogramOnlyManifestOf(parts).getHistogramIndexes());
		}

		/**
		 * Verifies the same through a transaction's layer: a store created and emptied inside a transaction emits its
		 * manifest when collected before the commit.
		 */
		@Test
		@DisplayName("a store created and emptied within a transaction emits its manifest through the layer")
		void shouldEmitManifestWhenCreatedAndEmptiedWithinATransaction() {
			// the same decision seen through a transaction's layer: the inherited flag is transactional, so the
			// collect inside the transaction must see it set. Asserted inside the transaction, so that nothing raised
			// by the commit afterwards can stand in for the answer
			assertStateAfterCommit(
				newStore(),
				store -> {
					store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0);
					store.removeHistogramValue(HISTOGRAM_NAME, null, 42, 10, 0);
					final List<StoragePart> parts = collect(store);
					assertEquals(sorted("manifest", "membership removal"), rolesOf(parts));
					assertEquals(Set.of(), histogramOnlyManifestOf(parts).getHistogramIndexes());
				},
				(original, committed) -> {
					assertNotNull(committed);
					assertTrue(committed.isEmpty(), "The committed copy of the emptied store must be empty");
				}
			);
		}

		/**
		 * Verifies that the same instance re-collected after a flush with nothing changed emits its manifest, the
		 * membership removal, its root and its cardinality again, since no flush resets a dirty flag.
		 */
		@Test
		@DisplayName("the same instance re-collected after a flush with nothing changed emits it all again")
		void shouldEmitEverythingAgainWhenTheSameInstanceIsReCollected() {
			// a warm-up instance is reused across flushes and nothing resets its dirty flags: the inherited one keeps
			// asking for the manifest (its membership is empty, so it never counts as already persisted) and the
			// histogram's own flags keep asking for its root and cardinality
			final GroupHistogramEntityIndex store = newStore();
			store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0);
			flushLikeTheBuffer(store);

			final List<StoragePart> parts = flushLikeTheBuffer(store);

			assertEquals(
				sorted("manifest", "membership removal", "histogram root", "histogram cardinality"),
				rolesOf(parts)
			);
			assertEquals(Set.of(HISTOGRAM_KEY), histogramOnlyManifestOf(parts).getHistogramIndexes());
		}

		/**
		 * Verifies that a store emptied after an earlier flush emits a manifest without the histogram and reclaims the
		 * histogram's root and cardinality.
		 */
		@Test
		@DisplayName("a store emptied after an earlier flush emits its emptied manifest and reclaims the histogram")
		void shouldReclaimTheHistogramWhenEmptiedAfterAnEarlierFlush() {
			final GroupHistogramEntityIndex store = newStore();
			store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0);
			flushLikeTheBuffer(store);
			store.removeHistogramValue(HISTOGRAM_NAME, null, 42, 10, 0);

			final List<StoragePart> parts = flushLikeTheBuffer(store);

			assertEquals(
				sorted(
					"manifest", "membership removal", "histogram root removal", "histogram cardinality removal"
				),
				rolesOf(parts)
			);
			assertEquals(Set.of(), histogramOnlyManifestOf(parts).getHistogramIndexes());
			for (final StoragePart part : parts) {
				if (part instanceof HistogramRootRemoval rootRemoval) {
					assertNull(rootRemoval.getLocale(), "The reclaimed root is the non-localized one");
				}
			}
		}

		/**
		 * Verifies that a committed copy registered by a later transaction that changes nothing emits nothing.
		 */
		@Test
		@DisplayName("a committed copy registered by a later transaction that changes nothing emits nothing")
		void shouldEmitNothingWhenACommittedCopyHasNothingPending() {
			final GroupHistogramEntityIndex committed = commit(
				newStore(), store -> store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0)
			);

			// the copy starts with every flag clean: its own dirty flag is fresh and the histogram's were reset by the
			// merge, so the manifest is neither changed nor owed
			assertEquals(List.of(), rolesOf(collectWithinTransaction(committed, store -> {
			})));
		}

		/**
		 * Verifies that a second unit of an existing (value, owner) emits the cardinality sibling and no histogram root
		 * or leaf page.
		 */
		@Test
		@DisplayName("a second unit of an existing (value, owner) emits the cardinality and no histogram root or page")
		void shouldEmitCardinalityOnlyForASecondUnitOfAnExistingValueAndOwner() {
			final GroupHistogramEntityIndex committed = commit(
				newStore(), store -> store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0)
			);

			// the copy starts clean, so what the next transaction collects is exactly what its own write dirtied
			final List<StoragePart> parts = collectWithinTransaction(
				committed, store -> store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0)
			);

			assertEquals(sorted("manifest", "membership removal", "histogram cardinality"), rolesOf(parts));
			assertEquals(Set.of(HISTOGRAM_KEY), histogramOnlyManifestOf(parts).getHistogramIndexes());
		}

		/**
		 * Verifies that a new value of an inline histogram re-emits its root beside the cardinality sibling.
		 */
		@Test
		@DisplayName("a new value of an inline histogram re-emits its root beside the cardinality")
		void shouldEmitRootForANewValueOfAnInlineHistogram() {
			final GroupHistogramEntityIndex committed = commit(
				newStore(), store -> store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0)
			);

			final List<StoragePart> parts = collectWithinTransaction(
				committed, store -> store.insertHistogramValue(HISTOGRAM_NAME, null, 43, 11, Integer.class, 0)
			);

			assertEquals(
				sorted("manifest", "membership removal", "histogram root", "histogram cardinality"),
				rolesOf(parts)
			);
			for (final StoragePart part : parts) {
				if (part instanceof HistogramIndexStoragePart root) {
					assertFalse(root.isPaged(), "A two-value histogram must ride inline on its root");
				}
			}
		}

		/**
		 * Verifies that dropping a store whose histogram paged out reclaims its whole persisted footprint, each part
		 * exactly once: every written leaf page, the root and the cardinality sibling. Parts and removals are compared
		 * by the storage key they resolve to through one key compressor - the key packs the owning index, the
		 * histogram, the locale and the stream with the page sequence - so a removal aimed at a wrong page, stream,
		 * locale or index, or one page removed twice in place of two, is a mismatch.
		 */
		@Test
		@DisplayName("a dropped paged store reclaims its root, every leaf page and its cardinality, each exactly once")
		void shouldReclaimTheWholeFootprintOfAPagedHistogramWhenDropped() {
			final GroupHistogramEntityIndex store = newStore();
			for (int i = 1; i <= PAGED_VALUE_COUNT; i++) {
				store.insertHistogramValue(HISTOGRAM_NAME, null, i, i, Integer.class, 0);
			}
			// one compressor resolves both sides, so a part and its removal meet on the same storage key: the key packs
			// the owning index, the histogram, the locale and the stream together with the page sequence
			final KeyCompressor keyCompressor = new ReadWriteKeyCompressor(new HashMap<>());
			final List<Long> writtenPages = new ArrayList<>(16);
			final List<Long> writtenRoots = new ArrayList<>(1);
			final List<Long> writtenCardinalities = new ArrayList<>(1);
			for (final StoragePart part : flushLikeTheBuffer(store)) {
				if (part instanceof HistogramIndexLeafPagePart) {
					writtenPages.add(part.computeUniquePartIdAndSet(keyCompressor));
				} else if (part instanceof HistogramIndexStoragePart) {
					writtenRoots.add(part.computeUniquePartIdAndSet(keyCompressor));
				} else if (part instanceof HistogramCardinalityStoragePart) {
					writtenCardinalities.add(part.computeUniquePartIdAndSet(keyCompressor));
				}
			}
			assertTrue(writtenPages.size() >= 3, "The fixture must page the histogram out over several leaves");
			assertEquals(1, writtenRoots.size(), "The fixture must write one histogram root");
			assertEquals(1, writtenCardinalities.size(), "The fixture must write one cardinality sibling");

			assertFootprintReclaimedExactlyOnce(store, keyCompressor, writtenPages, writtenRoots, writtenCardinalities);
		}

		/**
		 * Verifies that dropping a store holding a non-localized histogram beside a localized one that paged out
		 * reclaims the footprint of both, each part exactly once and under its own histogram and locale: both roots,
		 * both cardinality siblings and every leaf page of the localized histogram. A removal aimed at the wrong locale
		 * resolves to a storage key nothing was written under, so it is a mismatch.
		 */
		@Test
		@DisplayName("a dropped store reclaims the footprint of each histogram under its own name and locale")
		void shouldReclaimTheFootprintOfEachHistogramUnderItsOwnLocaleWhenDropped() {
			final GroupHistogramEntityIndex store = newStore();
			store.insertHistogramValue(HISTOGRAM_NAME, null, 42, 10, Integer.class, 0);
			store.insertHistogramValue(HISTOGRAM_NAME, null, 43, 11, Integer.class, 0);
			for (int i = 1; i <= PAGED_VALUE_COUNT; i++) {
				store.insertHistogramValue(OTHER_HISTOGRAM_NAME, Locale.ENGLISH, i, i, Integer.class, 0);
			}
			final KeyCompressor keyCompressor = new ReadWriteKeyCompressor(new HashMap<>());
			final List<Long> writtenPages = new ArrayList<>(16);
			final List<Long> writtenRoots = new ArrayList<>(2);
			final List<Long> writtenCardinalities = new ArrayList<>(2);
			for (final StoragePart part : flushLikeTheBuffer(store)) {
				if (part instanceof HistogramIndexLeafPagePart page) {
					// only the localized histogram holds enough values to page out, so every page carries its locale
					assertEquals(OTHER_HISTOGRAM_NAME, page.getHistogramName());
					assertEquals(Locale.ENGLISH, page.getLocale());
					writtenPages.add(part.computeUniquePartIdAndSet(keyCompressor));
				} else if (part instanceof HistogramIndexStoragePart) {
					writtenRoots.add(part.computeUniquePartIdAndSet(keyCompressor));
				} else if (part instanceof HistogramCardinalityStoragePart) {
					writtenCardinalities.add(part.computeUniquePartIdAndSet(keyCompressor));
				}
			}
			assertTrue(
				writtenPages.size() >= 3, "The fixture must page the localized histogram out over several leaves"
			);
			assertEquals(2, writtenRoots.size(), "The fixture must write one root per histogram");
			assertEquals(
				2, writtenCardinalities.size(), "The fixture must write one cardinality sibling per histogram"
			);

			assertFootprintReclaimedExactlyOnce(store, keyCompressor, writtenPages, writtenRoots, writtenCardinalities);
		}

		/**
		 * Drops the store's persisted footprint and asserts that the removals resolve to exactly the written storage
		 * keys, each once. Both sides go through one key compressor, so a part and its removal meet on the same storage
		 * key - the key packs the owning index, the histogram, the locale and the stream with the page sequence.
		 *
		 * @param store                the flushed store to drop
		 * @param keyCompressor        the compressor the written parts were resolved through
		 * @param writtenPages         the storage keys of the written leaf pages
		 * @param writtenRoots         the storage keys of the written histogram roots
		 * @param writtenCardinalities the storage keys of the written cardinality siblings
		 */
		private void assertFootprintReclaimedExactlyOnce(
			@Nonnull GroupHistogramEntityIndex store,
			@Nonnull KeyCompressor keyCompressor,
			@Nonnull List<Long> writtenPages,
			@Nonnull List<Long> writtenRoots,
			@Nonnull List<Long> writtenCardinalities
		) {
			final TrappedChanges removals = new TrappedChanges();
			store.emitFootprintRemovals(removals);

			final List<Long> removedPages = new ArrayList<>(writtenPages.size());
			final List<Long> removedRoots = new ArrayList<>(writtenRoots.size());
			final List<Long> removedCardinalities = new ArrayList<>(writtenCardinalities.size());
			final Iterator<StoragePart> iterator = removals.getTrappedChangesIterator();
			while (iterator.hasNext()) {
				final StoragePart removal = iterator.next();
				if (removal instanceof HistogramIndexLeafPageRemoval pageRemoval) {
					assertEquals(StreamKind.BUCKET, pageRemoval.getStreamKind());
					removedPages.add(pageRemoval.computeUniquePartIdAndSet(keyCompressor));
				} else if (removal instanceof HistogramRootRemoval rootRemoval) {
					removedRoots.add(rootRemoval.computeUniquePartIdAndSet(keyCompressor));
				} else if (removal instanceof HistogramCardinalityStoragePartRemoval cardinalityRemoval) {
					removedCardinalities.add(cardinalityRemoval.computeUniquePartIdAndSet(keyCompressor));
				} else {
					throw new AssertionError("Unexpected footprint removal " + removal);
				}
			}
			// compared as sorted lists, not sets: a part removed twice in place of two distinct ones is a mismatch
			assertEquals(
				sortedKeys(writtenPages), sortedKeys(removedPages),
				"Every written leaf page must be reclaimed exactly once"
			);
			assertEquals(
				sortedKeys(writtenRoots), sortedKeys(removedRoots),
				"Every written histogram root must be reclaimed exactly once"
			);
			assertEquals(
				sortedKeys(writtenCardinalities), sortedKeys(removedCardinalities),
				"Every written cardinality must be reclaimed exactly once"
			);
		}

		/**
		 * Returns a sorted copy of storage keys, so that two emissions are compared regardless of their order.
		 *
		 * @param keys the storage keys
		 * @return the sorted copy
		 */
		@Nonnull
		private List<Long> sortedKeys(@Nonnull List<Long> keys) {
			final List<Long> result = new ArrayList<>(keys);
			Collections.sort(result);
			return result;
		}
	}

}
