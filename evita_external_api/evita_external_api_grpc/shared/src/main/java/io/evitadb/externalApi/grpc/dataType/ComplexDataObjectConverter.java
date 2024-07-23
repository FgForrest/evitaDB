/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.grpc.dataType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.ComplexDataObjectToJsonConverter;
import io.evitadb.dataType.data.JsonToComplexDataObjectConverter;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * This class is used for converting {@link ComplexDataObject} to JSON and vice versa.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ComplexDataObjectConverter {
	/**
	 * Instance of {@link ObjectMapper} is used to convert {@link ComplexDataObject} to {@link JsonNode}.
	 */
	private static final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * This method converts {@link ComplexDataObject} to {@link JsonNode}.
	 *
	 * @param associatedDataValue to be converted
	 * @return converted {@link JsonNode}
	 */
	@Nonnull
	public static JsonNode convertComplexDataObjectToJson(@Nonnull ComplexDataObject associatedDataValue) {
		final ComplexDataObjectToJsonConverter converter = new ComplexDataObjectToJsonConverter(objectMapper);
		associatedDataValue.accept(converter);
		return converter.getRootNode();
	}

	/**
	 * This class is used to convert JSON in a {@link String} format to {@link ComplexDataObject}.
	 *
	 * @param associatedDataValueJson user defined object in json format
	 * @return converted {@link ComplexDataObject}
	 */
	@Nonnull
	public static ComplexDataObject convertJsonToComplexDataObject(@Nonnull String associatedDataValueJson) {
		final JsonToComplexDataObjectConverter converter = new JsonToComplexDataObjectConverter(objectMapper);
		try {
			return converter.fromJson(associatedDataValueJson);
		} catch (JsonProcessingException ex) {
			throw new EvitaInvalidUsageException("Invalid associated data json format.", ex);
		}
	}
}
