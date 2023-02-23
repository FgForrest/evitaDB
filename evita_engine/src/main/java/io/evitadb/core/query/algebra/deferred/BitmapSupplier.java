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

package io.evitadb.core.query.algebra.deferred;

import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.index.bitmap.Bitmap;

import java.util.function.Supplier;

/**
 * Implementations of this interface are part of the formula tree and provide access directly to integer bitmaps, that
 * are derived from transactional datastructures in indexes. They are usually computed later in the stage using
 * the lazy {@link Supplier#get()} call and are always enveloped in {@link DeferredFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface BitmapSupplier extends TransactionalDataRelatedStructure, Supplier<Bitmap> {

	/**
	 * Returns the cardinality estimate of {@link #get()} method without really computing the result. The estimate
	 * will not be precise but differs between AND/OR relations and helps us to compute {@link #getEstimatedCost()}.
	 */
	int getEstimatedCardinality();

}
