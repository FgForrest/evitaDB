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

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The primary keys the reference partitions of a catalog had at the checkpoints of one history, recorded on the test
 * side - so that the identity of a partition is checked against what was observed before, not against the lookup the
 * collection itself answers with.
 *
 * {@link VirtualReferencePartitionRawRowOracle} derives every partition from the entity bodies and resolves it to its
 * registry row or its index object. A transition that hands a partition another primary key and updates the row, the
 * object, the advertisement and the membership consistently leaves nothing that oracle could see: every structure
 * agrees with every other one. The ledger sees it, because it remembers. At every checkpoint it is given the primary
 * key of every partition the bodies produce, and it reports:
 *
 * - a partition whose key the bodies produced at the previous checkpoint of its collection too, and whose primary key
 *   differs from the one it had there - unless the history declared the key emptied in between (see
 *   {@link #declareEmptied(String, Scope, RepresentativeReferenceKey)}) or replayed by a crash recovery (see
 *   {@link #declareReplayed(String, Scope, RepresentativeReferenceKey)});
 * - a primary key observed under another key than the one it was first observed under, at any earlier checkpoint -
 *   unless that key was declared replayed (see below).
 *
 * A key the bodies stop producing is forgotten: a partition emptied between two checkpoints may come back under any
 * primary key the sequence hands out. A key emptied and entered again between two checkpoints cannot be told apart
 * from a reallocation by any observer - the history knows it, and declares it. The transition that legitimately does
 * it is the last owner leaving a partition and an owner entering it in a later root mutation before the next
 * checkpoint, which mints a new primary key. Within one root mutation the reservation of the vacated partition keeps
 * the primary key, so nothing needs declaring there; a declaration allows the change, it does not demand it.
 *
 * A crash recovery is the other event that changes the primary key of a partition legitimately. The primary key a
 * partition created after the last checkpoint has is not durable: the recovery replays the mutations that created it
 * from the write-ahead log, which mints its primary key anew from the sequence the checkpoint persisted. A partition
 * the checkpoint published keeps its primary key. Minted anew, one replayed partition may receive the primary key
 * another replayed partition had before the crash - so the primary key a key declared replayed held is released when
 * the next checkpoint consumes the declaration, and may then be observed under another key. The primary key of a key
 * declared emptied is not released: the sequence never hands it out again, so seeing it under another key is a reuse.
 *
 * The ledger must be given states whose primary keys are final. An open transaction of a live catalog is not one: the
 * commit executes the transaction once more on the trunk, which mints the primary keys of the partitions the
 * transaction created anew, so what the transaction showed of them is provisional.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class PartitionIdentityLedger {
	/**
	 * The primary key of every partition key observed at the last checkpoint, per entity type.
	 */
	private final Map<String, SortedMap<String, Integer>> primaryKeysByKey = CollectionUtils.createHashMap(8);
	/**
	 * The key every primary key was first observed under, per entity type - never forgotten.
	 */
	private final Map<String, Map<Integer, String>> keysByPrimaryKey = CollectionUtils.createHashMap(8);
	/**
	 * The partition keys declared emptied or replayed since the last checkpoint, per entity type.
	 */
	private final Map<String, Set<String>> declaredChanges = CollectionUtils.createHashMap(8);
	/**
	 * The partition keys declared replayed since the last checkpoint, per entity type - their primary keys are
	 * released for any key at the next checkpoint.
	 */
	private final Map<String, Set<String>> declaredReplays = CollectionUtils.createHashMap(8);
	/**
	 * How many partitions were observed at a checkpoint whose key had been observed at the previous one - the
	 * comparisons the ledger has actually made.
	 */
	private int comparedObservations;

	/**
	 * Returns the key the ledger identifies a partition by in one collection: the scope, the reference, the
	 * referenced entity and the representative attribute values by their canonical encoding.
	 *
	 * @param scope the scope of the partition
	 * @param key   the representative reference key of the partition
	 * @return the identity key
	 */
	@Nonnull
	static String identityKeyOf(@Nonnull Scope scope, @Nonnull RepresentativeReferenceKey key) {
		return scope + "/" + key.referenceName() + "/" + key.primaryKey() + "/" +
			CanonicalValue.encode(key.representativeAttributeValues());
	}

	/**
	 * Declares that the history emptied the partition of a key and let an owner enter it again since the last
	 * checkpoint - the next checkpoint accepts any primary key for it.
	 *
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope of the partition
	 * @param key        the representative reference key of the partition
	 */
	void declareEmptied(@Nonnull String entityType, @Nonnull Scope scope, @Nonnull RepresentativeReferenceKey key) {
		allowAnotherPrimaryKey(entityType, scope, key);
	}

	/**
	 * Declares that the history created the partition of a key after the last checkpoint of the catalog and a crash
	 * recovery replays its creation from the write-ahead log before the next checkpoint of the ledger - the next
	 * checkpoint accepts any primary key for it, and releases the primary key it had for another key replayed with it.
	 *
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope of the partition
	 * @param key        the representative reference key of the partition
	 */
	void declareReplayed(@Nonnull String entityType, @Nonnull Scope scope, @Nonnull RepresentativeReferenceKey key) {
		allowAnotherPrimaryKey(entityType, scope, key);
		this.declaredReplays.computeIfAbsent(entityType, __ -> new HashSet<>(4)).add(identityKeyOf(scope, key));
	}

	/**
	 * Lets the next checkpoint of the collection accept any primary key for the partition of a key.
	 *
	 * @param entityType the entity type owning the reference
	 * @param scope      the scope of the partition
	 * @param key        the representative reference key of the partition
	 */
	private void allowAnotherPrimaryKey(
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		this.declaredChanges.computeIfAbsent(entityType, __ -> new HashSet<>(4)).add(identityKeyOf(scope, key));
	}

	/**
	 * Returns how many partitions were compared with their previous observation so far, so a test can show the
	 * ledger was not satisfied vacuously.
	 *
	 * @return the count of compared observations
	 */
	int getComparedObservations() {
		return this.comparedObservations;
	}

	/**
	 * Compares the primary keys of the partitions of one collection at a checkpoint with what was observed before,
	 * reports every change of identity and records the observation as the last one of the collection. Consumes the
	 * declarations of emptied and replayed keys of the collection; the primary key a replayed key had at the previous
	 * checkpoint is released before the observation is compared, so another replayed key may hold it now.
	 *
	 * @param entityType the entity type of the collection
	 * @param observed   the primary key of every partition the bodies produce, by its identity key (see
	 *                   {@link #identityKeyOf(Scope, RepresentativeReferenceKey)})
	 * @param findings   receives the changes of identity
	 */
	void check(
		@Nonnull String entityType,
		@Nonnull SortedMap<String, Integer> observed,
		@Nonnull List<String> findings
	) {
		final SortedMap<String, Integer> previous = this.primaryKeysByKey.get(entityType);
		final Map<Integer, String> firstKeys = this.keysByPrimaryKey.computeIfAbsent(
			entityType, __ -> CollectionUtils.createHashMap(observed.size())
		);
		final Set<String> declared = this.declaredChanges.getOrDefault(entityType, Set.of());
		if (previous != null) {
			// a crash recovery mints every replayed partition anew, so one may take the primary key another had
			for (final String replayed : this.declaredReplays.getOrDefault(entityType, Set.of())) {
				final Integer previousPrimaryKey = previous.get(replayed);
				if (previousPrimaryKey != null) {
					firstKeys.remove(previousPrimaryKey, replayed);
				}
			}
		}
		for (final Entry<String, Integer> entry : observed.entrySet()) {
			final String key = entry.getKey();
			final int primaryKey = entry.getValue();
			final Integer previousPrimaryKey = previous == null ? null : previous.get(key);
			if (previousPrimaryKey != null) {
				this.comparedObservations++;
				if (previousPrimaryKey != primaryKey && !declared.contains(key)) {
					findings.add(
						"identity of `" + entityType + "` " + key + ": primary key " + previousPrimaryKey +
							" at the previous checkpoint, " + primaryKey + " now, and the history declared neither " +
							"an emptying nor a replay of the key in between"
					);
				}
			}
			final String firstKey = firstKeys.putIfAbsent(primaryKey, key);
			if (firstKey != null && !firstKey.equals(key)) {
				findings.add(
					"identity of `" + entityType + "` " + key + ": primary key " + primaryKey + " was observed as " +
						"the partition " + firstKey + " before"
				);
			}
		}
		this.primaryKeysByKey.put(entityType, new TreeMap<>(observed));
		this.declaredChanges.remove(entityType);
		this.declaredReplays.remove(entityType);
	}
}
