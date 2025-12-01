/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.query.sort.primaryKey.comparator;


import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Comparator that sorts references based on an exact order of primary keys. References are sorted according to their
 * position in the provided primary key array. References with primary keys not found in the array are tracked and
 * sorted naturally by their primary key value.
 *
 * This comparator can be chained with another comparator to handle references that don't match the exact order.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ReferencePrimaryKeyExactComparator implements ReferenceComparator, Serializable {
	@Serial private static final long serialVersionUID = -6430873939584364458L;
	/**
	 * Array of primary keys defining the exact sort order. References are sorted by their position in this array.
	 */
	private final int[] primaryKeys;
	/**
	 * Optional next comparator in the chain for handling references not found in the primary keys array.
	 */
	private final ReferenceComparator nextComparator;
	/**
	 * Set of primary keys that were encountered during comparison but are not present in the primaryKeys array.
	 * Lazily initialized when first non-sorted reference is found.
	 */
	private IntSet nonSortedInternalPks;

	public ReferencePrimaryKeyExactComparator(@Nonnull int[] primaryKeys) {
		this.primaryKeys = primaryKeys;
		this.nextComparator = null;
	}

	public ReferencePrimaryKeyExactComparator(
		@Nonnull int[] primaryKeys,
		@Nonnull ReferenceComparator nextComparator
	) {
		this.primaryKeys = primaryKeys;
		this.nextComparator = nextComparator;
	}

	@Override
	public int getNonSortedReferenceCount() {
		return this.nonSortedInternalPks == null ? 0 : this.nonSortedInternalPks.size();
	}

	@Nonnull
	@Override
	public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
		return new ReferencePrimaryKeyExactComparator(
			this.primaryKeys,
			comparatorForUnknownRecords
		);
	}

	@Nullable
	@Override
	public ReferenceComparator getNextComparator() {
		return this.nextComparator;
	}

	@SuppressWarnings("ObjectInstantiationInEqualsHashCode")
	@Override
	public int compare(ReferenceContract o1, ReferenceContract o2) {
		final int o1Index = ArrayUtils.indexOf(o1.getReferencedPrimaryKey(), this.primaryKeys);
		final int o2Index = ArrayUtils.indexOf(o2.getReferencedPrimaryKey(), this.primaryKeys);
		if (o1Index >= 0 && o2Index >= 0) {
			return Integer.compare(o1Index, o2Index);
		} else if (o1Index >= 0) {
			if (this.nonSortedInternalPks == null) {
				this.nonSortedInternalPks = new IntHashSet(128);
			}
			this.nonSortedInternalPks.add(o2.getReferencedPrimaryKey());
			return -1;
		} else if (o2Index >= 0) {
			if (this.nonSortedInternalPks == null) {
				this.nonSortedInternalPks = new IntHashSet(128);
			}
			this.nonSortedInternalPks.add(o1.getReferencedPrimaryKey());
			return 1;
		} else {
			if (this.nonSortedInternalPks == null) {
				this.nonSortedInternalPks = new IntHashSet(128);
			}
			this.nonSortedInternalPks.add(o1.getReferencedPrimaryKey());
			this.nonSortedInternalPks.add(o2.getReferencedPrimaryKey());
			return Integer.compare(o1.getReferencedPrimaryKey(), o2.getReferencedPrimaryKey());
		}
	}
}
