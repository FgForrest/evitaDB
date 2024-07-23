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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.algebra.prefetch;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;

/**
 * Interface is used by {@link SelectionFormula} to execute alternative logic for filtering prefetched entities which
 * may result in faster query evaluation in case explicit IDs are requested and most of them match the query (which is
 * usually true - most `getEntityById` filter only by a few constraints that don't usually evict the result - the client
 * knows what he asks for).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface EntityToBitmapFilter extends RequirementsDefiner {

	/**
	 * Method produces bitmap with entity ids of all {@link SealedEntity entities} that match the filter. Implementation
	 * may rely on entities having all data requested by {@link #getEntityRequire()} already fetched and available.
	 */
	@Nonnull
	Bitmap filter(@Nonnull QueryExecutionContext context);

}
