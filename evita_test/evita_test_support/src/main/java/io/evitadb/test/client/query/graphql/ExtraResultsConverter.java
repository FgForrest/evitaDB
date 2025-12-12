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

package io.evitadb.test.client.query.graphql;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.AttributeHistogram;
import io.evitadb.api.query.require.FacetSummary;
import io.evitadb.api.query.require.FacetSummaryOfReference;
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.query.require.PriceHistogram;
import io.evitadb.api.query.require.QueryTelemetry;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.query.require.RequireInScope;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.GraphQLExtraResultsDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.InScopeHeaderDescriptor;
import io.evitadb.test.client.query.graphql.GraphQLOutputFieldsBuilder.Argument;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * Converts extra result constraints into output fields.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class ExtraResultsConverter extends RequireConverter {

	private final FacetSummaryConverter facetSummaryConverter;
	private final HierarchyOfConverter hierarchyOfConverter;
	private final AttributeHistogramConverter attributeHistogramConverter;
	private final PriceHistogramConverter priceHistogramConverter;
	private final QueryTelemetryConverter queryTelemetryConverter;

	public ExtraResultsConverter(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull Query query
	) {
		super(catalogSchema, query);
		this.facetSummaryConverter = new FacetSummaryConverter(catalogSchema, query);
		this.hierarchyOfConverter = new HierarchyOfConverter(catalogSchema, query);
		this.attributeHistogramConverter = new AttributeHistogramConverter(catalogSchema, query);
		this.priceHistogramConverter = new PriceHistogramConverter(catalogSchema, query);
		this.queryTelemetryConverter = new QueryTelemetryConverter(catalogSchema, query);
	}

	public void convert(
		@Nonnull GraphQLOutputFieldsBuilder fieldsBuilder,
		@Nonnull Require require,
		@Nonnull String entityType,
		@Nullable Locale locale
	) {
		fieldsBuilder.addObjectField(
			ResponseDescriptor.EXTRA_RESULTS,
			extraResultsBuilder -> {
				convertFacetSummary(extraResultsBuilder, require, entityType, locale);
				convertHierarchyOf(extraResultsBuilder, require, entityType, locale);
				convertAttributeHistogram(extraResultsBuilder, require, entityType);
				convertPriceHistogram(extraResultsBuilder, require);
				convertQueryTelemetry(extraResultsBuilder, require);

				final List<RequireInScope> inScopeConstraints = QueryUtils.findConstraints(require, RequireInScope.class);
				for (final RequireInScope inScopeConstraint : inScopeConstraints) {
					convertInScope(extraResultsBuilder, inScopeConstraint, entityType, locale);
				}
			}
		);
	}

	private void convertFacetSummary(
		@Nonnull GraphQLOutputFieldsBuilder extraResultsBuilder,
		@Nonnull RequireConstraint container,
		@Nonnull String entityType,
		@Nullable Locale locale
	) {
		this.facetSummaryConverter.convert(
			extraResultsBuilder,
			entityType,
			locale,
			QueryUtils.findConstraint(container, FacetSummary.class, RequireInScope.class),
			QueryUtils.findConstraints(container, FacetSummaryOfReference.class, RequireInScope.class)
		);
	}

	private void convertHierarchyOf(
		@Nonnull GraphQLOutputFieldsBuilder extraResultsBuilder,
		@Nonnull RequireConstraint container,
		@Nonnull String entityType,
		@Nullable Locale locale
	) {
		this.hierarchyOfConverter.convert(
			extraResultsBuilder,
			entityType,
			locale,
			QueryUtils.findConstraint(container, HierarchyOfSelf.class, RequireInScope.class),
			QueryUtils.findConstraint(container, HierarchyOfReference.class, RequireInScope.class)
		);
	}

	private void convertAttributeHistogram(
		@Nonnull GraphQLOutputFieldsBuilder extraResultsBuilder,
		@Nonnull RequireConstraint container,
		@Nonnull String entityType
	) {
		Optional.of(QueryUtils.findConstraints(container, AttributeHistogram.class, RequireInScope.class))
			.ifPresent(attributeHistograms -> this.attributeHistogramConverter.convert(
				extraResultsBuilder,
				entityType,
				attributeHistograms
			));
	}

	private void convertPriceHistogram(@Nonnull GraphQLOutputFieldsBuilder extraResultsBuilder, @Nonnull RequireConstraint container) {
		ofNullable(QueryUtils.findConstraint(container, PriceHistogram.class, RequireInScope.class))
			.ifPresent(priceHistogram -> this.priceHistogramConverter.convert(extraResultsBuilder, priceHistogram));
	}

	private void convertQueryTelemetry(@Nonnull GraphQLOutputFieldsBuilder extraResultsBuilder, @Nonnull RequireConstraint container) {
		ofNullable(QueryUtils.findConstraint(container, QueryTelemetry.class, RequireInScope.class))
			.ifPresent(it -> this.queryTelemetryConverter.convert(extraResultsBuilder, it));
	}

	private void convertInScope(
		@Nonnull GraphQLOutputFieldsBuilder extraResultsBuilder,
		@Nonnull RequireInScope scopeContainer,
		@Nonnull String entityType,
		@Nullable Locale locale
	) {
		extraResultsBuilder.addObjectField(
			GraphQLExtraResultsDescriptor.IN_SCOPE.name(),
			inScopeBuilder -> {
				convertFacetSummary(inScopeBuilder, scopeContainer, entityType, locale);
				convertHierarchyOf(inScopeBuilder, scopeContainer, entityType, locale);
				convertAttributeHistogram(inScopeBuilder, scopeContainer, entityType);
				convertPriceHistogram(inScopeBuilder, scopeContainer);
			},
			(offset, multipleArguments) -> new Argument(
				InScopeHeaderDescriptor.SCOPE,
				offset,
				multipleArguments,
				scopeContainer.getScope()
			)
		);
	}
}
