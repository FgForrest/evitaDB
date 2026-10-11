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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor.LocaleWithScope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * The switch concerns the write path only. Queries read the registered single-owner partitions of every collection
 * through the registry, whatever the holder says.
 *
 * The holder also carries an optional {@link TransitionProbe} a test installs to observe the transitions of
 * single-owner partitions and the outcome of every root entity mutation, and to inject failures at them. The probe is
 * consulted whenever it is installed, with the switch on or off, so that two engines - one maintaining, one not - can
 * be made to fail at the very same point of one mutation; without a probe nothing is reported and nothing is allocated
 * per mutation (see {@link MutationHandle}).
 *
 * # Forced mode
 *
 * When the JVM runs with the system property {@link #FORCE_PROPERTY} set to `true`, the holder of a catalog with
 * objects follows a forced default: every collection of the catalog is maintained from the moment the catalog exists.
 * The engine creates and loads every catalog virtual (see the next section), so only a holder a factory method was
 * asked to build for a catalog with objects follows it. A holder leaves the forced default the first time a test sets
 * its switch explicitly ({@link #setMaintained(String, boolean)}) and from then on holds exactly what the test sets,
 * starting from nothing - so the tests that drive the switch themselves see the holder they expect. Holders created
 * by the public constructors never follow the forced default.
 *
 * The registry is persisted, but the switch is not, so a registry read back from disk is complete only when every
 * mutation of the catalog's history was maintained. The forced mode knows that only for catalogs born in this JVM
 * under the forced default and never taken out of it - their identifiers are remembered - and a catalog loaded from
 * disk follows the forced default only when it is one of them and the engine was not told anything else about it
 * before the load (see {@link #forLoadedCatalog(UUID, boolean, Set, TransitionProbe)}). Any other catalog with
 * objects loaded from disk is **not eligible**: it is not maintained.
 *
 * With the property absent {@link #FORCED} is a constant `false` and every forced branch folds away.
 *
 * # Virtual catalogs
 *
 * Every catalog the engine creates or loads represents its single-owner partitions **virtually** for good: no storage
 * protocol this version reads represents them otherwise - the store migrates a catalog of an older protocol to the
 * current {@link PersistenceService#STORAGE_PROTOCOL_VERSION} and refuses any other. The holder of a virtual catalog
 * (see {@link #isVirtual()}) maintains every collection of the catalog, those created later included, and refuses to
 * be switched off for any of them: once the registry is the only representation of a single-owner partition, a
 * collection that stopped maintaining it would lose the partition. The holder of a catalog loaded from disk is built
 * before any of its collections is constructed and before the write-ahead log is replayed onto it, so the replayed
 * mutations - those creating a collection among them - are virtual as well. Everything above about the switch and the
 * forced default concerns the other catalogs only.
 *
 * A virtual catalog also **writes** its single-owner partitions virtually: a partition of a reference whose
 * partitions may be virtual is a registry row without an index object while it has one owner, and an index object
 * without a row once it has more (see {@link #isWrittenVirtually()}). A test may switch the virtual writing off for
 * a virtual catalog it compares with the index objects (see {@link #setWrittenVirtually(boolean)}); the catalog then
 * writes its partitions as index objects and keeps the registry next to them, as a catalog with objects maintaining
 * it does. In the forced mode every root entity mutation of a catalog writing virtually that commits checks the
 * partitions its transitions passed: none may be both a row and an index object, and none but one that only had values
 * written into it may be an index object of fewer than two owners (see
 * {@link #rootMutationFinished(Catalog, Object, boolean, boolean)}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class VirtualReferencePartitionMaintenance {
	// TODO JNO - remove at the end of #1615
	/**
	 * The system property turning the forced mode on - see the class documentation.
	 */
	public static final String FORCE_PROPERTY = "evitadb.test.forceVirtualReferencePartitions";
	// TODO JNO - remove at the end of #1615
	/**
	 * TRUE when the JVM runs in the forced mode - read once, so that a run without the property pays nothing.
	 */
	public static final boolean FORCED = Boolean.getBoolean(FORCE_PROPERTY);
	// TODO JNO - remove at the end of #1615
	/**
	 * Identifiers of the catalogs born in this JVM under the forced default whose holder never left it - the only
	 * catalogs whose persisted registry is known to be complete when they are loaded from disk again.
	 */
	private static final Set<UUID> FORCED_CATALOG_IDS = ConcurrentHashMap.newKeySet();

	// TODO JNO - remove at the end of #1615
	/**
	 * Names of the entity types whose collections maintain virtual reference partitions. Concurrent, because the
	 * holder is shared by every catalog version and read by every entity mutation, whichever thread runs it.
	 */
	private final Set<String> maintainedEntityTypes = ConcurrentHashMap.newKeySet();
	// TODO JNO - remove at the end of #1615
	/**
	 * How many entity mutations have been executed with the switch on since this holder was created - a probe that
	 * lets a test tell whether the mutations replayed from the write-ahead log at load ran with the switch on, which no
	 * state of the switch read after the load can tell.
	 */
	private final AtomicLong maintainedMutationCount = new AtomicLong();
	// TODO JNO - remove at the end of #1615
	/**
	 * How many times the storage-part executors of the catalog's mutations have fetched the references part of an
	 * entity other than their own from the storage since this holder was created - see
	 * {@link #recordForeignReferencesFetch()}.
	 */
	private final AtomicLong foreignReferencesFetches = new AtomicLong();
	// TODO JNO - remove at the end of #1615
	/**
	 * The probe a test installed, `null` when none is. Volatile, because a test installs it on its own thread while
	 * mutations - the trunk replay of a commit among them - run on others.
	 */
	@Nullable private volatile TransitionProbe probe;
	// TODO JNO - remove at the end of #1615
	/**
	 * The identifier of the catalog while the holder follows the forced default, `null` when it does not - always
	 * `null` outside the forced mode.
	 */
	@Nullable private volatile UUID forcedCatalogId;
	// TODO JNO - remove at the end of #1615
	/**
	 * TRUE when the catalog represents its single-owner partitions virtually - see the class documentation. Fixed for
	 * the life of the holder, as the representation is for the life of the catalog.
	 */
	private final boolean virtual;
	// TODO JNO - remove at the end of #1615
	/**
	 * TRUE when a test switched the virtual writing of the single-owner partitions of a virtual catalog off - see
	 * {@link #setWrittenVirtually(boolean)}. Volatile for the same reason as {@link #probe}.
	 */
	private volatile boolean writtenAsObjects;
	// TODO JNO - remove at the end of #1615
	/**
	 * The partitions the transitions of each running root entity mutation passed, keyed by the identity of the root
	 * mutation - collected in the forced mode for a virtual catalog only, `null` otherwise. See
	 * {@link #rootMutationFinished(Catalog, Object, boolean, boolean)}.
	 */
	@Nullable private final Map<Object, Set<TouchedPartition>> forcedTouchedPartitions;

	// TODO JNO - remove at the end of #1615
	/**
	 * Creates a holder with the switch off for every collection.
	 */
	public VirtualReferencePartitionMaintenance() {
		this.virtual = false;
		this.forcedTouchedPartitions = null;
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
		this(false, maintainedEntityTypes, probe);
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Creates a holder of the given representation, with the switch on for the collections of the given entity types
	 * and the given probe installed.
	 *
	 * @param virtual               TRUE for the holder of a catalog representing its single-owner partitions virtually
	 * @param maintainedEntityTypes the names of the entity types to maintain - meaningless for a virtual catalog
	 * @param probe                 the probe to install, `null` for none
	 */
	private VirtualReferencePartitionMaintenance(
		boolean virtual,
		@Nonnull Set<String> maintainedEntityTypes,
		@Nullable TransitionProbe probe
	) {
		this.virtual = virtual;
		this.maintainedEntityTypes.addAll(maintainedEntityTypes);
		this.probe = probe;
		this.forcedTouchedPartitions = FORCED && virtual ? new ConcurrentHashMap<>(16) : null;
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Creates the holder of a brand-new catalog. The holder of a virtual catalog maintains every collection for good.
	 * Any other holder is switched off for every collection, unless the JVM runs in the forced
	 * mode, in which case the holder follows the forced default and the catalog is remembered as one whose registry
	 * is maintained from its birth.
	 *
	 * @param catalogId the identifier of the new catalog
	 * @param virtual   TRUE when the catalog is created virtual, as the engine creates every catalog
	 * @return the holder
	 */
	@Nonnull
	public static VirtualReferencePartitionMaintenance forNewCatalog(@Nonnull UUID catalogId, boolean virtual) {
		if (virtual) {
			return new VirtualReferencePartitionMaintenance(true, Set.of(), null);
		}
		final VirtualReferencePartitionMaintenance holder = new VirtualReferencePartitionMaintenance();
		if (FORCED) {
			FORCED_CATALOG_IDS.add(catalogId);
			holder.forcedCatalogId = catalogId;
		}
		return holder;
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Creates the holder of a catalog loaded from disk. The holder of a virtual catalog - every catalog the engine
	 * loads is one - maintains every collection for good, whatever the engine was told about the entity types, and
	 * only installs the probe. Any other holder is seeded as the engine was told before the load. In the forced mode a
	 * catalog the engine was told nothing about - no entity types and no probe - follows the forced default when it was
	 * born in this JVM under the forced default and never left it; any other such catalog is not eligible: it maintains
	 * nothing.
	 *
	 * @param catalogId             the identifier of the loaded catalog
	 * @param virtual               TRUE when the catalog is virtual, as every catalog the engine loads is
	 * @param maintainedEntityTypes the entity types the engine was told to maintain from the load on
	 * @param probe                 the probe the engine was told to install, `null` for none
	 * @return the holder
	 */
	@Nonnull
	public static VirtualReferencePartitionMaintenance forLoadedCatalog(
		@Nonnull UUID catalogId,
		boolean virtual,
		@Nonnull Set<String> maintainedEntityTypes,
		@Nullable TransitionProbe probe
	) {
		if (virtual) {
			return new VirtualReferencePartitionMaintenance(true, Set.of(), probe);
		}
		final VirtualReferencePartitionMaintenance holder =
			new VirtualReferencePartitionMaintenance(maintainedEntityTypes, probe);
		if (FORCED && maintainedEntityTypes.isEmpty() && probe == null && FORCED_CATALOG_IDS.contains(catalogId)) {
			holder.forcedCatalogId = catalogId;
		}
		return holder;
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns TRUE when the collection of the given entity type maintains virtual reference partitions.
	 *
	 * @param entityType the name of the entity type
	 * @return TRUE when the switch is on for the entity type
	 */
	public boolean isMaintained(@Nonnull String entityType) {
		if (this.virtual || (FORCED && this.forcedCatalogId != null)) {
			return true;
		}
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
	 * Without a probe installed the handle is one of the shared constants, so nothing is allocated per mutation - except
	 * in the forced mode for a virtual catalog, whose handles collect the partitions their transitions pass for the
	 * check at the end of the root mutation (see {@link #rootMutationFinished(Catalog, Object, boolean, boolean)}).
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
		// only a virtual catalog maintains every collection, so the virtual writing implies the maintenance
		final boolean theWrittenVirtually = isWrittenVirtually();
		final TransitionProbe theProbe = this.probe;
		final Set<TouchedPartition> touchedPartitions = FORCED && this.forcedTouchedPartitions != null ?
			this.forcedTouchedPartitions.computeIfAbsent(rootMutation, it -> new HashSet<>(16)) : null;
		if (theProbe == null && touchedPartitions == null) {
			if (theWrittenVirtually) {
				return MutationHandle.WRITTEN_VIRTUALLY;
			}
			return maintained ? MutationHandle.MAINTAINED : MutationHandle.OFF;
		}
		return new MutationHandle(
			maintained, theWrittenVirtually, entityType, theProbe, rootMutation, touchedPartitions
		);
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns TRUE when the mutations of the catalog write the single-owner partitions of the references it represents
	 * virtually as registry rows rather than as reduced entity indexes - see {@link MutationHandle#writtenVirtually()}.
	 * TRUE for every virtual catalog unless a test switched the virtual writing off, never TRUE outside a virtual
	 * catalog.
	 *
	 * @return TRUE when the virtual writing is on
	 */
	public boolean isWrittenVirtually() {
		return this.virtual && !this.writtenAsObjects;
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Turns the virtual writing of the single-owner partitions of a virtual catalog off or back on. On by default, so
	 * a virtual catalog writes a single-owner partition as its registry row only. A test switches it off to keep the
	 * index objects of the single-owner partitions next to their rows - to compare what a virtual catalog answers from
	 * a row with the object the row stands for - and a catalog representing its partitions as objects can never have
	 * it on, as it has no other way to write them. Switched off, the catalog writes exactly as a catalog with objects
	 * maintaining its registry does; mutations written before the switch stay as they were written.
	 *
	 * @param writtenVirtually TRUE to write the single-owner partitions virtually, FALSE to write them as objects
	 * @throws GenericEvitaInternalError when the virtual writing is turned on in a catalog that is not virtual
	 */
	public void setWrittenVirtually(boolean writtenVirtually) {
		if (writtenVirtually && !this.virtual) {
			throw new GenericEvitaInternalError(
				"The catalog represents its single-owner reference partitions as objects - they cannot be written " +
					"virtually!"
			);
		}
		this.writtenAsObjects = !writtenVirtually;
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Reports to the installed probe, if any, that a root entity mutation finished - its changes committed or rolled
	 * back. Called once per root entity mutation, after the outcome is final.
	 *
	 * In the forced mode a committed root mutation of a catalog writing virtually is then checked: no partition its
	 * transitions passed may be both a registry row and an index object, and an index object of a partition its
	 * transitions passed must hold at least two owners - a partition that gained its only owner, was left with one of
	 * two or lost its last owner is its row only, or nothing. A partition that only had values written into it is
	 * exempt from the second rule: a single-owner index object left by a reference whose indexing changed from
	 * partitioning to filtering keeps its only owner until the owner leaves or the catalog is reindexed. The check
	 * visits only the partitions the root mutation touched, never the other partitions of the catalog.
	 *
	 * @param catalog      the catalog version the root mutation wrote into
	 * @param rootMutation the identity of the root entity mutation, the one its mutations' handles carry
	 * @param committed    TRUE when the root mutation committed, FALSE when it was rolled back
	 * @param trunkReplay  TRUE when the root mutation replays a committed transaction - onto the trunk after a commit,
	 *                     or from the write-ahead log at load - rather than running in a session
	 * @throws GenericEvitaInternalError in the forced mode, when a touched partition is represented by both a row and
	 *                                   an index object, or by an index object of fewer than two owners while
	 *                                   something more than values was written into it
	 */
	public void rootMutationFinished(
		@Nonnull Catalog catalog,
		@Nonnull Object rootMutation,
		boolean committed,
		boolean trunkReplay
	) {
		final TransitionProbe theProbe = this.probe;
		if (theProbe != null) {
			theProbe.rootMutationFinished(
				committed ? TransitionStage.ROOT_COMMITTED : TransitionStage.ROOT_ROLLED_BACK,
				rootMutation,
				trunkReplay
			);
		}
		if (FORCED && this.forcedTouchedPartitions != null) {
			final Set<TouchedPartition> touchedPartitions = this.forcedTouchedPartitions.remove(rootMutation);
			// only virtual catalogs collect, so the representation is checked unless a test switched it to objects
			if (committed && touchedPartitions != null && !this.writtenAsObjects) {
				for (final TouchedPartition touchedPartition : touchedPartitions) {
					assertRepresentedOnce(catalog, touchedPartition);
				}
			}
		}
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Asserts that a partition a committed root mutation touched is represented once - see
	 * {@link #rootMutationFinished(Catalog, Object, boolean, boolean)}.
	 *
	 * @param catalog   the catalog version the root mutation wrote into
	 * @param partition the touched partition
	 * @throws GenericEvitaInternalError when the partition is represented by both a row and an index object, or by an
	 *                                   index object of fewer than two owners while something more than values was
	 *                                   written into it
	 */
	private static void assertRepresentedOnce(@Nonnull Catalog catalog, @Nonnull TouchedPartition partition) {
		final EntityCollection collection = catalog.getCollectionForEntityInternal(partition.entityType())
			.orElseThrow(
				() -> new GenericEvitaInternalError(
					"Collection `" + partition.entityType() + "` touched by a committed root mutation does not exist!"
				)
			);
		final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, partition.scope(), partition.referenceName())
		);
		final boolean hasRow = typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex &&
			referencedTypeIndex.getVirtualReferencePartitionIndex()
				.getPartitionOwner(partition.partitionPrimaryKey())
				.isPresent();
		// the index object of the partition, never its row - the lenient resolution reads index objects only
		final ReferencePartition object = collection.resolvePartitionIfExists(partition.partitionPrimaryKey());
		final int objectOwners = object == null ? 0 : object.size();
		final boolean representedOnce = hasRow ?
			object == null : object == null || objectOwners >= 2 || partition.valuesWritten();
		if (!representedOnce) {
			throw new GenericEvitaInternalError(
				"Partition " + partition.partitionPrimaryKey() + " of reference `" + partition.referenceName() +
					"` of collection `" + partition.entityType() + "` in scope `" + partition.scope() + "` is " +
					(hasRow ? "a registry row" : "no registry row") +
					(object == null ? " without an index object" : " with an index object of " + objectOwners +
						" owner(s)") +
					" after its root mutation committed - a catalog writing its single-owner partitions virtually " +
					"keeps a single-owner partition as its row only, and a shared one as its index object only!"
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
	 * Records that a storage-part executor of a mutation of the catalog has fetched the references part of an entity
	 * other than its own from the storage - the work the operation-count gates of the write path count.
	 */
	public void recordForeignReferencesFetch() {
		this.foreignReferencesFetches.incrementAndGet();
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns how many times the storage-part executors of the catalog's mutations have fetched the references part of
	 * an entity other than their own from the storage since this holder was created.
	 *
	 * @return the number of fetches
	 */
	public long getForeignReferencesFetchCount() {
		return this.foreignReferencesFetches.get();
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Turns the switch on or off for the collection of the given entity type. The collection does not have to exist
	 * yet. In a virtual catalog every collection is maintained for good: turning the switch on changes nothing there,
	 * and turning it off is refused.
	 *
	 * @param entityType the name of the entity type
	 * @param maintained TRUE to maintain virtual reference partitions, FALSE to stop
	 * @throws GenericEvitaInternalError when the maintenance is turned off in a virtual catalog
	 */
	public void setMaintained(@Nonnull String entityType, boolean maintained) {
		if (this.virtual) {
			assertVirtualSwitchOn(entityType, maintained, "maintained");
			return;
		}
		leaveForcedDefault();
		if (maintained) {
			this.maintainedEntityTypes.add(entityType);
		} else {
			this.maintainedEntityTypes.remove(entityType);
		}
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Carries the switch of a collection that is being renamed, or that replaces another collection, to the name it
	 * takes. The switch only ever moves towards *on* here: the source
	 * name keeps its own, because a commit replays the rename once more onto the last finalized catalog and must find
	 * the source still switched on to carry it again, and a target that was already on stays on.
	 *
	 * @param sourceEntityType the name the collection had
	 * @param targetEntityType the name the collection takes
	 */
	public void carryOver(@Nonnull String sourceEntityType, @Nonnull String targetEntityType) {
		if (this.virtual || (FORCED && this.forcedCatalogId != null)) {
			// the representation of a virtual catalog and the forced default cover every name already
			return;
		}
		if (isMaintained(sourceEntityType)) {
			this.maintainedEntityTypes.add(targetEntityType);
		}
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns TRUE when the catalog represents its single-owner reference partitions virtually, as every catalog the
	 * engine creates or loads does. Every collection of such a catalog is maintained, and the holder refuses to be
	 * switched off for any of them.
	 *
	 * @return TRUE for the holder of a virtual catalog
	 */
	public boolean isVirtual() {
		return this.virtual;
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Refuses to turn a switch of a virtual catalog off - see {@link #isVirtual()}.
	 *
	 * @param entityType the name of the entity type the switch is set for
	 * @param on         the value the switch is set to
	 * @param switchName names the switch in the error message
	 * @throws GenericEvitaInternalError when the switch is turned off
	 */
	private static void assertVirtualSwitchOn(@Nonnull String entityType, boolean on, @Nonnull String switchName) {
		if (!on) {
			throw new GenericEvitaInternalError(
				"Collection `" + entityType + "` belongs to a catalog representing its single-owner reference " +
					"partitions virtually - it is " + switchName + " for good!"
			);
		}
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Returns TRUE while the holder follows the forced default - see the class documentation.
	 *
	 * @return TRUE when every collection is maintained by the forced default
	 */
	public boolean isForcedDefault() {
		return FORCED && this.forcedCatalogId != null;
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Takes the holder out of the forced default - called before a test sets a switch of it explicitly, and by a test
	 * that verifies the default of the switch itself before the catalog holds anything: the holder then holds exactly
	 * what the test sets, starting from nothing, and its catalog is no longer known to have been maintained throughout
	 * - a later load of it is not eligible. Does nothing outside the forced mode or once the holder left it.
	 *
	 * @throws GenericEvitaInternalError when the catalog is virtual - its holder can never start from nothing
	 */
	public void leaveForcedDefault() {
		if (this.virtual) {
			throw new GenericEvitaInternalError(
				"The catalog represents its single-owner reference partitions virtually - its holder cannot start " +
					"from nothing!"
			);
		}
		if (FORCED) {
			final UUID catalogId = this.forcedCatalogId;
			if (catalogId != null) {
				this.forcedCatalogId = null;
				FORCED_CATALOG_IDS.remove(catalogId);
			}
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
	 * What one entity mutation knows about virtual reference partitions: whether it maintains them, whether it writes
	 * the single-owner ones virtually, and the probe it reports its transitions to. Without a probe the handle is one of
	 * the shared constants {@link #OFF}, {@link #MAINTAINED} and {@link #WRITTEN_VIRTUALLY}, so building it allocates
	 * nothing - unless the forced mode collects the partitions the transitions of a virtual catalog pass.
	 *
	 * @param maintained        TRUE when the mutation maintains the virtual reference partitions of its collection
	 * @param writtenVirtually  TRUE when the mutation writes the single-owner partitions of the references its catalog
	 *                          represents virtually as registry rows rather than as reduced entity indexes - TRUE in
	 *                          every virtual catalog whose holder has not had the virtual writing switched off by a
	 *                          test, never TRUE outside a virtual catalog
	 * @param entityType        the entity type the mutation writes into, `null` in the shared constants
	 * @param probe             the probe to report to, `null` for none
	 * @param rootMutation      the identity of the root entity mutation, `null` in the shared constants
	 * @param touchedPartitions the partitions the transitions of the root mutation passed so far, collected for the
	 *                          check at its end - `null` outside the forced mode and outside a virtual catalog
	 */
	public record MutationHandle(
		boolean maintained,
		boolean writtenVirtually,
		@Nullable String entityType,
		@Nullable TransitionProbe probe,
		@Nullable Object rootMutation,
		@Nullable Set<TouchedPartition> touchedPartitions
	) {
		/**
		 * The handle of a mutation that maintains nothing and reports to no probe.
		 */
		public static final MutationHandle OFF = new MutationHandle(false, false, null, null, null, null);
		/**
		 * The handle of a mutation that maintains the virtual reference partitions and reports to no probe.
		 */
		public static final MutationHandle MAINTAINED = new MutationHandle(true, false, null, null, null, null);
		/**
		 * The handle of a mutation that maintains the virtual reference partitions, writes the single-owner ones
		 * virtually and reports to no probe.
		 */
		public static final MutationHandle WRITTEN_VIRTUALLY = new MutationHandle(true, true, null, null, null, null);

		/**
		 * Reports a transition to the probe, if there is one, and collects its partition for the check at the end of
		 * the root mutation in the forced mode. A probe may throw, which fails the mutation at this point.
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
			if (this.touchedPartitions != null && this.entityType != null) {
				this.touchedPartitions.add(
					new TouchedPartition(
						this.entityType, referenceName, scope, partitionPrimaryKey,
						stage == TransitionStage.MIRRORED
					)
				);
			}
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

	// TODO JNO - remove at the end of #1615
	/**
	 * A partition a transition of a root entity mutation passed - see
	 * {@link #rootMutationFinished(Catalog, Object, boolean, boolean)}.
	 *
	 * @param entityType          the entity type owning the reference
	 * @param referenceName       the reference
	 * @param scope               the scope of the partition
	 * @param partitionPrimaryKey the primary key of the partition
	 * @param valuesWritten       TRUE when the transition only wrote values into the partition or removed them
	 *                            ({@link TransitionStage#MIRRORED}) rather than changing its owners
	 */
	public record TouchedPartition(
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		int partitionPrimaryKey,
		boolean valuesWritten
	) {
	}

}
