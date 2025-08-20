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

package io.evitadb.externalApi.rest.api.resolver.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.data.ComplexDataObjectToJsonConverter;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;

/**
 * Serializes Java object or Collections of objects into JsonNode
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ObjectJsonSerializer {
	@Getter private final ObjectMapper objectMapper;
	private final JsonNodeFactory jsonNodeFactory;
	public ObjectJsonSerializer(@Nonnull ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
		this.jsonNodeFactory = new JsonNodeFactory(true);
	}

	/**
	 * Create new empty object node from factory.
	 */
	public ObjectNode objectNode() {
		return this.jsonNodeFactory.objectNode();
	}

	/**
	 * Create new empty array node from factory.
	 */
	public ArrayNode arrayNode() {
		return this.jsonNodeFactory.arrayNode();
	}

	/**
	 * Serialize object into JSON {@link ObjectNode}
	 *
	 * @return value in form of JSON node
	 * @throws RestInternalError when Class ob object is not among supported classes for serialization
	 */
	@Nonnull
	public JsonNode serializeObject(@Nonnull Object value) {
		if (value instanceof Collection<?> values) return serializeCollection(values);
		if(value instanceof Object[] values) return serializeArray(values);
		if (value.getClass().isArray()) return serializeArray(value);
		if (value instanceof String string) return this.jsonNodeFactory.textNode(string);
		if (value instanceof Character character) return this.jsonNodeFactory.textNode(serialize(character));
		if (value instanceof Integer integer) return this.jsonNodeFactory.numberNode(integer);
		if (value instanceof Short shortNumber) return this.jsonNodeFactory.numberNode(shortNumber);
		if (value instanceof Long longNumber) return this.jsonNodeFactory.textNode(serialize(longNumber));
		if (value instanceof Boolean bool) return this.jsonNodeFactory.booleanNode(bool);
		if (value instanceof Byte byteVal) return this.jsonNodeFactory.numberNode(byteVal);
		if (value instanceof BigDecimal bigDecimal) return this.jsonNodeFactory.textNode(serialize(bigDecimal));
		if (value instanceof Locale locale) return this.jsonNodeFactory.textNode(serialize(locale));
		if (value instanceof Currency currency) return this.jsonNodeFactory.textNode(serialize(currency));
		if (value instanceof OffsetDateTime offsetDateTime) return this.jsonNodeFactory.textNode(serialize(offsetDateTime));
		if (value instanceof LocalDateTime localDateTime) return this.jsonNodeFactory.textNode(serialize(localDateTime));
		if (value instanceof LocalDate dateTime) return this.jsonNodeFactory.textNode(serialize(dateTime));
		if (value instanceof LocalTime localTime) return this.jsonNodeFactory.textNode(serialize(localTime));
		if (value instanceof ComplexDataObject complexDataObject) return serialize(complexDataObject);
		if (value instanceof Range<?> range) return serialize(range);
		if (value instanceof UUID uuid) return this.jsonNodeFactory.textNode(serialize(uuid));
		if (value instanceof Predecessor predecessor) return this.jsonNodeFactory.numberNode(serialize(predecessor));
		if (value instanceof PriceContract price) return serialize(price);
		if (value instanceof ExpressionNode expression) return this.jsonNodeFactory.textNode(expression.toString());
		if (value.getClass().isEnum()) return this.jsonNodeFactory.textNode(serialize((Enum<?>) value));

		throw new RestInternalError("Serialization of value of class: " + value.getClass().getName() + " is not implemented yet.");
	}

	/**
	 * Serialize {@link Collection} of values into JSON {@link ArrayNode}
	 *
	 * @param values list of values
	 * @return values in form of JsonNode
	 * @throws RestInternalError when Class ob object is not among supported classes for serialization
	 */
	public JsonNode serializeCollection(@Nonnull Collection<?> values) {
		final ArrayNode arrayNode = new ArrayNode(this.jsonNodeFactory, values.size());
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
	 * @throws RestInternalError when Class ob object is not among supported classes for serialization
	 */
	public JsonNode serializeArray(@Nonnull Object[] values) {
		final ArrayNode arrayNode = new ArrayNode(this.jsonNodeFactory, values.length);
		for (Object value : values) {
			arrayNode.add(serializeObject(value));
		}
		return arrayNode;
	}

	/**
	 * Serialize {@link Array} of unknown primitive type into JSON {@link ArrayNode}.
	 */
	public JsonNode serializeArray(@Nonnull Object values) {
		final ArrayNode arrayNode = this.jsonNodeFactory.arrayNode();

		final int arraySize = Array.getLength(values);
		for (int i = 0; i < arraySize; i++) {
			final Object item = Array.get(values, i);
			arrayNode.add(serializeObject(item));
		}

		return arrayNode;
	}

	@Nonnull
	private String serialize(@Nonnull Long longNumber) {
		return String.valueOf(longNumber);
	}

	@Nonnull
	private String serialize(@Nonnull Character character) {
		return character.toString();
	}

	@Nonnull
	private String serialize(@Nonnull BigDecimal bigDecimal) {
		return EvitaDataTypes.formatValue(bigDecimal);
	}

	@Nonnull
	private String serialize(@Nonnull Locale locale) {
		return locale.toLanguageTag();
	}

	@Nonnull
	private String serialize(@Nonnull Currency currency) {
		return currency.getCurrencyCode();
	}

	@Nonnull
	private String serialize(@Nonnull OffsetDateTime offsetDateTime) {
		return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(offsetDateTime.truncatedTo(ChronoUnit.MILLIS));
	}

	@Nonnull
	private String serialize(@Nonnull LocalDateTime localDateTime) {
		return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(localDateTime.truncatedTo(ChronoUnit.MILLIS));
	}

	@Nonnull
	private String serialize(@Nonnull LocalDate localDate) {
		return DateTimeFormatter.ISO_LOCAL_DATE.format(localDate);
	}

	@Nonnull
	private String serialize(@Nonnull LocalTime localTime) {
		return DateTimeFormatter.ISO_LOCAL_TIME.format(localTime.truncatedTo(ChronoUnit.MILLIS));
	}

	@Nonnull
	private String serialize(@Nonnull Enum<?> e) {
		return e.name();
	}

	@Nonnull
	private static String serialize(@Nonnull UUID uuid) {
		return uuid.toString();
	}

	private JsonNode serialize(@Nonnull ComplexDataObject complexDataObject) {
		final ComplexDataObjectToJsonConverter converter = new ComplexDataObjectToJsonConverter(this.objectMapper);
		complexDataObject.accept(converter);
		return converter.getRootNode();
	}

	private JsonNode serialize(@Nonnull Range<?> range) {
		final ArrayNode rangeNode = this.jsonNodeFactory.arrayNode(2);
		rangeNode.add(range.getPreciseFrom() != null ? serializeObject(range.getPreciseFrom()) : this.jsonNodeFactory.nullNode());
		rangeNode.add(range.getPreciseTo() != null ? serializeObject(range.getPreciseTo()) : this.jsonNodeFactory.nullNode());
		return rangeNode;
	}

	private int serialize(@Nonnull Predecessor predecessor) {
		return predecessor.predecessorPk();
	}

	private JsonNode serialize(@Nonnull PriceContract price) {
		final ObjectNode priceNode = this.jsonNodeFactory.objectNode();
		priceNode.putIfAbsent(PriceDescriptor.PRICE_ID.name(),serializeObject(price.priceId()));
		priceNode.putIfAbsent(PriceDescriptor.PRICE_LIST.name(),serializeObject(price.priceList()));
		priceNode.putIfAbsent(PriceDescriptor.CURRENCY.name(),serializeObject(price.currency()));
		priceNode.putIfAbsent(PriceDescriptor.INNER_RECORD_ID.name(),price.innerRecordId() != null?serializeObject(price.innerRecordId()):null);
		priceNode.putIfAbsent(PriceDescriptor.INDEXED.name(),serializeObject(price.indexed()));
		priceNode.putIfAbsent(PriceDescriptor.PRICE_WITHOUT_TAX.name(), serializeObject(price.priceWithoutTax()));
		priceNode.putIfAbsent(PriceDescriptor.PRICE_WITH_TAX.name(),serializeObject(price.priceWithTax()));
		priceNode.putIfAbsent(PriceDescriptor.TAX_RATE.name(),serializeObject(price.taxRate()));
		priceNode.putIfAbsent(PriceDescriptor.VALIDITY.name(), price.validity() != null?serializeObject(price.validity()):null);
		return priceNode;
	}
}
