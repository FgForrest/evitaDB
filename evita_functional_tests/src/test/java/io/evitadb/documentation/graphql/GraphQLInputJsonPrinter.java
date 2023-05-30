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

import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import javax.annotation.Nonnull;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
public class GraphQLInputJsonPrinter {

	private final Pattern enumPattern = Pattern.compile("\"([A-Z]+('_'[A-Z]+)*)\"");

	@Nonnull private final ObjectWriter constraintWriter;

	public GraphQLInputJsonPrinter() {
		final ObjectMapper objectMapper = new ObjectMapper(new JsonFactoryBuilder()
			.disable(JsonWriteFeature.QUOTE_FIELD_NAMES)
			.build());
		this.constraintWriter = objectMapper.writer(new CustomPrettyPrinter());
	}

	@Nonnull
	public String print(@Nonnull JsonNode node) {
		String graphQLJson;
		try {
			graphQLJson = constraintWriter.writeValueAsString(node);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}

		graphQLJson = correctEnumValues(graphQLJson);

		return graphQLJson;
	}

	@Nonnull
	private String correctEnumValues(@Nonnull String graphQLJson) {
		final Matcher enumMatcher = enumPattern.matcher(graphQLJson);
		return enumMatcher.replaceAll(mr -> mr.group(1));
	}

	private class CustomPrettyPrinter extends DefaultPrettyPrinter {
		public CustomPrettyPrinter() {
			super._arrayIndenter = new DefaultIndenter();
			super._objectFieldValueSeparatorWithSpaces = _separators.getObjectFieldValueSeparator() + " ";
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
