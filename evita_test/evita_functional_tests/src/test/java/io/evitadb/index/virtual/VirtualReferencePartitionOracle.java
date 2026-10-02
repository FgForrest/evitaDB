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
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionSlot;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the registry of single-owner reference partitions of one collection against the partitions themselves - the
 * real reduced entity indexes a referenced-type entity index advertises. Ground truth is always the real partition;
 * the registry is never used to check itself.
 *
 * The registry may hold rows only for references that are indexed `FOR_FILTERING` with the referenced entity
 * component in the scope (a *shadowed* reference), and for every partition such a reference advertises:
 *
 * - a row exists only when the partition has exactly one owner - and, in {@link Mode#EXACT}, always when it does;
 * - the row names that owner and the referenced entity the partition is keyed by;
 * - the row's representative attribute values are the ones in the partition's key - an absent tuple stands for the
 *   empty one;
 * - no row exists for a partition that is not advertised, and no tuple exists without an owner row.
 *
 * Sort and chain keys are not compared yet: the registry holds none until its writers maintain them, and their
 * comparison is added together with them. Whatever it compares, the oracle reads the real partition through public
 * reads of its own and never through a production extractor, so a defect shared by the writer and an extractor cannot
 * hide from it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class VirtualReferencePartitionOracle {

	/**
	 * How strictly the registry is held to the partitions.
	 */
	enum Mode {
		/**
		 * Every row the registry holds must be right, but a single-owner partition may lack its row.
		 */
		SUBSET,
		/**
		 * Every row the registry holds must be right, and every single-owner partition must have one.
		 */
		EXACT
	}

	/**
	 * The kinds of disagreement between the registry and the partitions.
	 */
	enum FindingKind {
		/**
		 * A single-owner partition has no row - reported in {@link Mode#EXACT} only.
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
		 * A row exists for a reference that is not shadowed in the scope.
		 */
		ROW_OF_UNSHADOWED_REFERENCE
	}

	/**
	 * One disagreement between the registry and the partitions.
	 *
	 * @param referenceName       the reference
	 * @param scope               the scope
	 * @param partitionPrimaryKey the partition the disagreement concerns
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
	 * Checks the registry of every reference of the collection in every scope against the partitions.
	 *
	 * @param collection the collection
	 * @param mode       how strictly to check
	 * @return every disagreement found; empty when the registry agrees with the partitions
	 */
	@Nonnull
	static List<Finding> inspect(@Nonnull EntityCollection collection, @Nonnull Mode mode) {
		final List<Finding> findings = new ArrayList<>(8);
		for (final Scope scope : Scope.values()) {
			for (final ReferenceSchemaContract referenceSchema : collection.getSchema().getReferences().values()) {
				final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceSchema.getName())
				);
				if (typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex) {
					inspect(collection, referenceSchema, scope, referencedTypeIndex, mode, findings);
				}
			}
		}
		return findings;
	}

	/**
	 * Fails when the registry of the collection disagrees with its partitions, listing every disagreement.
	 *
	 * @param label      names the checkpoint in the failure message
	 * @param collection the collection
	 * @param mode       how strictly to check
	 */
	static void assertConsistent(@Nonnull String label, @Nonnull EntityCollection collection, @Nonnull Mode mode) {
		final List<Finding> findings = inspect(collection, mode);
		if (!findings.isEmpty()) {
			fail(
				label + ": the virtual reference partitions of `" + collection.getEntityType() + "` disagree with " +
					"the partitions in " + findings.size() + " place(s):\n" +
					String.join("\n", findings.stream().map(Finding::toString).toList())
			);
		}
	}

	/**
	 * Checks the registry of one reference in one scope.
	 *
	 * @param collection      the collection resolving the partitions
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @param typeIndex       the referenced-type entity index carrying the registry
	 * @param mode            how strictly to check
	 * @param findings        receives the disagreements
	 */
	private static void inspect(
		@Nonnull EntityCollection collection,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		@Nonnull Mode mode,
		@Nonnull List<Finding> findings
	) {
		final String referenceName = referenceSchema.getName();
		final VirtualReferencePartitionIndex registry = typeIndex.getVirtualReferencePartitionIndex();
		final Set<Integer> owned = new TreeSet<>();
		registry.forEachEntry(
			VirtualReferencePartitionSlot.PARTITION_OWNER, (partition, payload) -> owned.add(partition)
		);
		registry.forEachEntry(
			VirtualReferencePartitionSlot.REPRESENTATIVE_TUPLE,
			(partition, payload) -> {
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
			for (final Integer partition : owned) {
				findings.add(
					new Finding(
						referenceName, scope, partition, FindingKind.ROW_OF_UNSHADOWED_REFERENCE,
						"the reference is " + referenceSchema.getReferenceIndexType(scope) + " with " +
							referenceSchema.getIndexedComponents(scope)
					)
				);
			}
			return;
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
		for (final Integer partitionPrimaryKey : advertised) {
			final EntityIndex partition = collection.getIndexByPrimaryKeyIfExists(partitionPrimaryKey);
			if (!(partition instanceof final ReducedEntityIndex reducedIndex)) {
				fail(
					"Reference `" + referenceName + "` in " + scope + " advertises partition " + partitionPrimaryKey +
						" which resolves to " + (partition == null ? "nothing" : partition.getClass().getSimpleName())
				);
				return;
			}
			inspectPartition(referenceName, scope, registry, partitionPrimaryKey, reducedIndex, mode, findings);
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
	 * @param mode                how strictly to check
	 * @param findings            receives the disagreements
	 */
	private static void inspectPartition(
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull VirtualReferencePartitionIndex registry,
		int partitionPrimaryKey,
		@Nonnull ReducedEntityIndex partition,
		@Nonnull Mode mode,
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
			return;
		}
		if (row.isEmpty()) {
			if (mode == Mode.EXACT) {
				findings.add(
					new Finding(
						referenceName, scope, partitionPrimaryKey, FindingKind.MISSING_ROW,
						"single owner " + owners.getFirst() + " of " + partition.getRepresentativeReferenceKey()
					)
				);
			}
			return;
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
}
