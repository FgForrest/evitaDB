/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.query.filter.translator.fulltext;

import io.evitadb.api.query.filter.AssociatedDataIn;
import io.evitadb.api.query.filter.TextMatches;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;

/**
 * Rejects a misplaced {@link AssociatedDataIn}. The enclosing {@link TextMatches} consumes valid nested selectors
 * itself.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class AssociatedDataInTranslator implements FilteringConstraintTranslator<AssociatedDataIn> {

	@Nonnull
	@Override
	public Formula translate(@Nonnull AssociatedDataIn selector, @Nonnull FilterByVisitor filterByVisitor) {
		throw new EvitaInvalidUsageException(
			"Constraint `" + selector + "` can only be used as a place selector inside the `textMatches` constraint!"
		);
	}

}
