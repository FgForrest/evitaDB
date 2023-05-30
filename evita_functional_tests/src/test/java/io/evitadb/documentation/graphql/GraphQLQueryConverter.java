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

import com.fasterxml.jackson.core.JsonProcessingException;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.FacetGroupsConjunction;
import io.evitadb.api.query.require.FacetGroupsDisjunction;
import io.evitadb.api.query.require.FacetGroupsNegation;
import io.evitadb.api.query.require.PriceType;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
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

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
public class GraphQLQueryConverter implements AutoCloseable {

	@Nonnull private final Set<Class<? extends Constraint<?>>> allowedRequireConstraints = Set.of(
		FacetGroupsConjunction.class,
		FacetGroupsDisjunction.class,
		FacetGroupsNegation.class,
		PriceType.class
	);

	@Nonnull private final EvitaContract evita;
	@Nonnull private final GraphQLInputJsonPrinter inputJsonPrinter;
//	@Nonnull private final FilterConstraintToJsonConverter filterConstraintToJsonConverter;
//	@Nonnull private final OrderConstraintToJsonConverter orderConstraintToJsonConverter;
//	@Nonnull private final RequireConstraintToJsonConverter requireConstraintToJsonConverter;

	public GraphQLQueryConverter(@Nonnull EvitaContract evita) {
		this.inputJsonPrinter = new GraphQLInputJsonPrinter();

		this.evita = evita;
		/*this.evita = new EvitaClient(
			EvitaClientConfiguration.builder()
				.host("demo.evitadb.io")
				.port(5556)
				// demo server provides Let's encrypt trusted certificate
				.useGeneratedCertificate(false)
				// the client will not be mutually verified by the server side
				.mtlsEnabled(false)
				.build()
		);*/
	}

	@Nonnull
	public String convert(@Nonnull Query query) {
		// we need active session to fetch entity schemas from catalog schema when converting constraints
		try (final EvitaSessionContract session = evita.createReadOnlySession("evita")) {
			final CatalogSchemaContract catalogSchema = session.getCatalogSchema();

			final FilterConstraintToJsonConverter filterConstraintToJsonConverter = new FilterConstraintToJsonConverter(catalogSchema);
			final OrderConstraintToJsonConverter orderConstraintToJsonConverter = new OrderConstraintToJsonConverter(catalogSchema);
			final RequireConstraintToJsonConverter requireConstraintToJsonConverter = new RequireConstraintToJsonConverter(
				catalogSchema,
				allowedRequireConstraints::contains
			);

			final String collection = query.getCollection().getEntityType();
			final String header = convertHeader(filterConstraintToJsonConverter,
				orderConstraintToJsonConverter,
				requireConstraintToJsonConverter,query, collection);
			final String output = convertOutputFields(query);

			return "{\n" +
				"  query" + StringUtils.toPascalCase(collection) + "(\n" +
				header + "\n" +
				"  ) {\n" +
				output + "\n" +
				"  }\n" +
				"}";
		}
	}

	@Override
	public void close() throws Exception {
		evita.close();
	}

	@Nonnull
	private String convertHeader(FilterConstraintToJsonConverter filterConstraintToJsonConverter,
	                             OrderConstraintToJsonConverter orderConstraintToJsonConverter,
	                             RequireConstraintToJsonConverter requireConstraintToJsonConverter,
	                             @Nonnull Query query,
	                             @Nonnull String entityType) {
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
	private String convertOutputFields(@Nonnull Query query) {
		final Require require = query.getRequire();
		Assert.isPremiseValid(
			require != null,
			"Missing require container to build output fields from."
		);

		final GraphQLOutputFieldsBuilder fieldsBuilder = new GraphQLOutputFieldsBuilder(1);

		// build main entity fields
		fieldsBuilder
			.addObjectField(ResponseDescriptor.RECORD_PAGE, b1 -> b1
				.addObjectField(DataChunkDescriptor.DATA, b2 ->
					new EntityFetchGraphQLFieldsBuilder().build(
						b2,
						QueryUtils.findConstraint(require, EntityFetch.class, SeparateEntityContentRequireContainer.class)
					)));

		// build extra results
		// todo lho implement

		return fieldsBuilder.build();
	}


}
