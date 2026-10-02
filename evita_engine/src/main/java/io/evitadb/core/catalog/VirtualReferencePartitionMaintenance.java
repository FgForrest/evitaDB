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

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
	 * How many entity mutations have been executed with the switch on since this holder was created - a probe that
	 * lets a test tell whether the mutations replayed from the write-ahead log at load ran with the switch on, which no
	 * state of the switch read after the load can tell.
	 */
	private final AtomicLong maintainedMutationCount = new AtomicLong();

	// TODO JNO - remove at the end of #1615
	/**
	 * Creates a holder with the switch off for every collection.
	 */
	public VirtualReferencePartitionMaintenance() {
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Creates a holder with the switch already on for the collections of the given entity types - the holder of a
	 * catalog being loaded from disk, seeded before any of its collections is attached.
	 *
	 * @param maintainedEntityTypes the names of the entity types to maintain
	 */
	public VirtualReferencePartitionMaintenance(@Nonnull Set<String> maintainedEntityTypes) {
		this.maintainedEntityTypes.addAll(maintainedEntityTypes);
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
	 * Returns TRUE when the collection of the given entity type maintains virtual reference partitions, and counts the
	 * answer when it is TRUE. Asked once per entity mutation, by the code building the executor of its entity index
	 * mutations - and by nothing else, so that {@link #getMaintainedMutationCount()} counts mutations rather than
	 * reads.
	 *
	 * @param entityType the name of the entity type the mutation writes into
	 * @return TRUE when the mutation maintains virtual reference partitions
	 */
	public boolean isMaintainedForMutation(@Nonnull String entityType) {
		final boolean maintained = isMaintained(entityType);
		if (maintained) {
			this.maintainedMutationCount.incrementAndGet();
		}
		return maintained;
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
	 * Turns the switch on or off for the collection of the given entity type. The collection does not have to exist
	 * yet.
	 *
	 * @param entityType the name of the entity type
	 * @param maintained TRUE to maintain virtual reference partitions, FALSE to stop
	 */
	public void setMaintained(@Nonnull String entityType, boolean maintained) {
		if (maintained) {
			this.maintainedEntityTypes.add(entityType);
		} else {
			this.maintainedEntityTypes.remove(entityType);
		}
	}

	// TODO JNO - remove at the end of #1615
	/**
	 * Carries the switch of a collection that is being renamed, or that replaces another collection, to the name it
	 * takes. The switch only ever moves towards *on* here: the source name keeps its own, because a commit replays the
	 * rename once more onto the last finalized catalog and must find the source still switched on to carry it again,
	 * and a target that was already on stays on.
	 *
	 * @param sourceEntityType the name the collection had
	 * @param targetEntityType the name the collection takes
	 */
	public void carryOver(@Nonnull String sourceEntityType, @Nonnull String targetEntityType) {
		if (isMaintained(sourceEntityType)) {
			this.maintainedEntityTypes.add(targetEntityType);
		}
	}

}
