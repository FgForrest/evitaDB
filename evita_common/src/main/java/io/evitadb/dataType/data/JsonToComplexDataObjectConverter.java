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

package io.evitadb.dataType.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * This class allows to convert an arbitrary JSON object to a {@link ComplexDataObject} via. the Jackson library.
 *
 * The type {@link Long} and {@link BigDecimal} are expected to be strings. In ECMAScript the Number type is IEEE 754
 * Standard for double-precision floating-point format with max integer value of
 * Number.MAX_SAFE_INTEGER = 9007199254740991 min integer value of Number.MIN_SAFE_INTEGER = -9007199254740991. Passing
 * larger values that are possible to have in Java would make these corrupted in JavaScript.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class JsonToComplexDataObjectConverter {
	private static final Pattern LONG_NUMBER = Pattern.compile("\\d+");
	private static final Pattern BIG_DECIMAL_NUMBER = Pattern.compile("\\d+\\.\\d+");

	/**
	 * Mapper is used to build JSON tree.
	 */
	private final ObjectMapper objectMapper;

	@Nullable
	private static DataItem convertToDataItem(@Nonnull JsonNode jsonNode) {
		if (jsonNode.isNull()) {
			return null;
		} else if (jsonNode instanceof ArrayNode arrayNode) {
			final int itemCount = arrayNode.size();
			final DataItem[] outputElements = new DataItem[itemCount];
			final Iterator<JsonNode> inputElements = arrayNode.elements();
			int index = 0;
			while (inputElements.hasNext()) {
				outputElements[index++] = convertToDataItem(inputElements.next());
			}
			return new DataItemArray(outputElements);
		} else if (jsonNode instanceof ObjectNode objectNode) {
			final int itemCount = objectNode.size();
			final HashMap<String, DataItem> outputElements = CollectionUtils.createHashMap(itemCount);
			final Iterator<Entry<String, JsonNode>> inputElements = objectNode.fields();
			while (inputElements.hasNext()) {
				final Entry<String, JsonNode> inputElement = inputElements.next();
				outputElements.put(inputElement.getKey(), convertToDataItem(inputElement.getValue()));
			}
			return new DataItemMap(outputElements);
		} else if (jsonNode.isBoolean()) {
			return new DataItemValue(jsonNode.booleanValue());
		} else if (jsonNode.isShort()) {
			return new DataItemValue(jsonNode.shortValue());
		} else if (jsonNode.isInt()) {
			return new DataItemValue(jsonNode.intValue());
		} else if (jsonNode.isLong()) {
			return new DataItemValue(jsonNode.longValue());
		} else if (jsonNode.isBigDecimal()) {
			return new DataItemValue(new BigDecimal(jsonNode.textValue()));
		} else if (jsonNode.isTextual()) {
			return new DataItemValue(jsonNode.textValue());
		} else if (jsonNode.isValueNode()) {
			final String value = jsonNode.asText();
			try {
				if (LONG_NUMBER.matcher(value).matches()) {
					return new DataItemValue(Long.valueOf(value));
				} else if (BIG_DECIMAL_NUMBER.matcher(value).matches()) {
					return new DataItemValue(new BigDecimal(value));
				} else {
					return new DataItemValue(value);
				}
			} catch (NumberFormatException ex) {
				return new DataItemValue(value);
			}
		} else {
			throw new EvitaInvalidUsageException("Unexpected input JSON format.");
		}
	}

	/**
	 * Method creates an instance of {@link ComplexDataObject} from a valid JSON string.
	 */
	@Nonnull
	public ComplexDataObject fromJson(@Nonnull String jsonString) throws JsonProcessingException {
		final JsonNode jsonNode = this.objectMapper.readTree(jsonString);
		return new ComplexDataObject(
			Objects.requireNonNull(convertToDataItem(jsonNode))
		);
	}

	/**
	 * Method creates an instance of {@link ComplexDataObject} from a valid Java map representing the JSON.
	 */
	@Nonnull
	public ComplexDataObject fromMap(@Nonnull Map<String, Object> map) throws JsonProcessingException {
		final JsonNode jsonNode = this.objectMapper.valueToTree(map);
		return new ComplexDataObject(
			Objects.requireNonNull(convertToDataItem(jsonNode))
		);
	}

}
