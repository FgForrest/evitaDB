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

package io.evitadb.core.query.filter;

import io.evitadb.api.query.filter.Not;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;

/**
 * Says where a {@link Not} nested in the current {@link ProcessingScope} is settled — which decides what
 * {@link io.evitadb.core.query.filter.translator.bool.NotTranslator} may emit for it.
 *
 * The distinction exists because a reference type-level index answers only *"which reduced indexes hold at least
 * one row matching X"*. It cannot answer a negation at all: an index holding a row that matches X may hold another
 * row that does not, so subtracting the matches would drop an index that should have survived.
 *
 * **This follows from who consumes the formula, never from which index produced it.** A caller that re-examines
 * every candidate row by row can afford the widened answer; a caller that takes the formula as its result cannot.
 * Facet filtering is the case that proves the two are independent — it establishes a type-level scope and still
 * needs a real subtraction. That is why the value is carried on the scope and passed in by the caller rather than
 * derived from {@link ProcessingScope#getIndexType()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum NegationResolution {

	/**
	 * The formula is a **candidate set** that the caller re-evaluates against each reference row afterwards, so a
	 * negation inside it may widen to the super set. Widening never loses a row — it keeps every index a candidate
	 * and leaves the negation to be settled per row, inside the index it belongs to.
	 */
	PER_ROW,

	/**
	 * The formula **is the answer** — nothing re-examines the rows behind it, so a negation inside it has to stay a
	 * real subtraction. Widening here would hand back the whole reference family instead of the complement.
	 */
	IN_PLACE

}
