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

package io.evitadb.core.query.extraResult.translator.histogram;

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.query.require.AttributeHistogram;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor;
import io.evitadb.core.query.extraResult.ExtraResultPlanningVisitor.ProcessingScope;
import io.evitadb.core.query.extraResult.ExtraResultProducer;
import io.evitadb.core.query.extraResult.translator.RequireConstraintTranslator;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramProducer;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This implementation of {@link RequireConstraintTranslator} converts {@link AttributeHistogram} to {@link Histogram}.
 * The producer instance has all pointer necessary to compute result. All operations in this translator are relatively
 * cheap comparing to final result computation, that is deferred to {@link ExtraResultProducer#fabricate(io.evitadb.core.query.QueryExecutionContext)}
 * method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AttributeHistogramTranslator implements RequireConstraintTranslator<AttributeHistogram> {

	@Nonnull
	@Override
	public ExtraResultProducer createProducer(@Nonnull AttributeHistogram attributeHistogram, @Nonnull ExtraResultPlanningVisitor extraResultPlanner) {
		// initialize basic data necessary for th computation
		final Locale language = extraResultPlanner.getEvitaRequest().getLocale();
		final EntitySchemaContract schema = extraResultPlanner.getSchema();
		final String[] attributeNames = attributeHistogram.getAttributeNames();
		final int bucketCount = attributeHistogram.getRequestedBucketCount();
		final HistogramBehavior behavior = attributeHistogram.getBehavior();

		// get scopes the histogram will be created from
		final ProcessingScope processingScope = extraResultPlanner.getProcessingScope();
		final Set<Scope> scopes = processingScope.getScopes();

		// get all indexes that should be used for query execution
		final TargetIndexes<?> indexSetToUse = extraResultPlanner.getIndexSetToUse();
		// find existing AttributeHistogramProducer for potential reuse
		AttributeHistogramProducer attributeHistogramProducer = extraResultPlanner.findExistingProducer(AttributeHistogramProducer.class);
		for (String attributeName : attributeNames) {
			// retrieve attribute schema for requested attribute
			final AttributeSchemaContract attributeSchema = getAttributeSchema(schema, scopes, attributeName);

			// if there was no producer ready, create new one
			if (attributeHistogramProducer == null) {
				attributeHistogramProducer = new AttributeHistogramProducer(
					bucketCount,
					behavior,
					extraResultPlanner.getFilteringFormula()
				);
			}

			// collect all FilterIndexes for requested attribute and requested language
			final ReferenceSchemaContract referenceSchema = processingScope.getReferenceSchema().orElse(null);
			final List<FilterIndex> attributeIndexes = indexSetToUse.getIndexStream(EntityIndex.class)
				.map(it -> it.getFilterIndex(referenceSchema, attributeSchema, language))
				.filter(Objects::nonNull)
				.collect(Collectors.toList());

			// register computational lambda for producing attribute histogram — the producer peels every
			// AttributeRangeCarrierFormula inside userFilter at fabrication time via UserFilterRelaxer, so there is
			// no need to forward the per-attribute formula set collected from the user filter tree anymore
			attributeHistogramProducer.addAttributeHistogramRequest(
				attributeSchema,
				FilterIndex.getComparator(
					AttributeIndex.createAttributeKey(
						referenceSchema,
						attributeSchema,
						extraResultPlanner.getLocale()
					),
					attributeSchema.getPlainType()
				),
				attributeIndexes
			);
		}

		Assert.isPremiseValid(
			attributeHistogramProducer != null,
			"AttributeHistogramProducer must be initialized!"
		);
		return attributeHistogramProducer;
	}

	@Nonnull
	private static AttributeSchemaContract getAttributeSchema(@Nonnull EntitySchemaContract schema, @Nonnull Set<Scope> scopes, @Nonnull String attributeName) {
		final AttributeSchemaContract attributeSchema = schema.getAttribute(attributeName)
			.orElseThrow(() -> new AttributeNotFoundException(attributeName, schema));
		Assert.isTrue(
			Number.class.isAssignableFrom(attributeSchema.getPlainType()),
			"Attribute `" + attributeName + "` must be a number in order to compute histogram!"
		);
		// verify that the attribute is indexed in all required scopes
		for (Scope scope : scopes) {
			Assert.isTrue(
				attributeSchema.isFilterableInScope(scope),
				() -> new AttributeNotFilterableException(attributeName, "filterable in scope `" + scope.name() + "`", schema)
			);
		}
		return attributeSchema;
	}

}
