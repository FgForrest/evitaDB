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

package io.evitadb.index.attribute;


import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.index.RepresentativeReferenceKey;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * This class is a specialized version of {@link SortedRecordsSupplier} that provides access to a {@link ReferenceKey}
 * of the index that is used to sort the records in case the records are sorted by a reference attribute.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ReferenceSortedRecordsProvider extends SortedRecordsSupplier {
	@Serial private static final long serialVersionUID = -3001386450022878707L;
	@Getter @Nonnull private final RepresentativeReferenceKey referenceKey;

	public ReferenceSortedRecordsProvider(
		long transactionalId,
		@Nonnull int[] sortedRecordIds,
		@Nonnull int[] recordPositions,
		@Nonnull Bitmap allRecords,
		@Nonnull SortedComparableForwardSeeker sortedComparableForwardSeeker,
		@Nonnull RepresentativeReferenceKey referenceKey
	) {
		super(transactionalId, sortedRecordIds, recordPositions, allRecords, sortedComparableForwardSeeker);
		this.referenceKey = referenceKey;
	}
}
