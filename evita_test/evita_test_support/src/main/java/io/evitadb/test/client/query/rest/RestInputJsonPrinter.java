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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import javax.annotation.Nonnull;

/**
 * Prints GraphQL input JSONs correctly formatted to spec.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class RestInputJsonPrinter {

	@Nonnull private final ObjectWriter constraintWriter;

	public RestInputJsonPrinter() {
		final ObjectMapper objectMapper = new ObjectMapper();
		this.constraintWriter = objectMapper.writer(new CustomPrettyPrinter());
	}

	@Nonnull
	public String print(@Nonnull JsonNode node) {
		String graphQLJson;
		try {
			graphQLJson = this.constraintWriter.writeValueAsString(node);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
		return graphQLJson;
	}

	private class CustomPrettyPrinter extends DefaultPrettyPrinter {
		public CustomPrettyPrinter() {
			super._arrayIndenter = new DefaultIndenter();
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
