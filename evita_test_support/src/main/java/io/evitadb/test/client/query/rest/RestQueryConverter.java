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

package io.evitadb.test.client.query.rest;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.head.Head;
import io.evitadb.api.query.visitor.ConstraintCloneVisitor;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.test.client.query.FilterConstraintToJsonConverter;
import io.evitadb.test.client.query.HeadConstraintToJsonConverter;
import io.evitadb.test.client.query.JsonConstraint;
import io.evitadb.test.client.query.OrderConstraintToJsonConverter;
import io.evitadb.test.client.query.RequireConstraintToJsonConverter;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Converts {@link Query} into REST equivalent query string.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class RestQueryConverter implements AutoCloseable {

	private static final String DEFAULT_CATALOG_NAME = "evita";

	@Nonnull private final RestInputJsonPrinter inputJsonPrinter = new RestInputJsonPrinter();
	@Nonnull private final JsonNodeFactory jsonNodeFactory = new JsonNodeFactory(true);

	@Nullable private final EvitaContract evita;

	public RestQueryConverter() {
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
			final String header = resolveHeader(catalogName, collection);
			final String body = convertBody(catalogSchema, query, collection);

			return constructRequest(header, body);
		}
	}

	@Override
	public void close() throws Exception {
		evita.close();
	}

	@Nonnull
	private String resolveHeader(@Nonnull String catalogName, @Nonnull String entityType) {
		return "/rest/" + catalogName + "/" + entityType + "/query";
	}

	@Nonnull
	private String convertBody(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull Query query, @Nonnull String entityType) {
		final HeadConstraintToJsonConverter headConstraintToJsonConverter = new HeadConstraintToJsonConverter(catalogSchema);
		final FilterConstraintToJsonConverter filterConstraintToJsonConverter = new FilterConstraintToJsonConverter(catalogSchema);
		final OrderConstraintToJsonConverter orderConstraintToJsonConverter = new OrderConstraintToJsonConverter(catalogSchema);
		final RequireConstraintToJsonConverter requireConstraintToJsonConverter = new RequireConstraintToJsonConverter(
			catalogSchema,
			new AtomicReference<>(filterConstraintToJsonConverter),
			new AtomicReference<>(orderConstraintToJsonConverter)
		);

		final ObjectNode body = jsonNodeFactory.objectNode();
		final List<JsonConstraint> rootConstraints = new ArrayList<>(4);
		if (query.getHead() != null) {
			final HeadConstraint head = ConstraintCloneVisitor.clone(query.getHead(), (visitor, theConstraint) -> theConstraint instanceof Collection ? null : theConstraint);
			if (head != null) { // the head might become `null` if there is only a `Collection` constraint, which we don't want to propagate
				rootConstraints.add(
					headConstraintToJsonConverter.convert(new GenericDataLocator(
						new ManagedEntityTypePointer(entityType)),
						head instanceof Head ? head : new Head(head) // we need the wrapper for the REST. REST expects it to be nested in the `head` constraint
					)
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
