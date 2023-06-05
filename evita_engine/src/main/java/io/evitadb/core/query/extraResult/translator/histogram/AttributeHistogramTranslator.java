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

package io.evitadb.core.query.extraResult.translator.histogram;

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.query.require.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramProducer;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link AttributeHistogram} to {@link Histogram}.
 * The producer instance has all pointer necessary to compute result. All operations in this translator are relatively
 * cheap comparing to final result computation, that is deferred to {@link ExtraResultProducer#fabricate(List)}
 * method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AttributeHistogramTranslator implements RequireConstraintTranslator<AttributeHistogram> {

	@Override
	public ExtraResultProducer apply(AttributeHistogram attributeHistogram, ExtraResultPlanningVisitor extraResultPlanner) {
		// initialize basic data necessary for th computation
		final Locale language = extraResultPlanner.getEvitaRequest().getLocale();
		final EntitySchemaContract schema = extraResultPlanner.getSchema();
		final String[] attributeNames = attributeHistogram.getAttributeNames();
		final int bucketCount = attributeHistogram.getRequestedBucketCount();

		// find user filters that enclose variable user defined part
		final Set<Formula> userFilters = extraResultPlanner.getUserFilteringFormula();
		// in them find all AttributeFormulas and create index for them
		final Map<String, List<AttributeFormula>> attributeFormulas = userFilters.stream()
			.flatMap(it -> FormulaFinder.find(it, AttributeFormula.class, LookUp.SHALLOW).stream())
			.collect(Collectors.groupingBy(AttributeFormula::getAttributeName));

		// get all indexes that should be used for query execution
		final TargetIndexes indexSetToUse = extraResultPlanner.getIndexSetToUse();
		// find existing AttributeHistogramProducer for potential reuse
		AttributeHistogramProducer attributeHistogramProducer = extraResultPlanner.findExistingProducer(AttributeHistogramProducer.class);
		for (String attributeName : attributeNames) {
			// if there was no producer ready, create new one
			if (attributeHistogramProducer == null) {
				attributeHistogramProducer = new AttributeHistogramProducer(
					schema.getName(), extraResultPlanner.getQueryContext(),
					bucketCount,
					extraResultPlanner.getFilteringFormula()
				);
			}

			// collect all FilterIndexes for requested attribute and requested language
			final List<FilterIndex> attributeIndexes = indexSetToUse.getIndexesOfType(EntityIndex.class)
				.stream()
				.map(it -> it.getFilterIndex(attributeName, language))
				.filter(Objects::nonNull)
				.collect(Collectors.toList());

			// retrieve attribute schema for requested attribute
			final AttributeSchemaContract attributeSchema = getAttributeSchema(schema, attributeName);

			// register computational lambda for producing attribute histogram
			attributeHistogramProducer.addAttributeHistogramRequest(
				attributeSchema, attributeIndexes, attributeFormulas.get(attributeName)
			);
		}
		return attributeHistogramProducer;
	}

	@Nonnull
	private AttributeSchemaContract getAttributeSchema(@Nonnull EntitySchemaContract schema, @Nonnull String attributeName) {
		final AttributeSchemaContract attributeSchema = schema.getAttribute(attributeName)
			.orElseThrow(() -> new AttributeNotFoundException(attributeName, schema));
		Assert.isTrue(
			Number.class.isAssignableFrom(attributeSchema.getPlainType()),
			"Attribute `" + attributeName + "` must be a number in order to compute histogram!"
		);
		Assert.isTrue(
			attributeSchema.isFilterable(),
			() -> new AttributeNotFilterableException(attributeName, "\"filterable\"", schema)
		);
		return attributeSchema;
	}

}
