/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.algebra.filter;

import io.evitadb.core.query.algebra.Formula;

/**
 * Marker interface tagging formulas that carry an attribute-family range selection (the
 * {@link io.evitadb.core.query.extraResult.translator.common.RangeCarrierGroup#ATTRIBUTE_HISTOGRAM} group). Carriers:
 *
 * - the `AttributeFormula` subclass produced by `AttributeBetweenTranslator` (plain attribute `attributeBetween`,
 *   reference-attribute `attributeBetween` inside `referenceHaving`, and referenced-entity-attribute `attributeBetween`
 *   inside `entityHaving`);
 * - the `HistogramHavingFormula` pass-through wrapper emitted by `HistogramHavingTranslator`.
 *
 * When an attribute-family histogram computes its own `[min, max]` baseline, the baseline cloner peels every
 * attribute-range carrier it finds inside `userFilter` so the user's current slider pick does not contract the span
 * of that same slider.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface AttributeRangeCarrierFormula extends Formula {
}
