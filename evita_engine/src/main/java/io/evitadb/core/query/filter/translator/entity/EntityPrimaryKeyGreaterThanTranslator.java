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

package io.evitadb.core.query.filter.translator.entity;

import io.evitadb.api.query.filter.EntityPrimaryKeyGreaterThan;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.entity.EntityPrimaryKeyRangeFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;

import javax.annotation.Nonnull;

/**
 * Translates {@link EntityPrimaryKeyGreaterThan} constraint into an {@link EntityPrimaryKeyRangeFormula}
 * that selects primary keys strictly greater than the specified threshold.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class EntityPrimaryKeyGreaterThanTranslator implements FilteringConstraintTranslator<EntityPrimaryKeyGreaterThan> {

	@Nonnull
	@Override
	public Formula translate(
		@Nonnull EntityPrimaryKeyGreaterThan constraint,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		final int threshold = constraint.getPrimaryKey();
		if (threshold == Integer.MAX_VALUE) {
			// no integer is strictly greater than MAX_VALUE; guard avoids threshold + 1 overflow
			return EmptyFormula.INSTANCE;
		}
		return new EntityPrimaryKeyRangeFormula(
			threshold + 1,
			Integer.MAX_VALUE,
			filterByVisitor.getSuperSetFormula()
		);
	}
}
