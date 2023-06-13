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

package io.evitadb.documentation.rest;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.documentation.constraint.FilterConstraintToJsonConverter;
import io.evitadb.documentation.constraint.JsonConstraint;
import io.evitadb.documentation.constraint.OrderConstraintToJsonConverter;
import io.evitadb.documentation.constraint.RequireConstraintToJsonConverter;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts {@link Query} into REST equivalent query string.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class RestQueryConverter implements AutoCloseable {

	private static final String CATALOG_NAME = "evita";

	@Nonnull private final RestInputJsonPrinter inputJsonPrinter = new RestInputJsonPrinter();
	@Nonnull private final JsonNodeFactory jsonNodeFactory = new JsonNodeFactory(true);

	@Nonnull private final EvitaContract evita;

	@Nonnull
	public String convert(@Nonnull Query query) {
		// we need active session to fetch entity schemas from catalog schema when converting constraints
		try (final EvitaSessionContract session = evita.createReadOnlySession(CATALOG_NAME)) {
			final CatalogSchemaContract catalogSchema = session.getCatalogSchema();

			// convert query parts
			final String collection = query.getCollection().getEntityType();
			final String header = resolveHeader(collection);
			final String body = convertBody(catalogSchema, query, collection);

			return constructRequest(header, body);
		}
	}

	@Override
	public void close() throws Exception {
		evita.close();
	}

	@Nonnull
	private String resolveHeader(@Nonnull String entityType) {
		return "/rest/" + CATALOG_NAME + "/" + StringUtils.toKebabCase(entityType) + "/query";
	}

	@Nonnull
	private String convertBody(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull Query query, @Nonnull String entityType) {
		final FilterConstraintToJsonConverter filterConstraintToJsonConverter = new FilterConstraintToJsonConverter(catalogSchema);
		final OrderConstraintToJsonConverter orderConstraintToJsonConverter = new OrderConstraintToJsonConverter(catalogSchema);
		final RequireConstraintToJsonConverter requireConstraintToJsonConverter = new RequireConstraintToJsonConverter(catalogSchema);

		final ObjectNode body = jsonNodeFactory.objectNode();
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

		rootConstraints.forEach(c -> body.putIfAbsent(c.key(), c.value()));
		return inputJsonPrinter.print(body);
	}

	@Nonnull
	private String constructRequest(@Nonnull String header, @Nonnull String query) {
		return "POST " + header + "\n\n" + query;
	}
}
