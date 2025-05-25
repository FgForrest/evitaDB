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
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Convenient map builder that uses {@link LinkedHashMap} to preserve order of keys. It is alternative to {@link Map#of()}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class JsonObjectBuilder {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();

	public static JsonObjectBuilder jsonObject() {
		return new JsonObjectBuilder();
	};

	public JsonObjectBuilder e(@Nonnull String key, @Nullable Integer value) {
		this.objectNode.put(key, value);
		return this;
	}

	public JsonObjectBuilder e(@Nonnull String key, @Nullable Long value) {
		this.objectNode.put(key, value);
		return this;
	}

	public JsonObjectBuilder e(@Nonnull String key, @Nullable String value) {
		this.objectNode.put(key, value);
		return this;
	}

	public JsonObjectBuilder e(@Nonnull String key, @Nullable Character value) {
		this.objectNode.put(key, value);
		return this;
	}

	public JsonObjectBuilder e(@Nonnull String key, @Nullable Boolean value) {
		this.objectNode.put(key, value);
		return this;
	}

	public JsonObjectBuilder e(@Nonnull String key, @Nullable BigDecimal value) {
		this.objectNode.put(key, value);
		return this;
	}

	public JsonObjectBuilder e(@Nonnull String key, @Nullable Short value) {
		this.objectNode.put(key, value);
		return this;
	}

	public JsonObjectBuilder e(@Nonnull String key, @Nullable Byte value) {
		this.objectNode.put(key, value);
		return this;
	}

	public JsonObjectBuilder e(@Nonnull String key, @Nullable Currency value) {
		this.objectNode.put(key, value.toString());
		return this;
	}

	public JsonObjectBuilder e(@Nonnull String key, @Nullable Locale value) {
		this.objectNode.put(key, value.toString());
		return this;
	}

	public JsonObjectBuilder e(@Nonnull String key, @Nullable JsonNode jsonNode) {
		this.objectNode.set(key, jsonNode);
		return this;
	}

	public JsonObjectBuilder e(@Nonnull String key, @Nullable JsonObjectBuilder jsonObjectBuilder) {
		return e(key, jsonObjectBuilder.build());
	}

	public JsonObjectBuilder e(@Nonnull String key, @Nullable JsonArrayBuilder jsonArrayBuilder) {
		return e(key, jsonArrayBuilder.build());
	}

	public ObjectNode build() {
		return this.objectNode;
	}
}
