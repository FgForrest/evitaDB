/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.query.sort.entity.translator;

import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.core.query.common.translator.SelfTraversingTranslator;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.sort.ReferenceOrderByVisitor;
import io.evitadb.core.query.sort.entity.EntityNestedQueryComparator;
import io.evitadb.core.query.sort.translator.ReferenceOrderingConstraintTranslator;

import javax.annotation.Nonnull;

/**
 * Entity property comparator sorts {@link ReferenceDecorator} according to a properties on referenced entity object.
 * The comparator creates {@link EntityNestedQueryComparator} that can be passed to {@link FilterByVisitor} to be
 * used when nested query targeting the referenced entity is constructed during reference fetch and thus be initialized
 * in an optimal way.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see EntityNestedQueryComparator
 */
public class EntityPropertyTranslator
	implements ReferenceOrderingConstraintTranslator<EntityProperty>, SelfTraversingTranslator {

	@Override
	public void createComparator(@Nonnull EntityProperty entityProperty, @Nonnull ReferenceOrderByVisitor orderByVisitor) {
		final EntityNestedQueryComparator existingNestedComparator = orderByVisitor.getOrCreateNestedQueryComparator();
		existingNestedComparator.setOrderBy(entityProperty, orderByVisitor.getScopes());
	}

}
