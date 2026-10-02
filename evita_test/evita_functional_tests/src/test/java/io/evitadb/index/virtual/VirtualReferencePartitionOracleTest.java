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

import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.virtual.VirtualReferencePartitionOracle.Finding;
import io.evitadb.index.virtual.VirtualReferencePartitionOracle.FindingKind;
import io.evitadb.index.virtual.VirtualReferencePartitionOracle.Mode;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link VirtualReferencePartitionOracle} reports what it claims to. Rows are planted straight into the
 * registry of a warming-up catalog - the writers that will maintain it do not exist yet - and every kind of
 * disagreement is planted once and must be reported. The expected single-owner partitions are counted from the entity
 * bodies, independently of the indexes the oracle reads.
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

	private TestPaths paths;
	private Evita evita;

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
	@DisplayName("an empty registry misses every single-owner partition in exact mode and nothing in subset mode")
	void shouldReportEverySingleOwnerPartitionMissingFromAnEmptyRegistry() {
		final List<Finding> exact = VirtualReferencePartitionOracle.inspect(product(), Mode.EXACT);
		assertTrue(exact.stream().allMatch(it -> it.kind() == FindingKind.MISSING_ROW), exact::toString);
		assertEquals(
			countSingleOwnerPartitionsFromBodies(), exact.size(),
			"Every single-owner partition of a shadowed reference must be reported missing: " + exact
		);
		assertTrue(VirtualReferencePartitionOracle.inspect(product(), Mode.SUBSET).isEmpty());
	}

	@Test
	@DisplayName("a correct row silences exactly its own partition")
	void shouldAcceptACorrectRow() {
		final int categoryPartition = partitionOf(REFERENCE_CATEGORIES, 4);
		registryOf(REFERENCE_CATEGORIES).putPartitionOwner(categoryPartition, 4, 4);
		final int galleryPartition = partitionOf(REFERENCE_GALLERY, 5, ROLE_DETAIL);
		registryOf(REFERENCE_GALLERY).putPartitionOwner(galleryPartition, 5, 5);
		registryOf(REFERENCE_GALLERY).putRepresentativeTuple(galleryPartition, new Serializable[]{ROLE_DETAIL});

		assertTrue(VirtualReferencePartitionOracle.inspect(product(), Mode.SUBSET).isEmpty());
		final List<Finding> exact = VirtualReferencePartitionOracle.inspect(product(), Mode.EXACT);
		assertEquals(countSingleOwnerPartitionsFromBodies() - 2, exact.size(), exact::toString);
		assertTrue(
			exact.stream().noneMatch(
				it -> it.partitionPrimaryKey() == categoryPartition || it.partitionPrimaryKey() == galleryPartition
			),
			exact::toString
		);
	}

	@Nested
	@DisplayName("A wrong row is reported in both modes")
	class WrongRow {

		@Test
		@DisplayName("a row naming another owner")
		void shouldReportAnotherOwner() {
			final int partition = partitionOf(REFERENCE_CATEGORIES, 4);
			registryOf(REFERENCE_CATEGORIES).putPartitionOwner(partition, 5, 4);
			assertReportedInBothModes(FindingKind.OWNER_MISMATCH, partition);
		}

		@Test
		@DisplayName("a row naming another referenced entity")
		void shouldReportAnotherTarget() {
			final int partition = partitionOf(REFERENCE_CATEGORIES, 4);
			registryOf(REFERENCE_CATEGORIES).putPartitionOwner(partition, 4, 5);
			assertReportedInBothModes(FindingKind.TARGET_MISMATCH, partition);
		}

		@Test
		@DisplayName("a row recording other representative attribute values")
		void shouldReportAnotherTuple() {
			final int partition = partitionOf(REFERENCE_GALLERY, 5, ROLE_DETAIL);
			registryOf(REFERENCE_GALLERY).putPartitionOwner(partition, 5, 5);
			registryOf(REFERENCE_GALLERY).putRepresentativeTuple(partition, new Serializable[]{ROLE_GALLERY});
			assertReportedInBothModes(FindingKind.TUPLE_MISMATCH, partition);
		}

		@Test
		@DisplayName("a row lacking the representative attribute values of its partition")
		void shouldReportAMissingTuple() {
			final int partition = partitionOf(REFERENCE_GALLERY, 5, ROLE_DETAIL);
			registryOf(REFERENCE_GALLERY).putPartitionOwner(partition, 5, 5);
			assertReportedInBothModes(FindingKind.TUPLE_MISMATCH, partition);
		}

		@Test
		@DisplayName("a row for a partition with several owners")
		void shouldReportARowOfASharedPartition() {
			final int partition = partitionOf(REFERENCE_CATEGORIES, SHARED_CATEGORY);
			registryOf(REFERENCE_CATEGORIES).putPartitionOwner(partition, 1, SHARED_CATEGORY);
			assertReportedInBothModes(FindingKind.MULTI_OWNER_ROW, partition);
		}

		@Test
		@DisplayName("a row for a partition the reference does not advertise")
		void shouldReportARowOutsideTheAdvertisedPartitions() {
			registryOf(REFERENCE_CATEGORIES).putPartitionOwner(UNKNOWN_PARTITION, 4, 4);
			assertReportedInBothModes(FindingKind.ROW_OUTSIDE_ADVERTISED, UNKNOWN_PARTITION);
		}

		@Test
		@DisplayName("representative attribute values without an owner row")
		void shouldReportATupleWithoutOwner() {
			final int partition = partitionOf(REFERENCE_GALLERY, 5, ROLE_DETAIL);
			registryOf(REFERENCE_GALLERY).putRepresentativeTuple(partition, new Serializable[]{ROLE_DETAIL});
			assertReportedInBothModes(FindingKind.TUPLE_WITHOUT_OWNER, partition);
		}

		@Test
		@DisplayName("a row of a reference indexed for partitioning, which the registry never shadows")
		void shouldReportARowOfAnUnshadowedReference() {
			registryOf(REFERENCE_BRAND).putPartitionOwner(UNKNOWN_PARTITION, 1, BRAND_PK);
			assertReportedInBothModes(FindingKind.ROW_OF_UNSHADOWED_REFERENCE, UNKNOWN_PARTITION);
		}

		/**
		 * Asserts the planted disagreement is reported, as the only finding of its kind and partition, in both modes.
		 *
		 * @param kind      the kind expected
		 * @param partition the partition the planted row concerns
		 */
		private void assertReportedInBothModes(@Nonnull FindingKind kind, int partition) {
			for (final Mode mode : Mode.values()) {
				final List<Finding> findings = VirtualReferencePartitionOracle.inspect(product(), mode);
				final List<Finding> planted = findings.stream()
					.filter(it -> it.kind() != FindingKind.MISSING_ROW)
					.toList();
				assertEquals(1, planted.size(), mode + ": " + findings);
				assertEquals(kind, planted.get(0).kind(), mode + ": " + findings);
				assertEquals(partition, planted.get(0).partitionPrimaryKey(), mode + ": " + findings);
			}
		}
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
			CATALOG,
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
		return (EntityCollection) this.evita.getCatalogInstance(CATALOG)
			.orElseThrow()
			.getCollectionForEntity(ENTITY_PRODUCT)
			.orElseThrow();
	}
}
