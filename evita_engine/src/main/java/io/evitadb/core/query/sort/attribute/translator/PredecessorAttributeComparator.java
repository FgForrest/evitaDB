/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.core.query.sort.attribute.translator;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.index.array.CompositeObjectArray;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.function.Supplier;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class PredecessorAttributeComparator implements EntityComparator {
	private final Supplier<SortedRecordsProvider[]> sortedRecordsSupplier;
	private SortedRecordsProvider[] resolvedSortedRecordsProviders;
	private CompositeObjectArray<EntityContract> nonSortedEntities;

	@Nonnull
	@Override
	public Iterable<EntityContract> getNonSortedEntities() {
		return nonSortedEntities == null ? Collections.emptyList() : nonSortedEntities;
	}

	@Override
	public int compare(EntityContract o1, EntityContract o2) {
		final SortedRecordsProvider[] sortedRecordsProviders = getSortedRecordsProviders();
		boolean o1Found = false;
		boolean o2Found = false;
		int result = 0;
		for (SortedRecordsProvider sortedRecordsProvider : sortedRecordsProviders) {
			final int o1Index = o1Found ? -1 : sortedRecordsProvider.getAllRecords().indexOf(o1.getPrimaryKey());
			final int o2Index = o2Found ? -1 : sortedRecordsProvider.getAllRecords().indexOf(o2.getPrimaryKey());
			if (o1Index >= 0 && o2Index >= 0) {
				result = Integer.compare(
					sortedRecordsProvider.getRecordPositions()[o1Index],
					sortedRecordsProvider.getRecordPositions()[o2Index]
				);
				o1Found = true;
				o2Found = true;
			} else if (o1Index >= 0) {
				result = result == 0 ? 1 : result;
				o1Found = true;
			} else if (o2Index >= 0) {
				result = result == 0 ? -1 : result;
				o2Found = true;
			}
			if (o1Found && o2Found) {
				break;
			}
		}
		if ((o1Found && o2Found) && nonSortedEntities == null) {
			nonSortedEntities = new CompositeObjectArray<>(EntityContract.class);
		}
		if (!o1Found) {
			nonSortedEntities.add(o1);
		}
		if (!o2Found) {
			nonSortedEntities.add(o2);
		}
		return result;
	}

	private SortedRecordsProvider[] getSortedRecordsProviders() {
		if (resolvedSortedRecordsProviders == null) {
			resolvedSortedRecordsProviders = sortedRecordsSupplier.get();
		}
		return resolvedSortedRecordsProviders;
	}

}
