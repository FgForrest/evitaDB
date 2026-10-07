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

// TODO JNO - remove at the end of #1615 (the whole file)
package io.evitadb.core.catalog;

import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor.LocaleWithScope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

// TODO JNO - remove at the end of #1615
/**
 * The switch saying for which entity collections of one catalog the virtual reference partitions are maintained -
 * whether a write into a collection also keeps the registry of single-owner partitions on its referenced-type entity
 * indexes up to date. It is off for every collection unless a test turns it on, and nothing reads it but the executor
 * of entity index mutations.
 *
 * Three properties of the holder, all deliberate:
 *
 * - **Owned by the catalog, keyed by entity type name.** A collection is rebuilt far more often than its catalog is:
 *   every commit that dirties it, going live, a rename. Worse, a collection created inside a transaction is created
 *   twice - once in the transaction and once more when the commit replays the transaction's mutations onto the last
 *   finalized catalog - and the second instance has no predecessor it could inherit anything from. Keyed by name on
 *   the catalog, the switch reaches every one of those instances, including the replayed one, and a test may turn it
 *   on for a collection that does not exist yet.
 * - **Shared across catalog versions by reference.** Every constructor that rebuilds an existing catalog - a commit,
 *   going live, a catalog rename - hands the holder over, exactly as it hands over the catalog's usage counters. Only a
 *   brand-new catalog and one loaded from disk start with a fresh one, so the switch is **not persisted**: a reopened
 *   catalog maintains nothing until the switch is turned on again - unless the engine was told before it loaded the
 *   catalog (see {@link io.evitadb.core.Evita#maintainVirtualReferencePartitionsOnLoad(String, Set)}), in which case
 *   the fresh holder starts switched on and the write-ahead log replayed at load is maintained too.
 * - **Non-transactional.** Turning the switch on inside a transaction that later rolls back leaves it on. It is a test
 *   instrument, not catalog state, and a test sets it deliberately.
 *
 * A collection renamed or replaced by another one carries its switch to its new name (see
 * {@link #carryOver(String, String)}). A name is never turned off implicitly - a collection that is removed and
 * created again under the same name is maintained again.
 *
 * The holder also carries a second switch per entity type - whether the queries over the collection **read** its
 * registered single-owner partitions virtually, through the registry rather than through their reduced entity
 * indexes (see {@link #isReadVirtually(String)}). It is meaningful only for a maintained collection, so it can be on
 * only while the collection is maintained, and it does not touch the write path at all: a collection read virtually
 * is written exactly as a merely maintained one. A query captures it once, when it first resolves a partition of the
 * collection, so a query never mixes the two readings.
 *
 * The holder also carries an optional {@link TransitionProbe} a test installs to observe the transitions of
 * single-owner partitions and the outcome of every root entity mutation, and to inject failures at them. The probe is
 * consulted whenever it is installed, with the switch on or off, so that two engines - one maintaining, one not - can
 * be made to fail at the very same point of one mutation; without a probe nothing is reported and nothing is allocated
 * per mutation (see {@link MutationHandle}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class VirtualReferencePartitionMaintenance {

	// TODO JNO - remove at the end of #1615
	/**
	 * Names of the entity types whose collections maintain virtual reference partitions. Concurrent, because the
	 * holder is shared by every catalog version and read by every entity mutation, whichever thread runs it.
	 */
	private final Set<String> maintainedEntityTypes = ConcurrentHashMap.newKeySet();
	// TODO JNO - remove at the end of #1615
	/**
	 * Names of the entity types whose collections are read virtually by queries - always a subset of
	 * {@link #maintainedEntityTypes}. Concurrent for the same reason.
	 */
	private final Set<String> readVirtuallyEntityTypes = ConcurrentHashMap.newKeySet();
	// TODO JNO - remove at the end of #1615
	/**
	 * How many entity mutations have been executed with the switch on since this holder was created - a probe that
	 * lets a test tell whether the mutations replayed from the write-ahead log at load ran with the switch on, which no
	 * state of the switch read after the load can tell.
	 */
	private final AtomicLong maintainedMutationCount = new AtomicLong();
	// TODO JNO - remove at the end of #1615
	/**
	 * How many rows of registered single-owner partitions queries have answered virtually since this holder was
	 * created - see {@link #recordVirtualRowsRead(int)}.
	 */
	private final AtomicLong virtualRowsRead = new AtomicLong();
	// TODO JNO - remove at the end of #1615
	/**
	 * How many target families the by-key memos of queries have built since this holder was created - see
	 * {@link #recordPartitionKeyMemoWork(int, int)}.
	 */
	private final AtomicLong partitionKeyFamilyBuilds = new AtomicLong();
	// TODO JNO - remove at the end of #1615
	/**
	 * How many family members the by-key memos of queries have visited since this holder was created - see
	 * {@link #recordPartitionKeyMemoWork(int, int)}.
	 */
	private final AtomicLong partitionKeyVisitedEntries = new AtomicLong();
	// TODO JNO - remove at the end of #1615
	/**
	 * The probe a test installed, `null` when none is. Volatile, because a test installs it on its own thread while
	 * mutations - the trunk replay of a commit among them - run on others.
	 */
	@Nullable private volatile TransitionProbe probe;

	// TODO JNO - remove at the end of #1615
	/**
	 * Creates a holder with the switch off for every collection.
	 */
	public VirtualReferencePartitionMaintenance() {
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Creates a holder with the switch already on for the collections of the given entity types and the given probe
	 * installed - the holder of a catalog being loaded from disk, seeded before any of its collections is attached, so
	 * the probe observes the mutations the write-ahead log replays at load as well.
	 *
	 * @param maintainedEntityTypes the names of the entity types to maintain
	 * @param probe                 the probe to install, `null` for none
	 */
	public VirtualReferencePartitionMaintenance(
		@Nonnull Set<String> maintainedEntityTypes,
		@Nullable TransitionProbe probe
	) {
		this.maintainedEntityTypes.addAll(maintainedEntityTypes);
		this.probe = probe;
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns TRUE when the collection of the given entity type maintains virtual reference partitions.
	 *
	 * @param entityType the name of the entity type
	 * @return TRUE when the switch is on for the entity type
	 */
	public boolean isMaintained(@Nonnull String entityType) {
		// the empty check keeps the hash lookup off every entity mutation of a catalog no test has switched
		return !this.maintainedEntityTypes.isEmpty() && this.maintainedEntityTypes.contains(entityType);
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns the handle one entity mutation consults about virtual reference partitions: whether it maintains them
	 * and which probe, if any, it reports its transitions to. Asked once per entity mutation, by the code building the
	 * executor of its entity index mutations - and by nothing else, so that {@link #getMaintainedMutationCount()}
	 * counts mutations rather than reads.
	 *
	 * Without a probe installed the handle is one of two shared constants, so nothing is allocated per mutation.
	 *
	 * @param entityType   the name of the entity type the mutation writes into
	 * @param rootMutation the identity of the root entity mutation this mutation belongs to - its own, or the one
	 *                     whose implicit or reflected mutation it is
	 * @return the handle of the mutation
	 */
	@Nonnull
	public MutationHandle forMutation(@Nonnull String entityType, @Nonnull Object rootMutation) {
		final boolean maintained = isMaintained(entityType);
		if (maintained) {
			this.maintainedMutationCount.incrementAndGet();
		}
		final TransitionProbe theProbe = this.probe;
		if (theProbe == null) {
			return maintained ? MutationHandle.MAINTAINED : MutationHandle.OFF;
		}
		return new MutationHandle(maintained, entityType, theProbe, rootMutation);
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Reports to the installed probe, if any, that a root entity mutation finished - its changes committed or rolled
	 * back. Called once per root entity mutation, after the outcome is final.
	 *
	 * @param rootMutation the identity of the root entity mutation, the one its mutations' handles carry
	 * @param committed    TRUE when the root mutation committed, FALSE when it was rolled back
	 * @param trunkReplay  TRUE when the root mutation replays a committed transaction - onto the trunk after a commit,
	 *                     or from the write-ahead log at load - rather than running in a session
	 */
	public void rootMutationFinished(@Nonnull Object rootMutation, boolean committed, boolean trunkReplay) {
		final TransitionProbe theProbe = this.probe;
		if (theProbe != null) {
			theProbe.rootMutationFinished(
				committed ? TransitionStage.ROOT_COMMITTED : TransitionStage.ROOT_ROLLED_BACK,
				rootMutation,
				trunkReplay
			);
		}
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Installs the probe, replacing the previous one, or removes it.
	 *
	 * @param probe the probe to install, `null` to remove it
	 */
	public void setProbe(@Nullable TransitionProbe probe) {
		this.probe = probe;
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns how many entity mutations have been executed with the switch on since this holder was created. A catalog
	 * loaded from disk gets a fresh holder, so right after the load the count is the number of mutations the
	 * write-ahead log replay executed with the switch on.
	 *
	 * @return the number of entity mutations that maintained virtual reference partitions
	 */
	public long getMaintainedMutationCount() {
		return this.maintainedMutationCount.get();
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Records that a query answered rows of registered single-owner partitions virtually - from the registry rather than
	 * from their reduced entity indexes. The count is the non-vacuity probe of the read parity tests: a parity case
	 * whose read-on arm read no virtual row compared the object reading with itself.
	 *
	 * @param rowCount how many virtual rows the query answered at one site, positive
	 */
	public void recordVirtualRowsRead(int rowCount) {
		this.virtualRowsRead.addAndGet(rowCount);
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns how many rows of registered single-owner partitions queries have answered virtually since this holder was
	 * created - see {@link #recordVirtualRowsRead(int)}.
	 *
	 * @return the number of virtual rows read
	 */
	public long getVirtualRowsRead() {
		return this.virtualRowsRead.get();
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Records the work the by-key memo of a query did resolving one key - the operation-count probe of the chain
	 * tests, see {@link io.evitadb.index.PartitionKeyMemo}.
	 *
	 * @param familyBuilds   how many target families the resolution built, zero or more
	 * @param visitedEntries how many family members the resolution visited, zero or more
	 */
	public void recordPartitionKeyMemoWork(int familyBuilds, int visitedEntries) {
		if (familyBuilds > 0) {
			this.partitionKeyFamilyBuilds.addAndGet(familyBuilds);
		}
		if (visitedEntries > 0) {
			this.partitionKeyVisitedEntries.addAndGet(visitedEntries);
		}
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns how many target families the by-key memos of queries have built since this holder was created.
	 *
	 * @return the number of family builds
	 */
	public long getPartitionKeyFamilyBuilds() {
		return this.partitionKeyFamilyBuilds.get();
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns how many family members the by-key memos of queries have visited since this holder was created.
	 *
	 * @return the number of visited family members
	 */
	public long getPartitionKeyVisitedEntries() {
		return this.partitionKeyVisitedEntries.get();
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Turns the switch on or off for the collection of the given entity type. The collection does not have to exist
	 * yet. A collection that is read virtually must have that switch turned off first - its registry would stop
	 * following its partitions while queries still read it.
	 *
	 * @param entityType the name of the entity type
	 * @param maintained TRUE to maintain virtual reference partitions, FALSE to stop
	 * @throws GenericEvitaInternalError when the maintenance is turned off while the collection is read virtually
	 */
	public void setMaintained(@Nonnull String entityType, boolean maintained) {
		if (maintained) {
			this.maintainedEntityTypes.add(entityType);
		} else {
			if (this.readVirtuallyEntityTypes.contains(entityType)) {
				throw new GenericEvitaInternalError(
					"Collection `" + entityType + "` is read virtually - turn that off before its maintenance!"
				);
			}
			this.maintainedEntityTypes.remove(entityType);
		}
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns TRUE when the queries over the collection of the given entity type read its registered single-owner
	 * partitions virtually - through the registry of single-owner partitions instead of through their reduced entity
	 * indexes, which still exist and still serve every write. Only the query routes that have been migrated to the
	 * virtual reading consult it.
	 *
	 * @param entityType the name of the entity type
	 * @return TRUE when the read switch is on for the entity type
	 */
	public boolean isReadVirtually(@Nonnull String entityType) {
		// the empty check keeps the hash lookup off every query of a catalog no test has switched
		return !this.readVirtuallyEntityTypes.isEmpty() && this.readVirtuallyEntityTypes.contains(entityType);
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Turns the virtual reading of the collection of the given entity type on or off. The collection does not have to
	 * exist yet, but it must be maintained: a registry nobody keeps up to date cannot be read.
	 *
	 * @param entityType    the name of the entity type
	 * @param readVirtually TRUE to read the registered single-owner partitions virtually, FALSE to stop
	 * @throws GenericEvitaInternalError when the virtual reading is turned on for a collection that is not maintained
	 */
	public void setReadVirtually(@Nonnull String entityType, boolean readVirtually) {
		if (readVirtually) {
			if (!isMaintained(entityType)) {
				throw new GenericEvitaInternalError(
					"Collection `" + entityType + "` is not maintained - its virtual reference partitions cannot " +
						"be read virtually!"
				);
			}
			this.readVirtuallyEntityTypes.add(entityType);
		} else {
			this.readVirtuallyEntityTypes.remove(entityType);
		}
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Carries the switch of a collection that is being renamed, or that replaces another collection, to the name it
	 * takes - the maintenance and the virtual reading alike. The switch only ever moves towards *on* here: the source
	 * name keeps its own, because a commit replays the rename once more onto the last finalized catalog and must find
	 * the source still switched on to carry it again, and a target that was already on stays on.
	 *
	 * @param sourceEntityType the name the collection had
	 * @param targetEntityType the name the collection takes
	 */
	public void carryOver(@Nonnull String sourceEntityType, @Nonnull String targetEntityType) {
		if (isMaintained(sourceEntityType)) {
			this.maintainedEntityTypes.add(targetEntityType);
		}
		if (isReadVirtually(sourceEntityType)) {
			this.readVirtuallyEntityTypes.add(targetEntityType);
		}
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * The points of an entity mutation a {@link TransitionProbe} is told about.
	 */
	public enum TransitionStage {
		/**
		 * A partition of a shadowed reference gained its first owner (0 -> 1), and its row was registered when the
		 * switch is on. Reported right after the registration, before the owner's data is replicated into the
		 * partition.
		 */
		ENTERED,
		/**
		 * A single-owner partition of a shadowed reference gained a second owner (1 -> 2), and its row was dropped
		 * when the switch is on. Reported right after the drop, before the new owner's data is replicated into the
		 * partition.
		 */
		SHARED,
		/**
		 * A partition of a shadowed reference lost its last owner (1 -> 0), and its row was dropped when the switch
		 * is on. Reported after the owner's data was removed from the partition and the row dropped.
		 */
		VACATED,
		/**
		 * A partition of a shadowed reference that a mutation left with one owner of two (2 -> 1) still has that one
		 * owner when the mutation applies its changes, and its row was registered and filled with the values the
		 * partition holds for that owner when the switch is on and it had none. Reported at the end of the mutation
		 * that left the partition with one owner - and whether or not a row was registered, so that an engine
		 * maintaining nothing reports it at the very same points.
		 */
		DEMOTED,
		/**
		 * A sort value or a chain predecessor of a partition of a shadowed reference was written into the partition
		 * or removed from it, and the registry mirrored the change when the switch is on and the partition's row names
		 * the mutated entity as its owner. Reported right after the mirror - and whether or not anything was mirrored,
		 * so that an engine maintaining nothing reports it at the very same points.
		 */
		MIRRORED,
		/**
		 * A root entity mutation committed.
		 */
		ROOT_COMMITTED,
		/**
		 * A root entity mutation failed and its changes were rolled back.
		 */
		ROOT_ROLLED_BACK
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Observes the transitions of the partitions of shadowed references and the outcome of every root entity mutation.
	 * A transition is reported while its mutation is still running, so a probe that throws fails that mutation exactly
	 * at that point - which is how a test injects a failure.
	 */
	public interface TransitionProbe {

		/**
		 * Reports a transition of a partition of a shadowed reference.
		 *
		 * @param stage               {@link TransitionStage#ENTERED}, {@link TransitionStage#SHARED},
		 *                            {@link TransitionStage#VACATED}, {@link TransitionStage#DEMOTED} or
		 *                            {@link TransitionStage#MIRRORED}
		 * @param entityType          the entity type owning the reference
		 * @param referenceName       the reference
		 * @param scope               the scope of the partition
		 * @param partitionPrimaryKey the primary key of the partition
		 * @param rootMutation        the identity of the root entity mutation the transition belongs to
		 */
		void transitionPassed(
			@Nonnull TransitionStage stage,
			@Nonnull String entityType,
			@Nonnull String referenceName,
			@Nonnull Scope scope,
			int partitionPrimaryKey,
			@Nonnull Object rootMutation
		);

		/**
		 * Reports that a root entity mutation finished.
		 *
		 * @param stage        {@link TransitionStage#ROOT_COMMITTED} or {@link TransitionStage#ROOT_ROLLED_BACK}
		 * @param rootMutation the identity of the root entity mutation
		 * @param trunkReplay  TRUE when the root mutation replays a committed transaction rather than running in a
		 *                     session
		 */
		void rootMutationFinished(
			@Nonnull TransitionStage stage,
			@Nonnull Object rootMutation,
			boolean trunkReplay
		);

		/**
		 * Reports that a single-owner partition of a shadowed reference is gaining its second owner (1 -> 2) - the
		 * point at which a virtual partition would have to be materialized from its first owner's data - and hands
		 * over the means to rehearse that materialization. Reported before the partition's row is dropped and before
		 * the second owner's data is written into the partition, with the switch on or off. A probe that throws fails
		 * the mutation at this point.
		 *
		 * The default does nothing.
		 *
		 * @param rehearsal    the partition, its owners and the rehearsal of its materialization, valid only for
		 *                     the duration of the call
		 * @param rootMutation the identity of the root entity mutation the transition belongs to
		 */
		default void promotionPointPassed(@Nonnull PromotionRehearsal rehearsal, @Nonnull Object rootMutation) {
			// nothing to observe by default
		}
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * A single-owner partition of a shadowed reference at the moment it gains its second owner (1 -> 2), and the
	 * rehearsal of its materialization from its first owner: the first owner written into a partition that no
	 * collection knows, through an executor writing as the first owner and acquiring no index - what a virtual
	 * partition gaining its second owner would have to go through to become a real one. The live partition still holds
	 * the first owner's data and nothing of the second owner's but its membership, so the two can be compared.
	 *
	 * Valid only during {@link TransitionProbe#promotionPointPassed}, on the thread running the mutation - inside its
	 * transaction, if there is one.
	 */
	public interface PromotionRehearsal {

		/**
		 * Returns the entity type owning the reference.
		 *
		 * @return the entity type
		 */
		@Nonnull
		String getEntityType();

		/**
		 * Returns the live partition gaining its second owner.
		 *
		 * @return the partition
		 */
		@Nonnull
		ReferencePartition getPartition();

		/**
		 * Returns the primary key of the partition's first owner, the one the partition holds the data of.
		 *
		 * @return the first owner's primary key
		 */
		int getOwnerPrimaryKey();

		/**
		 * Returns the primary key of the owner just added to the partition, whose data is not written yet.
		 *
		 * @return the second owner's primary key
		 */
		int getJoiningOwnerPrimaryKey();

		/**
		 * Returns the locales the first owner gained in the running root mutation and the partition learns only when
		 * the first owner's mutation applies its changes - empty when the root mutation does not mutate the first
		 * owner.
		 *
		 * @return the first owner's pending added locales
		 */
		@Nonnull
		LocaleWithScope[] getOwnerAddedLocales();

		/**
		 * Returns the locales the first owner lost in the running root mutation and the partition learns only when
		 * the first owner's mutation applies its changes - empty when the root mutation does not mutate the first
		 * owner.
		 *
		 * @return the first owner's pending removed locales
		 */
		@Nonnull
		LocaleWithScope[] getOwnerRemovedLocales();

		/**
		 * Writes the first owner into the given partition, as the materialization of a virtual partition would - see
		 * `ReferenceIndexMutator#populatePartitionWithOwner`. The partition must be empty, of the live partition's
		 * key, and known to no collection; the caller owns it and releases its transactional memory.
		 *
		 * @param partition the index of the empty partition to write the first owner into
		 */
		void populate(@Nonnull EntityIndex partition);
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * What one entity mutation knows about virtual reference partitions: whether it maintains them, and the probe it
	 * reports its transitions to. Without a probe the handle is one of the shared constants {@link #OFF} and
	 * {@link #MAINTAINED}, so building it allocates nothing.
	 *
	 * @param maintained   TRUE when the mutation maintains the virtual reference partitions of its collection
	 * @param entityType   the entity type the mutation writes into, `null` in the shared constants
	 * @param probe        the probe to report to, `null` for none
	 * @param rootMutation the identity of the root entity mutation, `null` in the shared constants
	 */
	public record MutationHandle(
		boolean maintained,
		@Nullable String entityType,
		@Nullable TransitionProbe probe,
		@Nullable Object rootMutation
	) {
		/**
		 * The handle of a mutation that maintains nothing and reports to no probe.
		 */
		public static final MutationHandle OFF = new MutationHandle(false, null, null, null);
		/**
		 * The handle of a mutation that maintains the virtual reference partitions and reports to no probe.
		 */
		public static final MutationHandle MAINTAINED = new MutationHandle(true, null, null, null);

		/**
		 * Reports a transition to the probe, if there is one. A probe may throw, which fails the mutation at this
		 * point.
		 *
		 * @param stage               the transition
		 * @param referenceName       the reference
		 * @param scope               the scope of the partition
		 * @param partitionPrimaryKey the primary key of the partition
		 */
		public void transitionPassed(
			@Nonnull TransitionStage stage,
			@Nonnull String referenceName,
			@Nonnull Scope scope,
			int partitionPrimaryKey
		) {
			if (this.probe != null && this.entityType != null && this.rootMutation != null) {
				this.probe.transitionPassed(
					stage, this.entityType, referenceName, scope, partitionPrimaryKey, this.rootMutation
				);
			}
		}

		/**
		 * Reports a partition gaining its second owner to the probe, if there is one. The rehearsal is created only
		 * when there is a probe to hand it to. A probe may throw, which fails the mutation at this point.
		 *
		 * @param rehearsal creates the rehearsal of the partition's materialization
		 */
		public void promotionPointPassed(@Nonnull Supplier<PromotionRehearsal> rehearsal) {
			if (this.probe != null && this.rootMutation != null) {
				this.probe.promotionPointPassed(rehearsal.get(), this.rootMutation);
			}
		}
	}

}
