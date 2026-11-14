/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.structure.AbstractBuilderTest;
import io.evitadb.api.requestResponse.data.structure.EntityAttributes;
import io.evitadb.api.requestResponse.data.structure.InitialEntityAttributesBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.dataType.BigDecimalNumberRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the content comparisons of {@link AttributesContract} tell apart attribute values a reader can tell
 * apart, although the values compare equal by `equals` - as the ranges `[1.0,2.0]` and `[10,20]` do, whose bounds
 * encode to the same comparable longs, each at its own scale.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("AttributesContract content comparison")
@Tag(CONTRACT)
@Tag(ATTRIBUTE)
class AttributesContractTest extends AbstractBuilderTest {
	private static final String RANGE = "range";

	/**
	 * Returns the range `[1.0,2.0]`.
	 *
	 * @return a new instance of the range
	 */
	@Nonnull
	private static BigDecimalNumberRange oneToTwo() {
		return BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"));
	}

	/**
	 * Returns the range `[10,20]`, which encodes to the same comparable longs as {@link #oneToTwo()}.
	 *
	 * @return a new instance of the range
	 */
	@Nonnull
	private static BigDecimalNumberRange tenToTwenty() {
		return BigDecimalNumberRange.between(new BigDecimal("10"), new BigDecimal("20"));
	}

	/**
	 * Builds entity attributes holding a single attribute of the type of the passed value.
	 *
	 * @param attributeName name of the attribute
	 * @param value         the value of the attribute
	 * @return the attributes
	 */
	@Nonnull
	private static EntityAttributes attributesWith(@Nonnull String attributeName, @Nonnull Serializable value) {
		return new InitialEntityAttributesBuilder(
			new InternalEntitySchemaBuilder(CATALOG_SCHEMA, PRODUCT_SCHEMA)
				.verifySchemaButCreateOnTheFly()
				.withAttribute(attributeName, value.getClass())
				.toInstance()
		)
			.setAttribute(attributeName, value)
			.build();
	}

	@Nested
	@DisplayName("Single attribute value")
	class AttributeValueTest {

		@Test
		@DisplayName("should differ from a value holding a range whose bounds encode to the same longs")
		void shouldDifferFromValueHoldingRangeEncodedToSameLongs() {
			final AttributeKey key = new AttributeKey(RANGE);

			assertTrue(new AttributeValue(key, oneToTwo()).differsFrom(new AttributeValue(key, tenToTwenty())));
			assertFalse(new AttributeValue(key, oneToTwo()).differsFrom(new AttributeValue(key, oneToTwo())));
		}
	}

	@Nested
	@DisplayName("Sets of attributes")
	class AttributeSetTest {

		@Test
		@DisplayName("should differ when an attribute holds a range whose bounds encode to the same longs")
		void shouldDifferWhenAttributeHoldsRangeEncodedToSameLongs() {
			assertTrue(
				AttributesContract.anyAttributeDifferBetween(
					attributesWith(RANGE, oneToTwo()), attributesWith(RANGE, tenToTwenty())
				)
			);
			assertFalse(
				AttributesContract.anyAttributeDifferBetween(
					attributesWith(RANGE, oneToTwo()), attributesWith(RANGE, oneToTwo())
				)
			);
		}

		@Test
		@DisplayName("should differ when an attribute holds an array of ranges whose bounds encode to the same longs")
		void shouldDifferWhenAttributeHoldsArrayOfRangesEncodedToSameLongs() {
			assertTrue(
				AttributesContract.anyAttributeDifferBetween(
					attributesWith(RANGE, new BigDecimalNumberRange[]{oneToTwo()}),
					attributesWith(RANGE, new BigDecimalNumberRange[]{tenToTwenty()})
				)
			);
		}

		/**
		 * The comparison of attribute sets - which decides whether a reference is stored again - compares plain values
		 * by `compareTo`, so a decimal number rescaled from `1.0` to `1.00` is no change. Only ranges are compared
		 * more strictly on top of it.
		 */
		@Test
		@DisplayName("should not differ when an attribute holds the same decimal number at another scale")
		void shouldNotDifferWhenAttributeHoldsSameDecimalNumberAtAnotherScale() {
			assertFalse(
				AttributesContract.anyAttributeDifferBetween(
					attributesWith("amount", new BigDecimal("1.0")), attributesWith("amount", new BigDecimal("1.00"))
				)
			);
		}
	}
}
