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

package io.evitadb.index.virtual;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.virtual.VirtualReferencePartitionOracle.ExpectedSortValue;
import io.evitadb.index.virtual.VirtualReferencePartitionOracle.Finding;
import io.evitadb.index.virtual.VirtualReferencePartitionOracle.FindingKind;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link VirtualReferencePartitionOracle} reports what it claims to. Rows, sort values and chain
 * predecessors are planted straight into the registry of a warming-up catalog that does not maintain it, and every
 * kind of disagreement is planted once and must be reported, while a correctly planted
 * registry and a declared chain key emptied while its owners remain must not be. The registry of such a catalog lacks
 * the row of every partition a test plants none for, so most tests set those missing rows aside (see
 * {@link #inspectIgnoringMissingRowsOf(Set)}) - and prove that a planted disagreement is reported whether they are set
 * aside or not. The expected single-owner partitions are counted from the entity bodies, independently of the
 * indexes the oracle reads.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition oracle")
@Tag(TEST_HARNESS)
@Tag(INDEXING)
@Tag(REFERENCE)
class VirtualReferencePartitionOracleTest implements EvitaTestSupport {
	private static final String CATALOG = "virtualReferencePartitionOracleTest";
	/**
	 * A partition primary key no index of the test catalog can ever hold - they are handed out from zero upwards.
	 */
	private static final int UNKNOWN_PARTITION = Integer.MAX_VALUE - 1;
	/**
	 * A sortable category reference attribute the key tests add, ordered by its instant.
	 */
	private static final String ATTRIBUTE_VALID_FROM = "validFrom";
	/**
	 * A chained category reference attribute the key tests add.
	 */
	private static final String ATTRIBUTE_CHAIN = "chainOrder";
	/**
	 * A sortable compound of the category reference the key tests add: the order descending with nulls first, then the
	 * validity.
	 */
	private static final String COMPOUND_ORDER_AND_VALIDITY = "orderAndValidity";
	/**
	 * The sort key of the order of a category reference - filterable and sortable, so its partition sort index is a
	 * view over the filter index.
	 */
	private static final AttributeIndexKey ORDER_KEY =
		new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_ORDER, null);
	/**
	 * The sort key of the validity of a category reference - sortable only.
	 */
	private static final AttributeIndexKey VALID_FROM_KEY =
		new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_VALID_FROM, null);
	/**
	 * The sort key of the compound of the order and the validity.
	 */
	private static final AttributeIndexKey COMPOUND_KEY =
		new AttributeIndexKey(REFERENCE_CATEGORIES, COMPOUND_ORDER_AND_VALIDITY, null);
	/**
	 * The chain key of the chained attribute of a category reference.
	 */
	private static final AttributeIndexKey CHAIN_KEY =
		new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_CHAIN, null);
	/**
	 * The comparator base the registry declares the order key with.
	 */
	private static final ComparatorSource[] INTEGER_BASE = {
		new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
	};
	/**
	 * The comparator base the registry declares the validity key with.
	 */
	private static final ComparatorSource[] VALID_FROM_BASE = {
		new ComparatorSource(OffsetDateTime.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
	};
	/**
	 * The comparator base the registry declares the compound key with.
	 */
	private static final ComparatorSource[] COMPOUND_BASE = {
		new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
		new ComparatorSource(OffsetDateTime.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
	};
	/**
	 * The instant products 4 and 5 are valid from - at two offsets, so the two tie as instants.
	 */
	private static final OffsetDateTime VALID_FROM = OffsetDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneOffset.ofHours(2));
	/**
	 * The same instant as {@link #VALID_FROM} at another offset.
	 */
	private static final OffsetDateTime VALID_FROM_ELSEWHERE = VALID_FROM.withOffsetSameInstant(ZoneOffset.ofHours(5));
	/**
	 * The instant product 6 is valid from - an hour earlier.
	 */
	private static final OffsetDateTime VALID_EARLIER = VALID_FROM.minusHours(1);
	/**
	 * How the chain index receives the head of a chain.
	 */
	private static final int HEAD = -1;

	/**
	 * The catalog of the key tests, whose category reference has extra sort and chain keys.
	 */
	private static final String CATALOG_WITH_KEYS = "virtualReferencePartitionOracleKeysTest";

	private TestPaths paths;
	private Evita evita;
	/**
	 * The catalog the test inspects.
	 */
	private String catalog = CATALOG;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("VirtualReferencePartitionOracleTest");
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths)
				.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
				.build()
		);
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(CATALOG, SingleOwnerPartitionFixture::setUp);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("an empty registry misses every single-owner partition, and nothing else")
	void shouldReportEverySingleOwnerPartitionMissingFromAnEmptyRegistry() {
		final List<Finding> exact = VirtualReferencePartitionOracle.inspect(product());
		assertTrue(exact.stream().allMatch(it -> it.kind() == FindingKind.MISSING_ROW), exact::toString);
		assertEquals(
			countSingleOwnerPartitionsFromBodies(), exact.size(),
			"Every single-owner partition of a shadowed reference must be reported missing: " + exact
		);
		assertTrue(inspectIgnoringMissingRowsOf(missingRows(exact)).isEmpty());
	}

	@Test
	@DisplayName("a correct row silences exactly its own partition")
	void shouldAcceptACorrectRow() {
		final Set<Integer> unplanted = missingRows(VirtualReferencePartitionOracle.inspect(product()));
		final int categoryPartition = partitionOf(REFERENCE_CATEGORIES, 4);
		registryOf(REFERENCE_CATEGORIES).putPartitionOwner(categoryPartition, 4, 4);
		plantOrder(categoryPartition, 4);
		final int galleryPartition = partitionOf(REFERENCE_GALLERY, 5, ROLE_DETAIL);
		registryOf(REFERENCE_GALLERY).putPartitionOwner(galleryPartition, 5, 5);
		registryOf(REFERENCE_GALLERY).putRepresentativeTuple(galleryPartition, new Serializable[]{ROLE_DETAIL});

		assertTrue(inspectIgnoringMissingRowsOf(unplanted).isEmpty());
		final List<Finding> exact = VirtualReferencePartitionOracle.inspect(product());
		assertEquals(countSingleOwnerPartitionsFromBodies() - 2, exact.size(), exact::toString);
		assertTrue(
			exact.stream().noneMatch(
				it -> it.partitionPrimaryKey() == categoryPartition || it.partitionPrimaryKey() == galleryPartition
			),
			exact::toString
		);
	}

	@Test
	@DisplayName("a catalog maintained from its first write passes the full check, its sort values mirrored")
	void shouldAcceptTheRegistryTheWritersMaintain() {
		// a catalog of its own, maintained from its first write, so the writers register every row and mirror every
		// sort value
		final String maintained = CATALOG + "Maintained";
		this.catalog = maintained;
		this.evita.defineCatalog(maintained).updateViaNewSession(this.evita);
		final Catalog theCatalog = (Catalog) this.evita.getCatalogInstance(maintained).orElseThrow();
		for (final String entityType : ENTITY_TYPES) {
			theCatalog.getVirtualReferencePartitionMaintenance().setMaintained(entityType, true);
		}
		this.evita.updateCatalog(maintained, SingleOwnerPartitionFixture::setUp);

		assertNoFindings(VirtualReferencePartitionOracle.inspect(product()));
		// not vacuous: the order of every single-owner category partition is in the registry
		final OwnerSortIndex order = registryOf(REFERENCE_CATEGORIES).getSortIndex(ORDER_KEY);
		assertNotNull(order, "the order of the category partitions must have been mirrored");
		assertEquals(PRODUCT_COUNT, order.size(), "every product's own category is a single-owner partition");

		// and a single disagreement planted next to the mirrored values is still found - as the missing value and as
		// the block the partition no longer has
		registryOf(REFERENCE_CATEGORIES).removeSortValue(ORDER_KEY, partitionOf(REFERENCE_CATEGORIES, 4));
		final List<Finding> findings = VirtualReferencePartitionOracle.inspect(product());
		assertEquals(
			Set.of(FindingKind.SORT_VALUE_MISMATCH, FindingKind.SORT_ORDER_MISMATCH),
			findings.stream().map(Finding::kind).collect(Collectors.toSet()),
			findings::toString
		);
	}

	@Test
	@DisplayName("the registry is empty while nothing writes it, and a single entry or declaration breaks that")
	void shouldTellAnEmptyRegistryFromANonEmptyOne() {
		VirtualReferencePartitionOracle.assertRegistryEmpty("fresh catalog", product());

		registryOf(REFERENCE_GALLERY).declareSortKey(
			new AttributeIndexKey(REFERENCE_GALLERY, ATTRIBUTE_ROLE, null),
			new ComparatorSource[]{new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)},
			0
		);
		final AssertionError failure = assertThrows(
			AssertionError.class,
			() -> VirtualReferencePartitionOracle.assertRegistryEmpty("after a declaration", product())
		);
		assertTrue(failure.getMessage().contains("gallery/LIVE"), failure.getMessage());
	}

	@Nested
	@DisplayName("A wrong row is reported whatever rows are missing")
	class WrongRow {

		@Test
		@DisplayName("a row naming another owner")
		void shouldReportAnotherOwner() {
			final int partition = partitionOf(REFERENCE_CATEGORIES, 4);
			registryOf(REFERENCE_CATEGORIES).putPartitionOwner(partition, 5, 4);
			// the sort value is the one of the partition's only member, whatever owner the row names
			plantOrder(partition, 4);
			assertReportedWhateverIsMissing(FindingKind.OWNER_MISMATCH, partition);
		}

		@Test
		@DisplayName("a row naming another referenced entity")
		void shouldReportAnotherTarget() {
			final int partition = partitionOf(REFERENCE_CATEGORIES, 4);
			registryOf(REFERENCE_CATEGORIES).putPartitionOwner(partition, 4, 5);
			plantOrder(partition, 4);
			assertReportedWhateverIsMissing(FindingKind.TARGET_MISMATCH, partition);
		}

		@Test
		@DisplayName("a row recording other representative attribute values")
		void shouldReportAnotherTuple() {
			final int partition = partitionOf(REFERENCE_GALLERY, 5, ROLE_DETAIL);
			registryOf(REFERENCE_GALLERY).putPartitionOwner(partition, 5, 5);
			registryOf(REFERENCE_GALLERY).putRepresentativeTuple(partition, new Serializable[]{ROLE_GALLERY});
			assertReportedWhateverIsMissing(FindingKind.TUPLE_MISMATCH, partition);
		}

		@Test
		@DisplayName("a row lacking the representative attribute values of its partition")
		void shouldReportAMissingTuple() {
			final int partition = partitionOf(REFERENCE_GALLERY, 5, ROLE_DETAIL);
			registryOf(REFERENCE_GALLERY).putPartitionOwner(partition, 5, 5);
			assertReportedWhateverIsMissing(FindingKind.TUPLE_MISMATCH, partition);
		}

		@Test
		@DisplayName("a row for a partition with several owners")
		void shouldReportARowOfASharedPartition() {
			final int partition = partitionOf(REFERENCE_CATEGORIES, SHARED_CATEGORY);
			registryOf(REFERENCE_CATEGORIES).putPartitionOwner(partition, 1, SHARED_CATEGORY);
			assertReportedWhateverIsMissing(FindingKind.MULTI_OWNER_ROW, partition);
		}

		@Test
		@DisplayName("a row for a partition the reference does not advertise")
		void shouldReportARowOutsideTheAdvertisedPartitions() {
			registryOf(REFERENCE_CATEGORIES).putPartitionOwner(UNKNOWN_PARTITION, 4, 4);
			assertReportedWhateverIsMissing(FindingKind.ROW_OUTSIDE_ADVERTISED, UNKNOWN_PARTITION);
		}

		@Test
		@DisplayName("representative attribute values without an owner row")
		void shouldReportATupleWithoutOwner() {
			final int partition = partitionOf(REFERENCE_GALLERY, 5, ROLE_DETAIL);
			registryOf(REFERENCE_GALLERY).putRepresentativeTuple(partition, new Serializable[]{ROLE_DETAIL});
			assertReportedWhateverIsMissing(FindingKind.TUPLE_WITHOUT_OWNER, partition);
		}

		@Test
		@DisplayName("a row of a reference indexed for partitioning, which the registry never shadows")
		void shouldReportARowOfAnUnshadowedReference() {
			registryOf(REFERENCE_BRAND).putPartitionOwner(UNKNOWN_PARTITION, 1, BRAND_PK);
			assertReportedWhateverIsMissing(FindingKind.ROW_OF_UNSHADOWED_REFERENCE, UNKNOWN_PARTITION);
		}

		@Test
		@DisplayName("a key declared while the registry holds no owner row")
		void shouldReportAKeyDeclaredWithoutAnyOwner() {
			registryOf(REFERENCE_GALLERY).declareSortKey(
				new AttributeIndexKey(REFERENCE_GALLERY, ATTRIBUTE_ROLE, null),
				new ComparatorSource[]{
					new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				},
				0
			);
			assertReportedWhateverIsMissing(
				FindingKind.KEYS_WITHOUT_OWNER, VirtualReferencePartitionOracle.NO_PARTITION
			);
		}

		/**
		 * Asserts the planted disagreement is reported, as the only finding besides missing rows, both when every
		 * missing row is reported and when every one of them is set aside.
		 *
		 * @param kind      the kind expected
		 * @param partition the partition the planted disagreement concerns
		 */
		private void assertReportedWhateverIsMissing(@Nonnull FindingKind kind, int partition) {
			final List<Finding> exact = VirtualReferencePartitionOracle.inspect(product());
			for (final Set<Integer> setAside : List.of(Set.<Integer>of(), missingRows(exact))) {
				final List<Finding> findings = inspectIgnoringMissingRowsOf(setAside);
				final List<Finding> planted = findings.stream()
					.filter(it -> it.kind() != FindingKind.MISSING_ROW)
					.toList();
				assertEquals(1, planted.size(), setAside + ": " + findings);
				assertEquals(kind, planted.get(0).kind(), setAside + ": " + findings);
				assertEquals(partition, planted.get(0).partitionPrimaryKey(), setAside + ": " + findings);
			}
		}
	}

	@Nested
	@DisplayName("Sort values, chain predecessors and counts")
	class Keys {
		/**
		 * The partition of category 7, owned by product 7.
		 */
		private int partition7;
		/**
		 * The partition of category 8, owned by product 8.
		 */
		private int partition8;
		/**
		 * The partition of category 9, owned by product 9.
		 */
		private int partition9;
		/**
		 * Every single-owner partition but the three above - none of them gets a row.
		 */
		private Set<Integer> unplanted;

		@BeforeEach
		void setUpKeys() {
			// a catalog of its own, whose category reference has the extra keys before the first product is written
			VirtualReferencePartitionOracleTest.this.catalog = CATALOG_WITH_KEYS;
			VirtualReferencePartitionOracleTest.this.evita.defineCatalog(CATALOG_WITH_KEYS)
				.updateViaNewSession(VirtualReferencePartitionOracleTest.this.evita);
			VirtualReferencePartitionOracleTest.this.evita.updateCatalog(
				CATALOG_WITH_KEYS,
				session -> {
					SingleOwnerPartitionFixture.setUpExtendingCategories(
						session,
						categories -> categories
							.withAttribute(
								ATTRIBUTE_VALID_FROM, OffsetDateTime.class,
								thatIs -> thatIs.sortableInScope(Scope.LIVE).nullable()
							)
							.withAttribute(
								ATTRIBUTE_CHAIN, Predecessor.class,
								thatIs -> thatIs.sortableInScope(Scope.LIVE).nullable()
							)
							.withSortableAttributeCompound(
								COMPOUND_ORDER_AND_VALIDITY,
								new AttributeElement[]{
									new AttributeElement(
										ATTRIBUTE_ORDER, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST
									),
									new AttributeElement(
										ATTRIBUTE_VALID_FROM, OrderDirection.ASC, OrderBehaviour.NULLS_LAST
									)
								},
								thatIs -> thatIs.indexedInScope(Scope.LIVE)
							)
					);
					addProduct(session, 7, VALID_FROM, Predecessor.HEAD, true);
					addProduct(session, 8, VALID_FROM_ELSEWHERE, new Predecessor(7), true);
					// a missing order: no value for the order key, a null element - sorted first - in the compound
					addProduct(session, 9, VALID_EARLIER, null, false);
				}
			);
			this.partition7 = partitionOf(REFERENCE_CATEGORIES, 7);
			this.partition8 = partitionOf(REFERENCE_CATEGORIES, 8);
			this.partition9 = partitionOf(REFERENCE_CATEGORIES, 9);
			this.unplanted = missingRows(VirtualReferencePartitionOracle.inspect(product()));
			this.unplanted.removeAll(Set.of(this.partition7, this.partition8, this.partition9));
		}

		@Test
		@DisplayName("a correctly planted registry is accepted, ties, null elements and an absent value included")
		void shouldAcceptACorrectRegistry() {
			plantCorrectly();

			// the planting must engage everything the oracle checks, or its silence proves nothing
			final VirtualReferencePartitionIndex registry = registryOf(REFERENCE_CATEGORIES);
			assertEquals(13, registry.getEntryCount());
			assertEquals(Set.of(ORDER_KEY, VALID_FROM_KEY, COMPOUND_KEY), registry.getDeclaredSortKeys());
			final SortIndexWalk validity = SortIndexWalk.of(registry.getSortIndex(VALID_FROM_KEY));
			assertEquals(2, validity.blocks().size(), "the premise: partitions 7 and 8 tie as one block");
			assertArrayEquals(
				new int[]{this.partition9, this.partition8, this.partition7},
				SortIndexWalk.of(registry.getSortIndex(COMPOUND_KEY)).records(),
				"the premise: the null element first, then the descending order"
			);

			assertNoFindings(inspectIgnoringMissingRowsOf(this.unplanted));
		}

		@Test
		@DisplayName("a sort value other than the partition's own is reported")
		void shouldReportAnotherSortValue() {
			plantCorrectly();
			registryOf(REFERENCE_CATEGORIES).putSortValue(VALID_FROM_KEY, this.partition9, VALID_EARLIER.minusHours(1));

			assertOnly(FindingKind.SORT_VALUE_MISMATCH, this.partition9);
		}

		@Test
		@DisplayName("a sort value of a partition without a row is reported")
		void shouldReportASortEntryOfAPartitionWithoutRow() {
			plantCorrectly();
			final int unregistered = partitionOf(REFERENCE_CATEGORIES, 3);
			assertTrue(this.unplanted.contains(unregistered), "the premise: the partition has no row");
			registryOf(REFERENCE_CATEGORIES).putSortValue(ORDER_KEY, unregistered, 3);

			// the entry is reported, and the block it occupies breaks the order of the registered partitions too
			final List<Finding> findings = inspectIgnoringMissingRowsOf(this.unplanted);
			assertEquals(2, findings.size(), findings::toString);
			assertEquals(FindingKind.SORT_ENTRY_WITHOUT_ROW, findings.get(0).kind(), findings::toString);
			assertEquals(unregistered, findings.get(0).partitionPrimaryKey(), findings::toString);
			assertEquals(FindingKind.SORT_ORDER_MISMATCH, findings.get(1).kind(), findings::toString);
		}

		@Test
		@DisplayName("a sort value under a key no partition holds is reported")
		void shouldReportASortValueOfAKeyOnlyTheRegistryHolds() {
			plantCorrectly();
			// the validity of a locale no reference ever had - a key the registry kept after the partitions lost it
			final AttributeIndexKey staleKey =
				new AttributeIndexKey(REFERENCE_CATEGORIES, ATTRIBUTE_VALID_FROM, Locale.ENGLISH);
			final VirtualReferencePartitionIndex registry = registryOf(REFERENCE_CATEGORIES);
			registry.declareSortKey(staleKey, VALID_FROM_BASE, 0);
			registry.putSortValue(staleKey, this.partition7, VALID_FROM);

			final List<Finding> findings = inspectIgnoringMissingRowsOf(this.unplanted);
			assertTrue(
				findings.stream().anyMatch(
					it -> it.kind() == FindingKind.SORT_VALUE_MISMATCH && it.partitionPrimaryKey() == this.partition7
				),
				findings::toString
			);
		}

		@Test
		@DisplayName("a chain entry swapped for one of a partition without a row fails the read, not the count")
		void shouldReportASwappedChainEntry() {
			plantCorrectly();
			final VirtualReferencePartitionIndex registry = registryOf(REFERENCE_CATEGORIES);
			registry.removeChainPredecessor(CHAIN_KEY, this.partition8);
			registry.putChainPredecessor(CHAIN_KEY, partitionOf(REFERENCE_CATEGORIES, 3), 7);
			assertEquals(13, registry.getEntryCount(), "the premise: the count is unchanged");

			assertOnly(FindingKind.CHAIN_PREDECESSOR_MISMATCH, this.partition8);
		}

		@Test
		@DisplayName("a chain entry of a partition without a row, which no read reaches, breaks the count")
		void shouldReportASurplusChainEntry() {
			plantCorrectly();
			registryOf(REFERENCE_CATEGORIES).putChainPredecessor(CHAIN_KEY, partitionOf(REFERENCE_CATEGORIES, 3), 7);

			assertOnly(FindingKind.ENTRY_COUNT_MISMATCH, VirtualReferencePartitionOracle.NO_PARTITION);
		}

		@Test
		@DisplayName("predecessors the partitions no longer declare are reported though no partition holds the key")
		void shouldReportPredecessorsOfAKeyOnlyTheRegistryHolds() {
			plantCorrectly();
			removeChain(7);
			removeChain(8);
			assertNull(product().getIndexByPrimaryKeyIfExists(this.partition7).getChainIndex(CHAIN_KEY));

			final List<Finding> findings = inspectIgnoringMissingRowsOf(this.unplanted);
			assertEquals(
				Set.of(this.partition7, this.partition8),
				findings.stream()
					.filter(it -> it.kind() == FindingKind.CHAIN_PREDECESSOR_MISMATCH)
					.map(Finding::partitionPrimaryKey)
					.collect(Collectors.toSet()),
				findings::toString
			);
			assertTrue(
				findings.stream().anyMatch(it -> it.kind() == FindingKind.ENTRY_COUNT_MISMATCH), findings::toString
			);
		}

		@Test
		@DisplayName("a declared chain key without a single entry is accepted while owners remain")
		void shouldAcceptAnEmptyDeclaredChainKey() {
			plantCorrectly();
			removeChain(7);
			removeChain(8);
			final VirtualReferencePartitionIndex registry = registryOf(REFERENCE_CATEGORIES);
			registry.removeChainPredecessor(CHAIN_KEY, this.partition7);
			registry.removeChainPredecessor(CHAIN_KEY, this.partition8);
			assertEquals(Set.of(CHAIN_KEY), registry.getDeclaredChainKeys());

			assertNoFindings(inspectIgnoringMissingRowsOf(this.unplanted));
		}

		@Test
		@DisplayName("a missing sort value is reported")
		void shouldReportAMissingSortValue() {
			plantCorrectly();
			registryOf(REFERENCE_CATEGORIES).removeSortValue(COMPOUND_KEY, this.partition7);

			final List<Finding> findings = inspectIgnoringMissingRowsOf(this.unplanted);
			assertTrue(
				findings.stream().anyMatch(
					it -> it.kind() == FindingKind.SORT_VALUE_MISMATCH && it.partitionPrimaryKey() == this.partition7
				),
				findings::toString
			);
		}

		/**
		 * Plants the registry the writers will be expected to keep: a row of each of the three partitions, their
		 * values of the order, the validity and the compound, and the predecessors of partitions 7 and 8.
		 */
		private void plantCorrectly() {
			final VirtualReferencePartitionIndex registry = registryOf(REFERENCE_CATEGORIES);
			registry.putPartitionOwner(this.partition7, 7, 7);
			registry.putPartitionOwner(this.partition8, 8, 8);
			registry.putPartitionOwner(this.partition9, 9, 9);
			registry.declareSortKey(ORDER_KEY, INTEGER_BASE, 0);
			registry.putSortValue(ORDER_KEY, this.partition7, 7);
			registry.putSortValue(ORDER_KEY, this.partition8, 8);
			registry.declareSortKey(VALID_FROM_KEY, VALID_FROM_BASE, 0);
			registry.putSortValue(VALID_FROM_KEY, this.partition7, VALID_FROM);
			registry.putSortValue(VALID_FROM_KEY, this.partition8, VALID_FROM_ELSEWHERE);
			registry.putSortValue(VALID_FROM_KEY, this.partition9, VALID_EARLIER);
			registry.declareSortKey(COMPOUND_KEY, COMPOUND_BASE, 0);
			registry.putSortValue(COMPOUND_KEY, this.partition7, new Serializable[]{7, VALID_FROM});
			registry.putSortValue(COMPOUND_KEY, this.partition8, new Serializable[]{8, VALID_FROM_ELSEWHERE});
			registry.putSortValue(COMPOUND_KEY, this.partition9, new Serializable[]{null, VALID_EARLIER});
			registry.declareChainKey(CHAIN_KEY);
			registry.putChainPredecessor(CHAIN_KEY, this.partition7, HEAD);
			registry.putChainPredecessor(CHAIN_KEY, this.partition8, 7);
		}

		/**
		 * Removes the chained attribute from the category reference of one product.
		 *
		 * @param productPrimaryKey the product, whose category reference has the same primary key
		 */
		private void removeChain(int productPrimaryKey) {
			VirtualReferencePartitionOracleTest.this.evita.updateCatalog(
				VirtualReferencePartitionOracleTest.this.catalog,
				session -> {
					updateProduct(
						productPrimaryKey,
						it -> it.updateReference(
							REFERENCE_CATEGORIES, productPrimaryKey, ref -> ref.removeAttribute(ATTRIBUTE_CHAIN)
						)
					).accept(session);
				}
			);
		}

		/**
		 * Asserts the planted disagreement is the only finding.
		 *
		 * @param kind      the kind expected
		 * @param partition the partition the planted disagreement concerns
		 */
		private void assertOnly(@Nonnull FindingKind kind, int partition) {
			final List<Finding> findings = inspectIgnoringMissingRowsOf(this.unplanted);
			assertEquals(1, findings.size(), findings::toString);
			assertEquals(kind, findings.get(0).kind(), findings::toString);
			assertEquals(partition, findings.get(0).partitionPrimaryKey(), findings::toString);
		}
	}

	@Nested
	@DisplayName("The order check")
	class OrderCheck {
		/**
		 * The key the checked sort index orders by.
		 */
		private static final AttributeIndexKey KEY = ORDER_KEY;

		@Test
		@DisplayName("accepts ties ordered by ascending partition primary key")
		void shouldAcceptAscendingTies() {
			assertNoFindings(checkOrder(new int[]{3, 4, 5}, new Serializable[]{1, 2}, Map.of(2, 2)));
		}

		@Test
		@DisplayName("reports a reversed tie inside one block")
		void shouldReportAReversedTie() {
			final List<Finding> findings = checkOrder(new int[]{3, 5, 4}, new Serializable[]{1, 2}, Map.of(2, 2));

			assertEquals(1, findings.size(), findings::toString);
			assertEquals(FindingKind.SORT_ORDER_MISMATCH, findings.get(0).kind());
		}

		@Test
		@DisplayName("reports a block boundary the values do not have")
		void shouldReportAnotherBlockBoundary() {
			final List<Finding> findings = checkOrder(new int[]{3, 4, 5}, new Serializable[]{1, 2}, Map.of(1, 2));

			assertEquals(1, findings.size(), findings::toString);
			assertEquals(FindingKind.SORT_ORDER_MISMATCH, findings.get(0).kind());
		}

		/**
		 * Builds a registry sort index from the given arrays as they are - in whatever order they come - and checks
		 * it against partition 3 valued 1 and partitions 4 and 5 valued 2.
		 *
		 * @param records       the partitions in the order the sort index holds them
		 * @param values        the distinct values in the order the sort index holds them
		 * @param cardinalities the size of every block larger than one
		 * @return the findings
		 */
		@Nonnull
		private List<Finding> checkOrder(
			@Nonnull int[] records,
			@Nonnull Serializable[] values,
			@Nonnull Map<Serializable, Integer> cardinalities
		) {
			final OwnerSortIndex registrySortIndex = new OwnerSortIndex(
				INTEGER_BASE, null, KEY, records, values, cardinalities
			);
			final List<Finding> findings = new ArrayList<>(1);
			VirtualReferencePartitionOracle.checkOrder(
				REFERENCE_CATEGORIES, Scope.LIVE, KEY, SortIndexWalk.of(registrySortIndex),
				List.of(new ExpectedSortValue(5, 2), new ExpectedSortValue(3, 1), new ExpectedSortValue(4, 2)),
				VirtualReferencePartitionOracle.comparatorOf(
					product().getSchema().getReference(REFERENCE_CATEGORIES).orElseThrow(), KEY
				),
				findings
			);
			return findings;
		}
	}

	/**
	 * Adds a product as the fixture creates it, its category reference also carrying the validity and the chained
	 * attribute, and optionally no order.
	 *
	 * @param session           the session to write in
	 * @param productPrimaryKey the product, whose category reference has the same primary key
	 * @param validFrom         the validity
	 * @param predecessor       the chained attribute, `null` for none
	 * @param keepOrder         FALSE to leave the reference without its order
	 */
	private static void addProduct(
		@Nonnull EvitaSessionContract session,
		int productPrimaryKey,
		@Nonnull OffsetDateTime validFrom,
		@Nullable Predecessor predecessor,
		boolean keepOrder
	) {
		session.upsertEntity(
			newProduct(session, productPrimaryKey).updateReference(
				REFERENCE_CATEGORIES, productPrimaryKey,
				ref -> {
					ref.setAttribute(ATTRIBUTE_VALID_FROM, validFrom);
					if (predecessor != null) {
						ref.setAttribute(ATTRIBUTE_CHAIN, predecessor);
					}
				}
			)
		);
		if (!keepOrder) {
			// removed from the stored product, the fixture always creates the reference with its order
			updateProduct(
				productPrimaryKey,
				it -> it.updateReference(
					REFERENCE_CATEGORIES, productPrimaryKey, ref -> ref.removeAttribute(ATTRIBUTE_ORDER)
				)
			).accept(session);
		}
	}

	/**
	 * Plants the sort value the partition of category `owner` orders its owner by under the order key - the fixture
	 * sets the order of a product's own category to the product's primary key - declaring the key first. A row of
	 * such a partition is correct only together with it.
	 *
	 * @param partition the partition of category `owner`
	 * @param owner     the product owning the partition
	 */
	private void plantOrder(int partition, int owner) {
		final VirtualReferencePartitionIndex registry = registryOf(REFERENCE_CATEGORIES);
		registry.declareSortKey(ORDER_KEY, INTEGER_BASE, 0);
		registry.putSortValue(ORDER_KEY, partition, owner);
	}

	/**
	 * Asserts there is no finding.
	 *
	 * @param findings the findings
	 */
	private static void assertNoFindings(@Nonnull List<Finding> findings) {
		assertTrue(findings.isEmpty(), () -> "Expected no finding, got " + findings.size() + ":\n" + findings);
	}

	/**
	 * Inspects the registry of the products, setting aside the missing rows of the given partitions - the rows a test
	 * deliberately plants none for in a registry nothing maintains.
	 *
	 * @param unplanted the partitions whose missing row is not reported
	 * @return every other finding
	 */
	@Nonnull
	private List<Finding> inspectIgnoringMissingRowsOf(@Nonnull Set<Integer> unplanted) {
		return VirtualReferencePartitionOracle.inspect(product())
			.stream()
			.filter(it -> it.kind() != FindingKind.MISSING_ROW || !unplanted.contains(it.partitionPrimaryKey()))
			.toList();
	}

	/**
	 * Collects the partitions reported as missing their row.
	 *
	 * @param findings the findings
	 * @return the partitions, in a mutable set
	 */
	@Nonnull
	private static Set<Integer> missingRows(@Nonnull List<Finding> findings) {
		return findings.stream()
			.filter(it -> it.kind() == FindingKind.MISSING_ROW)
			.map(Finding::partitionPrimaryKey)
			.collect(Collectors.toCollection(TreeSet::new));
	}

	/**
	 * Counts, from the bodies of the live products, the partitions of the shadowed references that have exactly one
	 * owner - one partition per referenced entity and representative attribute values.
	 *
	 * @return the number of single-owner partitions
	 */
	private int countSingleOwnerPartitionsFromBodies() {
		final Map<String, TreeSet<Integer>> ownersByPartition = CollectionUtils.createHashMap(64);
		this.evita.queryCatalog(
			this.catalog,
			session -> {
				for (final SealedEntity product : session.queryList(
					query(collection(ENTITY_PRODUCT), require(page(1, 100), entityFetchAll())), SealedEntity.class
				)) {
					for (final ReferenceContract reference : product.getReferences()) {
						if (REFERENCE_BRAND.equals(reference.getReferenceName())) {
							continue;
						}
						final String role = REFERENCE_GALLERY.equals(reference.getReferenceName()) ?
							reference.getAttribute(ATTRIBUTE_ROLE) : "";
						ownersByPartition
							.computeIfAbsent(
								reference.getReferenceName() + "/" + reference.getReferencedPrimaryKey() + "/" + role,
								__ -> new TreeSet<>()
							)
							.add(product.getPrimaryKeyOrThrowException());
					}
				}
			}
		);
		return (int) ownersByPartition.values().stream().filter(it -> it.size() == 1).count();
	}

	/**
	 * Resolves the live partition of a reference without representative attribute values.
	 *
	 * @param referenceName the reference
	 * @param target        the referenced entity
	 * @return the partition primary key
	 */
	private int partitionOf(@Nonnull String referenceName, int target) {
		return partitionOf(new RepresentativeReferenceKey(new ReferenceKey(referenceName, target)));
	}

	/**
	 * Resolves the live partition of a reference told apart by one representative attribute value.
	 *
	 * @param referenceName the reference
	 * @param target        the referenced entity
	 * @param role          the representative attribute value
	 * @return the partition primary key
	 */
	private int partitionOf(@Nonnull String referenceName, int target, @Nonnull String role) {
		return partitionOf(
			new RepresentativeReferenceKey(new ReferenceKey(referenceName, target), new Serializable[]{role})
		);
	}

	/**
	 * Resolves the live partition of a representative reference key.
	 *
	 * @param key the key
	 * @return the partition primary key
	 */
	private int partitionOf(@Nonnull RepresentativeReferenceKey key) {
		final EntityIndex partition = product().getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, Scope.LIVE, key)
		);
		assertNotNull(partition, "No partition for " + key);
		return partition.getPrimaryKey();
	}

	/**
	 * Returns the live registry of a reference - written in place, as the catalog is warming up.
	 *
	 * @param referenceName the reference
	 * @return the registry
	 */
	@Nonnull
	private VirtualReferencePartitionIndex registryOf(@Nonnull String referenceName) {
		final EntityIndex typeIndex = product().getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, referenceName)
		);
		return assertInstanceOf(ReferencedTypeEntityIndex.class, typeIndex).getVirtualReferencePartitionIndex();
	}

	/**
	 * Returns the product collection.
	 *
	 * @return the product collection
	 */
	@Nonnull
	private EntityCollection product() {
		return (EntityCollection) this.evita.getCatalogInstance(this.catalog)
			.orElseThrow()
			.getCollectionForEntity(ENTITY_PRODUCT)
			.orElseThrow();
	}
}
