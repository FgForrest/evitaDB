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

package io.evitadb.core.query.sort.attribute.comparator;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import com.carrotsearch.hppc.LongIntHashMap;
import com.carrotsearch.hppc.cursors.LongIntCursor;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator.OwnerReferencesAwareComparator;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Locale;

/**
 * Orders the rows of one owner by the predecessor links between their referenced entities.
 * Each target has one position. Valued duplicates at that position compare equal, so the stable sort preserves
 * their stored order. The first valued row of a target supplies its predecessor. Rows without a position follow
 * the ranked rows and go to the next ordering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@SuppressWarnings("ComparatorNotSerializable")
public final class ReferencedEntityPredecessorComparator implements OwnerReferencesAwareComparator {
	private static final int NO_POSITION = Integer.MAX_VALUE;
	private final AttributeIndexKey chainKey;
	private final AttributeKey attributeKey;
	private final OrderDirection direction;
	private LongIntHashMap positionsByRow = new LongIntHashMap(0);

	/**
	 * Creates an ordering over a referenced-entity predecessor attribute.
	 *
	 * @param referenceName name of the ordered reference
	 * @param attributeName name of the predecessor attribute
	 * @param locale locale of the attribute, or null for a non-localized attribute
	 * @param direction requested order of the chain
	 */
	public ReferencedEntityPredecessorComparator(
		@Nonnull String referenceName,
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull OrderDirection direction
	) {
		this.chainKey = new AttributeIndexKey(referenceName, attributeName, locale);
		this.attributeKey = new AttributeKey(attributeName, locale);
		this.direction = direction;
	}

	/**
	 * Materializes the positions of the owner's original list using the same chain builder and ordering as the index.
	 *
	 * @param references original rows of the reference, in stored order
	 */
	@Override
	public void prepareOwner(@Nonnull Collection<? extends ReferenceContract> references) {
		final IntIntMap ranksByTarget = new IntIntHashMap(references.size());
		final LongIntHashMap ranksByRow = new LongIntHashMap(references.size());
		final ChainIndex chain = new ChainIndex(this.chainKey);
		final TransactionalLayerMaintainer transactionalLayer = Transaction.getTransactionalLayerMaintainer();
		try {
			for (final ReferenceContract reference : references) {
				if (reference.exists()) {
					final ReferencedEntityPredecessor predecessor = this.predecessorOf(reference);
					if (predecessor != null) {
						final ReferenceKey key = reference.getReferenceKey();
						final int target = key.primaryKey();
						if (ranksByTarget.putIfAbsent(target, NO_POSITION)) {
							chain.upsertPredecessor(predecessor, target);
						}
						ranksByRow.put(rowKey(key), target);
					}
				}
			}
			final int[] keys = (this.direction == OrderDirection.ASC ?
				chain.getAscendingOrderRecordsSupplier() : chain.getDescendingOrderRecordsSupplier()
			).getSortedRecordIds();
			for (int position = 0; position < keys.length; position++) {
				ranksByTarget.put(keys[position], position);
			}
			// Replace each valued row's target key with the position shared by that target's valued rows.
			for (final LongIntCursor row : ranksByRow) {
				ranksByRow.indexReplace(row.index, ranksByTarget.get(row.value));
			}
		} finally {
			// The ranks outlive the temporary chain, including when the query runs inside a write transaction.
			if (transactionalLayer != null) {
				chain.removeLayer(transactionalLayer);
			}
		}
		this.positionsByRow = ranksByRow;
	}

	/**
	 * Checks whether this row supplied a value and received a rank in the owner's chain.
	 *
	 * @param reference reference row to classify
	 * @return whether the row has a rank
	 */
	@Override
	public boolean canRank(@Nonnull ReferenceContract reference) {
		return this.positionsByRow.containsKey(rowKey(reference.getReferenceKey()));
	}

	/**
	 * Compares the positions of two ranked rows in the owner's chain.
	 *
	 * @param first first ranked row
	 * @param second second ranked row
	 * @return comparison of their positions
	 */
	@Override
	public int compare(@Nonnull ReferenceContract first, @Nonnull ReferenceContract second) {
		return Integer.compare(
			this.positionsByRow.get(rowKey(first.getReferenceKey())),
			this.positionsByRow.get(rowKey(second.getReferenceKey()))
		);
	}

	/**
	 * Identifies a row within the ordered reference. Reflected rows for different targets can share an internal key.
	 *
	 * @param key reference row key
	 * @return packed referenced and internal primary keys
	 */
	private static long rowKey(@Nonnull ReferenceKey key) {
		return NumberUtils.pack(key.primaryKey(), key.internalPrimaryKey());
	}

	/**
	 * Reads the predecessor from the original row even when the response omits reference attributes.
	 *
	 * @param reference row being read
	 * @return the predecessor declared by the row, or null when the value is absent
	 */
	@Nullable
	private ReferencedEntityPredecessor predecessorOf(@Nonnull ReferenceContract reference) {
		final AttributeValue value = reference.getAttributeValue(this.attributeKey).orElse(null);
		return value != null && value.exists() ? (ReferencedEntityPredecessor) value.value() : null;
	}
}
