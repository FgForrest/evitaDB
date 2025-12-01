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
import io.evitadb.comparator.IntComparator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * A comparator that compares references based on the primary key of the referenced entity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ReferencePrimaryKeyComparator implements ReferenceComparator, Serializable {
	@Serial private static final long serialVersionUID = 3382236727899731588L;

	/**
	 * The comparator used to compare the primary keys of the referenced entities.
	 */
	@Nonnull private final IntComparator comparator;

	public ReferencePrimaryKeyComparator(
		@Nonnull IntComparator comparator
	) {
		this.comparator = comparator;
	}

	@Override
	public int getNonSortedReferenceCount() {
		return 0;
	}

	@Nonnull
	@Override
	public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
		return this;
	}

	@Nullable
	@Override
	public ReferenceComparator getNextComparator() {
		return null;
	}

	@Override
	public int compare(ReferenceContract o1, ReferenceContract o2) {
		return this.comparator.compare(
			o1.getReferencedPrimaryKey(),
			o2.getReferencedPrimaryKey()
		);
	}
}
