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

package io.evitadb.index.attribute;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.attribute.AttributeIndex.UniquenessEnforcement;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.VMLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.index.IndexHeapSizeAssertions.readField;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the lazy allocation of {@link AttributeIndex}'s seven sub-index maps.
 *
 * # What is being defended
 *
 * The maps used to be built by the constructor, so an attribute index that never indexed anything still cost 680 B —
 * an 80 B object plus 600 B of empty scaffolding. A production e-commerce catalog carries 564,187 entity indexes and
 * left 64.4 % of the observable family slots allocated and empty, which made that scaffolding a floor of 366 MiB
 * before a single value was stored.
 *
 * The property that removes it is narrow and easy to lose by accident: **a family must be allocated by a write and by
 * nothing else**, and the commit merge must not resurrect the families that committed nothing. Each test below fixes
 * one half of that, and the read-path test is the one that would catch a future accessor quietly calling a
 * `getOrCreate…Map()` because it needed a non-null map to call `get` on.
 *
 * The fields are read reflectively because absence is exactly what is being asserted — an unallocated family has no
 * public surface to observe, which is the point of it. {@link io.evitadb.index.IndexHeapSizeAssertions#readField} is
 * the same accessor the heap-size suites use for the same reason.
 *
 * @author Claude (lazy attribute-index families), FG Forrest a.s. (c) 2026
 */
@DisplayName("Lazy attribute-index families")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class LazyAttributeIndexFamilyTest {

	private static final String ENTITY_TYPE = "product";
	private static final String ATTRIBUTE_FOLDABLE_CODE = "code";
	private static final String ATTRIBUTE_GLOBAL_CODE = "globalCode";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_RANGE = "range";
	private static final Set<Locale> ALLOWED_LOCALES = Set.of(Locale.ENGLISH);

	/**
	 * The names of the seven sub-index map fields, so a test can assert about all of them without naming each one
	 * twice. The order is the order the class declares them in.
	 */
	private static final String[] FAMILY_FIELDS = {
		"uniqueIndex", "filterIndex", "uniqueViewIndex", "sortIndex", "chainIndex",
		"sharedValueIndex", "sharedRangeIndex"
	};

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
	);

	/**
	 * One schema carrying the shape that reaches each family:
	 *
	 * - `code` — non-localized unique, therefore FOLDABLE, so it registers a folded unique view
	 * - `globalCode` — localized and unique ACROSS locales, the only shape that keeps a standalone owner unique index
	 * - `name` — plain filterable, which builds the shared value tree and its filter view and nothing else
	 * - `priority` — sortable {@link Integer}, which builds a sort index
	 * - `order` — sortable {@link Predecessor}, which builds a chain index
	 * - `range` — filterable {@link IntegerNumberRange}, the only shape that also builds the range companion
	 */
	private static final EntitySchemaContract SCHEMA = new InternalEntitySchemaBuilder(
		CATALOG_SCHEMA, EntitySchema._internalBuild(ENTITY_TYPE)
	)
		.withAttribute(ATTRIBUTE_FOLDABLE_CODE, String.class, AttributeSchemaEditor::unique)
		.withAttribute(ATTRIBUTE_GLOBAL_CODE, String.class, thatIs -> thatIs.localized().unique())
		.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::filterable)
		.withAttribute(ATTRIBUTE_PRIORITY, Integer.class, AttributeSchemaEditor::sortable)
		.withAttribute(ATTRIBUTE_ORDER, Predecessor.class, AttributeSchemaEditor::sortable)
		.withAttribute(ATTRIBUTE_RANGE, IntegerNumberRange.class, AttributeSchemaEditor::filterable)
		.toInstance();

	private static final EntityAttributeSchemaContract FOLDABLE_UNIQUE_CODE = attribute(ATTRIBUTE_FOLDABLE_CODE);
	private static final EntityAttributeSchemaContract OWNER_UNIQUE_CODE = attribute(ATTRIBUTE_GLOBAL_CODE);
	private static final EntityAttributeSchemaContract FILTERABLE_NAME = attribute(ATTRIBUTE_NAME);
	private static final EntityAttributeSchemaContract SORTABLE_PRIORITY = attribute(ATTRIBUTE_PRIORITY);
	private static final EntityAttributeSchemaContract CHAIN_ORDER = attribute(ATTRIBUTE_ORDER);
	private static final EntityAttributeSchemaContract FILTERABLE_RANGE = attribute(ATTRIBUTE_RANGE);

	/**
	 * @param name the attribute declared on {@link #SCHEMA}
	 * @return its assembled schema
	 */
	@Nonnull
	private static EntityAttributeSchemaContract attribute(@Nonnull String name) {
		return SCHEMA.getAttribute(name).orElseThrow();
	}

	/**
	 * Reads one sub-index family off an index without going through a public accessor, because an unallocated family
	 * has no public accessor that can tell absence from emptiness.
	 *
	 * @param index the index to inspect
	 * @param field the family's field name
	 * @return the family map, or `null` when nothing has ever written to it
	 */
	@Nullable
	private static Object family(@Nonnull AttributeIndex index, @Nonnull String field) {
		return readField(index, field);
	}

	/**
	 * Asserts that exactly the named families are allocated on `index` and every other one is still absent.
	 *
	 * @param index    the index to inspect
	 * @param expected the field names of the families that must be present
	 */
	private static void assertOnlyFamiliesAllocated(@Nonnull AttributeIndex index, @Nonnull String... expected) {
		final Set<String> wanted = new HashSet<>(List.of(expected));
		final List<String> allocated = new ArrayList<>(FAMILY_FIELDS.length);
		for (final String field : FAMILY_FIELDS) {
			if (family(index, field) != null) {
				allocated.add(field);
			}
		}
		assertEquals(
			wanted, new HashSet<>(allocated),
			"exactly the families the write needs must be allocated - allocated " + allocated
		);
	}

	/**
	 * What an {@link AttributeIndex} that has never been written to must report: its own object and nothing else.
	 * The object holds an id and fourteen references (the entity type, the reference key, the seven sub-index maps
	 * and the five leaf-page snapshots), and those slots exist whether or not anything hangs off them.
	 *
	 * @return the expected heap size of an untouched attribute index in bytes
	 */
	private static long emptyIndexBytes() {
		final VMLayout layout = VMLayout.current();
		return layout.sizeOfObject(Long.BYTES + 14L * layout.referenceSize());
	}

	@Nested
	@DisplayName("An index nothing was ever written to")
	class UntouchedIndex {

		@Test
		@DisplayName("costs its own object alone, an eighth of the 680 B it used to cost")
		void shouldCostOnlyItsOwnObject() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			final long reported = index.getHeapSizeInBytes();
			assertEquals(
				emptyIndexBytes(), reported,
				"an untouched attribute index must weigh its object and nothing more"
			);
			// the gate, stated in the absolute terms the production measurement used: on a 64-bit VM with compressed
			// oops the object is 80 B, where the eagerly built index measured 680 B (80 B object + 7 maps x ~86 B)
			assertTrue(
				reported <= 96,
				"an untouched attribute index must stay under 96 B, where it used to be 680 B - was " + reported
			);
		}

		@Test
		@DisplayName("allocates none of the seven sub-index families")
		void shouldAllocateNoFamilyAtConstruction() {
			assertOnlyFamiliesAllocated(new EntityAttributeIndex(ENTITY_TYPE));
		}

		@Test
		@DisplayName("answers every read accessor without allocating anything")
		void shouldNotAllocateAFamilyByReadingFromIt() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			final AttributeIndexKey probe = new AttributeIndexKey(null, ATTRIBUTE_NAME, null);

			assertTrue(index.isAttributeIndexEmpty());
			assertTrue(index.getUniqueIndexes().isEmpty());
			assertTrue(index.getFilterIndexes().isEmpty());
			assertTrue(index.getSortIndexes().isEmpty());
			assertTrue(index.getChainIndexes().isEmpty());
			assertNull(index.getUniqueIndex(probe));
			assertNull(index.getUniqueIndex(null, OWNER_UNIQUE_CODE, Scope.LIVE, Locale.ENGLISH));
			assertNull(index.getFilterIndex(probe));
			assertNull(index.getFilterIndex(null, FILTERABLE_NAME, null));
			assertNull(index.getSortIndex(probe));
			assertNull(index.getSortIndex(null, SORTABLE_PRIORITY, null));
			assertNull(index.getChainIndex(probe));
			assertNull(index.getChainIndex(null, CHAIN_ORDER, null));

			assertOnlyFamiliesAllocated(index);
			assertEquals(
				emptyIndexBytes(), index.getHeapSizeInBytes(),
				"reading an absent family must not bring it into existence"
			);
		}

		@Test
		@DisplayName("emits no storage part and no manifest key when flushed")
		void shouldFlushWithoutTouchingAnAbsentFamily() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			final TrappedChanges changes = new TrappedChanges();
			index.getModifiedStorageParts(1, changes);
			assertEquals(0, countTrapped(changes), "an index holding nothing has nothing to write");

			assertOnlyFamiliesAllocated(index);
		}
	}

	@Nested
	@DisplayName("The first write to a family")
	class FirstWrite {

		@Test
		@DisplayName("brings up the shared value tree and its filter view, and nothing else")
		void shouldAllocateTheFilterFamiliesOnAPlainFilterWrite() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			index.insertFilterAttribute(null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "value", 1, false);

			// notably NOT the range companion: only a range-typed attribute has one, and paying for it on every
			// filterable string attribute is precisely the waste being removed
			assertOnlyFamiliesAllocated(index, "sharedValueIndex", "filterIndex");
		}

		@Test
		@DisplayName("brings up the range companion only for a range-typed attribute")
		void shouldAllocateTheRangeCompanionOnlyForARangeTypedFilterWrite() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			index.insertFilterAttribute(
				null, FILTERABLE_RANGE, ALLOWED_LOCALES, null, IntegerNumberRange.between(1, 10), 1, false
			);

			assertOnlyFamiliesAllocated(index, "sharedValueIndex", "filterIndex", "sharedRangeIndex");
		}

		@Test
		@DisplayName("brings up the sort family alone on a sort write")
		void shouldAllocateOnlyTheSortFamilyOnASortWrite() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			index.insertSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 10, 1);

			assertOnlyFamiliesAllocated(index, "sortIndex");
		}

		@Test
		@DisplayName("brings up the chain family alone on a predecessor write")
		void shouldAllocateOnlyTheChainFamilyOnAPredecessorWrite() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			index.insertSortAttribute(null, CHAIN_ORDER, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);

			assertOnlyFamiliesAllocated(index, "chainIndex");
		}

		@Test
		@DisplayName("brings up the owner unique family alone on a non-foldable unique write")
		void shouldAllocateOnlyTheOwnerUniqueFamilyOnANonFoldableUniqueWrite() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			final UniquenessEnforcement enforcement = index.insertUniqueAttribute(
				null, OWNER_UNIQUE_CODE, ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, "ABC", 1
			);

			assertEquals(
				UniquenessEnforcement.BY_OWNER_INDEX, enforcement,
				"the fixture must be the shape that keeps a standalone owner unique index"
			);
			assertOnlyFamiliesAllocated(index, "uniqueIndex");
		}

		@Test
		@DisplayName("brings up the folded unique view family on a folded unique write")
		void shouldAllocateTheFoldedUniqueViewFamilyOnAFoldedUniqueWrite() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			final UniquenessEnforcement enforcement = index.insertUniqueAttribute(
				null, FOLDABLE_UNIQUE_CODE, ALLOWED_LOCALES, Scope.LIVE, null, "ABC", 1
			);
			assertEquals(
				UniquenessEnforcement.BY_FILTER_WRITE, enforcement,
				"the fixture must be the foldable unique shape"
			);
			// the unique-insert itself stores nothing for a folded attribute, so it must not have allocated anything
			assertOnlyFamiliesAllocated(index);

			index.insertFilterAttribute(null, FOLDABLE_UNIQUE_CODE, ALLOWED_LOCALES, null, "ABC", 1, true);

			assertOnlyFamiliesAllocated(index, "sharedValueIndex", "filterIndex", "uniqueViewIndex");
		}
	}

	@Nested
	@DisplayName("Transactional lifecycle of a first write")
	class TransactionalLifecycle {

		@Test
		@DisplayName("is visible in the committed copy, which brings up only the family that was written")
		void shouldMakeAFirstWriteVisibleAfterCommitWithoutResurrectingTheRest() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertStateAfterCommit(
				index,
				original -> original.insertSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 10, 1),
				(original, committed) -> {
					assertNotNull(committed);
					assertEquals(1, committed.getSortIndexes().size(), "the first write must survive the merge");
					assertNotNull(
						committed.getSortIndex(null, SORTABLE_PRIORITY, null),
						"and the sub-index it created must be reachable"
					);
					// the six families the transaction never touched committed nothing, so the merge copy must not
					// hand them an empty map each - that would put the whole 600 B back on the first write of any kind
					assertOnlyFamiliesAllocated(committed, "sortIndex");
				}
			);
		}

		@Test
		@DisplayName("leaves the family absent in a copy committed after the write was undone")
		void shouldLeaveTheFamilyAbsentInACopyCommittedFromAnEmptiedFamily() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertStateAfterCommit(
				index,
				original -> {
					original.insertSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 10, 1);
					original.removeSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 10, 1);
				},
				(original, committed) -> {
					assertNotNull(committed);
					assertTrue(committed.isAttributeIndexEmpty(), "the write was undone before the commit");
					// the from-committed-maps constructor is the one place an empty family gets to go back to being
					// absent, and this is what proves it does rather than resurrecting it empty for the next snapshot
					assertOnlyFamiliesAllocated(committed);
					assertEquals(
						emptyIndexBytes(), committed.getHeapSizeInBytes(),
						"a snapshot that committed nothing must weigh what a fresh index weighs"
					);
				}
			);
		}

		@Test
		@DisplayName("commits nothing when rolled back, and leaves behind only the family's empty shell")
		void shouldKeepNoContentInAFamilyMaterialisedByARolledBackWrite() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);

			assertStateAfterRollback(
				index,
				original -> original.insertSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 10, 1),
				(original, committed) -> {
					assertNull(committed, "a rolled-back transaction commits nothing");
					assertTrue(original.isAttributeIndexEmpty(), "and leaves no content behind");
					assertTrue(original.getSortIndexes().isEmpty(), "the sort family holds nothing");
				}
			);

			// The residue this design accepts, pinned so it cannot silently grow: the map OBJECT stays on the
			// pre-commit instance, because a write inside a transaction has to have somewhere to put its diff. It is
			// bounded by what construction used to cost unconditionally, and it is the SEALED shell only - the layer
			// froze the empty buffer into the JVM-wide empty trie, which nothing owns. On the trunk it is transient:
			// the committed copy is rebuilt by the from-maps constructor and materialises only what holds something.
			assertNotNull(family(index, "sortIndex"), "the rolled-back write did materialise the family");
			assertOnlyFamiliesAllocated(index, "sortIndex");
			final VMLayout layout = VMLayout.current();
			assertEquals(
				emptyIndexBytes() + layout.sizeOfObject(Long.BYTES + 3L * layout.referenceSize() + 1L),
				index.getHeapSizeInBytes(),
				"the residue must be the producer map's shell alone - its sealed empty state is JVM-wide and free"
			);
		}
	}

	@Nested
	@DisplayName("A populated index")
	class PopulatedIndex {

		/**
		 * Seeds one value into every one of the seven families, so a test can assert the whole surface at once.
		 *
		 * @return an index in which no family is absent
		 */
		@Nonnull
		private AttributeIndex fullySeededIndex() {
			final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
			index.insertUniqueAttribute(
				null, OWNER_UNIQUE_CODE, ALLOWED_LOCALES, Scope.LIVE, Locale.ENGLISH, "OWNED", 1);
			index.insertUniqueAttribute(null, FOLDABLE_UNIQUE_CODE, ALLOWED_LOCALES, Scope.LIVE, null, "FOLDED", 1);
			index.insertFilterAttribute(null, FOLDABLE_UNIQUE_CODE, ALLOWED_LOCALES, null, "FOLDED", 1, true);
			index.insertFilterAttribute(null, FILTERABLE_NAME, ALLOWED_LOCALES, null, "product", 1, false);
			index.insertFilterAttribute(
				null, FILTERABLE_RANGE, ALLOWED_LOCALES, null, IntegerNumberRange.between(1, 10), 1, false);
			index.insertSortAttribute(null, SORTABLE_PRIORITY, ALLOWED_LOCALES, null, 10, 1);
			index.insertSortAttribute(null, CHAIN_ORDER, ALLOWED_LOCALES, null, Predecessor.HEAD, 1);
			return index;
		}

		@Test
		@DisplayName("answers every accessor exactly as it did before the families became lazy")
		void shouldAnswerEveryAccessorOnAFullyPopulatedIndex() {
			final AttributeIndex index = fullySeededIndex();

			assertFalse(index.isAttributeIndexEmpty());
			// `globalCode` owns a standalone index and `code` is advertised through its folded view
			assertEquals(2, index.getUniqueIndexes().size());
			// `code`, `name` and `range` each own a shared value tree
			assertEquals(3, index.getFilterIndexes().size());
			assertEquals(1, index.getSortIndexes().size());
			assertEquals(1, index.getChainIndexes().size());
			assertNotNull(index.getUniqueIndex(null, OWNER_UNIQUE_CODE, Scope.LIVE, Locale.ENGLISH));
			assertNotNull(index.getUniqueIndex(null, FOLDABLE_UNIQUE_CODE, Scope.LIVE, null));
			assertNotNull(index.getFilterIndex(null, FILTERABLE_NAME, null));
			assertNotNull(index.getFilterIndex(null, FILTERABLE_RANGE, null));
			assertNotNull(index.getSortIndex(null, SORTABLE_PRIORITY, null));
			assertNotNull(index.getChainIndex(null, CHAIN_ORDER, null));
			assertOnlyFamiliesAllocated(index, FAMILY_FIELDS);
		}

		@Test
		@DisplayName("announces one manifest key per sub-index it holds")
		void shouldCollectTheManifestKeysOfEveryFamily() {
			final AttributeIndex index = fullySeededIndex();

			final Set<AttributeIndexStorageKey> keys = new HashSet<>();
			index.collectKeys(new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE), keys);

			// two unique (one owner, one folded view), three filter, one sort, one chain
			assertEquals(7, keys.size(), "every live sub-index must announce exactly one manifest key - " + keys);
		}

		@Test
		@DisplayName("still writes a storage part for every family when flushed")
		void shouldEmitStoragePartsForEveryFamily() {
			final AttributeIndex index = fullySeededIndex();

			final TrappedChanges changes = new TrappedChanges();
			index.getModifiedStorageParts(1, changes);

			assertTrue(
				countTrapped(changes) >= 7,
				"a part per sub-index at the very least - was " + countTrapped(changes)
			);
		}
	}

	/**
	 * Drains a {@link TrappedChanges} accumulator and counts what it holds.
	 *
	 * @param changes the accumulator a flush wrote into
	 * @return how many storage parts it collected
	 */
	private static int countTrapped(@Nonnull TrappedChanges changes) {
		int count = 0;
		final Iterator<StoragePart> iterator = changes.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			iterator.next();
			count++;
		}
		return count;
	}

	/**
	 * Guards against the fixture drifting: every field this suite reasons about must still exist and still be one of
	 * the sub-index families, or the reflective assertions above would silently start proving nothing.
	 */
	@Test
	@DisplayName("the seven family fields this suite names all still exist")
	void shouldStillNameEveryFamilyField() {
		final AttributeIndex index = new EntityAttributeIndex(ENTITY_TYPE);
		for (final String field : FAMILY_FIELDS) {
			// readField throws when the field is gone, which is the assertion - the null is the expected value
			assertNull(family(index, field), "`" + field + "` must be absent on a fresh index");
		}
		assertEquals(7, FAMILY_FIELDS.length, "the index declares seven sub-index families");
	}

}
