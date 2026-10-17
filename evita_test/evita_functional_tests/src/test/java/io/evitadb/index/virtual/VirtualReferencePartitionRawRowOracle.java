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
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionIndexEntries;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the reference partitions of one collection against the stored entity bodies - the partitions the indexes
 * must hold, derived from what the owners say they reference, not from any index.
 *
 * A check of the registry against the index objects, and a comparison of an engine writing the partitions as rows
 * with one writing them as objects, are both blind to a defect of the indexing path the two share: a sortable value
 * the path never writes is missing from the object, from the row and from the other engine alike, while the body still
 * holds it. This oracle reads the bodies through the public query API - every page of them, in both scopes - and
 * derives, for every reference written virtually in a scope
 * (see {@link VirtualPartitionInvariants#isWrittenVirtually}):
 *
 * - **the closed world of the partitions**: the rows of the owners grouped by referenced entity and - for a reference
 *   allowing duplicates - by the values of its representative attributes, a missing one taking its default. Every
 *   group is a partition with the group's owners. A partition with one owner must be a registry row naming that owner,
 *   and must have no index object; a partition with more owners must be an index object holding exactly them, and
 *   must have no row. Every row and every index object of the reference the bodies produce no partition for is
 *   reported, as is every representative tuple the registry keeps for a primary key without a row;
 * - **the sort values of a row**: for every attribute of the reference sortable in the scope, the value of the owner's
 *   reference at its locale, and for every sortable attribute compound of the reference indexed in the scope, its
 *   elements - per attribute locale of the owner for a localized compound - unless all of them are missing. The
 *   registry must hold exactly these values under exactly these keys, and nothing under a primary key without a row,
 *   and must order the rows of every key as these values order them - rows whose values compare equal forming one
 *   block;
 * - **the chain predecessors of a row**: for every chainable attribute of the reference sortable in the scope, the
 *   predecessor the owner's reference declares;
 * - **the content of an index object**: its owners, and per owner the same sort values and chain predecessors a row of
 *   the owner would hold, its owners ordered as those values order them - so a promotion that populates the object of
 *   a partition gaining its second owner is held to what the first owner's body says, not to what its row said.
 *
 * A `ReferencedEntityPredecessor` chain is left out: the chain index keys it by the referenced entity, and a partition
 * that had a second owner may have lost the predecessor (#1691).
 *
 * **What it is independent of, and what not.** The partitions, their owners and every value are derived from the
 * bodies. The primary key of a partition is not: it is whatever its row or its object carries, because the bodies
 * know nothing of primary keys. A transition that hands a partition another primary key and keeps every structure in
 * step with it passes this oracle; the identity is held by a {@link PartitionIdentityLedger}, which the caller may pass
 * to compare the primary keys with those observed at earlier checkpoints. Every sort value and representative tuple
 * the registry holds is enumerated and accounted for; the chain predecessors are read under the primary keys of the
 * matched rows only, because the registry offers no enumeration of the entries of a chain - one it keeps under a
 * primary key without a row is not found.
 *
 * A body value is compared in the space the index stores it in. It is taken through the conversions an indexed value
 * goes through - {@link NumberUtils#normalizeForIndexing(Serializable, int)} at the attribute's indexed decimal places,
 * then the normalizer of the sort index - and compared by {@link CanonicalValue#encodeIndexed(Object)}. The values are
 * ordered by the comparator a sort index of the key is created with (see
 * {@link SortIndex#createComparatorFor(Locale, ComparatorSource)}), built from the schema of the attribute or compound.
 * Those are pure functions of a value and a schema; which values reach which index, for which partition, and in which
 * order they end up, is what the oracle derives on its own.
 *
 * The oracle holds for histories without drift only: a schema change that leaves existing partitions as they were -
 * an attribute turned sortable, a reference turned virtually written or another scale - makes the bodies and the
 * partitions disagree by design.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class VirtualReferencePartitionRawRowOracle {
	/**
	 * How many bodies of one collection and scope one query reads.
	 */
	static final int PAGE_SIZE = 10_000;
	/**
	 * Stands for a partition that has neither a row nor an index object - partition primary keys are positive.
	 */
	private static final int NO_PARTITION = 0;

	/**
	 * One partition as the bodies describe it.
	 *
	 * @param key    the key of the partition
	 * @param owners the rows of its owners referencing the entity, by ascending owner primary key
	 */
	private record ExpectedPartition(@Nonnull RepresentativeReferenceKey key, @Nonnull List<OwnerRow> owners) {

		/**
		 * Returns the primary keys of the owners in ascending order.
		 *
		 * @return the owner primary keys
		 */
		@Nonnull
		int[] ownerPrimaryKeys() {
			final int[] primaryKeys = new int[this.owners.size()];
			for (int i = 0; i < primaryKeys.length; i++) {
				primaryKeys[i] = primaryKeyOf(this.owners.get(i));
			}
			Arrays.sort(primaryKeys);
			return primaryKeys;
		}
	}

	/**
	 * One row of a reference in the body of its owner.
	 *
	 * @param entity    the body of the owner
	 * @param reference the row
	 */
	private record OwnerRow(@Nonnull SealedEntity entity, @Nonnull ReferenceContract reference) {
	}

	/**
	 * One owner row of the registry with the representative tuple of its partition.
	 *
	 * @param partitionPrimaryKey the primary key of the partition
	 * @param owner               the owner the row names
	 * @param target              the referenced entity the row names
	 * @param tuple               the representative tuple of the partition, empty when it has none
	 */
	private record RegistryRow(int partitionPrimaryKey, int owner, int target, @Nonnull Serializable[] tuple) {

		@Nonnull
		@Override
		public String toString() {
			return "row " + this.partitionPrimaryKey + " (owner " + this.owner + " -> " + this.target +
				(this.tuple.length == 0 ? "" : ", tuple " + Arrays.toString(this.tuple)) + ")";
		}
	}

	private VirtualReferencePartitionRawRowOracle() {
	}

	/**
	 * Fails when the partitions of the collection disagree with the bodies of its entities, listing every
	 * disagreement. The collection must represent its single-owner partitions as rows only.
	 *
	 * @param label      names the checkpoint in the failure message
	 * @param session    the session to read the bodies in - inside an open session the one its steps ran in
	 * @param collection the collection
	 */
	public static void assertConsistent(
		@Nonnull String label,
		@Nonnull EvitaSessionContract session,
		@Nonnull EntityCollection collection
	) {
		assertConsistent(label, session, collection, null);
	}

	/**
	 * Fails when the partitions of the collection disagree with the bodies of its entities or - given a ledger - with
	 * the primary keys the ledger observed before, listing every disagreement. The collection must represent its
	 * single-owner partitions as rows only.
	 *
	 * @param label      names the checkpoint in the failure message
	 * @param session    the session to read the bodies in - inside an open session the one its steps ran in
	 * @param collection the collection
	 * @param ledger     the identity of the partitions observed at the earlier checkpoints of the history, which
	 *                   records this one; `null` to leave the identity unchecked
	 */
	static void assertConsistent(
		@Nonnull String label,
		@Nonnull EvitaSessionContract session,
		@Nonnull EntityCollection collection,
		@Nullable PartitionIdentityLedger ledger
	) {
		failOnFindings(label, collection, inspect(session, collection, ledger, PAGE_SIZE));
	}

	/**
	 * Checks the partitions of every reference written virtually of the collection in every scope against the bodies.
	 * The collection must represent its single-owner partitions as rows only.
	 *
	 * @param session    the session to read the bodies in
	 * @param collection the collection
	 * @return every disagreement found; empty when the partitions agree with the bodies
	 */
	@Nonnull
	static List<String> inspect(@Nonnull EvitaSessionContract session, @Nonnull EntityCollection collection) {
		return inspect(session, collection, null, PAGE_SIZE);
	}

	/**
	 * Checks the partitions of every reference written virtually of the collection in every scope against the bodies
	 * and - given a ledger - their primary keys against those it observed before. The collection must represent its
	 * single-owner partitions as rows only.
	 *
	 * @param session    the session to read the bodies in
	 * @param collection the collection
	 * @param ledger     the identity of the partitions observed at the earlier checkpoints of the history, which
	 *                   records this one; `null` to leave the identity unchecked
	 * @return every disagreement found; empty when the partitions agree with the bodies and the ledger
	 */
	@Nonnull
	static List<String> inspect(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntityCollection collection,
		@Nullable PartitionIdentityLedger ledger
	) {
		return inspect(session, collection, ledger, PAGE_SIZE);
	}

	/**
	 * Checks the partitions of every reference written virtually of the collection in every scope against the bodies,
	 * read page by page, and - given a ledger - their primary keys against those it observed before. The collection
	 * must represent its single-owner partitions as rows only.
	 *
	 * @param session    the session to read the bodies in
	 * @param collection the collection
	 * @param ledger     the identity of the partitions observed at the earlier checkpoints of the history, which
	 *                   records this one; `null` to leave the identity unchecked
	 * @param pageSize   how many bodies one query reads
	 * @return every disagreement found; empty when the partitions agree with the bodies and the ledger
	 */
	@Nonnull
	static List<String> inspect(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntityCollection collection,
		@Nullable PartitionIdentityLedger ledger,
		int pageSize
	) {
		return inspect(session, collection, ledger, pageSize, partition -> true);
	}

	/**
	 * Checks the partitions as {@link #inspect(EvitaSessionContract, EntityCollection, PartitionIdentityLedger, int)}
	 * does, reading only the index objects of the collection the filter admits - the means of proving that the oracle
	 * reports an index object missing from its input, as it would one missing from the collection.
	 *
	 * @param session      the session to read the bodies in
	 * @param collection   the collection
	 * @param ledger       the identity of the partitions observed at the earlier checkpoints of the history, which
	 *                     records this one; `null` to leave the identity unchecked
	 * @param pageSize     how many bodies one query reads
	 * @param objectFilter admits the index objects of the collection the oracle reads
	 * @return every disagreement found; empty when the partitions agree with the bodies and the ledger
	 */
	@Nonnull
	static List<String> inspect(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntityCollection collection,
		@Nullable PartitionIdentityLedger ledger,
		int pageSize,
		@Nonnull Predicate<EntityIndex> objectFilter
	) {
		final List<String> findings = new ArrayList<>(4);
		final String entityType = collection.getEntityType();
		final EntitySchemaContract schema = session.getEntitySchemaOrThrowException(entityType);
		// the index objects of every reference whose partitions may be virtual, by scope and reference
		final Map<String, List<EntityIndex>> objects = new TreeMap<>();
		VirtualPartitionInvariants.forEachPartitionObjectOfVirtualReference(
			collection,
			(scope, partition) -> {
				if (objectFilter.test(partition)) {
					objects.computeIfAbsent(
						scope + "/" + ((RepresentativeReferenceKey) Objects.requireNonNull(
							partition.getIndexKey().discriminator()
						)).referenceName(),
						__ -> new ArrayList<>(4)
					).add(partition);
				}
			}
		);
		final SortedMap<String, Integer> identities = new TreeMap<>();
		for (final Scope scope : Scope.values()) {
			final List<SealedEntity> bodies = readBodies(session, entityType, scope, pageSize);
			for (final ReferenceSchemaContract referenceSchema : schema.getReferences().values()) {
				if (VirtualPartitionInvariants.isWrittenVirtually(referenceSchema, scope)) {
					inspect(
						collection, referenceSchema, scope, bodies,
						objects.getOrDefault(scope + "/" + referenceSchema.getName(), List.of()),
						identities, findings
					);
				}
			}
		}
		if (ledger != null) {
			ledger.check(entityType, identities, findings);
		}
		return findings;
	}

	/**
	 * Fails when the oracle found any disagreement, listing every one.
	 *
	 * @param label      names the checkpoint in the failure message
	 * @param collection the collection checked
	 * @param findings   the disagreements
	 */
	private static void failOnFindings(
		@Nonnull String label,
		@Nonnull EntityCollection collection,
		@Nonnull List<String> findings
	) {
		if (!findings.isEmpty()) {
			fail(
				label + ": the reference partitions of `" + collection.getEntityType() + "` disagree with the " +
					"entity bodies in " + findings.size() + " place(s):\n" + String.join("\n", findings)
			);
		}
	}

	/**
	 * Reads the body of every entity of the collection in one scope, page by page until the total the query reports.
	 *
	 * @param session    the session to read the bodies in
	 * @param entityType the entity type of the collection
	 * @param scope      the scope
	 * @param pageSize   how many bodies one query reads
	 * @return every body of the scope
	 */
	@Nonnull
	private static List<SealedEntity> readBodies(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		int pageSize
	) {
		final List<SealedEntity> bodies = new ArrayList<>(Math.min(pageSize, 64));
		final Set<Integer> primaryKeys = new HashSet<>(64);
		int pageNumber = 1;
		int total;
		do {
			final EvitaResponse<SealedEntity> response = session.querySealedEntity(
				query(
					collection(entityType),
					filterBy(scope(scope)),
					require(page(pageNumber++, pageSize), entityFetchAll())
				)
			);
			total = response.getTotalRecordCount();
			for (final SealedEntity body : response.getRecordData()) {
				if (!primaryKeys.add(body.getPrimaryKeyOrThrowException())) {
					fail(
						"The body of `" + entityType + "` " + body.getPrimaryKey() + " in " + scope + " was read twice"
					);
				}
				bodies.add(body);
			}
			if (response.getRecordData().isEmpty()) {
				break;
			}
		} while (bodies.size() < total);
		if (bodies.size() != total) {
			fail(
				"The bodies of `" + entityType + "` in " + scope + " were read incompletely: " + bodies.size() +
					" of " + total
			);
		}
		return bodies;
	}

	/**
	 * Checks the partitions of one reference written virtually in one scope against the bodies: matches every partition
	 * the bodies produce to its row or its index object, reports the rows and objects no body produces, records the
	 * primary key of every matched partition and checks the values of every matched row and object.
	 *
	 * @param collection      the collection of the partitions
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @param bodies          the bodies of every entity of the collection in the scope
	 * @param objects         the index objects of the reference in the scope
	 * @param identities      receives the primary key of every matched partition by its identity key
	 * @param findings        receives the disagreements
	 */
	private static void inspect(
		@Nonnull EntityCollection collection,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull List<SealedEntity> bodies,
		@Nonnull List<EntityIndex> objects,
		@Nonnull SortedMap<String, Integer> identities,
		@Nonnull List<String> findings
	) {
		final String referenceName = referenceSchema.getName();
		final String prefix = referenceName + "/" + scope + ": ";
		final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName)
		);
		final VirtualReferencePartitionIndex registry =
			typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex ?
				referencedTypeIndex.getVirtualReferencePartitionIndex() : null;
		final int[] probedPartitions = registry == null ?
			new int[0] : probedPartitionsOf(collection, registry, objects);
		final Map<String, List<RegistryRow>> rowsByKey = registry == null ?
			new TreeMap<>() : rowsByKey(registry, probedPartitions, prefix, findings);
		final Map<String, EntityIndex> objectsByKey = new TreeMap<>();
		for (final EntityIndex object : objects) {
			objectsByKey.put(
				encodingOf((RepresentativeReferenceKey) Objects.requireNonNull(object.getIndexKey().discriminator())),
				object
			);
		}

		// the closed world: every partition the bodies produce is matched to its row or its index object
		final SortedMap<Integer, OwnerRow> rowsToCheck = new TreeMap<>();
		for (final Map.Entry<String, ExpectedPartition> entry : groupRows(referenceSchema, bodies).entrySet()) {
			final ExpectedPartition expected = entry.getValue();
			final EntityIndex object = objectsByKey.remove(entry.getKey());
			final int primaryKey = matchPartition(
				expected, rowsByKey.remove(entry.getKey()), object, prefix, rowsToCheck, findings
			);
			if (primaryKey != NO_PARTITION) {
				identities.put(PartitionIdentityLedger.identityKeyOf(scope, expected.key()), primaryKey);
			}
			if (object != null) {
				inspectObject(referenceSchema, scope, object, expected, prefix, findings);
			}
		}
		// ... and nothing else is a partition
		for (final List<RegistryRow> rows : rowsByKey.values()) {
			for (final RegistryRow row : rows) {
				findings.add(prefix + row + " is a partition no body produces");
			}
		}
		for (final EntityIndex object : objectsByKey.values()) {
			findings.add(
				prefix + "the index object " + object.getPrimaryKey() + " of " + object.getIndexKey().discriminator() +
					" with the owners " + Arrays.toString(object.getAllPrimaryKeys().getArray()) + " is a partition " +
					"no body produces"
			);
		}

		if (registry != null) {
			inspectSortValues(referenceSchema, scope, registry, rowsToCheck, probedPartitions, prefix, findings);
			inspectChains(referenceSchema, scope, registry, rowsToCheck, prefix, findings);
		}
	}

	/**
	 * Returns the primary keys of the partitions the oracle reads the entries of besides those an owner row names:
	 * every primary key the collection may have handed out - up to the largest of the persisted index sequence, of the
	 * index objects of the reference and of the owner rows. The registry has no reading that lists the partitions an
	 * entry other than an owner row is kept under, so an entry left under a primary key without a row is looked for
	 * among these; one left under a primary key above all of them would go unseen.
	 *
	 * @param collection the collection of the partitions
	 * @param registry   the registry
	 * @param objects    the index objects of the reference in the scope
	 * @return the primary keys, ascending
	 */
	@Nonnull
	private static int[] probedPartitionsOf(
		@Nonnull EntityCollection collection,
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull List<EntityIndex> objects
	) {
		final int[] largest = {collection.getEntityCollectionHeader().lastEntityIndexPrimaryKey()};
		for (final EntityIndex object : objects) {
			largest[0] = Math.max(largest[0], object.getPrimaryKey());
		}
		VirtualReferencePartitionIndexEntries.forEachOwnerRow(
			registry, (partition, payload) -> largest[0] = Math.max(largest[0], partition)
		);
		return IntStream.rangeClosed(1, largest[0]).toArray();
	}

	/**
	 * Reads the owner rows of the registry by the key of their partition - the referenced entity they name and the
	 * representative tuple of the partition - and reports every representative tuple of a primary key without a row,
	 * looked for among the probed primary keys.
	 *
	 * @param registry         the registry
	 * @param probedPartitions the primary keys a representative tuple without a row is looked for among
	 * @param prefix           the prefix of every finding
	 * @param findings         receives the disagreements
	 * @return the rows by the encoding of their partition key (see {@link #encodingOf(int, Serializable[])})
	 */
	@Nonnull
	private static Map<String, List<RegistryRow>> rowsByKey(
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull int[] probedPartitions,
		@Nonnull String prefix,
		@Nonnull List<String> findings
	) {
		final Map<String, List<RegistryRow>> rows = new TreeMap<>();
		final Set<Integer> rowPrimaryKeys = new TreeSet<>();
		VirtualReferencePartitionIndexEntries.forEachOwnerRow(
			registry,
			(partition, payload) -> {
				rowPrimaryKeys.add(partition);
				final int tupleId = registry.getRepresentativeTupleId(partition);
				final Serializable[] tuple = tupleId == VirtualReferencePartitionIndex.NO_TUPLE_ID ?
					new Serializable[0] : registry.getTuple(tupleId);
				final RegistryRow row = new RegistryRow(
					partition, NumberUtils.unpackHigh(payload), NumberUtils.unpackLow(payload), tuple
				);
				rows.computeIfAbsent(encodingOf(row.target(), tuple), __ -> new ArrayList<>(1)).add(row);
			}
		);
		VirtualReferencePartitionIndexEntries.forEachRepresentativeTuple(
			registry,
			(partition, payload) -> {
				if (!rowPrimaryKeys.contains(partition)) {
					findings.add(
						prefix + "the registry holds the tuple " + Arrays.toString(registry.getTuple((int) payload)) +
							" of partition " + partition + ", which has no row"
					);
				}
			},
			probedPartitions
		);
		return rows;
	}

	/**
	 * Matches a partition the bodies produce to its row and its index object and reports every way they disagree with
	 * the representation of the partition - a row without an index object while it has a single owner, an index object
	 * without a row once it has more. A single-owner partition whose row the match finds is put among the rows whose
	 * values are checked.
	 *
	 * @param expected    the partition as the bodies describe it
	 * @param rows        the rows of the partition's key, `null` when it has none
	 * @param object      the index object of the partition's key, `null` when it has none
	 * @param prefix      the prefix of every finding
	 * @param rowsToCheck receives the owner of a matched single-owner row by the row's primary key
	 * @param findings    receives the disagreements
	 * @return the primary key of the partition - of its row when it has a single owner and a row, of its object
	 * otherwise - or {@link #NO_PARTITION} when it has neither
	 */
	private static int matchPartition(
		@Nonnull ExpectedPartition expected,
		@Nullable List<RegistryRow> rows,
		@Nullable EntityIndex object,
		@Nonnull String prefix,
		@Nonnull SortedMap<Integer, OwnerRow> rowsToCheck,
		@Nonnull List<String> findings
	) {
		final RepresentativeReferenceKey key = expected.key();
		final int[] owners = expected.ownerPrimaryKeys();
		final RegistryRow row = rows == null || rows.isEmpty() ? null : rows.get(0);
		if (rows != null && rows.size() > 1) {
			findings.add(prefix + "the partition " + key + " has " + rows.size() + " rows: " + rows);
		}
		if (owners.length == 1) {
			if (row == null) {
				findings.add(prefix + "the single owner " + owners[0] + " of " + key + " has no row");
			} else {
				if (row.owner() != owners[0]) {
					findings.add(
						prefix + "the " + row + " of " + key + " names another owner than its single owner " + owners[0]
					);
				}
				rowsToCheck.put(row.partitionPrimaryKey(), expected.owners().get(0));
			}
			if (object != null) {
				findings.add(
					prefix + "the single-owner partition " + key + " has the index object " + object.getPrimaryKey()
				);
			}
			if (row != null) {
				return row.partitionPrimaryKey();
			}
		} else {
			if (row != null) {
				findings.add(
					prefix + "the partition " + key + " with the owners " + Arrays.toString(owners) + " has the " + row
				);
			}
			if (object == null) {
				findings.add(
					prefix + "the partition " + key + " with the owners " + Arrays.toString(owners) + " has no " +
						"index object"
				);
			}
		}
		return object == null ? (row == null ? NO_PARTITION : row.partitionPrimaryKey()) : object.getPrimaryKey();
	}

	/**
	 * Groups the rows of a reference by the partition they fall into.
	 *
	 * @param referenceSchema the reference
	 * @param bodies          the bodies of the owners
	 * @return the partitions by the encoding of their key (see {@link #encodingOf(RepresentativeReferenceKey)})
	 */
	@Nonnull
	private static SortedMap<String, ExpectedPartition> groupRows(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull List<SealedEntity> bodies
	) {
		final SortedMap<String, ExpectedPartition> partitions = new TreeMap<>();
		for (final SealedEntity body : bodies) {
			for (final ReferenceContract reference : body.getReferences(referenceSchema.getName())) {
				final RepresentativeReferenceKey key = keyOf(referenceSchema, reference);
				partitions.computeIfAbsent(
					encodingOf(key), __ -> new ExpectedPartition(key, new ArrayList<>(2))
				).owners().add(new OwnerRow(body, reference));
			}
		}
		return partitions;
	}

	/**
	 * Encodes the key of a partition for matching: its referenced entity and representative attribute values.
	 *
	 * @param key the key of the partition
	 * @return the encoding
	 */
	@Nonnull
	private static String encodingOf(@Nonnull RepresentativeReferenceKey key) {
		return encodingOf(key.primaryKey(), key.representativeAttributeValues());
	}

	/**
	 * Encodes the key of a partition for matching: its referenced entity and representative attribute values.
	 *
	 * @param target the referenced entity
	 * @param tuple  the representative attribute values, empty for a reference without them
	 * @return the encoding
	 */
	@Nonnull
	private static String encodingOf(int target, @Nonnull Serializable[] tuple) {
		return target + "/" + CanonicalValue.encode(tuple);
	}

	/**
	 * Derives the key of the partition a row falls into: the referenced entity and, for a reference allowing
	 * duplicates, the values of its representative attributes in the order of their names, a missing one taking the
	 * attribute's default.
	 *
	 * @param referenceSchema the reference
	 * @param reference       the row
	 * @return the partition key
	 */
	@Nonnull
	private static RepresentativeReferenceKey keyOf(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceContract reference
	) {
		final ReferenceKey referenceKey = new ReferenceKey(
			referenceSchema.getName(), reference.getReferencedPrimaryKey()
		);
		if (!referenceSchema.getCardinality().allowsDuplicates()) {
			return new RepresentativeReferenceKey(referenceKey);
		}
		final List<String> names = referenceSchema.getAttributes()
			.values()
			.stream()
			.filter(AttributeSchemaContract::isRepresentative)
			.map(AttributeSchemaContract::getName)
			.sorted()
			.toList();
		final Serializable[] values = new Serializable[names.size()];
		for (int i = 0; i < values.length; i++) {
			final String name = names.get(i);
			values[i] = reference.getAttributeValue(name)
				.filter(AttributeValue::exists)
				.map(AttributeValue::value)
				.orElseGet(() -> referenceSchema.getAttribute(name).orElseThrow().getDefaultValue());
		}
		return new RepresentativeReferenceKey(referenceKey, values);
	}

	/**
	 * Checks the sort values of the registry: under every key it declares or the bodies call for, exactly the values
	 * the rows of the single owners hold, and none under a primary key without such a row.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @param registry         the registry
	 * @param rows             the single owners by the primary key of their row
	 * @param probedPartitions the primary keys a value without a row is looked for among
	 * @param prefix           the prefix of every finding
	 * @param findings         receives the disagreements
	 */
	private static void inspectSortValues(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull SortedMap<Integer, OwnerRow> rows,
		@Nonnull int[] probedPartitions,
		@Nonnull String prefix,
		@Nonnull List<String> findings
	) {
		final Map<AttributeIndexKey, Map<Integer, Serializable>> expectedValues = new TreeMap<>();
		final Map<AttributeIndexKey, Comparator<Serializable>> comparators = new TreeMap<>();
		for (final Map.Entry<Integer, OwnerRow> entry : rows.entrySet()) {
			collectSortValues(referenceSchema, scope, entry.getKey(), entry.getValue(), expectedValues, comparators);
		}
		final Set<AttributeIndexKey> declared = registry.getDeclaredSortKeys();
		final Set<AttributeIndexKey> keys = new TreeSet<>(declared);
		keys.addAll(expectedValues.keySet());
		for (final AttributeIndexKey key : keys) {
			compareSortValues(
				prefix + "sort key " + key, " of partition ", SortIndexWalk.ofRegistry(registry, key, probedPartitions),
				expectedValues.getOrDefault(key, Map.of()), comparators.get(key), "the registry holds ",
				"the body of its owner holds ", findings
			);
		}
	}

	/**
	 * Compares the values a sort index holds with those the bodies call for, record by record, and - when every record
	 * holds the value the bodies call for - the order it keeps them in with the order the comparator of the key puts
	 * those values in (see {@link #compareSortOrder}).
	 *
	 * @param keySubject     names the sort index in a finding
	 * @param recordSubject  follows the key subject and precedes the record in a finding
	 * @param walk           the content of the sort index, `null` when there is none
	 * @param expectedValues the values the bodies call for, by record
	 * @param comparator     orders the values the bodies call for as a sort index of the key does, `null` when the
	 *                       bodies call for no value of the key
	 * @param actualPrefix   precedes the value the index holds in a finding
	 * @param expectedPrefix precedes the value the bodies call for in a finding
	 * @param findings       receives the disagreements
	 */
	private static void compareSortValues(
		@Nonnull String keySubject,
		@Nonnull String recordSubject,
		@Nullable SortIndexWalk walk,
		@Nonnull Map<Integer, Serializable> expectedValues,
		@Nullable Comparator<Serializable> comparator,
		@Nonnull String actualPrefix,
		@Nonnull String expectedPrefix,
		@Nonnull List<String> findings
	) {
		final String subject = keySubject + recordSubject;
		final Map<Integer, Serializable> actual = new TreeMap<>();
		if (walk != null) {
			for (int i = 0; i < walk.records().length; i++) {
				actual.put(walk.records()[i], walk.values()[i]);
			}
		}
		final Set<Integer> records = new TreeSet<>(actual.keySet());
		records.addAll(expectedValues.keySet());
		boolean valuesAgree = true;
		for (final Integer record : records) {
			final Serializable expectedValue = expectedValues.get(record);
			final Serializable actualValue = actual.get(record);
			final String expectedEncoding = expectedValue == null ?
				"absent" : CanonicalValue.encodeIndexed(expectedValue);
			final String actualEncoding = actualValue == null ?
				"absent" : CanonicalValue.encodeIndexed(actualValue);
			if (!expectedEncoding.equals(actualEncoding)) {
				valuesAgree = false;
				findings.add(
					subject + record + ": " + actualPrefix + actualEncoding + ", " + expectedPrefix + expectedEncoding
				);
			}
		}
		// a wrong, missing or surplus value is reported above and would only shift the order - the order is compared
		// once every record holds the value the bodies call for
		if (walk != null && comparator != null && valuesAgree) {
			compareSortOrder(keySubject, walk, expectedValues, comparator, findings);
		}
	}

	/**
	 * Compares the order a sort index keeps its records in with the order the values the bodies call for put them in:
	 * the records sorted by those values under the comparator of the key, the records whose values it finds equal
	 * forming one block. A sort index that holds the right value of every record still orders them wrongly when it
	 * compares by another comparator - another collation, direction or placement of missing elements - or splits one
	 * value into several blocks.
	 *
	 * @param keySubject     names the sort index in a finding
	 * @param walk           the content of the sort index, holding exactly the values the bodies call for
	 * @param expectedValues the values the bodies call for, by record
	 * @param comparator     orders the values as a sort index of the key does
	 * @param findings       receives the disagreement
	 */
	private static void compareSortOrder(
		@Nonnull String keySubject,
		@Nonnull SortIndexWalk walk,
		@Nonnull Map<Integer, Serializable> expectedValues,
		@Nonnull Comparator<Serializable> comparator,
		@Nonnull List<String> findings
	) {
		final List<Map.Entry<Integer, Serializable>> ordered = new ArrayList<>(expectedValues.entrySet());
		ordered.sort(
			(first, second) -> {
				final int byValue = comparator.compare(first.getValue(), second.getValue());
				return byValue == 0 ? Integer.compare(first.getKey(), second.getKey()) : byValue;
			}
		);
		final List<List<Integer>> expectedBlocks = new ArrayList<>(ordered.size());
		for (int i = 0; i < ordered.size(); i++) {
			if (i == 0 || comparator.compare(ordered.get(i - 1).getValue(), ordered.get(i).getValue()) != 0) {
				expectedBlocks.add(new ArrayList<>(2));
			}
			expectedBlocks.get(expectedBlocks.size() - 1).add(ordered.get(i).getKey());
		}
		final List<List<Integer>> actualBlocks = new ArrayList<>(walk.blocks().size());
		for (final SortIndexWalk.Block block : walk.blocks()) {
			final List<Integer> recordsOfBlock = new ArrayList<>(block.length());
			for (int i = block.start(); i < block.start() + block.length(); i++) {
				recordsOfBlock.add(walk.records()[i]);
			}
			recordsOfBlock.sort(Comparator.naturalOrder());
			actualBlocks.add(recordsOfBlock);
		}
		if (!expectedBlocks.equals(actualBlocks)) {
			findings.add(
				keySubject + " orders the records in the blocks " + actualBlocks + ", the values of their bodies in " +
					"the blocks " + expectedBlocks
			);
		}
	}

	/**
	 * Adds the sort values the row of an owner calls for: its sortable attributes and compounds, and the comparator a
	 * sort index of each of their keys orders the values by.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @param record          the record the values are kept under - the primary key of the partition in the
	 *                        registry, the primary key of the owner in an index object
	 * @param row             the row of the owner
	 * @param expectedValues  receives the values by key and record, in the space the index stores them in
	 * @param comparators     receives the comparator of every key a value is received for
	 */
	private static void collectSortValues(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		int record,
		@Nonnull OwnerRow row,
		@Nonnull Map<AttributeIndexKey, Map<Integer, Serializable>> expectedValues,
		@Nonnull Map<AttributeIndexKey, Comparator<Serializable>> comparators
	) {
		final String referenceName = referenceSchema.getName();
		for (final AttributeValue attributeValue : row.reference().getAttributeValues()) {
			final AttributeSchemaContract attribute = referenceSchema
				.getAttribute(attributeValue.key().attributeName())
				.orElse(null);
			if (
				attribute == null || !attributeValue.exists() || attributeValue.value() == null ||
					!attribute.isSortableInScope(scope) ||
					ChainableType.class.isAssignableFrom(attribute.getPlainType())
			) {
				continue;
			}
			// a sort index of a plain attribute orders ascending with missing values last - the query decides the rest
			final ComparatorSource comparatorSource = new ComparatorSource(
				attribute.getPlainType(), OrderDirection.ASC, OrderBehaviour.NULLS_LAST
			);
			final UnaryOperator<Serializable> normalizer = SortIndex.createNormalizerFor(
				comparatorSource, attribute.getIndexedDecimalPlaces()
			).orElseGet(UnaryOperator::identity);
			final AttributeIndexKey key = new AttributeIndexKey(
				referenceName, attribute.getName(), attributeValue.key().locale()
			);
			expectedValues
				.computeIfAbsent(key, __ -> new TreeMap<>())
				.put(record, normalizer.apply(indexed(attribute, attributeValue.value())));
			comparators.computeIfAbsent(
				key, __ -> comparatorOf(SortIndex.createComparatorFor(key.locale(), comparatorSource))
			);
		}
		for (final SortableAttributeCompoundSchemaContract compound :
			referenceSchema.getSortableAttributeCompounds().values()) {
			if (!compound.isIndexedInScope(scope)) {
				continue;
			}
			final List<AttributeElement> elements = compound.getAttributeElements();
			final boolean localized = elements.stream().anyMatch(
				it -> referenceSchema.getAttribute(it.attributeName()).map(AttributeSchemaContract::isLocalized)
					.orElse(false)
			);
			// a localized compound is indexed per attribute locale of the owner, a language-agnostic one once
			final List<Locale> locales = localized ?
				new ArrayList<>(attributeLocalesOf(row.entity())) : Arrays.asList((Locale) null);
			final ComparatorSource[] comparatorBase = comparatorBaseOf(referenceSchema, elements);
			for (final Locale locale : locales) {
				final Serializable[] values = compoundValues(
					referenceSchema, row.reference(), elements, comparatorBase, locale
				);
				if (Arrays.stream(values).allMatch(Objects::isNull)) {
					continue;
				}
				final AttributeIndexKey key = new AttributeIndexKey(referenceName, compound.getName(), locale);
				expectedValues
					.computeIfAbsent(key, __ -> new TreeMap<>())
					.put(record, new ComparableArray(values));
				comparators.computeIfAbsent(
					key, __ -> comparatorOf(SortIndex.createCombinedComparatorFor(locale, comparatorBase))
				);
			}
		}
	}

	/**
	 * Returns the attribute locales of an entity as its body keeps them: the locales of its own attributes and of the
	 * attributes of every reference it has.
	 *
	 * @param entity the body
	 * @return the attribute locales
	 */
	@Nonnull
	private static Set<Locale> attributeLocalesOf(@Nonnull SealedEntity entity) {
		final Set<Locale> locales = new HashSet<>(entity.getAttributeLocales());
		for (final ReferenceContract reference : entity.getReferences()) {
			locales.addAll(reference.getAttributeLocales());
		}
		return locales;
	}

	/**
	 * Returns the comparator base of a sortable attribute compound: per element, the type of its attribute, its
	 * direction and its placement of a missing value.
	 *
	 * @param referenceSchema the reference
	 * @param elements        the elements of the compound
	 * @return the comparator base
	 */
	@Nonnull
	private static ComparatorSource[] comparatorBaseOf(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull List<AttributeElement> elements
	) {
		final ComparatorSource[] comparatorBase = new ComparatorSource[elements.size()];
		for (int i = 0; i < comparatorBase.length; i++) {
			final AttributeElement element = elements.get(i);
			comparatorBase[i] = new ComparatorSource(
				referenceSchema.getAttribute(element.attributeName()).orElseThrow().getPlainType(),
				element.direction(), element.behaviour()
			);
		}
		return comparatorBase;
	}

	/**
	 * Views a comparator of sort index values as one of any value - the values the oracle orders by it are of the type
	 * it compares.
	 *
	 * @param comparator the comparator a sort index factory created
	 * @return the same comparator
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	private static Comparator<Serializable> comparatorOf(@Nonnull Comparator comparator) {
		return (Comparator<Serializable>) comparator;
	}

	/**
	 * Builds the value of a sortable attribute compound from a row, in the space the index stores it in.
	 *
	 * @param referenceSchema the reference
	 * @param reference       the row
	 * @param elements        the elements of the compound
	 * @param comparatorBase  the comparator base of the compound (see {@link #comparatorBaseOf})
	 * @param locale          the locale of a localized compound, `null` for a language-agnostic one
	 * @return the values of the elements, `null` for a missing one
	 */
	@Nonnull
	private static Serializable[] compoundValues(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceContract reference,
		@Nonnull List<AttributeElement> elements,
		@Nonnull ComparatorSource[] comparatorBase,
		@Nullable Locale locale
	) {
		final Serializable[] values = new Serializable[elements.size()];
		for (int i = 0; i < values.length; i++) {
			final AttributeElement element = elements.get(i);
			final AttributeSchemaContract attribute = referenceSchema.getAttribute(element.attributeName())
				.orElseThrow();
			final Serializable value = (
				attribute.isLocalized() ?
					reference.getAttributeValue(element.attributeName(), locale) :
					reference.getAttributeValue(element.attributeName())
			)
				.filter(AttributeValue::exists)
				.map(AttributeValue::value)
				.orElse(null);
			values[i] = value == null ? null : indexed(attribute, value);
		}
		return (Serializable[]) SortIndex.createNormalizerFor(comparatorBase, 0).apply(values);
	}

	/**
	 * Takes a value through the conversion every indexed attribute value goes through before an index receives it.
	 *
	 * @param attribute the attribute
	 * @param value     the value as the body holds it
	 * @return the value an index receives
	 */
	@Nonnull
	private static Serializable indexed(@Nonnull AttributeSchemaContract attribute, @Nonnull Serializable value) {
		return NumberUtils.normalizeForIndexing(value, attribute.getIndexedDecimalPlaces());
	}

	/**
	 * Checks the chain predecessors of the registry: for every chain key it declares or the bodies call for, the
	 * predecessor the row of each single owner declares, or none.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @param registry        the registry
	 * @param rows            the single owners by the primary key of their row
	 * @param prefix          the prefix of every finding
	 * @param findings        receives the disagreements
	 */
	private static void inspectChains(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull VirtualReferencePartitionIndex registry,
		@Nonnull SortedMap<Integer, OwnerRow> rows,
		@Nonnull String prefix,
		@Nonnull List<String> findings
	) {
		final Map<AttributeIndexKey, Map<Integer, Integer>> expectedPredecessors =
			collectPredecessors(referenceSchema, scope, rows);
		final Set<AttributeIndexKey> declared = registry.getDeclaredChainKeys();
		final Set<AttributeIndexKey> keys = new TreeSet<>(expectedPredecessors.keySet());
		for (final AttributeIndexKey key : declared) {
			// a chain the oracle leaves out, see the class documentation
			final boolean leftOut = referenceSchema.getAttribute(key.attributeName())
				.map(it -> !isComparedChain(it, scope))
				.orElse(false);
			if (!leftOut) {
				keys.add(key);
			}
		}
		for (final AttributeIndexKey key : keys) {
			final Map<Integer, Integer> expectedOfKey = expectedPredecessors.getOrDefault(key, Map.of());
			for (final Integer partition : rows.keySet()) {
				final OptionalInt actual = declared.contains(key) ?
					registry.getChainPredecessor(key, partition) : OptionalInt.empty();
				final Integer expectedPredecessor = expectedOfKey.get(partition);
				final OptionalInt expectedOptional = expectedPredecessor == null ?
					OptionalInt.empty() : OptionalInt.of(expectedPredecessor);
				if (!expectedOptional.equals(actual)) {
					findings.add(
						prefix + "chain key " + key + " of partition " + partition + ": the registry holds " + actual +
							", the body of its owner declares " + expectedOptional
					);
				}
			}
		}
	}

	/**
	 * Collects the predecessors the rows of owners declare for every compared chain of the reference.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @param rows            the rows of the owners by the record their predecessors are kept under
	 * @return the predecessors by chain key and record - every compared chain key present, possibly empty
	 */
	@Nonnull
	private static Map<AttributeIndexKey, Map<Integer, Integer>> collectPredecessors(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull Map<Integer, OwnerRow> rows
	) {
		final String referenceName = referenceSchema.getName();
		final Map<AttributeIndexKey, Map<Integer, Integer>> predecessors = new TreeMap<>();
		for (final AttributeSchemaContract attribute : referenceSchema.getAttributes().values()) {
			if (isComparedChain(attribute, scope)) {
				predecessors.put(new AttributeIndexKey(referenceName, attribute.getName(), null), new TreeMap<>());
			}
		}
		for (final Map.Entry<Integer, OwnerRow> entry : rows.entrySet()) {
			for (final Map.Entry<AttributeIndexKey, Map<Integer, Integer>> chain : predecessors.entrySet()) {
				entry.getValue().reference().getAttributeValue(chain.getKey().attributeName())
					.filter(AttributeValue::exists)
					.map(AttributeValue::value)
					.ifPresent(value -> chain.getValue().put(entry.getKey(), ((ChainableType) value).predecessorPk()));
			}
		}
		return predecessors;
	}

	/**
	 * Checks an index object of a partition the bodies produce: its owners, and per owner the sort values and chain
	 * predecessors of the reference - under every key the object holds or the bodies call for.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @param object          the index object
	 * @param expected        the partition as the bodies describe it
	 * @param prefix          the prefix of every finding
	 * @param findings        receives the disagreements
	 */
	private static void inspectObject(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull EntityIndex object,
		@Nonnull ExpectedPartition expected,
		@Nonnull String prefix,
		@Nonnull List<String> findings
	) {
		final String referenceName = referenceSchema.getName();
		final String objectPrefix = prefix + "index object " + object.getPrimaryKey() + " of " + expected.key() + ", ";
		final int[] owners = expected.ownerPrimaryKeys();
		final int[] actualOwners = object.getAllPrimaryKeys().getArray();
		if (!Arrays.equals(owners, actualOwners)) {
			findings.add(
				objectPrefix + "the object holds the owners " + Arrays.toString(actualOwners) + ", the bodies " +
					Arrays.toString(owners)
			);
		}
		final Map<Integer, OwnerRow> ownerRows = new TreeMap<>();
		for (final OwnerRow owner : expected.owners()) {
			ownerRows.put(primaryKeyOf(owner), owner);
		}

		// the sort values, kept under the owner's primary key
		final Map<AttributeIndexKey, Map<Integer, Serializable>> expectedValues = new TreeMap<>();
		final Map<AttributeIndexKey, Comparator<Serializable>> comparators = new TreeMap<>();
		for (final Map.Entry<Integer, OwnerRow> owner : ownerRows.entrySet()) {
			collectSortValues(referenceSchema, scope, owner.getKey(), owner.getValue(), expectedValues, comparators);
		}
		final Set<AttributeIndexKey> sortKeys = new TreeSet<>(expectedValues.keySet());
		object.forEachAttributeIndexKey(
			AttributeIndexType.SORT,
			key -> {
				if (referenceName.equals(key.referenceName())) {
					sortKeys.add(key);
				}
			}
		);
		for (final AttributeIndexKey key : sortKeys) {
			final SortIndex sortIndex = object.getSortIndex(key);
			compareSortValues(
				objectPrefix + "sort key " + key, " of owner ", sortIndex == null ? null : SortIndexWalk.of(sortIndex),
				expectedValues.getOrDefault(key, Map.of()), comparators.get(key), "the object holds ",
				"the body of the owner holds ", findings
			);
		}

		// the chain predecessors, the chain elements being the owners
		final Map<AttributeIndexKey, Map<Integer, Integer>> expectedPredecessors =
			collectPredecessors(referenceSchema, scope, ownerRows);
		final Set<AttributeIndexKey> chainKeys = new TreeSet<>(expectedPredecessors.keySet());
		object.forEachAttributeIndexKey(
			AttributeIndexType.CHAIN,
			key -> {
				// a chain the oracle leaves out, see the class documentation
				final boolean compared = referenceSchema.getAttribute(key.attributeName())
					.map(it -> isComparedChain(it, scope))
					.orElse(true);
				if (referenceName.equals(key.referenceName()) && compared) {
					chainKeys.add(key);
				}
			}
		);
		for (final AttributeIndexKey key : chainKeys) {
			final ChainIndex chainIndex = object.getChainIndex(key);
			final Map<Integer, Integer> expectedOfKey = expectedPredecessors.getOrDefault(key, Map.of());
			final Set<Integer> elements = new TreeSet<>(expectedOfKey.keySet());
			if (chainIndex != null) {
				for (final int element : chainIndex.getUnorderedLookup().getArray()) {
					elements.add(element);
				}
			}
			for (final Integer element : elements) {
				final OptionalInt actual = chainIndex == null ?
					OptionalInt.empty() : chainIndex.getPredecessorPrimaryKey(element);
				final Integer expectedPredecessor = expectedOfKey.get(element);
				final OptionalInt expectedOptional = expectedPredecessor == null ?
					OptionalInt.empty() : OptionalInt.of(expectedPredecessor);
				if (!expectedOptional.equals(actual)) {
					findings.add(
						objectPrefix + "chain key " + key + " of owner " + element + ": the object holds " + actual +
							", the body of the owner declares " + expectedOptional
					);
				}
			}
		}
	}

	/**
	 * Tells whether the oracle compares the chain of an attribute - a chainable attribute sortable in the scope, but
	 * not a `ReferencedEntityPredecessor`, see the class documentation.
	 *
	 * @param attribute the attribute of the reference
	 * @param scope     the scope
	 * @return true for a compared chain
	 */
	private static boolean isComparedChain(@Nonnull AttributeSchemaContract attribute, @Nonnull Scope scope) {
		return attribute.isSortableInScope(scope) &&
			ChainableType.class.isAssignableFrom(attribute.getPlainType()) &&
			!ReferencedEntityPredecessor.class.isAssignableFrom(attribute.getPlainType());
	}

	/**
	 * Returns the primary key of the owner of a row.
	 *
	 * @param row the row
	 * @return the owner's primary key
	 */
	private static int primaryKeyOf(@Nonnull OwnerRow row) {
		return row.entity().getPrimaryKeyOrThrowException();
	}
}
