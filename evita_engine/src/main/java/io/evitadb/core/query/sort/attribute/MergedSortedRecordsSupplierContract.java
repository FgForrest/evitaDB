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

package io.evitadb.core.query.sort.attribute;


import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;

import javax.annotation.Nonnull;

/**
 * Generic interface for sorted records suppliers that merge multiple sorted records suppliers into one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public sealed interface MergedSortedRecordsSupplierContract extends Sorter
	permits MergedComparableSortedRecordsSupplierSorter, MergedSortedRecordsSupplierSorter {

	/**
	 * Retrieves an array of {@link SortedRecordsProvider} instances. Each provider supplies access
	 * to a collection of sorted records, enabling operations such as retrieving records count,
	 * sorted record IDs, and their positions.
	 *
	 * @return an array of {@link SortedRecordsProvider} instances representing the sorted records providers.
	 */
	@Nonnull
	SortedRecordsProvider[] getSortedRecordsProviders();

}
