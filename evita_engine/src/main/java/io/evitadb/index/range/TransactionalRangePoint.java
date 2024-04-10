/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.range;

import io.evitadb.core.transaction.memory.TransactionalCreatorMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.index.array.TransactionalObject;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * Read write transactional implementation of the {@link RangePoint}. The class is PROTECTED from modifications
 * only when transactional memory has currently transaction open. Otherwise it is NOT PROTECTED from modifications
 * because it has to return arrays that are by nature modifiable and thus not thread safe.
 *
 * Arrays are used because we want to stick to Java primitives in order to achieve maximum performance.
 *
 * The developers must ensure no code modifies contents of the arrays when transaction is not open.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see RangePoint for more information about the purpose of the class
 */
@ThreadSafe
@EqualsAndHashCode(of = "threshold")
public class TransactionalRangePoint implements TransactionalObject<TransactionalRangePoint, Void>, VoidTransactionMemoryProducer<TransactionalRangePoint>, RangePoint<TransactionalRangePoint>, TransactionalCreatorMaintainer, Serializable {
	@Serial private static final long serialVersionUID = -7357845800177404940L;
	@Getter private final long threshold;
	@Getter private TransactionalBitmap starts = new TransactionalBitmap();
	@Getter private TransactionalBitmap ends = new TransactionalBitmap();

	public TransactionalRangePoint(long threshold) {
		this.threshold = threshold;
	}

	public TransactionalRangePoint(long threshold, int[] starts, int[] ends) {
		this.threshold = threshold;
		this.starts = new TransactionalBitmap(starts);
		this.ends = new TransactionalBitmap(ends);
	}

	public TransactionalRangePoint(long threshold, Bitmap starts, Bitmap ends) {
		this.threshold = threshold;
		this.starts = new TransactionalBitmap(starts);
		this.ends = new TransactionalBitmap(ends);
	}

	/**
	 * Registers that passed record id range starts at this point.
	 */
	public void addStart(int recordId) {
		this.starts.add(recordId);
	}

	/**
	 * Registers that passed record id range ends at this point.
	 */
	public void addEnd(int recordId) {
		this.ends.add(recordId);
	}

	/**
	 * Registers that passed record id ranges starts at this point.
	 */
	public void addStarts(int[] recordIds) {
		this.starts.addAll(recordIds);
	}

	/**
	 * Registers that passed record id ranges ends at this point.
	 */
	public void addEnds(int[] recordIds) {
		this.ends.addAll(recordIds);
	}

	/**
	 * Unregisters that passed record id ranges starts at this point.
	 */
	public void removeStarts(int[] recordIds) {
		this.starts.removeAll(recordIds);
	}

	/**
	 * Unregisters that passed record id ranges ends at this point.
	 */
	public void removeEnds(int[] recordIds) {
		this.ends.removeAll(recordIds);
	}

	/**
	 * Compares this range point for all values (not only threshold but also both bitmaps).
	 */
	public boolean deepEquals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		TransactionalRangePoint that = (TransactionalRangePoint) o;
		return threshold == that.threshold && Objects.equals(starts, that.starts) && Objects.equals(ends, that.ends);
	}

	@Override
	public String toString() {
		return "TransactionalRangePoint{" +
			"threshold=" + threshold +
			", starts=" + starts +
			", ends=" + ends +
			'}';
	}

	/*
		TransactionalObject implementation
	 */

	@Nonnull
	@Override
	public Collection<TransactionalLayerCreator<?>> getMaintainedTransactionalCreators() {
		return Arrays.asList(
			this.starts, this.ends
		);
	}

	@Nonnull
	@Override
	public TransactionalRangePoint createCopyWithMergedTransactionalMemory(Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		return new TransactionalRangePoint(
			threshold,
			transactionalLayer.getStateCopyWithCommittedChanges(starts),
			transactionalLayer.getStateCopyWithCommittedChanges(ends)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.starts.removeLayer(transactionalLayer);
		this.ends.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public TransactionalRangePoint makeClone() {
		return new TransactionalRangePoint(
			this.threshold,
			getStarts(),
			getEnds()
		);
	}

}
