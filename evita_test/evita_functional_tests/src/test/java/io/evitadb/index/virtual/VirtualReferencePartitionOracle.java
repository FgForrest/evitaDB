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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionSlot;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.virtual.SortIndexWalk.Block;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the registry of single-owner reference partitions of one collection against the partitions themselves - the
 * real reduced entity indexes a referenced-type entity index advertises. Ground truth is always the real partition;
 * the registry is never used to check itself.
 *
 * The check is closed-world: everything the registry holds must be accounted for by a partition, and everything a
 * registered partition holds must be in the registry.
 *
 * # Rows
 *
 * The registry may hold rows only for references that are indexed `FOR_FILTERING` with the referenced entity
 * component in the scope (a *shadowed* reference), and for every partition such a reference advertises:
 *
 * - a row exists exactly when the partition has one owner - except for the partitions the caller names as awaiting
 *   their demotion, which may lack it;
 * - the row names that owner and the referenced entity the partition is keyed by;
 * - the row's representative attribute values are the ones in the partition's key - an absent tuple stands for the
 *   empty one;
 * - no row exists for a partition that is not advertised, and no tuple exists without an owner row.
 *
 * # Sort and chain keys
 *
 * The keys checked are the union of the keys the registry declares and the sort and chain keys of this reference the
 * registered partitions hold. For every registered partition and every key:
 *
 * - its sort value in the registry equals the value the partition orders its only owner by. Both values are read
 *   position by position (see {@link SortIndexWalk}) as the two sort indexes store them, in the normalized space of
 *   the index, and compared by their {@link CanonicalValue} encoding; absence must meet absence;
 * - its chain predecessor in the registry equals the predecessor the owner declares in the partition's chain index -
 *   the referenced entity's record for a `ReferencedEntityPredecessor` attribute. A key the registry does not declare
 *   holds no predecessor; its strict reader is never asked. A declared chain key may have no entry at all while owners
 *   remain - the partition's own chain index disappears when emptied, the declaration stays until it is retired.
 *
 * The registry's sort index of a declared key must order the registered partitions exactly as the key's comparator
 * orders their values, with ascending partition primary keys inside a block of equal values - checked block by block,
 * so a reversed tie and a misplaced block boundary are both found. Its entries of partitions without a row are found
 * too.
 *
 * # Counts
 *
 * The registry's entry count must equal its owner rows, its tuples, the entries of its sort indexes and the chain
 * predecessors the registered partitions declare. A chain entry the reads above do not reach - one of a partition
 * without a row - therefore shows as a surplus, and an entry swapped for another one as a failed read. A registry
 * without a single owner row must declare no key.
 *
 * Whatever it compares, the oracle reads the real partition through public reads of its own and never through a
 * production extractor, so a defect shared by the writer and an extractor cannot hide from it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class VirtualReferencePartitionOracle {
	/**
	 * Partition primary key of a finding that concerns no single partition - a count or a declaration.
	 */
	static final int NO_PARTITION = 0;

	/**
	 * The kinds of disagreement between the registry and the partitions.
	 */
	enum FindingKind {
		/**
		 * A single-owner partition has no row, and is not named as awaiting its demotion.
		 */
		MISSING_ROW,
		/**
		 * A partition with more than one owner has a row.
		 */
		MULTI_OWNER_ROW,
		/**
		 * A partition with no owner at all has a row.
		 */
		OWNERLESS_ROW,
		/**
		 * The row names another owner than the partition's only member.
		 */
		OWNER_MISMATCH,
		/**
		 * The row names another referenced entity than the one the partition is keyed by.
		 */
		TARGET_MISMATCH,
		/**
		 * The row's representative attribute values differ from the ones in the partition's key.
		 */
		TUPLE_MISMATCH,
		/**
		 * A row names a partition the reference does not advertise.
		 */
		ROW_OUTSIDE_ADVERTISED,
		/**
		 * A partition has representative attribute values recorded but no owner row.
		 */
		TUPLE_WITHOUT_OWNER,
		/**
		 * A row, an entry or a declared key exists for a reference that is not shadowed in the scope.
		 */
		ROW_OF_UNSHADOWED_REFERENCE,
		/**
		 * The sort value of a registered partition differs from the value the partition orders its owner by, or only
		 * one of them exists.
		 */
		SORT_VALUE_MISMATCH,
		/**
		 * The registry holds a sort value of a partition that has no registered row.
		 */
		SORT_ENTRY_WITHOUT_ROW,
		/**
		 * The registry's sort index of a key orders the partitions otherwise than the key's comparator orders their
		 * values, or splits them into other blocks.
		 */
		SORT_ORDER_MISMATCH,
		/**
		 * The chain predecessor of a registered partition differs from the one its owner declares, or only one of them
		 * exists.
		 */
		CHAIN_PREDECESSOR_MISMATCH,
		/**
		 * The registry holds more or fewer entries than the rows, tuples, sort values and chain predecessors account
		 * for.
		 */
		ENTRY_COUNT_MISMATCH,
		/**
		 * The registry declares a key while it holds no owner row.
		 */
		KEYS_WITHOUT_OWNER
	}

	/**
	 * One disagreement between the registry and the partitions.
	 *
	 * @param referenceName       the reference
	 * @param scope               the scope
	 * @param partitionPrimaryKey the partition the disagreement concerns, {@link #NO_PARTITION} when it concerns none
	 * @param kind                what disagrees
	 * @param detail              the values that disagree
	 */
	record Finding(
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		int partitionPrimaryKey,
		@Nonnull FindingKind kind,
		@Nonnull String detail
	) {

		@Nonnull
		@Override
		public String toString() {
			return this.kind + " " + this.referenceName + "/" + this.scope + " partition " + this.partitionPrimaryKey +
				": " + this.detail;
		}
	}

	/**
	 * A registered partition: one owner, and a row in the registry.
	 *
	 * @param partition the partition itself
	 * @param owner     the primary key of its only owner
	 * @param target    the primary key of the entity it references
	 */
	private record Registered(@Nonnull ReducedEntityIndex partition, int owner, int target) {
	}

	/**
	 * The sort value a registered partition is expected to have in the registry.
	 *
	 * @param partitionPrimaryKey the partition
	 * @param value               the value the partition orders its owner by, as its sort index stores it
	 */
	record ExpectedSortValue(int partitionPrimaryKey, @Nonnull Serializable value) {
	}

	private VirtualReferencePartitionOracle() {
	}

	/**
	 * Returns TRUE when the registry may hold rows for the reference in the scope - the reference is indexed
	 * `FOR_FILTERING`, not for partitioning, and with the referenced entity component.
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
	 * Checks the registry of every reference of the collection in every scope against the partitions, requiring a row
	 * for every single-owner partition.
	 *
	 * @param collection the collection
	 * @return every disagreement found; empty when the registry agrees with the partitions
	 */
	@Nonnull
	static List<Finding> inspect(@Nonnull EntityCollection collection) {
		return inspect(collection, Set.of());
	}

	/**
	 * Checks the registry of every reference of the collection in every scope against the partitions, tolerating a
	 * missing row only for the named partitions - the ones whose demotion is still pending. An empty set requires a
	 * row for every single-owner partition.
	 *
	 * @param collection        the collection
	 * @param awaitingDemotion  primary keys of the partitions that may lack their row
	 * @return every disagreement found; empty when the registry agrees with the partitions
	 */
	@Nonnull
	static List<Finding> inspect(@Nonnull EntityCollection collection, @Nonnull Set<Integer> awaitingDemotion) {
		final List<Finding> findings = new ArrayList<>(8);
		forEachRegistry(
			collection,
			(referenceSchema, scope, typeIndex) ->
				inspect(collection, referenceSchema, scope, typeIndex, awaitingDemotion, findings)
		);
		return findings;
	}

	/**
	 * Fails when the registry of the collection disagrees with its partitions, listing every disagreement. A row is
	 * required for every single-owner partition.
	 *
	 * @param label      names the checkpoint in the failure message
	 * @param collection the collection
	 */
	static void assertConsistent(@Nonnull String label, @Nonnull EntityCollection collection) {
		assertConsistent(label, collection, Set.of());
	}

	/**
	 * Fails when the registry of the collection disagrees with its partitions, listing every disagreement. A missing
	 * row is tolerated only for the named partitions.
	 *
	 * @param label            names the checkpoint in the failure message
	 * @param collection       the collection
	 * @param awaitingDemotion primary keys of the partitions that may lack their row
	 */
	static void assertConsistent(
		@Nonnull String label,
		@Nonnull EntityCollection collection,
		@Nonnull Set<Integer> awaitingDemotion
	) {
		final List<Finding> findings = inspect(collection, awaitingDemotion);
		if (!findings.isEmpty()) {
			fail(
				label + ": the virtual reference partitions of `" + collection.getEntityType() + "` disagree with " +
					"the partitions in " + findings.size() + " place(s):\n" +
					String.join("\n", findings.stream().map(Finding::toString).toList())
			);
		}
	}

	/**
	 * Fails when the registry of any reference of the collection holds an entry or declares a key - the state the
	 * registry must stay in for as long as nothing writes it.
	 *
	 * @param label      names the checkpoint in the failure message
	 * @param collection the collection
	 */
	static void assertRegistryEmpty(@Nonnull String label, @Nonnull EntityCollection collection) {
		final List<String> nonEmpty = new ArrayList<>(4);
		forEachRegistry(
			collection,
			(referenceSchema, scope, typeIndex) -> {
				final VirtualReferencePartitionIndex registry = typeIndex.getVirtualReferencePartitionIndex();
				if (
					registry.getEntryCount() != 0 || !registry.getDeclaredSortKeys().isEmpty() ||
						!registry.getDeclaredChainKeys().isEmpty()
				) {
					nonEmpty.add(
						referenceSchema.getName() + "/" + scope + ": " + registry.getEntryCount() + " entries, " +
							"sort keys " + registry.getDeclaredSortKeys() + ", chain keys " +
							registry.getDeclaredChainKeys()
					);
				}
			}
		);
		if (!nonEmpty.isEmpty()) {
			fail(
				label + ": the virtual reference partitions of `" + collection.getEntityType() + "` must be empty, " +
					"but:\n" + String.join("\n", nonEmpty)
			);
		}
	}

	/**
	 * Checks that the registry's sort index of one key orders the partitions as their expected values dictate: by the
	 * key's comparator, and by ascending partition primary key inside a block of values the comparator finds equal.
	 * The two are compared block by block, so a reversed tie is found as well as a block split or merged otherwise.
	 *
	 * @param referenceName the reference
	 * @param scope         the scope
	 * @param key           the sort key
	 * @param registryWalk  the registry's sort index of the key, read position by position
	 * @param expected      the expected sort value of every registered partition that has one, in any order
	 * @param comparator    the comparator of the key, over normalized values
	 * @param findings      receives the disagreement
	 */
	static void checkOrder(
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull AttributeIndexKey key,
		@Nonnull SortIndexWalk registryWalk,
		@Nonnull List<ExpectedSortValue> expected,
		@Nonnull Comparator<Serializable> comparator,
		@Nonnull List<Finding> findings
	) {
		final List<ExpectedSortValue> sorted = new ArrayList<>(expected);
		sorted.sort(
			(first, second) -> {
				final int byValue = comparator.compare(first.value(), second.value());
				return byValue == 0 ?
					Integer.compare(first.partitionPrimaryKey(), second.partitionPrimaryKey()) : byValue;
			}
		);
		final List<List<Integer>> expectedBlocks = new ArrayList<>(sorted.size());
		for (int i = 0; i < sorted.size(); i++) {
			if (i == 0 || comparator.compare(sorted.get(i - 1).value(), sorted.get(i).value()) != 0) {
				expectedBlocks.add(new ArrayList<>(2));
			}
			expectedBlocks.get(expectedBlocks.size() - 1).add(sorted.get(i).partitionPrimaryKey());
		}
		final List<List<Integer>> actualBlocks = new ArrayList<>(registryWalk.blocks().size());
		for (final Block block : registryWalk.blocks()) {
			final List<Integer> partitions = new ArrayList<>(block.length());
			for (int i = 0; i < block.length(); i++) {
				partitions.add(registryWalk.records()[block.start() + i]);
			}
			actualBlocks.add(partitions);
		}
		if (!expectedBlocks.equals(actualBlocks)) {
			findings.add(
				new Finding(
					referenceName, scope, NO_PARTITION, FindingKind.SORT_ORDER_MISMATCH,
					"sort key " + key + " orders the partitions in blocks " + actualBlocks + ", their values order " +
						"them in blocks " + expectedBlocks
				)
			);
		}
	}

	/**
	 * Creates the comparator a sort key of the reference orders its normalized values by - the one {@link SortIndex}
	 * derives from the attribute's type, or from the elements of a sortable attribute compound with their directions
	 * and null behaviours, with the key's locale.
	 *
	 * @param referenceSchema the reference
	 * @param key             the sort key
	 * @return the comparator
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	static Comparator<Serializable> comparatorOf(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeIndexKey key
	) {
		final AttributeSchemaContract attribute = referenceSchema.getAttribute(key.attributeName()).orElse(null);
		if (attribute != null) {
			return SortIndex.createComparatorFor(
				key.locale(),
				new ComparatorSource(attribute.getPlainType(), OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			);
		}
		final SortableAttributeCompoundSchemaContract compound = referenceSchema
			.getSortableAttributeCompound(key.attributeName())
			.orElseThrow(
				() -> new IllegalStateException(
					"Sort key " + key + " names neither an attribute nor a compound of `" + referenceSchema.getName() +
						"`!"
				)
			);
		final List<AttributeElement> elements = compound.getAttributeElements();
		final ComparatorSource[] comparatorBase = new ComparatorSource[elements.size()];
		for (int i = 0; i < comparatorBase.length; i++) {
			final AttributeElement element = elements.get(i);
			comparatorBase[i] = new ComparatorSource(
				referenceSchema.getAttribute(element.attributeName()).orElseThrow().getPlainType(),
				element.direction(),
				element.behaviour()
			);
		}
		return (Comparator) SortIndex.createCombinedComparatorFor(key.locale(), comparatorBase);
	}

	/**
	 * Visits the referenced-type entity index of every reference of the collection in every scope that has one.
	 *
	 * @param collection the collection
	 * @param visitor    receives the reference, the scope and the referenced-type entity index
	 */
	private static void forEachRegistry(@Nonnull EntityCollection collection, @Nonnull RegistryVisitor visitor) {
		for (final Scope scope : Scope.values()) {
			for (final ReferenceSchemaContract referenceSchema : collection.getSchema().getReferences().values()) {
				final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceSchema.getName())
				);
				if (typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex) {
					visitor.visit(referenceSchema, scope, referencedTypeIndex);
				}
			}
		}
	}

	/**
	 * Checks the registry of one reference in one scope.
	 *
	 * @param collection       the collection resolving the partitions
	 * @param referenceSchema  the reference
	 * @param scope            the scope
	 * @param typeIndex        the referenced-type entity index carrying the registry
	 * @param awaitingDemotion primary keys of the partitions that may lack their row
	 * @param findings         receives the disagreements
	 */
	private static void inspect(
		@Nonnull EntityCollection collection,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		@Nonnull Set<Integer> awaitingDemotion,
		@Nonnull List<Finding> findings
	) {
		final String referenceName = referenceSchema.getName();
		final VirtualReferencePartitionIndex registry = typeIndex.getVirtualReferencePartitionIndex();
		final Set<Integer> owned = new TreeSet<>();
		registry.forEachEntry(
			VirtualReferencePartitionSlot.PARTITION_OWNER, (partition, payload) -> owned.add(partition)
		);
		final int[] tupleCount = {0};
		registry.forEachEntry(
			VirtualReferencePartitionSlot.REPRESENTATIVE_TUPLE,
			(partition, payload) -> {
				tupleCount[0]++;
				if (!owned.contains(partition)) {
					findings.add(
						new Finding(
							referenceName, scope, partition, FindingKind.TUPLE_WITHOUT_OWNER,
							"tuple " + Arrays.toString(registry.getTuple((int) payload))
						)
					);
				}
			}
		);
		if (!isShadowed(referenceSchema, scope)) {
			inspectUnshadowed(referenceSchema, scope, registry, owned, findings);
			return;
		}
		final boolean keysDeclared =
			!registry.getDeclaredSortKeys().isEmpty() || !registry.getDeclaredChainKeys().isEmpty();
		if (owned.isEmpty() && keysDeclared) {
			findings.add(
				new Finding(
					referenceName, scope, NO_PARTITION, FindingKind.KEYS_WITHOUT_OWNER,
					"no owner row, but sort keys " + registry.getDeclaredSortKeys() + " and chain keys " +
						registry.getDeclaredChainKeys()
				)
			);
		}

		final Set<Integer> advertised = new TreeSet<>();
		typeIndex.forEachReferenceIndexPrimaryKey(advertised::add);
		for (final Integer partition : owned) {
			if (!advertised.contains(partition)) {
				findings.add(
					new Finding(
						referenceName, scope, partition, FindingKind.ROW_OUTSIDE_ADVERTISED,
						"owner row " + describeRow(registry.getPartitionOwner(partition))
					)
				);
			}
		}
		final SortedMap<Integer, Registered> registered = new TreeMap<>();
		for (final Integer partitionPrimaryKey : advertised) {
			final EntityIndex partition = collection.getIndexByPrimaryKeyIfExists(partitionPrimaryKey);
			if (!(partition instanceof final ReducedEntityIndex reducedIndex)) {
				fail(
					"Reference `" + referenceName + "` in " + scope + " advertises partition " + partitionPrimaryKey +
						" which resolves to " + (partition == null ? "nothing" : partition.getClass().getSimpleName())
				);
				return;
			}
			final boolean hasRow = inspectRow(
				referenceName, scope, registry, partitionPrimaryKey, reducedIndex, awaitingDemotion, findings
			);
			if (hasRow) {
				registered.put(
					partitionPrimaryKey,
					new Registered(
						reducedIndex,
						reducedIndex.getAllPrimaryKeys().getFirst(),
						reducedIndex.getRepresentativeReferenceKey().primaryKey()
					)
				);
			}
		}

		final int sortEntries = inspectSortKeys(referenceSchema, scope, registry, registered, findings);
		final int chainEntries = inspectChainKeys(referenceSchema, scope, registry, registered, findings);
		final int expectedCount = owned.size() + tupleCount[0] + sortEntries + chainEntries;
		if (registry.getEntryCount() != expectedCount) {
			findings.add(
				new Finding(
					referenceName, scope, NO_PARTITION, FindingKind.ENTRY_COUNT_MISMATCH,
					"the registry holds " + registry.getEntryCount() + " entries, " + owned.size() + " owner rows, " +
						tupleCount[0] + " tuples, " + sortEntries + " sort values and " + chainEntries +
						" expected chain predecessors account for " + expectedCount
				)
			);
		}
	}

	/**
	 * Reports everything the registry holds for a reference that is not shadowed in the scope.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @param registry        the registry
	 * @param owned           the partitions with an owner row
	 * @param findings        receives the disagreements
	 */
	private static void inspectUnshadowed(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull Set<Integer> owned,
		@Nonnull List<Finding> findings
	) {
		final String reason = "the reference is " + referenceSchema.getReferenceIndexType(scope) + " with " +
			referenceSchema.getIndexedComponents(scope);
		for (final Integer partition : owned) {
			findings.add(
				new Finding(
					referenceSchema.getName(), scope, partition, FindingKind.ROW_OF_UNSHADOWED_REFERENCE, reason
				)
			);
		}
		if (
			registry.getEntryCount() > owned.size() || !registry.getDeclaredSortKeys().isEmpty() ||
				!registry.getDeclaredChainKeys().isEmpty()
		) {
			findings.add(
				new Finding(
					referenceSchema.getName(), scope, NO_PARTITION, FindingKind.ROW_OF_UNSHADOWED_REFERENCE,
					reason + ", yet the registry holds " + registry.getEntryCount() + " entries, sort keys " +
						registry.getDeclaredSortKeys() + " and chain keys " + registry.getDeclaredChainKeys()
				)
			);
		}
	}

	/**
	 * Checks the row of one advertised partition.
	 *
	 * @param referenceName       the reference
	 * @param scope               the scope
	 * @param registry            the registry
	 * @param partitionPrimaryKey the partition primary key
	 * @param partition           the partition itself
	 * @param awaitingDemotion    primary keys of the partitions that may lack their row
	 * @param findings            receives the disagreements
	 * @return TRUE when the partition has one owner and a row - a registered partition whose keys are checked next
	 */
	private static boolean inspectRow(
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull VirtualReferencePartitionIndex registry,
		int partitionPrimaryKey,
		@Nonnull ReducedEntityIndex partition,
		@Nonnull Set<Integer> awaitingDemotion,
		@Nonnull List<Finding> findings
	) {
		final Bitmap owners = partition.getAllPrimaryKeys();
		final OptionalLong row = registry.getPartitionOwner(partitionPrimaryKey);
		if (owners.size() != 1) {
			if (row.isPresent()) {
				findings.add(
					new Finding(
						referenceName, scope, partitionPrimaryKey,
						owners.isEmpty() ? FindingKind.OWNERLESS_ROW : FindingKind.MULTI_OWNER_ROW,
						"owners " + Arrays.toString(owners.getArray()) + ", row " + describeRow(row)
					)
				);
			}
			return false;
		}
		if (row.isEmpty()) {
			if (!awaitingDemotion.contains(partitionPrimaryKey)) {
				findings.add(
					new Finding(
						referenceName, scope, partitionPrimaryKey, FindingKind.MISSING_ROW,
						"single owner " + owners.getFirst() + " of " + partition.getRepresentativeReferenceKey()
					)
				);
			}
			return false;
		}

		final RepresentativeReferenceKey key = partition.getRepresentativeReferenceKey();
		final int owner = NumberUtils.unpackHigh(row.getAsLong());
		final int target = NumberUtils.unpackLow(row.getAsLong());
		if (owner != owners.getFirst()) {
			findings.add(
				new Finding(
					referenceName, scope, partitionPrimaryKey, FindingKind.OWNER_MISMATCH,
					"row names owner " + owner + ", the partition's only member is " + owners.getFirst()
				)
			);
		}
		if (target != key.primaryKey()) {
			findings.add(
				new Finding(
					referenceName, scope, partitionPrimaryKey, FindingKind.TARGET_MISMATCH,
					"row names referenced entity " + target + ", the partition is keyed by " + key.primaryKey()
				)
			);
		}
		final int tupleId = registry.getRepresentativeTupleId(partitionPrimaryKey);
		final Serializable[] recordedTuple = tupleId == VirtualReferencePartitionIndex.NO_TUPLE_ID ?
			new Serializable[0] : registry.getTuple(tupleId);
		if (!Arrays.equals(recordedTuple, key.representativeAttributeValues())) {
			findings.add(
				new Finding(
					referenceName, scope, partitionPrimaryKey, FindingKind.TUPLE_MISMATCH,
					"row records " + Arrays.toString(recordedTuple) + ", the partition is keyed by " +
						Arrays.toString(key.representativeAttributeValues())
				)
			);
		}
		return true;
	}

	/**
	 * Checks every sort key - the declared ones and the ones of this reference the registered partitions hold - value
	 * by value and in order.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @param registry        the registry
	 * @param registered      the registered partitions by primary key
	 * @param findings        receives the disagreements
	 * @return how many sort values the registry's sort indexes hold, the count the entry count must include
	 */
	private static int inspectSortKeys(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull SortedMap<Integer, Registered> registered,
		@Nonnull List<Finding> findings
	) {
		final String referenceName = referenceSchema.getName();
		final Set<AttributeIndexKey> declared = registry.getDeclaredSortKeys();
		final Set<AttributeIndexKey> keys = new TreeSet<>(declared);
		for (final Registered partition : registered.values()) {
			collectKeys(partition.partition(), AttributeIndexType.SORT, referenceName, keys);
		}
		int sortEntries = 0;
		for (final AttributeIndexKey key : keys) {
			final OwnerSortIndex registrySortIndex = declared.contains(key) ? registry.getSortIndex(key) : null;
			final SortIndexWalk registryWalk = registrySortIndex == null ? null : SortIndexWalk.of(registrySortIndex);
			final Map<Integer, Serializable> actualValues = valuesByRecord(registryWalk);
			sortEntries += actualValues.size();
			final List<ExpectedSortValue> expected = new ArrayList<>(registered.size());
			for (final Map.Entry<Integer, Registered> entry : registered.entrySet()) {
				final int partitionPrimaryKey = entry.getKey();
				final SortIndex realSortIndex = entry.getValue().partition().getSortIndex(key);
				final Serializable expectedValue = realSortIndex == null ?
					null : valuesByRecord(SortIndexWalk.of(realSortIndex)).get(entry.getValue().owner());
				final Serializable actualValue = actualValues.get(partitionPrimaryKey);
				final String expectedEncoding = expectedValue == null ? "absent" : CanonicalValue.encode(expectedValue);
				final String actualEncoding = actualValue == null ? "absent" : CanonicalValue.encode(actualValue);
				if (!expectedEncoding.equals(actualEncoding)) {
					findings.add(
						new Finding(
							referenceName, scope, partitionPrimaryKey, FindingKind.SORT_VALUE_MISMATCH,
							"sort key " + key + ": the registry holds " + actualEncoding + ", the partition orders " +
								"its owner " + entry.getValue().owner() + " by " + expectedEncoding
						)
					);
				}
				if (expectedValue != null) {
					expected.add(new ExpectedSortValue(partitionPrimaryKey, expectedValue));
				}
			}
			for (final Map.Entry<Integer, Serializable> actual : actualValues.entrySet()) {
				if (!registered.containsKey(actual.getKey())) {
					findings.add(
						new Finding(
							referenceName, scope, actual.getKey(), FindingKind.SORT_ENTRY_WITHOUT_ROW,
							"sort key " + key + " holds " + CanonicalValue.encode(actual.getValue()) +
								" for a partition without a registered row"
						)
					);
				}
			}
			if (registryWalk != null) {
				checkOrder(
					referenceName, scope, key, registryWalk, expected, comparatorOf(referenceSchema, key), findings
				);
			}
		}
		return sortEntries;
	}

	/**
	 * Checks every chain key - the declared ones and the ones of this reference the registered partitions hold - by
	 * an exhaustive read of every registered partition.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @param registry        the registry
	 * @param registered      the registered partitions by primary key
	 * @param findings        receives the disagreements
	 * @return how many chain predecessors the registered partitions declare, the count the entry count must include
	 */
	private static int inspectChainKeys(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull SortedMap<Integer, Registered> registered,
		@Nonnull List<Finding> findings
	) {
		final String referenceName = referenceSchema.getName();
		final Set<AttributeIndexKey> declared = registry.getDeclaredChainKeys();
		final Set<AttributeIndexKey> keys = new TreeSet<>(declared);
		for (final Registered partition : registered.values()) {
			collectKeys(partition.partition(), AttributeIndexType.CHAIN, referenceName, keys);
		}
		int chainEntries = 0;
		for (final AttributeIndexKey key : keys) {
			final boolean byReferencedEntity = referenceSchema.getAttribute(key.attributeName())
				.map(it -> ReferencedEntityPredecessor.class.isAssignableFrom(it.getPlainType()))
				.orElse(false);
			for (final Map.Entry<Integer, Registered> entry : registered.entrySet()) {
				final Registered partition = entry.getValue();
				final ChainIndex realChainIndex = partition.partition().getChainIndex(key);
				final int recordId = byReferencedEntity ? partition.target() : partition.owner();
				final OptionalInt expected = realChainIndex == null ?
					OptionalInt.empty() : realChainIndex.getPredecessorPrimaryKey(recordId);
				// a key the registry does not declare holds nothing - its strict reader is never asked
				final OptionalInt actual = declared.contains(key) ?
					registry.getChainPredecessor(key, entry.getKey()) : OptionalInt.empty();
				if (!expected.equals(actual)) {
					findings.add(
						new Finding(
							referenceName, scope, entry.getKey(), FindingKind.CHAIN_PREDECESSOR_MISMATCH,
							"chain key " + key + ": the registry holds " + actual + ", the partition's record " +
								recordId + " declares " + expected
						)
					);
				}
				if (expected.isPresent()) {
					chainEntries++;
				}
			}
		}
		return chainEntries;
	}

	/**
	 * Adds the keys of one kind of attribute index of the partition that belong to the reference.
	 *
	 * @param partition     the partition
	 * @param type          the kind of attribute index
	 * @param referenceName the reference
	 * @param keys          receives the keys
	 */
	private static void collectKeys(
		@Nonnull ReducedEntityIndex partition,
		@Nonnull AttributeIndexType type,
		@Nonnull String referenceName,
		@Nonnull Set<AttributeIndexKey> keys
	) {
		partition.forEachAttributeIndexKey(
			type,
			key -> {
				if (referenceName.equals(key.referenceName())) {
					keys.add(key);
				}
			}
		);
	}

	/**
	 * Maps every record of a walked sort index to its value.
	 *
	 * @param walk the walk, possibly `null` for no sort index
	 * @return the value of every record; empty for no sort index
	 */
	@Nonnull
	private static Map<Integer, Serializable> valuesByRecord(@Nullable SortIndexWalk walk) {
		if (walk == null) {
			return Map.of();
		}
		final Map<Integer, Serializable> values = CollectionUtils.createHashMap(walk.records().length);
		for (int i = 0; i < walk.records().length; i++) {
			values.put(walk.records()[i], walk.values()[i]);
		}
		return values;
	}

	/**
	 * Renders an owner row.
	 *
	 * @param row the packed owner and referenced entity, possibly absent
	 * @return the rendering
	 */
	@Nonnull
	private static String describeRow(@Nonnull OptionalLong row) {
		return row.isPresent() ?
			"(owner " + NumberUtils.unpackHigh(row.getAsLong()) + ", target " + NumberUtils.unpackLow(row.getAsLong()) +
				")" :
			"(none)";
	}

	/**
	 * Receives the referenced-type entity index of one reference in one scope.
	 */
	@FunctionalInterface
	private interface RegistryVisitor {

		/**
		 * Visits one referenced-type entity index.
		 *
		 * @param referenceSchema the reference
		 * @param scope           the scope
		 * @param typeIndex       the referenced-type entity index
		 */
		void visit(
			@Nonnull ReferenceSchemaContract referenceSchema,
			@Nonnull Scope scope,
			@Nonnull ReferencedTypeEntityIndex typeIndex
		);
	}
}
