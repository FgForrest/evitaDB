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

package io.evitadb.index.array;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.iterator.ConstantIntIterator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalIntToLongBPlusTree;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.PrimitiveIterator.OfInt;

/**
 * This array keeps unique (distinct) integer values in an unordered fashion, providing fast positional access,
 * fast lookup by value, and full transactional isolation.
 *
 * It is a **composite** of two coordinated, count-augmented B+ trees, coupled by a stable `long` order-key:
 *
 * - the **value index** ({@link TransactionalIntToLongBPlusTree}) maps each record id to the order-key of the
 *   container that holds it (no boxing), and
 * - the **position tree** ({@link UnorderedLookupTree}) is an order-statistic tree whose leaves are containers of
 *   record ids in logical order; it answers `getRecordAt(position)` (count descent) and `findPosition` (order-key
 *   descent → prefix count).
 *
 * Both trees mutate **in place** when no transaction is open (the warm-up / committed delegate) and **path-copy**
 * inside a transaction, so multiple readers see the original data while a writer accumulates an isolated diff that
 * materialises atomically on commit — each transaction is bound to a single thread. If no transaction is open the
 * class is not thread safe for multiple writers.
 *
 * The previous dual-`int[]` {@link UnorderedLookup} delegate is retained only as the immutable flattened snapshot
 * DTO consumed downstream; this façade no longer renumbers a suffix or reallocates `O(N)` arrays per write, so a
 * single insert / remove is `O(log N)` with no humongous allocation, and a commit of `e` edits is `O(e·log N)`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@ThreadSafe
public class TransactionalUnorderedIntArray
	implements TransactionalLayerProducer<Void, TransactionalUnorderedIntArray>,
	OrderKeyConsumer,
	Serializable {
	@Serial private static final long serialVersionUID = 4753581686040233219L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Order-statistic position tree (order-key → container of record ids in logical order).
	 */
	@Nonnull private final UnorderedLookupTree positionTree;
	/**
	 * Value index mapping each record id to the order-key of its container (no boxing).
	 */
	@Nonnull private final TransactionalIntToLongBPlusTree valueIndex;

	/**
	 * Creates an empty transactional unordered int array.
	 */
	public TransactionalUnorderedIntArray() {
		this.positionTree = new UnorderedLookupTree();
		this.valueIndex = new TransactionalIntToLongBPlusTree();
	}

	/**
	 * Creates a transactional unordered int array wrapping the given delegate (record ids in logical order).
	 *
	 * @param delegate the initial unordered array of record ids
	 */
	public TransactionalUnorderedIntArray(@Nonnull int[] delegate) {
		this.positionTree = new UnorderedLookupTree();
		this.valueIndex = new TransactionalIntToLongBPlusTree();
		// A freshly-constructed array establishes its committed BASE state. If we happen to be inside a transaction
		// (e.g. ChainIndex creating a new chain mid-transaction), the build must run OUTSIDE the transaction's
		// awareness - otherwise the data would land in per-transaction layers of the two trees, and a later
		// map.remove of this value (TransactionalMap delete-cleanup releases a removed producer's layer) would
		// discard those layers and silently empty the array. Detaching the transaction for the duration of the build
		// makes the data land in the base, exactly as the former plain UnorderedLookup delegate did.
		final Transaction transaction = Transaction.getTransaction().orElse(null);
		if (transaction != null) {
			transaction.unbindTransactionFromThread();
			try {
				this.positionTree.bulkLoad(delegate, this);
			} finally {
				transaction.bindTransactionToThread();
			}
		} else {
			// bulk-load the position tree (O(N)); the order-key consumer (this) populates the value index
			this.positionTree.bulkLoad(delegate, this);
		}
	}

	/**
	 * Internal constructor used by {@link #createCopyWithMergedTransactionalMemory} to wrap the already-merged
	 * (committed) child trees.
	 */
	private TransactionalUnorderedIntArray(
		@Nonnull UnorderedLookupTree positionTree,
		@Nonnull TransactionalIntToLongBPlusTree valueIndex
	) {
		this.positionTree = positionTree;
		this.valueIndex = valueIndex;
	}

	/**
	 * Order-key consumer hook used by the position tree to keep the value index coherent (INV-COUPLE): records each
	 * `recordId → orderKey` assignment (insert overwrites an existing mapping).
	 */
	@Override
	public void accept(int recordId, long orderKey) {
		this.valueIndex.insert(recordId, orderKey);
	}

	/**
	 * Returns the array of positions corresponding to the ascending record id array {@link #getRecordIds()}; i.e.
	 * `getArray()[positions[i]] == getRecordIds()[i]`.
	 */
	public int[] getPositions() {
		final int[] permutation = getArray();
		final IntIntMap recordToPosition = new IntIntHashMap(permutation.length);
		for (int position = 0; position < permutation.length; position++) {
			recordToPosition.put(permutation[position], position);
		}
		final int[] sortedRecordIds = permutation.clone();
		Arrays.sort(sortedRecordIds);
		final int[] positions = new int[sortedRecordIds.length];
		for (int i = 0; i < sortedRecordIds.length; i++) {
			positions[i] = recordToPosition.get(sortedRecordIds[i]);
		}
		return positions;
	}

	/**
	 * Returns the record ids sorted by their id (ascending) as a bitmap.
	 */
	public Bitmap getRecordIds() {
		final int[] sortedRecordIds = getArray().clone();
		Arrays.sort(sortedRecordIds);
		return new BaseBitmap(sortedRecordIds);
	}

	/**
	 * Method returns record id on specified index of the array.
	 */
	public int get(int index) {
		return this.positionTree.getRecordAt(index);
	}

	/**
	 * Method returns last record in the array.
	 *
	 * @return record id
	 * @throws ArrayIndexOutOfBoundsException when array is empty
	 */
	public int getLastRecordId() throws ArrayIndexOutOfBoundsException {
		return this.positionTree.getLastRecordId();
	}

	/**
	 * Method returns the underlying array of record ids (logical order).
	 */
	public int[] getArray() {
		return this.positionTree.getArray();
	}

	/**
	 * Method returns subset of underlying array of record ids.
	 *
	 * @param startIndex inclusive
	 * @param endIndex   exclusive
	 */
	public int[] getSubArray(int startIndex, int endIndex) {
		return Arrays.copyOfRange(this.positionTree.getArray(), startIndex, endIndex);
	}

	/**
	 * Method adds new record to the array, just after the record specified as `previousRecordId`
	 * ({@link Integer#MIN_VALUE} adds it to the head).
	 */
	public void add(int previousRecordId, int recordId) {
		Assert.isTrue(
			this.valueIndex.search(recordId).isEmpty(),
			"Record with id " + recordId + " is already part of the array!"
		);
		if (previousRecordId == Integer.MIN_VALUE) {
			this.positionTree.insertAtPosition(0, recordId, this);
		} else {
			final OptionalLong previousOrderKey = this.valueIndex.search(previousRecordId);
			Assert.isTrue(
				previousOrderKey.isPresent(),
				"Record with id " + previousRecordId + " is not present in the array,"
					+ " cannot add record " + recordId + " after it!"
			);
			this.positionTree.insertAfter(previousOrderKey.getAsLong(), previousRecordId, recordId, this);
		}
	}

	/**
	 * Method adds new record to the array on specified index.
	 */
	public void addOnIndex(int index, int recordId) {
		Assert.isTrue(
			this.valueIndex.search(recordId).isEmpty(),
			"Record with id " + recordId + " is already part of the array!"
		);
		this.positionTree.insertAtPosition(index, recordId, this);
	}

	/**
	 * Method adds multiple record ids to the array (each just after the previous one).
	 */
	public void addAll(int previousRecordId, int... recordIds) {
		int currentPreviousRecordId = previousRecordId;
		for (final int recordId : recordIds) {
			add(currentPreviousRecordId, recordId);
			currentPreviousRecordId = recordId;
		}
	}

	/**
	 * Method adds multiple record ids to the end of the array.
	 *
	 * @param recordIds record ids to add
	 */
	public void appendAll(int... recordIds) {
		for (final int recordId : recordIds) {
			Assert.isTrue(
				this.valueIndex.search(recordId).isEmpty(),
				"Record with id " + recordId + " is already part of the array!"
			);
			this.positionTree.insertAtPosition(this.positionTree.size(), recordId, this);
		}
	}

	/**
	 * Method removes record id from the array.
	 */
	public void remove(int recordId) {
		final long orderKey = this.valueIndex.search(recordId).orElseThrow(
			() -> new GenericEvitaInternalError(
				"Record id " + recordId + " is not part of the array!",
				"Record id is not part of the array."
			)
		);
		this.positionTree.removeByOrderKey(orderKey, recordId, this);
		this.valueIndex.delete(recordId);
	}

	/**
	 * Method removes multiple record ids from the array.
	 */
	public void removeAll(int... recordIds) {
		for (final int recordId : recordIds) {
			remove(recordId);
		}
	}

	/**
	 * Method removes all records between two indexes.
	 *
	 * @param startIndex inclusive
	 * @param endIndex   exclusive
	 * @return removed records
	 */
	public int[] removeRange(int startIndex, int endIndex) {
		final int[] removed = Arrays.copyOfRange(this.positionTree.getArray(), startIndex, endIndex);
		for (final int recordId : removed) {
			remove(recordId);
		}
		return removed;
	}

	/**
	 * Returns length of the array.
	 */
	public int getLength() {
		return this.positionTree.size();
	}

	/**
	 * Returns true if array contain no record ids.
	 */
	public boolean isEmpty() {
		return this.positionTree.isEmpty();
	}

	/**
	 * Returns index (position) of the record id in the array.
	 *
	 * @return {@link Integer#MIN_VALUE} when the record is not found, the position otherwise
	 */
	public int indexOf(int recordId) {
		final OptionalLong orderKey = this.valueIndex.search(recordId);
		return orderKey.isEmpty()
			? Integer.MIN_VALUE
			: this.positionTree.findPositionByOrderKey(orderKey.getAsLong(), recordId);
	}

	/**
	 * Returns true if record id is part of the array.
	 */
	public boolean contains(int recordId) {
		return this.valueIndex.search(recordId).isPresent();
	}

	/**
	 * Returns iterator that allows to iterate through all record ids of the array in logical order.
	 */
	public OfInt iterator() {
		return new ConstantIntIterator(getArray());
	}

	@Override
	public int hashCode() {
		/* we deliberately want Object.hashCode() default implementation */
		return super.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		/* we deliberately want Object.equals() default implementation */
		return super.equals(obj);
	}

	@Override
	public String toString() {
		return Arrays.toString(getArray());
	}

	/*
		TransactionalLayerProducer implementation
	 */

	@Nullable
	@Override
	public Void createLayer() {
		// the façade holds no diff of its own - all state lives in the two child producers
		return null;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.positionTree.removeLayer(transactionalLayer);
		this.valueIndex.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public TransactionalUnorderedIntArray createCopyWithMergedTransactionalMemory(
		@Nullable Void layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		return new TransactionalUnorderedIntArray(
			transactionalLayer.getStateCopyWithCommittedChanges(this.positionTree),
			transactionalLayer.getStateCopyWithCommittedChanges(this.valueIndex)
		);
	}

}
