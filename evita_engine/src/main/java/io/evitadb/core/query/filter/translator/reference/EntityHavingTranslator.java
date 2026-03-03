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

package io.evitadb.core.query.filter.translator.reference;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link EntityHaving} to
 * {@link AbstractFormula}.
 * The translator filters entities by evaluating a nested query against the referenced entity type of a reference
 * and then translating the matched referenced entity primary keys back to owner entity primary keys.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityHavingTranslator implements FilteringConstraintTranslator<EntityHaving>, SelfTraversingTranslator {

	@Nonnull
	@Override
	public Formula translate(@Nonnull EntityHaving entityHaving, @Nonnull FilterByVisitor filterByVisitor) {
		final EntitySchemaContract entitySchema = Objects.requireNonNull(
			filterByVisitor.getProcessingScope().getEntitySchema()
		);
		final ReferenceSchemaContract referenceSchema = filterByVisitor
			.getReferenceSchema()
			.orElseThrow(() -> new EvitaInvalidUsageException(
				"Filtering constraint `" + entityHaving + "` needs to be placed within `ReferenceHaving` " +
					"parent constraint that allows resolving the entity `" +
					entitySchema.getName() + "` referenced entity type."
			));
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		Assert.isTrue(
			referenceSchema.isReferencedEntityTypeManaged(),
			() -> "Filtering constraint `" + entityHaving + "` targets entity " +
				"`" + referencedEntityType + "` that is not managed by evitaDB."
		);
		final FilterConstraint filterConstraint = entityHaving.getChild();
		if (filterConstraint != null) {
			final Supplier<String> nestedQueryDescription = () ->
				"filtering reference `" + referenceSchema.getName() +
					"` by entity `" + referencedEntityType + "` having: " + filterConstraint;

			return HavingTranslatorHelper.translateHavingConstraint(
				filterConstraint,
				filterByVisitor,
				entitySchema,
				referenceSchema,
				referencedEntityType,
				referenceSchema.isReferencedEntityTypeManaged(),
				filterByVisitor::getReferencedEntityIndexes,
				nestedQueryDescription
			);
		}
		return EmptyFormula.INSTANCE;
	}

}
