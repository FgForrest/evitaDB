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

package io.evitadb.test.client.query.graphql;

import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.test.client.query.FilterConstraintToJsonConverter;
import io.evitadb.test.client.query.JsonConstraint;
import io.evitadb.test.client.query.OrderConstraintToJsonConverter;
import io.evitadb.test.client.query.RequireConstraintToJsonConverter;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converts {@link Query} into GraphQL equivalent query string.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class GraphQLQueryConverter {

	private static final String DEFAULT_CATALOG_NAME = "evita";

	@Nonnull private final Set<Class<? extends Constraint<?>>> allowedRequireConstraints = Set.of(
		FacetGroupsConjunction.class,
		FacetGroupsDisjunction.class,
		FacetGroupsNegation.class,
		PriceType.class
	);
	@Nonnull private final GraphQLInputJsonPrinter inputJsonPrinter = new GraphQLInputJsonPrinter();

	@Nullable private final EvitaContract evita;

	public GraphQLQueryConverter() {
		this.evita = null;
	}

	@Nonnull
	public String convert(@Nonnull Query query) {
		Assert.isPremiseValid(this.evita != null, "No evitaDB instance was provided.");
		return convert(this.evita, DEFAULT_CATALOG_NAME, query);
	}

	@Nonnull
	public String convert(@Nonnull EvitaContract evita, @Nonnull String catalogName, @Nonnull Query query) {
		// we need active session to fetch entity schemas from catalog schema when converting constraints
		try (final EvitaSessionContract session = evita.createReadOnlySession(catalogName)) {
			final CatalogSchemaContract catalogSchema = session.getCatalogSchema();

			// prepare common converters and builders
			final EntityFetchConverter entityFetchConverter = new EntityFetchConverter(catalogSchema, inputJsonPrinter);

			// convert query parts
			final String collection = query.getCollection().getEntityType();
			final String header = convertHeader(catalogSchema, query, collection);
			final String outputFields = convertOutputFields(catalogSchema, entityFetchConverter, query);

			return constructQuery(collection, header, outputFields);
		}
	}

	@Nonnull
	private String convertHeader(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull Query query, @Nonnull String entityType) {
		final FilterConstraintToJsonConverter filterConstraintToJsonConverter = new FilterConstraintToJsonConverter(catalogSchema);
		final OrderConstraintToJsonConverter orderConstraintToJsonConverter = new OrderConstraintToJsonConverter(catalogSchema);
		final RequireConstraintToJsonConverter requireConstraintToJsonConverter = new RequireConstraintToJsonConverter(
			catalogSchema,
			allowedRequireConstraints::contains
		);

		final List<JsonConstraint> rootConstraints = new ArrayList<>(3);
		if (query.getFilterBy() != null) {
			rootConstraints.add(
				filterConstraintToJsonConverter.convert(new EntityDataLocator(entityType), query.getFilterBy())
					.orElseThrow(() -> new IllegalStateException("Root JSON filter constraint cannot be null if original query has filter constraint."))
			);
		}
		if (query.getOrderBy() != null) {
			rootConstraints.add(
				orderConstraintToJsonConverter.convert(new GenericDataLocator(entityType), query.getOrderBy())
					.orElseThrow(() -> new IllegalStateException("Root JSON order constraint cannot be null if original query has order constraint."))
			);
		}
		if (query.getRequire() != null) {
			requireConstraintToJsonConverter.convert(new GenericDataLocator(entityType), query.getRequire())
				.ifPresent(rootConstraints::add);
		}

		return rootConstraints.stream()
			.filter(Objects::nonNull)
			.map(it -> it.key() + ": " + inputJsonPrinter.print(it.value()))
			.collect(Collectors.joining(",\n"))
			.lines()
			.map(it -> "    " + it)
			.collect(Collectors.joining("\n"));
	}

	@Nonnull
	private String convertOutputFields(@Nonnull CatalogSchemaContract catalogSchema,
	                                   @Nonnull EntityFetchConverter entityFetchConverter,
	                                   @Nonnull Query query) {
		final RecordsConverter recordsConverter = new RecordsConverter(catalogSchema, inputJsonPrinter);
		final FacetSummaryConverter facetSummaryConverter = new FacetSummaryConverter(catalogSchema, inputJsonPrinter);
		final HierarchyOfConverter hierarchyOfConverter = new HierarchyOfConverter(catalogSchema, inputJsonPrinter);
		final AttributeHistogramConverter attributeHistogramConverter = new AttributeHistogramConverter(catalogSchema, inputJsonPrinter);
		final PriceHistogramConverter priceHistogramConverter = new PriceHistogramConverter(catalogSchema, inputJsonPrinter);
		final QueryTelemetryConverter queryTelemetryConverter = new QueryTelemetryConverter(catalogSchema, inputJsonPrinter);

		final String entityType = query.getCollection().getEntityType();
		final Locale locale = Optional.ofNullable(query.getFilterBy())
			.map(f -> QueryUtils.findConstraint(f, EntityLocaleEquals.class, SeparateEntityContentRequireContainer.class))
			.map(EntityLocaleEquals::getLocale)
			.orElse(null);
		final Require require = query.getRequire();

		final GraphQLOutputFieldsBuilder fieldsBuilder = new GraphQLOutputFieldsBuilder(1);
		if (require == null) {
			fieldsBuilder
				.addObjectField(ResponseDescriptor.RECORD_PAGE, b1 -> b1
					.addObjectField(DataChunkDescriptor.DATA, b2 ->
						entityFetchConverter.convert(b2, entityType, locale, null)));
		} else {
			// builds records
			final EntityFetch entityFetch = QueryUtils.findConstraint(require, EntityFetch.class, SeparateEntityContentRequireContainer.class);
			final Page page = QueryUtils.findConstraint(require, Page.class, SeparateEntityContentRequireContainer.class);
			final Strip strip = QueryUtils.findConstraint(require, Strip.class, SeparateEntityContentRequireContainer.class);
			recordsConverter.convert(fieldsBuilder, entityType, locale, entityFetch, page, strip);

			// build extra results
			final List<Constraint<?>> extraResultConstraints = QueryUtils.findConstraints(require, c -> c instanceof ExtraResultRequireConstraint);
			if (!extraResultConstraints.isEmpty()) {
				fieldsBuilder.addObjectField(ResponseDescriptor.EXTRA_RESULTS, extraResultsBuilder -> {
					facetSummaryConverter.convert(
						extraResultsBuilder,
						entityType,
						locale,
						QueryUtils.findConstraint(require, FacetSummary.class),
						QueryUtils.findConstraints(require, FacetSummaryOfReference.class)
					);

					hierarchyOfConverter.convert(
						extraResultsBuilder,
						entityType,
						locale,
						QueryUtils.findConstraint(require, HierarchyOfSelf.class),
						QueryUtils.findConstraint(require, HierarchyOfReference.class)
					);

					Optional.of(QueryUtils.findConstraints(require, AttributeHistogram.class))
						.ifPresent(attributeHistograms -> attributeHistogramConverter.convert(
							extraResultsBuilder,
							entityType,
							attributeHistograms
						));

					Optional.ofNullable(QueryUtils.findConstraint(require, PriceHistogram.class))
						.ifPresent(priceHistogram -> priceHistogramConverter.convert(extraResultsBuilder, priceHistogram));

					Optional.ofNullable(QueryUtils.findConstraint(require, QueryTelemetry.class))
						.ifPresent(queryTelemetry -> queryTelemetryConverter.convert(extraResultsBuilder, queryTelemetry));
				});
			}
		}

		return fieldsBuilder.build();
	}

	@Nonnull
	private String constructQuery(@Nonnull String collection, @Nonnull String header, @Nonnull String outputFields) {
		final String arguments = header.isEmpty() ? "" : "(\n" + header + "\n  )";
		return "{\n" +
			"  query" + StringUtils.toPascalCase(collection) + arguments + " {\n" +
			outputFields + "\n" +
			"  }\n" +
			"}";
	}
}
