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

package io.evitadb.documentation.graphql;

import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.documentation.constraint.FilterConstraintToJsonConverter;
import io.evitadb.documentation.constraint.JsonConstraint;
import io.evitadb.documentation.constraint.OrderConstraintToJsonConverter;
import io.evitadb.documentation.constraint.RequireConstraintToJsonConverter;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
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
public class GraphQLQueryConverter implements AutoCloseable {

	private static final String CATALOG_NAME = "evita";

	@Nonnull private final Set<Class<? extends Constraint<?>>> allowedRequireConstraints = Set.of(
		FacetGroupsConjunction.class,
		FacetGroupsDisjunction.class,
		FacetGroupsNegation.class,
		PriceType.class
	);
	@Nonnull private final GraphQLInputJsonPrinter inputJsonPrinter = new GraphQLInputJsonPrinter();

	@Nonnull private final EvitaContract evita;

	@Nonnull
	public String convert(@Nonnull Query query) {
		// we need active session to fetch entity schemas from catalog schema when converting constraints
		try (final EvitaSessionContract session = evita.createReadOnlySession(CATALOG_NAME)) {
			final CatalogSchemaContract catalogSchema = session.getCatalogSchema();

			// prepare common converters and builders
			final EntityFetchConverter entityFetchConverter = new EntityFetchConverter();

			// convert query parts
			final String collection = query.getCollection().getEntityType();
			final String header = convertHeader(catalogSchema, query, collection);
			final String outputFields = convertOutputFields(catalogSchema, entityFetchConverter, query);

			return constructQuery(collection, header, outputFields);
		}
	}

	@Override
	public void close() throws Exception {
		evita.close();
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
		Assert.isPremiseValid(
			!rootConstraints.isEmpty(),
			"There are no root constraints, this is strange!"
		);

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
		final HierarchyOfConverter hierarchyOfConverter = new HierarchyOfConverter(catalogSchema, inputJsonPrinter);

		final String entityType = query.getCollection().getEntityType();
		final Require require = query.getRequire();

		final GraphQLOutputFieldsBuilder fieldsBuilder = new GraphQLOutputFieldsBuilder(1);
		if (require == null) {
			fieldsBuilder
				.addObjectField(ResponseDescriptor.RECORD_PAGE, b1 -> b1
					.addObjectField(DataChunkDescriptor.DATA, b2 ->
						entityFetchConverter.convert(catalogSchema, b2, entityType, null)));
		} else {
			// build main entity fields
			Optional.ofNullable(QueryUtils.findConstraint(require, EntityFetch.class, SeparateEntityContentRequireContainer.class))
				.ifPresent(entityFetch -> fieldsBuilder
					.addObjectField(ResponseDescriptor.RECORD_PAGE, b1 -> b1
						.addObjectField(DataChunkDescriptor.DATA, b2 ->
							entityFetchConverter.convert(catalogSchema, b2, entityType, entityFetch))));

			// build extra results
			final List<Constraint<?>> extraResultConstraints = QueryUtils.findConstraints(require, c -> c instanceof ExtraResultRequireConstraint);
			if (!extraResultConstraints.isEmpty()) {
				fieldsBuilder.addObjectField(ResponseDescriptor.EXTRA_RESULTS, extraResultsBuilder -> {
					hierarchyOfConverter.convert(
						extraResultsBuilder,
						entityType,
						QueryUtils.findConstraint(require, HierarchyOfSelf.class),
						QueryUtils.findConstraint(require, HierarchyOfReference.class)
					);

					// todo lho add another extra results support
				});
			}
		}

		return fieldsBuilder.build();
	}

	@Nonnull
	private String constructQuery(@Nonnull String collection, @Nonnull String header, @Nonnull String outputFields) {
		return "{\n" +
			"  query" + StringUtils.toPascalCase(collection) + "(\n" +
			header + "\n" +
			"  ) {\n" +
			outputFields + "\n" +
			"  }\n" +
			"}";
	}
}
