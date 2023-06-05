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

package io.evitadb.core.query.sort.attribute.translator;

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.ReferenceProperty;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;

import javax.annotation.Nonnull;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link ReferenceProperty} to {@link Sorter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReferencePropertyTranslator implements OrderingConstraintTranslator<ReferenceProperty>, SelfTraversingTranslator {

	@Override
	public @Nonnull Sorter createSorter(@Nonnull ReferenceProperty orderConstraint, @Nonnull OrderByVisitor orderByVisitor) {
		final String referenceName = orderConstraint.getReferenceName();

		final EntityIndexKey entityIndexKey = new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, referenceName);
		return orderByVisitor.executeInContext(
			orderByVisitor.getQueryContext().getIndex(entityIndexKey),
			orderByVisitor.getProcessingScope().withReferenceSchemaAccessor(referenceName),
			new EntityReferenceAttributeExtractor(referenceName),
			() -> {
				for (OrderConstraint innerConstraint : orderConstraint.getChildren()) {
					innerConstraint.accept(orderByVisitor);
				}
				return orderByVisitor.getLastUsedSorter();
			}
		);
	}

}
