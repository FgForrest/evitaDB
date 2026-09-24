/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.transaction.memory;

/**
 * Interface allowing classes to participate in a transaction memory handling process. Implementations should implement
 * all get / set state methods in following pattern (these accessors are normally reached through the static
 * {@link io.evitadb.core.transaction.Transaction} facade rather than {@link TransactionalMemory} directly):
 *
 * ``` java
 * GET:
 *      final Changes layer = Transaction.getTransactionalMemoryLayerIfExists(this);
 * 		if (layer == null) {
 * 			// execute original logic
 *      } else {
 * 			// execute logic and propagate all changes captured in transactional layer (Changes object)
 *      }
 *
 * SET:
 *      final Changes layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
 * 		if (layer == null) {
 * 			// execute original logic
 *      } else {
 * 			// put changes to the transactional layer
 *      }
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public interface TransactionalLayerCreator<T> {

	/**
	 * Uniquely identifies this instance among **all** live {@link TransactionalLayerCreator} instances, not merely among
	 * instances of the same class. The id must not change in time - it connects the origin object with its transactional
	 * state in memory, and is the sole key of the diff-layer registry.
	 *
	 * Ids must be drawn from {@link TransactionalObjectVersion#SEQUENCE}, which is what guarantees the global
	 * uniqueness this contract requires. Returning a constant, or an id from any other source, lets one object be handed
	 * a diff layer belonging to another - {@link TransactionalLayerMaintainer} therefore verifies the invariant and
	 * fails loudly on a collision.
	 *
	 * The value {@link TransactionalObjectVersion#NO_LAYER_ID} is reserved to denote "no layer" and is never emitted by
	 * the sequence, so it must never be returned here.
	 */
	long getId();

	/**
	 * Creates and returns new instance of the transactional memory instance. There is only single instance of
	 * the transactional state in single transactional layer - transactional state is linked with origin object via
	 * {@link #getId()} value.
	 */
	T createLayer();

	/**
	 * Declares that this creator's DELEGATE branch — the one it takes when there is no diff layer to write into — is
	 * safe to run inside an open {@link WarmUpSavepoint}, i.e. that a failed bulk-indexing entity mutation can be
	 * rewound to the state this object held before the mutation began.
	 *
	 * The declaration is honoured, not verified — {@link WarmUpSavepoint#verifyRollbackSupported} only reads it, so
	 * returning `true` without meeting one of the two conditions below silently reintroduces the partial-rollback gap
	 * the mechanism exists to close. Exactly one of them must hold:
	 *
	 * - **The delegate branch journals what it writes.** Before each in-place write it records the inverse restoring
	 *   the state it is about to overwrite, through {@link WarmUpSavepoint#recordFirstTouch(Snapshotable)},
	 *   {@link WarmUpSavepoint#claimFirstTouch(Object)} + {@link WarmUpSavepoint#push(Runnable)}, or
	 *   {@link WarmUpSavepoint#writeLayer(TransactionalLayerCreator, boolean)}.
	 * - **The delegate branch writes nothing of its own.** The diff layer is pure in-transaction bookkeeping (dirty
	 *   tracking that only a commit-merge consumes), so outside a transaction there is simply no state to rewind;
	 *   whatever real state the operation touches lives in contained transactional structures that journal their own
	 *   writes. This is the shape of the composite index layers — see `CatalogIndex` or `AttributeIndex`.
	 *
	 * Defaulting to `false` is what makes the mechanism safe by construction: a structure ported to the warm-up write
	 * path without journalling its writes is caught the first time a bracketed mutation reaches it, rather than
	 * discovered later as an index that a rollback quietly failed to rewind. The default costs nothing outside
	 * WARM_UP — the check runs only on the layer-null branch, only while the mechanism is switched on, and only while
	 * a savepoint is actually open.
	 *
	 * @return `true` when a warm-up savepoint can rewind everything this creator's delegate branch writes
	 * @see WarmUpSavepoint
	 */
	default boolean supportsWarmUpRollback() {
		return false;
	}

}
