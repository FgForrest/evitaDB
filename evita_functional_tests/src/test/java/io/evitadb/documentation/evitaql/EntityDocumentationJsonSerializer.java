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

package io.evitadb.documentation.evitaql;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.NumberRange;
import io.evitadb.dataType.data.ComplexDataObjectToJsonConverter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * Custom Jackson serializer for {@link EntityContract} for the sake of generated JSON outputs in documentation.
 * We want to display all crucial data but not more to keep the output understandable to the human reader.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class EntityDocumentationJsonSerializer extends JsonSerializer<EntityContract> {

	/**
	 * Wraps and rethrows possible {@link IOException} declared on lambda.
	 */
	private static void wrap(@Nonnull RunnableThrowingException lambda) {
		try {
			lambda.run();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Writes a generic number into a JSON.
	 */
	private static void writeNumber(@Nonnull JsonGenerator gen, @Nonnull Number theValue) {
		wrap(() -> {
			if (theValue instanceof Byte number) {
				gen.writeNumber(number);
			} else if (theValue instanceof Short number) {
				gen.writeNumber(number);
			} else if (theValue instanceof Integer number) {
				gen.writeNumber(number);
			} else if (theValue instanceof Long number) {
				gen.writeNumber(number);
			} else if (theValue instanceof BigDecimal number) {
				gen.writeNumber(number);
			} else {
				throw new UnsupportedOperationException("Unsupported number type: " + theValue.getClass().getName());
			}
		});
	}

	/**
	 * Writes {@link OffsetDateTime} to a JSON.
	 */
	private static void writeDateTime(@Nonnull JsonGenerator gen, @Nonnull OffsetDateTime value) {
		wrap(() -> gen.writeString(EvitaDataTypes.formatValue(value)));
	}

	/**
	 * Writes {@link NumberRange} of a specific number type to a JSON.
	 */
	private static void writeNumberRange(@Nonnull JsonGenerator gen, @Nonnull NumberRange<?> range) {
		try {
			gen.writeStartArray();
			if (range.getPreciseFrom() == null) {
				gen.writeNull();
			} else {
				writeNumber(gen, range.getPreciseFrom());
			}
			if (range.getPreciseFrom() == null) {
				gen.writeNull();
			} else {
				writeNumber(gen, range.getPreciseTo());
			}
			gen.writeEndArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Writes {@link DateTimeRange} to a JSON.
	 */
	private static void writeDateTimeRange(@Nonnull JsonGenerator gen, @Nonnull DateTimeRange range) {
		try {
			gen.writeStartArray();
			if (range.getPreciseFrom() == null) {
				gen.writeNull();
			} else {
				writeDateTime(gen, range.getPreciseFrom());
			}
			if (range.getPreciseFrom() == null) {
				gen.writeNull();
			} else {
				writeDateTime(gen, range.getPreciseTo());
			}
			gen.writeEndArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Writes all attributes from {@link AttributesContract} to a JSON.
	 */
	private static void writeAttributes(@Nonnull JsonGenerator gen, @Nonnull AttributesContract value) {
		if (!value.getAttributeValues().isEmpty()) {
			wrap(() -> {
				gen.writeFieldName("attributes");
				gen.writeStartObject();
				for (AttributeValue attributeValue : value.getAttributeValues()) {
					final Serializable theValue = attributeValue.value();
					final String fieldName = attributeValue.key().toString();
					gen.writeFieldName(fieldName);
					if (theValue instanceof Number number) {
						writeNumber(gen, number);
					} else if (theValue instanceof String string) {
						gen.writeString(string);
					} else if (theValue instanceof Boolean booleanValue) {
						gen.writeBoolean(booleanValue);
					} else if (theValue instanceof Character character) {
						gen.writeString(String.valueOf(character));
					} else if (theValue instanceof OffsetDateTime dateTime) {
						writeDateTime(gen, dateTime);
					} else if (theValue instanceof DateTimeRange range) {
						writeDateTimeRange(gen, range);
					} else if (theValue instanceof NumberRange<?> range) {
						writeNumberRange(gen, range);
					} else {
						gen.writeString(EvitaDataTypes.formatValue(theValue));
					}
				}
				gen.writeEndObject();
			});

		}
	}

	/**
	 * Writes all associated data from {@link AssociatedDataContract} to a JSON.
	 */
	private static void writeAssociatedData(@Nonnull JsonGenerator gen, @Nonnull AssociatedDataContract value) {
		if (!value.getAssociatedDataValues().isEmpty()) {
			wrap(() -> {
				gen.writeFieldName("associatedData");
				gen.writeStartObject();
				for (AssociatedDataValue associatedDataValue : value.getAssociatedDataValues()) {
					final Serializable theValue = associatedDataValue.value();
					final String fieldName = associatedDataValue.key().toString();
					if (theValue instanceof Number number) {
						writeNumber(gen, number);
					} else if (theValue instanceof String string) {
						gen.writeString(string);
					} else if (theValue instanceof Boolean booleanValue) {
						gen.writeBoolean(booleanValue);
					} else if (theValue instanceof Character character) {
						gen.writeString(String.valueOf(character));
					} else if (theValue instanceof OffsetDateTime dateTime) {
						writeDateTime(gen, dateTime);
					} else if (theValue instanceof DateTimeRange range) {
						writeDateTimeRange(gen, range);
					} else if (theValue instanceof NumberRange<?> range) {
						writeNumberRange(gen, range);
					} else if (theValue instanceof ComplexDataObject complexDataObject) {
						final ComplexDataObjectToJsonConverter converter = new ComplexDataObjectToJsonConverter(new ObjectMapper());
						complexDataObject.accept(converter);
						gen.writeFieldName(fieldName);
						gen.writeTree(converter.getRootNode());
					} else {
						gen.writeString(EvitaDataTypes.formatValue(theValue));
					}
				}
				gen.writeEndObject();
			});
		}
	}

	/**
	 * Writes all reference from {@link ReferenceContract} to a JSON.
	 */
	private static void writeReferences(@Nonnull JsonGenerator gen, @Nonnull EntityContract value) {
		if (!value.getReferences().isEmpty()) {
			wrap(() -> {
				gen.writeFieldName("references");
				gen.writeStartObject();
				value.getReferences()
					.stream()
					.collect(Collectors.groupingBy(ReferenceContract::getReferenceName))
					.forEach((refKey, refValue) -> writeReference(gen, refKey, refValue));
				gen.writeEndObject();
			});

		}
	}

	/**
	 * Writes all references of particular name to a JSON.
	 */
	private static void writeReference(@Nonnull JsonGenerator gen, @Nonnull String referenceName, @Nonnull List<ReferenceContract> references) {
		wrap(() -> {
			gen.writeFieldName(referenceName);
			gen.writeStartArray();
			for (ReferenceContract reference : references) {
				gen.writeStartObject();
				reference.getGroup()
					.ifPresent(group -> wrap(() -> gen.writeNumberField("group", group.getPrimaryKey())));
				reference.getGroupEntity()
					.ifPresent(group -> wrap(() -> gen.writeObjectField("groupEntity", group)));

				gen.writeNumberField("referencedKey", reference.getReferenceKey().primaryKey());
				reference.getReferencedEntity()
					.ifPresent(group -> wrap(() -> gen.writeObjectField("referencedEntity", group)));
				writeAttributes(gen, reference);
				gen.writeEndObject();
			}
			gen.writeEndArray();
		});
	}

	/**
	 * Writes all prices from {@link PriceContract} to a JSON.
	 */
	private static void writePrices(@Nonnull JsonGenerator gen, @Nonnull EntityContract value) {
		final Optional<PriceContract> priceForSale = value.getPriceForSaleIfAvailable();

		priceForSale.ifPresent(it -> {
			wrap(() -> gen.writeFieldName("priceForSale"));
			writePrice(gen, it);
		});

		if (!value.getPrices().isEmpty()) {
			wrap(() -> {
				gen.writeFieldName("prices");
				gen.writeStartArray();
				for (PriceContract price : value.getPrices()) {
					writePrice(gen, price);
				}
				gen.writeEndArray();
			});

		}

	}

	/**
	 * Writes all {@link PriceContract} to a JSON.
	 */
	private static void writePrice(@Nonnull JsonGenerator gen, @Nonnull PriceContract value) {
		wrap(() -> {
			gen.writeStartObject();
			gen.writeStringField("currency", value.currency().toString());
			gen.writeStringField("priceList", value.priceList());
			gen.writeNumberField("priceWithoutTax", value.priceWithoutTax());
			gen.writeNumberField("priceWithTax", value.priceWithTax());
			ofNullable(value.validity())
				.ifPresent(it -> {
					wrap(() -> gen.writeFieldName("validity"));
					writeDateTimeRange(gen, value.validity());
				});
			gen.writeEndObject();
		});
	}

	@Override
	public void serialize(EntityContract value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
		gen.writeStartObject();
		gen.writeNumberField("primaryKey", value.getPrimaryKey());
		value.getParent().ifPresent(
			parent -> wrap(() -> gen.writeNumberField("parent", parent))
		);
		value.getParentEntity().ifPresent(
			parentEntity -> wrap(() -> gen.writeObject(parentEntity))
		);
		if (value.getPriceInnerRecordHandling() != PriceInnerRecordHandling.UNKNOWN) {
			gen.writeStringField("priceInnerRecordHandling", value.getPriceInnerRecordHandling().name());
		}
		writeAttributes(gen, value);
		writeAssociatedData(gen, value);
		writePrices(gen, value);
		writeReferences(gen, value);
		gen.writeEndObject();
	}

	/**
	 * A lambda that can throw {@link IOException}.
	 */
	@FunctionalInterface
	private interface RunnableThrowingException {

		void run() throws IOException;

	}

}
