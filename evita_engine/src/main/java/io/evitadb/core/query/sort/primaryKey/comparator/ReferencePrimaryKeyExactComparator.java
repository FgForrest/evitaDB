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


import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Ranks references by the position of their primary key in the requested sequence. Unlisted keys can be handled
 * by the next ordering.
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
	 * Creates an ordering from the requested primary key sequence.
	 *
	 * @param primaryKeys keys in the requested order
	 */
	public ReferencePrimaryKeyExactComparator(@Nonnull int[] primaryKeys) {
		this.primaryKeys = primaryKeys;
	}

	/**
	 * Checks membership in the requested sequence.
	 *
	 * @param reference row to classify
	 * @return whether the key is listed
	 */
	@Override
	public boolean canRank(@Nonnull ReferenceContract reference) {
		return ArrayUtils.indexOf(reference.getReferencedPrimaryKey(), this.primaryKeys) >= 0;
	}

	/**
	 * Compares references by their positions in the requested sequence. Unlisted keys sort at the end by key.
	 *
	 * @param o1 first row
	 * @param o2 second row
	 * @return the order of the rows
	 */
	@Override
	public int compare(@Nonnull ReferenceContract o1, @Nonnull ReferenceContract o2) {
		final int o1Index = ArrayUtils.indexOf(o1.getReferencedPrimaryKey(), this.primaryKeys);
		final int o2Index = ArrayUtils.indexOf(o2.getReferencedPrimaryKey(), this.primaryKeys);
		if (o1Index < 0 && o2Index < 0) {
			return Integer.compare(o1.getReferencedPrimaryKey(), o2.getReferencedPrimaryKey());
		}
		return Integer.compare(o1Index < 0 ? Integer.MAX_VALUE : o1Index, o2Index < 0 ? Integer.MAX_VALUE : o2Index);
	}
}
