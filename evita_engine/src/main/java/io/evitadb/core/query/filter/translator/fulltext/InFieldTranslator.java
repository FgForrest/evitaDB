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

import io.evitadb.api.query.filter.InField;
import io.evitadb.api.query.filter.TextMatches;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;

/**
 * Rejects an {@link InField} placed anywhere but directly inside {@link TextMatches}. The {@link TextMatchesTranslator}
 * consumes its `inField` children itself and never lets the visitor reach them, so a translation reaching this one
 * means the constraint was misplaced - which the Java API and the EvitaQL grammar both allow. Without a translator of
 * its own it would fail as an internal "no translator found" error instead of telling the client what is wrong.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class InFieldTranslator implements FilteringConstraintTranslator<InField> {

	@Nonnull
	@Override
	public Formula translate(@Nonnull InField inField, @Nonnull FilterByVisitor filterByVisitor) {
		throw new EvitaInvalidUsageException(
			"Constraint `" + inField + "` can only be used directly inside the `textMatches` constraint!"
		);
	}

}
