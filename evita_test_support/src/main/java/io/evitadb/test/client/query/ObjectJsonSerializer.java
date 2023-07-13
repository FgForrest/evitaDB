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

package io.evitadb.test.client.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.data.ComplexDataObjectToJsonConverter;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Currency;
import java.util.Locale;

/**
 * Serializes Java object or Collections of objects into JsonNode
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ObjectJsonSerializer {

	@Getter private final ObjectMapper objectMapper;
	private final JsonNodeFactory jsonNodeFactory;

	public ObjectJsonSerializer() {
		this.objectMapper = new ObjectMapper();
		this.jsonNodeFactory = new JsonNodeFactory(true);
	}

	/**
	 * Serialize object into JSON {@link ObjectNode}
	 *
	 * @return value in form of JSON node
	 * @throws EvitaInternalError when Class ob object is not among supported classes for serialization
	 */
	@Nonnull
	public JsonNode serializeObject(@Nullable Object value) {
		if (value == null) {
			return jsonNodeFactory.nullNode();
		}

		if (value instanceof Collection<?> values) return serializeCollection(values);
		if (value instanceof Object[] values) return serializeArray(values);
		if (value.getClass().isArray()) return serializeArray(value);
		if (value instanceof String string) return jsonNodeFactory.textNode(string);
		if (value instanceof Character character) return jsonNodeFactory.textNode(character.toString());
		if (value instanceof Integer integer) return jsonNodeFactory.numberNode(integer);
		if (value instanceof Short shortNumber) return jsonNodeFactory.numberNode(shortNumber);
		if (value instanceof Long longNumber) return jsonNodeFactory.textNode(String.valueOf(longNumber));
		if (value instanceof Boolean bool) return jsonNodeFactory.booleanNode(bool);
		if (value instanceof Byte byteVal) return serialize(byteVal);
		if (value instanceof BigDecimal bigDecimal) return serialize(bigDecimal);
		if (value instanceof Locale locale) return serialize(locale);
		if (value instanceof Currency currency) return serialize(currency);
		if (value instanceof OffsetDateTime offsetDateTime) return serialize(offsetDateTime);
		if (value instanceof LocalDateTime localDateTime) return serialize(localDateTime);
		if (value instanceof LocalDate dateTime) return serialize(dateTime);
		if (value instanceof LocalTime localTime) return serialize(localTime);
		if (value instanceof ComplexDataObject complexDataObject) return serialize(complexDataObject);
		if (value instanceof Range<?> range) return serialize(range);
		if (value instanceof PriceContract price) return serialize(price);
		if (value.getClass().isEnum()) return serialize((Enum<?>) value);

		throw new EvitaInternalError("Serialization of value of class: " + value.getClass().getName() + " is not implemented yet.");
	}

	/**
	 * Serialize {@link Collection} of values into JSON {@link ArrayNode}
	 *
	 * @param values list of values
	 * @return values in form of JsonNode
	 * @throws EvitaInternalError when Class ob object is not among supported classes for serialization
	 */
	public JsonNode serializeCollection(@Nonnull Collection<?> values) {
		final ArrayNode arrayNode = new ArrayNode(jsonNodeFactory, values.size());
		for (Object value : values) {
			arrayNode.add(serializeObject(value));
		}
		return arrayNode;
	}

	/**
	 * Serialize {@link java.lang.reflect.Array} of values into JSON {@link ArrayNode}
	 *
	 * @param values array of values
	 * @return values in form of JsonNode
	 * @throws EvitaInternalError when Class ob object is not among supported classes for serialization
	 */
	public JsonNode serializeArray(@Nonnull Object[] values) {
		final ArrayNode arrayNode = new ArrayNode(jsonNodeFactory, values.length);
		for (Object value : values) {
			arrayNode.add(serializeObject(value));
		}
		return arrayNode;
	}

	/**
	 * Serialize {@link Array} of unknown primitive type into JSON {@link ArrayNode}.
	 */
	public JsonNode serializeArray(@Nonnull Object values) {
		final ArrayNode arrayNode = jsonNodeFactory.arrayNode();

		final int arraySize = Array.getLength(values);
		for (int i = 0; i < arraySize; i++) {
			final Object item = Array.get(values, i);
			arrayNode.add(serializeObject(item));
		}

		return arrayNode;
	}

	private JsonNode serialize(@Nonnull BigDecimal bigDecimal) {
		return jsonNodeFactory.textNode(bigDecimal.toPlainString());
	}

	private JsonNode serialize(@Nonnull Byte byteValue) {
		return jsonNodeFactory.numberNode(byteValue);
	}

	private JsonNode serialize(@Nonnull Locale locale) {
		return jsonNodeFactory.textNode(locale.toLanguageTag());
	}

	private JsonNode serialize(@Nonnull Currency currency) {
		return jsonNodeFactory.textNode(currency.getCurrencyCode());
	}

	private JsonNode serialize(@Nonnull OffsetDateTime offsetDateTime) {
		return jsonNodeFactory.textNode(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(offsetDateTime));
	}

	private JsonNode serialize(@Nonnull LocalDateTime localDateTime) {
		return jsonNodeFactory.textNode(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(localDateTime));
	}

	private JsonNode serialize(@Nonnull LocalDate localDate) {
		return jsonNodeFactory.textNode(DateTimeFormatter.ISO_LOCAL_DATE.format(localDate));
	}

	private JsonNode serialize(@Nonnull LocalTime localTime) {
		return jsonNodeFactory.textNode(DateTimeFormatter.ISO_LOCAL_TIME.format(localTime));
	}

	private JsonNode serialize(@Nonnull Enum<?> e) {
		return jsonNodeFactory.textNode(e.name());
	}

	private JsonNode serialize(@Nonnull ComplexDataObject complexDataObject) {
		final ComplexDataObjectToJsonConverter converter = new ComplexDataObjectToJsonConverter(objectMapper);
		complexDataObject.accept(converter);
		return converter.getRootNode();
	}

	private JsonNode serialize(@Nonnull Range<?> range) {
		final ArrayNode rangeNode = jsonNodeFactory.arrayNode(2);
		rangeNode.add(range.getPreciseFrom() != null ? serializeObject(range.getPreciseFrom()) : jsonNodeFactory.nullNode());
		rangeNode.add(range.getPreciseTo() != null ? serializeObject(range.getPreciseTo()) : jsonNodeFactory.nullNode());
		return rangeNode;
	}

	private JsonNode serialize(@Nonnull PriceContract price) {
		final ObjectNode priceNode = jsonNodeFactory.objectNode();
		priceNode.putIfAbsent(PriceDescriptor.PRICE_ID.name(),serializeObject(price.getPriceId()));
		priceNode.putIfAbsent(PriceDescriptor.PRICE_LIST.name(),serializeObject(price.getPriceList()));
		priceNode.putIfAbsent(PriceDescriptor.CURRENCY.name(),serializeObject(price.getCurrency()));
		priceNode.putIfAbsent(PriceDescriptor.INNER_RECORD_ID.name(),price.getInnerRecordId() != null?serializeObject(price.getInnerRecordId()):null);
		priceNode.putIfAbsent(PriceDescriptor.SELLABLE.name(),serializeObject(price.isSellable()));
		priceNode.putIfAbsent(PriceDescriptor.PRICE_WITHOUT_TAX.name(), serializeObject(price.getPriceWithoutTax()));
		priceNode.putIfAbsent(PriceDescriptor.PRICE_WITH_TAX.name(),serializeObject(price.getPriceWithTax()));
		priceNode.putIfAbsent(PriceDescriptor.TAX_RATE.name(),serializeObject(price.getTaxRate()));
		priceNode.putIfAbsent(PriceDescriptor.VALIDITY.name(), price.getValidity() != null?serializeObject(price.getValidity()):null);
		return priceNode;
	}
}
