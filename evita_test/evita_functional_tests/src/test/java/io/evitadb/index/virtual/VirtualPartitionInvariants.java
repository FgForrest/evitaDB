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

import io.evitadb.api.CatalogContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionSlot;
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.spi.store.catalog.header.model.EntityCollectionHeader;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The invariants every catalog representing its single-owner reference partitions virtually keeps once a root mutation
 * is committed - whatever produced it: its own write path, a reload, a crash recovery, a copy, or the storage protocol
 * migration converting a catalog of index objects - and the index-level description such catalogs are compared by.
 *
 * The invariants (see {@link #assertInvariants(String, Evita, String)}):
 *
 * - no index object of a partition with fewer than two owners of a reference whose partitions may be virtual;
 * - every registered partition has no index object, is not among the indexes its collection header lists as stored,
 *   and no entity index storage part is stored under its primary key;
 * - the index primary key sequence the collection header persists is at least every registered primary key;
 * - the reduced-index membership covers every registered partition with its owner and holds none of them as residual.
 *
 * The description (see {@link #describeIndexes(Evita, String)}) is the index level of {@link CatalogDescriber}: the
 * registry rows with their primary keys, owners, sort values and chain predecessors, the advertised partitions, the
 * index objects and the membership.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class VirtualPartitionInvariants {

	private VirtualPartitionInvariants() {
	}

	/**
	 * Asserts the invariants of every collection of a committed catalog - see the class documentation - and that the
	 * catalog has registered partitions at all, so the invariants are not satisfied vacuously.
	 *
	 * @param label       names the moment in failure messages
	 * @param evita       the engine
	 * @param catalogName the catalog, representing its single-owner partitions virtually
	 */
	public static void assertInvariants(@Nonnull String label, @Nonnull Evita evita, @Nonnull String catalogName) {
		final Catalog catalog = catalogOf(evita, catalogName);
		int registered = 0;
		for (final String entityType : catalog.getEntityTypes()) {
			registered += assertInvariants(label, collectionOf(evita, catalogName, entityType), catalog.getVersion());
		}
		assertTrue(registered > 0, label + ": the catalog `" + catalogName + "` must hold registered partitions");
	}

	/**
	 * Asserts the invariants of one collection of a committed catalog - see the class documentation.
	 *
	 * @param label          names the moment in failure messages
	 * @param collection     the collection
	 * @param catalogVersion the version of the catalog the storage is read at
	 * @return how many registered partitions the collection holds
	 */
	public static int assertInvariants(
		@Nonnull String label,
		@Nonnull EntityCollection collection,
		long catalogVersion
	) {
		final String prefix = label + ": collection `" + collection.getEntityType() + "`";
		assertNoPartitionObjectBelowTwoOwners(label, collection);
		final EntityCollectionHeader header = collection.getEntityCollectionHeader();
		final Set<Integer> storedIndexes = Set.copyOf(header.usedEntityIndexPrimaryKeys());
		final Integer globalIndexPrimaryKey = header.globalEntityIndexPrimaryKey();
		if (globalIndexPrimaryKey != null) {
			// the storage read below must be able to find an index that is stored, or its silence proves nothing
			assertNotNull(
				collection.getStoragePartPersistenceService().getStoragePart(
					catalogVersion, globalIndexPrimaryKey, EntityIndexStoragePart.class
				),
				prefix + " must find the stored global index " + globalIndexPrimaryKey
			);
		}
		final List<String> violations = new ArrayList<>(4);
		int registered = 0;
		for (final Entry<Integer, RegisteredRow> entry : registeredRowsOf(collection).entrySet()) {
			registered++;
			final int partitionPrimaryKey = entry.getKey();
			final RegisteredRow row = entry.getValue();
			if (collection.getIndexByPrimaryKeyIfExists(partitionPrimaryKey) != null) {
				violations.add(row + " has an index object");
			}
			if (partitionPrimaryKey > header.lastEntityIndexPrimaryKey()) {
				violations.add(row + " is above the persisted sequence " + header.lastEntityIndexPrimaryKey());
			}
			if (storedIndexes.contains(partitionPrimaryKey)) {
				violations.add(row + " is listed among the stored indexes of the collection header");
			}
			if (
				collection.getStoragePartPersistenceService().getStoragePart(
					catalogVersion, partitionPrimaryKey, EntityIndexStoragePart.class
				) != null
			) {
				violations.add(row + " has a stored entity index");
			}
			final ReducedIndexMembership membership = membershipOf(collection, row.scope(), row.referenceName());
			if (membership == null) {
				violations.add(row + " has no membership lookup of its reference");
			} else {
				if (!membership.getCoveredIndexPrimaryKeys().contains(partitionPrimaryKey)) {
					violations.add(row + " is not covered by the membership");
				}
				if (membership.getResidualIndexPrimaryKeys().contains(partitionPrimaryKey)) {
					violations.add(row + " is residual in the membership");
				}
				if (!membership.getIndexPrimaryKeys(row.owner()).contains(partitionPrimaryKey)) {
					violations.add(row + " is not among the partitions the membership lists for its owner");
				}
			}
		}
		if (!violations.isEmpty()) {
			fail(prefix + " breaks " + violations.size() + " invariant(s):\n" + String.join("\n", violations));
		}
		return registered;
	}

	/**
	 * Fails when the collection holds an index object of a partition with a single owner, or with none, of a reference
	 * whose partitions may be virtual in the partition's scope - a catalog writing its single-owner partitions
	 * virtually represents every such partition by its registry row alone, and no checkpoint falls inside a mutation,
	 * where a partition object may be left without owners until the mutation sweeps it away.
	 *
	 * @param label      names the checkpoint in the failure message
	 * @param collection the collection of the catalog writing virtually
	 */
	public static void assertNoPartitionObjectBelowTwoOwners(
		@Nonnull String label,
		@Nonnull EntityCollection collection
	) {
		final List<String> found = new ArrayList<>(2);
		forEachPartitionObjectOfVirtualReference(
			collection,
			(scope, partition) -> {
				if (partition.getAllPrimaryKeys().size() <= 1) {
					found.add(
						partition.getIndexKey() + " (" + partition.getPrimaryKey() + ", " +
							partition.getAllPrimaryKeys().size() + " owners)"
					);
				}
			}
		);
		if (!found.isEmpty()) {
			fail(
				label + ": collection `" + collection.getEntityType() + "` holds index objects of partitions with " +
					"fewer than two owners it writes virtually: " + found
			);
		}
	}

	/**
	 * Passes every index object of a partition of a reference whose partitions may be virtual in the partition's scope
	 * the collection holds to the consumer - advertised or not, so an object a write created and left without owners is
	 * found too.
	 *
	 * The browse of the indexes reads the committed state only, while a check inside an open transaction must see the
	 * state of the transaction: so the primary keys the browse lists are joined with those the type index advertises
	 * in the transaction, and each is resolved in the transaction - an index object the transaction installed is
	 * found through its advertisement, and one it retired is not found at all.
	 *
	 * @param collection the collection
	 * @param consumer   receives the scope and the index object
	 */
	static void forEachPartitionObjectOfVirtualReference(
		@Nonnull EntityCollection collection,
		@Nonnull PartitionObjectConsumer consumer
	) {
		for (final Scope scope : Scope.values()) {
			for (final ReferenceSchemaContract referenceSchema : collection.getSchema().getReferences().values()) {
				if (!isShadowed(referenceSchema, scope)) {
					continue;
				}
				final SortedSet<Integer> primaryKeys = new TreeSet<>();
				int pageNumber = 1;
				IndexBrowseResult page;
				do {
					page = collection.browseIndexes(
						new IndexBrowseCriteria(
							pageNumber++, IndexBrowseCriteria.MAX_PAGE_SIZE, IndexBrowseOrdering.MAP_ORDER,
							OrderDirection.ASC, EnumSet.of(EntityIndexType.REFERENCED_ENTITY), EnumSet.of(scope),
							Set.of(referenceSchema.getName())
						)
					);
					for (final BrowsedIndex browsed : page.indexes()) {
						primaryKeys.add(browsed.indexPrimaryKey());
					}
				} while (page.indexes().length == IndexBrowseCriteria.MAX_PAGE_SIZE);
				if (
					collection.getIndexByKeyIfExists(
						new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceSchema.getName())
					) instanceof final ReferencedTypeEntityIndex typeIndex
				) {
					typeIndex.forEachReferenceIndexPrimaryKey(primaryKeys::add);
				}
				for (final Integer primaryKey : primaryKeys) {
					// a primary key of a row without an object, or of an object the transaction retired
					final EntityIndex partition = collection.getIndexByPrimaryKeyIfExists(primaryKey);
					if (partition != null && partition.getIndexKey().type() == EntityIndexType.REFERENCED_ENTITY) {
						consumer.accept(scope, partition);
					}
				}
			}
		}
	}

	/**
	 * Returns TRUE when the registry may hold rows for the reference in the scope - the reference is indexed
	 * `FOR_FILTERING`, not for partitioning, and with the referenced entity component. It restates the predicate of the
	 * writer of the registry on purpose, so the invariants do not take the writer's word for which references they
	 * cover.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @return TRUE when the reference is shadowed in the scope
	 */
	static boolean isShadowed(@Nonnull ReferenceSchemaContract referenceSchema, @Nonnull Scope scope) {
		return referenceSchema.getReferenceIndexType(scope) == ReferenceIndexType.FOR_FILTERING &&
			referenceSchema.getIndexedComponents(scope).contains(ReferenceIndexedComponents.REFERENCED_ENTITY);
	}

	/**
	 * Returns every registered partition of a collection - in both scopes, of every reference.
	 *
	 * @param collection the collection
	 * @return the registered rows by the primary key of their partition
	 */
	@Nonnull
	public static SortedMap<Integer, RegisteredRow> registeredRowsOf(@Nonnull EntityCollection collection) {
		final SortedMap<Integer, RegisteredRow> rows = new TreeMap<>();
		for (final Scope scope : Scope.values()) {
			for (final ReferenceSchemaContract referenceSchema : collection.getSchema().getReferences().values()) {
				final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceSchema.getName())
				);
				if (typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex) {
					final VirtualReferencePartitionIndex registry =
						referencedTypeIndex.getVirtualReferencePartitionIndex();
					registry.forEachEntry(
						VirtualReferencePartitionSlot.PARTITION_OWNER,
						(partitionPrimaryKey, payload) -> {
							final RegisteredRow previous = rows.put(
								partitionPrimaryKey,
								new RegisteredRow(
									scope, referenceSchema.getName(), partitionPrimaryKey,
									NumberUtils.unpackHigh(payload), NumberUtils.unpackLow(payload)
								)
							);
							assertNull(
								previous,
								"collection `" + collection.getEntityType() + "` registers partition " +
									partitionPrimaryKey + " twice: " + previous
							);
						}
					);
				}
			}
		}
		return rows;
	}

	/**
	 * Describes the indexes of every collection of a committed catalog - see
	 * {@link CatalogDescriber#describeIndexesOf(EntityCollection)}.
	 *
	 * @param evita       the engine
	 * @param catalogName the catalog
	 * @return the lines of every collection, sorted by path
	 */
	@Nonnull
	public static SortedMap<String, String> describeIndexes(@Nonnull Evita evita, @Nonnull String catalogName) {
		final SortedMap<String, String> lines = new TreeMap<>();
		final Catalog catalog = catalogOf(evita, catalogName);
		for (final String entityType : catalog.getEntityTypes()) {
			lines.putAll(CatalogDescriber.describeIndexesOf(collectionOf(evita, catalogName, entityType)));
		}
		return lines;
	}

	/**
	 * Asserts that two descriptions are equal, listing the lines that differ.
	 *
	 * @param label    names the moment in the failure message
	 * @param expected the description before the event
	 * @param actual   the description after it
	 */
	public static void assertUnchanged(
		@Nonnull String label,
		@Nonnull SortedMap<String, String> expected,
		@Nonnull SortedMap<String, String> actual
	) {
		final List<String> differences = new ArrayList<>(8);
		for (final Entry<String, String> line : expected.entrySet()) {
			final String actualValue = actual.get(line.getKey());
			if (actualValue == null) {
				differences.add("lost: " + line.getKey() + " = " + line.getValue());
			} else if (!actualValue.equals(line.getValue())) {
				differences.add(
					"changed: " + line.getKey() + "\n   before: " + line.getValue() + "\n   after:  " + actualValue
				);
			}
		}
		for (final Entry<String, String> line : actual.entrySet()) {
			if (!expected.containsKey(line.getKey())) {
				differences.add("new: " + line.getKey() + " = " + line.getValue());
			}
		}
		if (!differences.isEmpty()) {
			fail(
				label + ": " + differences.size() + " line(s) differ:\n" +
					String.join("\n", differences.subList(0, Math.min(differences.size(), 40)))
			);
		}
	}

	/**
	 * Returns a loaded catalog of an engine.
	 *
	 * @param evita       the engine
	 * @param catalogName the catalog
	 * @return the catalog
	 */
	@Nonnull
	public static Catalog catalogOf(@Nonnull Evita evita, @Nonnull String catalogName) {
		final CatalogContract catalog = evita.getCatalogInstanceOrThrowException(catalogName);
		return assertInstanceOf(Catalog.class, catalog, "catalog `" + catalogName + "` must be loaded");
	}

	/**
	 * Returns a collection of a loaded catalog of an engine.
	 *
	 * @param evita       the engine
	 * @param catalogName the catalog
	 * @param entityType  the entity type
	 * @return the collection
	 */
	@Nonnull
	public static EntityCollection collectionOf(
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		@Nonnull String entityType
	) {
		return (EntityCollection) catalogOf(evita, catalogName).getCollectionForEntityOrThrowException(entityType);
	}

	/**
	 * Returns the reduced-index membership of a reference of a collection in a scope.
	 *
	 * @param collection    the collection
	 * @param scope         the scope
	 * @param referenceName the reference
	 * @return the membership, `null` when the collection has none for the reference in the scope
	 */
	@Nullable
	private static ReducedIndexMembership membershipOf(
		@Nonnull EntityCollection collection,
		@Nonnull Scope scope,
		@Nonnull String referenceName
	) {
		final EntityIndex globalIndex =
			collection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, scope));
		return globalIndex instanceof final GlobalEntityIndex typedGlobalIndex ?
			typedGlobalIndex.getReducedIndexMembership(referenceName) : null;
	}

	/**
	 * One registered partition of a collection.
	 *
	 * @param scope               the scope of the partition
	 * @param referenceName       the reference of the partition
	 * @param partitionPrimaryKey the primary key of the partition
	 * @param owner               the only owner
	 * @param target              the referenced entity
	 */
	public record RegisteredRow(
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		int partitionPrimaryKey,
		int owner,
		int target
	) {

		@Nonnull
		@Override
		public String toString() {
			return "row " + this.partitionPrimaryKey + " (" + this.referenceName + " -> " + this.target + " in " +
				this.scope + ", owner " + this.owner + ")";
		}
	}

	/**
	 * Receives an index object of a partition with its scope.
	 */
	@FunctionalInterface
	interface PartitionObjectConsumer {

		/**
		 * Receives one index object.
		 *
		 * @param scope     the scope of the partition
		 * @param partition the index object
		 */
		void accept(@Nonnull Scope scope, @Nonnull EntityIndex partition);
	}

}
