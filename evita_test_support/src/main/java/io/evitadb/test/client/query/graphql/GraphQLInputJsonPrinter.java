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

import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.FacetGroupRelationLevel;
import io.evitadb.api.query.require.FacetRelationType;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Prints GraphQL input JSONs correctly formatted to spec.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GraphQLInputJsonPrinter {

	private final static Pattern LOCALE_PATTERN = Pattern.compile("\"(cs-CZ|en-US|de-DE|cs|en|de)\"");
	private final static Pattern CURRENCY_PATTERN = Pattern.compile("\"(CZK|EUR|USD|GBP)\"");

	private final static Set<Class<? extends Enum<?>>> KNOWN_ENUMS = Set.of(
		AttributeSpecialValue.class,
		OrderDirection.class,
		EmptyHierarchicalEntityBehaviour.class,
		FacetStatisticsDepth.class,
		PriceContentMode.class,
		QueryPriceMode.class,
		StatisticsBase.class,
		StatisticsType.class,
		Scope.class,
		FacetGroupRelationLevel.class,
		FacetRelationType.class,
		TraversalMode.class
	);

	@Nonnull private final ObjectWriter constraintWriter;
	@Nonnull private final Pattern knownEnumItemsPattern;

	public GraphQLInputJsonPrinter() {
		final ObjectMapper objectMapper = new ObjectMapper(new JsonFactoryBuilder()
			.disable(JsonWriteFeature.QUOTE_FIELD_NAMES)
			.build());
		this.constraintWriter = objectMapper.writer(new CustomPrettyPrinter());

		this.knownEnumItemsPattern = Pattern.compile(
			KNOWN_ENUMS.stream()
				.flatMap(enumClass -> Set.of(enumClass.getEnumConstants()).stream())
				.map(Enum::name)
				.collect(Collectors.joining("|", "\"(", ")\""))
		);
	}

	@Nonnull
	public String print(@Nonnull JsonNode node) {
		String graphQLJson;
		try {
			graphQLJson = this.constraintWriter.writeValueAsString(node);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
		graphQLJson = correctEnumValues(graphQLJson);
		graphQLJson = correctLocaleValues(graphQLJson);
		graphQLJson = correctCurrencyValues(graphQLJson);
		return graphQLJson;
	}

	@Nonnull
	private String correctEnumValues(@Nonnull String graphQLJson) {
		final Matcher enumMatcher = this.knownEnumItemsPattern.matcher(graphQLJson);
		return enumMatcher.replaceAll(mr -> mr.group(1));
	}

	@Nonnull
	private String correctLocaleValues(@Nonnull String graphQLJson) {
		final Matcher localeMatcher = LOCALE_PATTERN.matcher(graphQLJson);
		return localeMatcher.replaceAll(mr -> mr.group(1).replace("-", "_"));
	}

	@Nonnull
	private String correctCurrencyValues(@Nonnull String graphQLJson) {
		final Matcher currencyMatcher = CURRENCY_PATTERN.matcher(graphQLJson);
		return currencyMatcher.replaceAll(mr -> mr.group(1));
	}

	private class CustomPrettyPrinter extends DefaultPrettyPrinter {
		public CustomPrettyPrinter() {
			super._arrayIndenter = new DefaultIndenter();
			super._objectFieldValueSeparatorWithSpaces = this._separators.getObjectFieldValueSeparator() + " ";
		}

		@Override
		public CustomPrettyPrinter createInstance() {
			final CustomPrettyPrinter instance = new CustomPrettyPrinter();
			instance.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
			instance.indentObjectsWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
			return instance;
		}
	}
}
