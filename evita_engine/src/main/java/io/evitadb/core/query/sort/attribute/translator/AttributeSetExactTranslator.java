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
import io.evitadb.index.EntityIndex;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link AttributeSetExact} to {@link Sorter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class AttributeSetExactTranslator implements OrderingConstraintTranslator<AttributeSetExact> {

	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull AttributeSetExact attributeSetExact, @Nonnull OrderByVisitor orderByVisitor) {
		final String attributeName = attributeSetExact.getAttributeName();
		final ProcessingScope processingScope = orderByVisitor.getProcessingScope();
		final AttributeSchemaContract attributeSchema = processingScope.getAttributeSchema(attributeName, AttributeTrait.SORTABLE);

		// if prefetch happens we need to prefetch attributes so that the attribute comparator can work
		orderByVisitor.addRequirementToPrefetch(attributeContentAll());

		final EntityIndex[] indexForSort = orderByVisitor.getIndexesForSort();
		Assert.isTrue(
			indexForSort.length == 1,
			"Expected exactly one index for sorting, but " + indexForSort.length + " were found."
		);

		return Stream.of(
			new AttributeExactSorter(
				attributeName,
				Arrays.stream(attributeSetExact.getAttributeValues())
					.map(it -> EvitaDataTypes.toTargetType(it, attributeSchema.getPlainType()))
					.toArray(Serializable[]::new),
				indexForSort[0].getSortIndex(attributeName, orderByVisitor.getLocale())
			)
		);
	}

}
