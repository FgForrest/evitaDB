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

import io.evitadb.api.query.order.AttributeSetExact;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.OrderByVisitor.ProcessingScope;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.AttributeExactSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.dataType.EvitaDataTypes;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link AttributeSetExact} to {@link Sorter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class AttributeSetExactTranslator implements OrderingConstraintTranslator<AttributeSetExact> {

	@Nonnull
	@Override
	public Sorter createSorter(@Nonnull AttributeSetExact attributeSetExact, @Nonnull OrderByVisitor orderByVisitor) {
		final String attributeName = attributeSetExact.getAttributeName();
		final ProcessingScope processingScope = orderByVisitor.getProcessingScope();
		final AttributeSchemaContract attributeSchema = processingScope.getAttributeSchema(attributeName, AttributeTrait.SORTABLE);

		// if prefetch happens we need to prefetch attributes so that the attribute comparator can work
		orderByVisitor.addRequirementToPrefetch(attributeContentAll());
		@SuppressWarnings("SuspiciousToArrayCall")
		final AttributeExactSorter sorter = new AttributeExactSorter(
			attributeName,
			Arrays.stream(attributeSetExact.getAttributeValues())
				.map(it -> EvitaDataTypes.toTargetType(it, attributeSchema.getPlainType()))
				.toArray(Comparable[]::new),
			orderByVisitor.getIndexForSort().getSortIndex(attributeName, orderByVisitor.getLocale())
		);

		final Sorter lastUsedSorter = orderByVisitor.getLastUsedSorter();
		if (lastUsedSorter == null) {
			return sorter;
		} else {
			return lastUsedSorter.andThen(sorter);
		}
	}

}
