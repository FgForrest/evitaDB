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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.test.client.query.FilterConstraintToJsonConverter;
import io.evitadb.test.client.query.OrderConstraintToJsonConverter;
import io.evitadb.test.client.query.RequireConstraintToJsonConverter;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Common ancestor for GraphQL require constraint converters with helper methods and common converters.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
abstract class RequireConverter {

	@Nonnull protected final CatalogSchemaContract catalogSchema;
	@Nonnull protected final JsonNodeFactory jsonNodeFactory;

	@Nonnull protected final GraphQLInputJsonPrinter inputJsonPrinter;
	@Nonnull protected final FilterConstraintToJsonConverter filterConstraintToJsonConverter;
	@Nonnull protected final OrderConstraintToJsonConverter orderConstraintToJsonConverter;
	@Nonnull protected final RequireConstraintToJsonConverter requireConstraintToJsonConverter;

	public RequireConverter(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull GraphQLInputJsonPrinter inputJsonPrinter) {
		this.catalogSchema = catalogSchema;
		this.inputJsonPrinter = inputJsonPrinter;
		this.jsonNodeFactory = new JsonNodeFactory(true);
		this.filterConstraintToJsonConverter = new FilterConstraintToJsonConverter(catalogSchema);
		this.orderConstraintToJsonConverter = new OrderConstraintToJsonConverter(catalogSchema);
		this.requireConstraintToJsonConverter = new RequireConstraintToJsonConverter(catalogSchema);
	}

	@Nonnull
	protected Optional<String> convertFilterConstraint(@Nonnull DataLocator dataLocator,
	                                                   @Nonnull FilterConstraint filterConstraint,
	                                                   int offset) {
		return filterConstraintToJsonConverter.convert(dataLocator, filterConstraint)
			.map(it -> inputJsonPrinter.print(offset, it.value()).stripLeading());
	}

	@Nonnull
	protected Optional<String> convertOrderConstraint(@Nonnull DataLocator dataLocator,
	                                                  @Nonnull OrderConstraint orderConstraint,
	                                                  int offset) {
		return orderConstraintToJsonConverter.convert(dataLocator, orderConstraint)
			.map(it -> inputJsonPrinter.print(offset, it.value()).stripLeading());
	}

	@Nonnull
	protected Optional<String> convertRequireConstraint(@Nonnull DataLocator dataLocator,
	                                                    @Nonnull RequireConstraint requireConstraint,
	                                                    int offset) {
		return requireConstraintToJsonConverter.convert(dataLocator, requireConstraint)
			.map(it -> inputJsonPrinter.print(offset, it.value()).stripLeading());
	}
}
