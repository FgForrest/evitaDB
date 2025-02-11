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

package io.evitadb.test.client.query.graphql;

import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.require.*;
import io.evitadb.api.query.visitor.ConstraintCloneVisitor;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.test.client.query.FilterConstraintToJsonConverter;
import io.evitadb.test.client.query.HeadConstraintToJsonConverter;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Converts {@link Query} into GraphQL equivalent query string.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class GraphQLQueryConverter {

	private static final String DEFAULT_CATALOG_NAME = "evita";
	@Nonnull private static final GraphQLInputJsonPrinter INPUT_JSON_PRINTER = new GraphQLInputJsonPrinter();

	@Nonnull private final Set<Class<? extends Constraint<?>>> allowedRequireConstraints = Set.of(
		Require.class,
		FacetGroupsConjunction.class,
		FacetGroupsDisjunction.class,
		FacetGroupsNegation.class,
		PriceType.class
	);

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

			// convert query parts
			final String collection = query.getCollection().getEntityType();
			final String header = convertHeader(catalogSchema, query, collection);
			final String outputFields = convertOutputFields(catalogSchema, query);

			return constructQuery(collection, header, outputFields);
		}
	}

	@Nonnull
	private String convertHeader(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull Query query, @Nonnull String entityType) {
		final HeadConstraintToJsonConverter headConstraintToJsonConverter = new HeadConstraintToJsonConverter(catalogSchema);
		final FilterConstraintToJsonConverter filterConstraintToJsonConverter = new FilterConstraintToJsonConverter(catalogSchema);
		final OrderConstraintToJsonConverter orderConstraintToJsonConverter = new OrderConstraintToJsonConverter(catalogSchema);
		final RequireConstraintToJsonConverter requireConstraintToJsonConverter = new RequireConstraintToJsonConverter(
			catalogSchema,
			allowedRequireConstraints::contains,
			new AtomicReference<>(filterConstraintToJsonConverter),
			new AtomicReference<>(orderConstraintToJsonConverter)
		);

		final List<JsonConstraint> rootConstraints = new ArrayList<>(4);
		if (query.getHead() != null) {
			final HeadConstraint head = ConstraintCloneVisitor.clone(query.getHead(), (visitor, theConstraint) -> theConstraint instanceof Collection ? null : theConstraint);
			if (head != null && (!(head instanceof ConstraintContainer<?> cc) || cc.getChildrenCount() > 0)) {
				rootConstraints.add(
					headConstraintToJsonConverter.convert(new GenericDataLocator(new ManagedEntityTypePointer(entityType)), head)
						.orElseThrow(() -> new IllegalStateException("Root JSON head constraint cannot be null if original query has head constraint."))
				);
			}
		}
		if (query.getFilterBy() != null) {
			rootConstraints.add(
				filterConstraintToJsonConverter.convert(new EntityDataLocator(new ManagedEntityTypePointer(entityType)), query.getFilterBy())
					.orElseThrow(() -> new IllegalStateException("Root JSON filter constraint cannot be null if original query has filter constraint."))
			);
		}
		if (query.getOrderBy() != null) {
			rootConstraints.add(
				orderConstraintToJsonConverter.convert(new GenericDataLocator(new ManagedEntityTypePointer(entityType)), query.getOrderBy())
					.orElseThrow(() -> new IllegalStateException("Root JSON order constraint cannot be null if original query has order constraint."))
			);
		}
		if (query.getRequire() != null) {
			requireConstraintToJsonConverter.convert(new GenericDataLocator(new ManagedEntityTypePointer(entityType)), query.getRequire())
				.filter(it -> !it.value().isEmpty())
				.ifPresent(rootConstraints::add);
		}

		return rootConstraints.stream()
			.filter(Objects::nonNull)
			.map(it -> it.key() + ": " + INPUT_JSON_PRINTER.print(it.value()))
			.collect(Collectors.joining(",\n"))
			.lines()
			.map(it -> "    " + it)
			.collect(Collectors.joining("\n"));
	}

	@Nonnull
	private String convertOutputFields(@Nonnull CatalogSchemaContract catalogSchema,
	                                   @Nonnull Query query) {
		final EntityFetchConverter entityFetchConverter = new EntityFetchConverter(catalogSchema, query);
		final RecordsConverter recordsConverter = new RecordsConverter(catalogSchema, query);
		final FacetSummaryConverter facetSummaryConverter = new FacetSummaryConverter(catalogSchema, query);
		final HierarchyOfConverter hierarchyOfConverter = new HierarchyOfConverter(catalogSchema, query);
		final AttributeHistogramConverter attributeHistogramConverter = new AttributeHistogramConverter(catalogSchema, query);
		final PriceHistogramConverter priceHistogramConverter = new PriceHistogramConverter(catalogSchema, query);
		final QueryTelemetryConverter queryTelemetryConverter = new QueryTelemetryConverter(catalogSchema, query);

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
			final List<Constraint<?>> extraResultConstraints = QueryUtils.findConstraints(require, c -> c instanceof ExtraResultRequireConstraint);
			final QueryTelemetry queryTelemetry = QueryUtils.findConstraint(require, QueryTelemetry.class);

			recordsConverter.convert(fieldsBuilder, entityType, locale, entityFetch, page, strip, !extraResultConstraints.isEmpty());

			// build extra results
			if (!extraResultConstraints.isEmpty() || queryTelemetry != null) {
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

					Optional.ofNullable(queryTelemetry)
						.ifPresent(it -> queryTelemetryConverter.convert(extraResultsBuilder, it));
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
