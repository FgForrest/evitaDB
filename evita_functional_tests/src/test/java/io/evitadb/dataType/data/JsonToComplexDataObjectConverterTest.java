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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.data.ComplexDataObjectToJsonConverter.SortingNodeFactory;
import io.evitadb.dataType.data.ComplexDataObjectToJsonConverterTest.ArrayContainer;
import io.evitadb.dataType.data.ComplexDataObjectToJsonConverterTest.TestComplexObject;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Set;

import static io.evitadb.dataType.data.ComplexDataObjectToJsonConverterTest.createArrayComplexObject;
import static io.evitadb.dataType.data.ComplexDataObjectToJsonConverterTest.createVeryComplexObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies behaviour of {@link JsonToComplexDataObjectConverter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class JsonToComplexDataObjectConverterTest {
	private final ObjectMapper objectMapper = JsonMapper.builder()
		.nodeFactory(new SortingNodeFactory())
		.build();

	@Test
	void shouldSerializeComplexObjectToJson() throws IOException {
		final TestComplexObject veryComplexObject = createVeryComplexObject();
		final ComplexDataObjectConverter<TestComplexObject> converter = new ComplexDataObjectConverter<>(veryComplexObject);
		final ComplexDataObject serializableForm = (ComplexDataObject) converter.getSerializableForm();

		final ComplexDataObjectToJsonConverter toJsonConverter = new ComplexDataObjectToJsonConverter(this.objectMapper);
		serializableForm.accept(toJsonConverter);

		final ComplexDataObject deserializedForm = new JsonToComplexDataObjectConverter(this.objectMapper).fromJson(toJsonConverter.getJsonAsString());
		serializableForm.accept(new AutoTypingAsserter(deserializedForm.root()));
	}

	@Test
	void shouldSerializeComplexArrayObjectToJson() throws IOException {
		final ArrayContainer veryComplexObject = createArrayComplexObject();
		final ComplexDataObjectConverter<ArrayContainer> converter = new ComplexDataObjectConverter<>(veryComplexObject);
		final ComplexDataObject serializableForm = (ComplexDataObject) converter.getSerializableForm();

		final ComplexDataObjectToJsonConverter toJsonConverter = new ComplexDataObjectToJsonConverter(this.objectMapper);
		serializableForm.accept(toJsonConverter);

		final ComplexDataObject deserializedForm = new JsonToComplexDataObjectConverter(this.objectMapper).fromJson(toJsonConverter.getJsonAsString());
		serializableForm.accept(new AutoTypingAsserter(deserializedForm.root()));
	}

	@RequiredArgsConstructor
	private static class AutoTypingAsserter implements DataItemVisitor {
		private final DataItem deserializedForm;

		@Override
		public void visit(@Nonnull DataItemArray arrayItem) {
			assertTrue(this.deserializedForm instanceof DataItemArray);
			final DataItemArray deserializedItemArray = (DataItemArray) this.deserializedForm;
			final DataItem[] serializedChildren = arrayItem.children();
			final DataItem[] deserializedChildren = deserializedItemArray.children();
			assertEquals(serializedChildren.length, deserializedChildren.length);
			for (int i = 0; i < serializedChildren.length; i++) {
				final DataItem serializedChild = serializedChildren[i];
				final DataItem deserializedChild = deserializedChildren[i];

				serializedChild.accept(new AutoTypingAsserter(deserializedChild));
			}
		}

		@Override
		public void visit(@Nonnull DataItemMap mapItem) {
			assertTrue(this.deserializedForm instanceof DataItemMap);
			final DataItemMap deserializedItemMap = (DataItemMap) this.deserializedForm;
			final Set<String> serializedPropertyNames = mapItem.getPropertyNames();
			final Set<String> deserializedPropertyNames = deserializedItemMap.getPropertyNames();

			assertEquals(serializedPropertyNames.size(), deserializedPropertyNames.size());
			for (String serializedPropertyName : serializedPropertyNames) {
				assertTrue(deserializedPropertyNames.contains(serializedPropertyName));
				final DataItem serializedValue = mapItem.getProperty(serializedPropertyName);
				final DataItem deserializedValue = deserializedItemMap.getProperty(serializedPropertyName);
				if (serializedValue == null && deserializedValue == null) {
					return;
				} else {
					assertNotNull(serializedValue);
					assertNotNull(deserializedValue);
					serializedValue.accept(new AutoTypingAsserter(deserializedValue));
				}
			}
		}

		@Override
		public void visit(@Nonnull DataItemValue valueItem) {
			assertTrue(this.deserializedForm instanceof DataItemValue);
			final DataItemValue deserializedItem = (DataItemValue) this.deserializedForm;
			final Serializable serializedValue = valueItem.value();
			final Serializable deserializedValue = deserializedItem.value();

			if (deserializedValue.getClass().equals(serializedValue.getClass())) {
				assertEquals(serializedValue, deserializedValue);
			} else {
				final Serializable convertedDeserializedValue = EvitaDataTypes.toTargetType(
					deserializedValue, serializedValue.getClass(), 2
				);
				if (serializedValue instanceof OffsetDateTime) {
					assertEquals(serializedValue, convertedDeserializedValue);
				} else {
					assertEquals(serializedValue, convertedDeserializedValue);
				}
			}

		}

	}
}
