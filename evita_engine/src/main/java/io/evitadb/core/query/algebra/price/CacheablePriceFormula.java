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

package io.evitadb.core.query.algebra.price;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.termination.PriceTerminationFormula;

/**
 * The interface marks all {@link Formula} that can be cached and are placed within {@link PriceTerminationFormula}.
 * Such formulas need to be either price agnostic (i.e. low-level) or must implement {@link FilteredPriceRecordAccessor}
 * interface to provide information about prices they cover so that ordering by price can access those.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface CacheablePriceFormula extends Formula {
}
