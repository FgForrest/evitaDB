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

package io.evitadb.index.invertedIndex;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.core.transaction.memory.WarmUpTouchStamped;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VMLayout;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;

import static io.evitadb.core.transaction.Transaction.getTransactionalMemoryLayerIfExists;

/**
 * Mints the stable **value ids** of ONE shared value tree — the ids that name a distinct attribute value
 * independently of where that value currently sits in the tree.
 *
 * ## Lifecycle: monotonic with holes
 *
 * Ids are handed out in ascending order and **are never reused while the tree lives**. When a distinct value
 * disappears its id becomes a permanent hole; the id space is therefore sparse, which is exactly what a Roaring
 * bitmap of ids compresses well. Reuse is not merely unhelpful here, it is unsound: an older MVCC snapshot may still
 * be reading a generation in which the dead id named its original value, and handing the same id to a different value
 * would make that snapshot silently resolve the wrong string. Reclaiming the holes is the job of a generation-scoped
 * compaction, which rebuilds the dictionary and the id-keyed structures together and leaves old snapshots on the old
 * generation — it is not implemented yet, and {@link ValueIdAllocatorChanges#allocate()} throws rather than wrap when
 * the space runs out.
 *
 * ## Why it is transactional
 *
 * Unlike the page-sequence allocator in {@link io.evitadb.index.page.PageStreamRegistry} — which advances only on the
 * single-writer flush path and therefore lives outside transactional memory — value ids are minted *during* a
 * transaction, the moment a value the tree has never seen is inserted. The allocator must therefore roll back with
 * its transaction: an aborted write must not burn ids, and a savepoint rollback must rewind the counter in lockstep
 * with the leaf id columns the same rollback rewinds.
 *
 * ## Scope
 *
 * One allocator per shared value tree — per (attribute, locale, scope) — never one catalog-global instance. The
 * write path re-shells on the order of a hundred thousand reduced indexes per commit (write-path tuning ADR,
 * 2026-07-27), and a single shared counter across all of them would be a contention point on precisely the path
 * that can least afford one.
 *
 * The allocator is **persisted** with the tree, so ids survive a restart. Without that, a reload would renumber every
 * value and invalidate every id-keyed structure built on top of the tree.
 *
 * ## Single-writer, by the same argument
 *
 * This class is NOT thread safe, and deliberately so. Inside a transaction every mint goes through that
 * transaction's own {@link ValueIdAllocatorChanges} layer, so concurrent writers never touch the counter below;
 * outside one, {@link #allocate()} advances a plain `int` and is reached only from the single-writer bulk/load path
 * that owns the tree at that moment. Reaching for an `AtomicInteger` would not buy safety worth having — it would add
 * a contended write to precisely the write path the *Scope* section above is designed to keep contention-free — so
 * the obligation stays with the caller, exactly as {@link ValueIdConsumerRegistry} documents for the same reason.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
public class ValueIdAllocator
	implements TransactionalLayerProducer<ValueIdAllocatorChanges, ValueIdAllocator>, WarmUpTouchStamped,
	Serializable {
	@Serial private static final long serialVersionUID = -2871695142993471845L;

	/**
	 * This allocator's first-touch mark for the warm-up savepoint mechanism: the stamp of the
	 * {@link WarmUpSavepoint} that most recently captured its counter. {@link WarmUpTouchStamped} carries the
	 * requirements the field has to meet, and why breaking one of them corrupts a rollback rather than merely
	 * slowing it down.
	 */
	@Getter @Setter private transient long warmUpTouchStamp;

	/**
	 * The id an id column slot carries when no value id has been assigned to it. It is `0` rather than `-1` on
	 * purpose: the leaf id column is a plain `int[]` whose vacated slots are zero-filled by the record column's
	 * `clearAt` / `fillEmpty`, so a zeroed slot means "unassigned" without any extra bookkeeping. The first id handed
	 * out is consequently {@link #FIRST_VALUE_ID}.
	 */
	public static final int UNASSIGNED_VALUE_ID = 0;

	/**
	 * The first id this allocator ever hands out; `0` is spent on {@link #UNASSIGNED_VALUE_ID}.
	 */
	public static final int FIRST_VALUE_ID = 1;

	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * The high-water mark: the id {@link #allocate()} will hand out next. Everything below it has been minted at some
	 * point in this tree's life, whether or not the value that received it is still alive.
	 */
	private int nextValueId;

	/**
	 * Creates a fresh allocator for a brand-new tree, positioned to hand out {@link #FIRST_VALUE_ID}.
	 */
	public ValueIdAllocator() {
		this(FIRST_VALUE_ID);
	}

	/**
	 * Creates an allocator restored to a previously persisted position.
	 *
	 * @param nextValueId the persisted high-water mark — the id to hand out next; must be at least
	 *                    {@link #FIRST_VALUE_ID}
	 */
	public ValueIdAllocator(int nextValueId) {
		Assert.isPremiseValid(
			nextValueId >= FIRST_VALUE_ID,
			() -> "Value id high-water mark must be at least " + FIRST_VALUE_ID + ", got " + nextValueId + "!"
		);
		this.nextValueId = nextValueId;
	}

	/**
	 * Mints the next value id for a distinct value this tree has never held before, in a transaction-safe way.
	 *
	 * @return the freshly minted id, always greater than {@link #UNASSIGNED_VALUE_ID}
	 */
	public int allocate() {
		final ValueIdAllocatorChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			// no transaction is open — the single-writer bulk/load path mints directly. The guard runs BEFORE the id
			// is handed out, so the counter can never wrap into the negative range where it would collide with
			// UNASSIGNED_VALUE_ID and start silently aliasing live values
			if (this.nextValueId == Integer.MAX_VALUE) {
				throw exhausted();
			}
			recordWarmUpSavepointTouch();
			return this.nextValueId++;
		} else {
			return layer.allocate();
		}
	}

	/**
	 * Builds the refusal both minting branches raise when the id space runs out.
	 *
	 * Exhaustion is ONE condition, so it gets one exception shape: the direct single-writer branch of
	 * {@link #allocate()} and the per-transaction {@link ValueIdAllocatorChanges#allocate()} beside it are reached
	 * purely by whether a transaction happens to be bound to the thread, and a caller writing a handler — or an
	 * operator matching on the text — must not have to know which one fired. Built here rather than duplicated at the
	 * two sites so the wording cannot drift.
	 *
	 * @return the exception to throw; never thrown by this method itself, so the caller's `throw` stays visible
	 */
	@Nonnull
	static GenericEvitaInternalError exhausted() {
		return new GenericEvitaInternalError(
			"The value id space of this shared value tree is exhausted — " + Integer.MAX_VALUE +
				" ids have been minted since the tree was created and ids are never reused within a generation. " +
				"Reclaiming the holes requires a generation-scoped compaction of the tree.",
			"The value id space of this shared value tree is exhausted!"
		);
	}

	/**
	 * Returns the high-water mark without advancing it, in a transaction-safe way. This is the value that gets
	 * persisted with the tree and restored by {@link #ValueIdAllocator(int)}.
	 *
	 * @return the id the next {@link #allocate()} would hand out
	 */
	public int getNextValueId() {
		final ValueIdAllocatorChanges layer = getTransactionalMemoryLayerIfExists(this);
		return layer == null ? this.nextValueId : layer.getNextValueId();
	}

	/**
	 * Returns the heap this allocator occupies, in bytes — a header, the version id and the counter.
	 *
	 * The figure is a constant: the allocator holds no array and nothing that grows with the data. The
	 * per-transaction {@link ValueIdAllocatorChanges} layer is deliberately **not** counted — it belongs to the
	 * transaction that created it and disappears on commit or rollback.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		// id + the counter
		return VMLayout.current().sizeOfObject(Long.BYTES + Integer.BYTES);
	}

	/**
	 * Records, for the warm-up savepoint bracketing the current root entity mutation if one is open, the counter this
	 * allocator held before the mutation started, so a rollback restores it (see {@link WarmUpSavepoint}).
	 *
	 * Granularity is a first-touch memento rather than a per-mint inverse, which is what the per-family cost rule in
	 * `documentation/developer/stm/savepoints.md` prescribes for a scalar: the whole pre-image is one `int` grabbed in
	 * `O(1)`, so capturing it once per savepoint is strictly cheaper than pushing a lambda per minted id — and a bulk
	 * load mints one for every distinct value it meets, which is the hot case rather than a rare one.
	 *
	 * The restore is absolute, not a decrement. A savepoint may cover many mints, and reverse replay runs the
	 * earliest-pushed inverse last, so one absolute assignment of the pre-savepoint counter is what makes an entity
	 * that minted twenty ids give all twenty back.
	 *
	 * ## Why the ids are given back at all
	 *
	 * A leaked id would not corrupt anything — ids are monotonic **with holes** by design (see the class JavaDoc), so
	 * a hole left by a rolled-back entity is indistinguishable from one left by a deleted value. Two reasons to
	 * restore it anyway: the high-water mark is persisted with the tree and decides whether the root storage part has
	 * to be rewritten ({@link io.evitadb.index.invertedIndex.InvertedIndex#isValueIdHighWaterDirty()}), so a mark that
	 * advanced for a mutation that never happened forces a root out for nothing; and
	 * {@link ValueIdAllocatorChanges} already gives them back on a transactional abort, so warm-up doing otherwise
	 * would make the two paths disagree about the same question for no gain.
	 *
	 * Recorded once per savepoint, and only from the non-transactional branch — inside a transaction no warm-up
	 * savepoint is ever open. Outside a savepoint it costs one {@link ThreadLocal} read returning `null`.
	 */
	private void recordWarmUpSavepointTouch() {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint != null && savepoint.claimFirstTouch(this)) {
			final int restored = this.nextValueId;
			savepoint.push(() -> this.nextValueId = restored);
		}
	}

	@Override
	public String toString() {
		return "ValueIdAllocator(nextValueId=" + getNextValueId() + ')';
	}

	/*
		TransactionalLayerProducer implementation
	 */

	@Nonnull
	@Override
	public ValueIdAllocatorChanges createLayer() {
		return new ValueIdAllocatorChanges(this.nextValueId);
	}

	@Nonnull
	@Override
	public ValueIdAllocator createCopyWithMergedTransactionalMemory(
		@Nullable ValueIdAllocatorChanges layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// an untouched allocator is carried forward by reference, mirroring how an untouched tree keeps its identity
		return layer == null ? this : new ValueIdAllocator(layer.getNextValueId());
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}

	/**
	 * The delegate branch of {@link #allocate()} advances {@link #nextValueId} in place, and
	 * {@link #recordWarmUpSavepointTouch()} captures the counter before the first such advance inside a savepoint, so
	 * everything that branch writes is rewindable.
	 *
	 * @return always `true`
	 */
	@Override
	public boolean supportsWarmUpRollback() {
		return true;
	}

}
