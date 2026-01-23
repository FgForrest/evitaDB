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

package io.evitadb.test.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.evitadb.exception.GenericEvitaInternalError;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Convenient map builder that uses {@link LinkedHashMap} to preserve order of keys. It is alternative to {@link Map#of()}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class JsonArrayBuilder {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final ArrayNode arrayNode = OBJECT_MAPPER.createArrayNode();

	public static JsonArrayBuilder jsonArray() {
		return new JsonArrayBuilder();
	};

	public static ArrayNode jsonArray(@Nonnull List<?> items) {
		final JsonArrayBuilder builder = jsonArray();
		for (Object item : items) {
			if (item instanceof JsonNode value) {
				builder.add(value);
			} else if (item instanceof Integer value) {
				builder.add(value);
			} else if (item instanceof Long value) {
				builder.add(value);
			} else if (item instanceof String value) {
				builder.add(value);
			} else if (item instanceof Character value) {
				builder.add(value);
			} else if (item instanceof Boolean value) {
				builder.add(value);
			} else if (item instanceof BigDecimal value) {
				builder.add(value);
			} else if (item instanceof Short value) {
				builder.add(value);
			} else if (item instanceof Byte value) {
				builder.add(value);
			} else if (item instanceof Locale value) {
				builder.add(value);
			} else if (item instanceof Currency value) {
				builder.add(value);
			} else {
				throw new GenericEvitaInternalError("Unsupported item type.");
			}
		}
		return builder.build();
	}

	public static ArrayNode jsonArray(@Nonnull Object... items) {
		return jsonArray(Arrays.asList(items));
	};

	public JsonArrayBuilder add(@Nonnull JsonNode jsonNode) {
		this.arrayNode.add(jsonNode);
		return this;
	}

	public JsonArrayBuilder add(@Nonnull JsonObjectBuilder jsonObjectBuilder) {
		return add(jsonObjectBuilder.build());
	}

	public JsonArrayBuilder add(@Nullable Integer value) {
		this.arrayNode.add(value);
		return this;
	}

	public JsonArrayBuilder add(@Nullable Long value) {
		this.arrayNode.add(value);
		return this;
	}

	public JsonArrayBuilder add(@Nullable String value) {
		this.arrayNode.add(value);
		return this;
	}

	public JsonArrayBuilder add(@Nullable Character value) {
		this.arrayNode.add(value);
		return this;
	}

	public JsonArrayBuilder add(@Nullable Boolean value) {
		this.arrayNode.add(value);
		return this;
	}

	public JsonArrayBuilder add(@Nullable BigDecimal value) {
		this.arrayNode.add(value);
		return this;
	}

	public JsonArrayBuilder add(@Nullable Short value) {
		this.arrayNode.add(value);
		return this;
	}

	public JsonArrayBuilder add(@Nullable Byte value) {
		this.arrayNode.add(value);
		return this;
	}

	public JsonArrayBuilder add(@Nullable Locale value) {
		this.arrayNode.add(value.toString());
		return this;
	}

	public JsonArrayBuilder add(@Nullable Currency value) {
		this.arrayNode.add(value.toString());
		return this;
	}

	public ArrayNode build() {
		return this.arrayNode;
	}
}
