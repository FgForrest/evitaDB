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

package io.evitadb.core.query.sort.price.translator;

import io.evitadb.api.exception.TargetEntityHasNoPricesException;
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.OrderByVisitor;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.price.FilteredPricesSorter;
import io.evitadb.core.query.sort.translator.OrderingConstraintTranslator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.stream.Stream;

/**
 * This implementation of {@link OrderingConstraintTranslator} converts {@link PriceNatural} to {@link Sorter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PriceNaturalTranslator implements OrderingConstraintTranslator<PriceNatural> {

	@Nonnull
	@Override
	public Stream<Sorter> createSorter(@Nonnull PriceNatural priceNatural, @Nonnull OrderByVisitor orderByVisitor) {
		if (orderByVisitor.isEntityTypeKnown()) {
			final EntitySchemaContract schema = orderByVisitor.getSchema();
			Assert.isTrue(
				schema.isWithPrice(),
				() -> new TargetEntityHasNoPricesException(schema.getName())
			);
		}

		// if prefetch happens we need to prefetch prices so that the attribute comparator can work
		orderByVisitor.addRequirementToPrefetch(PriceContent.respectingFilter());
		// are filtered prices used in the filtering?
		final Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors = FormulaFinder.find(
			orderByVisitor.getFilteringFormula(), FilteredPriceRecordAccessor.class, LookUp.SHALLOW
		);

		final Sorter thisSorter;
		if (!filteredPriceRecordAccessors.isEmpty()) {
			// if so, create filtered prices sorter
			thisSorter = new FilteredPricesSorter(
					priceNatural.getOrderDirection(),
					orderByVisitor.getQueryPriceMode(),
					filteredPriceRecordAccessors
			);
		} else {
			// otherwise, we cannot sort the entities by price
			thisSorter = NoSorter.INSTANCE;
		}

		return Stream.of(thisSorter);
	}

}
