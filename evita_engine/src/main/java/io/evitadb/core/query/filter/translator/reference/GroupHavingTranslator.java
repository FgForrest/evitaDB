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
import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndexType;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link GroupHaving} to {@link AbstractFormula}.
 * The translator filters entities by evaluating a nested query against the group entity type of a reference and
 * then translating the matched group entity primary keys back to owner entity primary keys using group-specific
 * indexes ({@link EntityIndexType#REFERENCED_GROUP_ENTITY_TYPE} / {@link EntityIndexType#REFERENCED_GROUP_ENTITY}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class GroupHavingTranslator implements FilteringConstraintTranslator<GroupHaving>, SelfTraversingTranslator {

	@Nonnull
	@Override
	public Formula translate(@Nonnull GroupHaving groupHaving, @Nonnull FilterByVisitor filterByVisitor) {
		final EntitySchemaContract entitySchema = Objects.requireNonNull(
			filterByVisitor.getProcessingScope().getEntitySchema()
		);
		final ReferenceSchemaContract referenceSchema = filterByVisitor
			.getReferenceSchema()
			.orElseThrow(() -> new EvitaInvalidUsageException(
				"Filtering constraint `" + groupHaving + "` needs to be placed within `ReferenceHaving` " +
					"parent constraint that allows resolving the entity `" +
					entitySchema.getName() + "` referenced entity type."
			));
		final String referencedGroupType = referenceSchema.getReferencedGroupType();
		Assert.notNull(
			referencedGroupType,
			() -> "Filtering constraint `" + groupHaving + "` targets reference `" +
				referenceSchema.getName() + "` that does not reference any group type."
		);
		Assert.isTrue(
			referenceSchema.isReferencedGroupTypeManaged(),
			() -> "Filtering constraint `" + groupHaving + "` targets group entity " +
				"`" + referencedGroupType + "` that is not managed by evitaDB."
		);
		final FilterConstraint filterConstraint = groupHaving.getChild();
		if (filterConstraint != null) {
			final Supplier<String> nestedQueryDescription = () ->
				"filtering reference `" + referenceSchema.getName() +
					"` by group entity `" + referencedGroupType + "` having: " + filterConstraint;

			return HavingTranslatorHelper.translateHavingConstraint(
				filterConstraint,
				filterByVisitor,
				entitySchema,
				referenceSchema,
				referencedGroupType,
				referenceSchema.isReferencedGroupTypeManaged(),
				filterByVisitor::getReferencedGroupEntityIndexes,
				nestedQueryDescription
			);
		}
		return EmptyFormula.INSTANCE;
	}

}
