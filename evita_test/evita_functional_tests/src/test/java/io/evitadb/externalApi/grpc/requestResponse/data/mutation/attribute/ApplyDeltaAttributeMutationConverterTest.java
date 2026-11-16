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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation.attribute;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.LongNumberRange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.ATTRIBUTE;

@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(ATTRIBUTE)
class ApplyDeltaAttributeMutationConverterTest {


	private static ApplyDeltaAttributeMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = ApplyDeltaAttributeMutationConverter.INSTANCE;
	}

	@Test
	void shouldConvertMutation() {
		final ApplyDeltaAttributeMutation<?> mutation1 = new ApplyDeltaAttributeMutation<>("code", Locale.US, 10L);
		assertEquals(mutation1, converter.convert(converter.convert(mutation1)));

		final ApplyDeltaAttributeMutation<?> mutation2 = new ApplyDeltaAttributeMutation<>("code", 10L);
		assertEquals(mutation2, converter.convert(converter.convert(mutation2)));

		final ApplyDeltaAttributeMutation<?> mutation3 = new ApplyDeltaAttributeMutation<>("code", 10L, LongNumberRange.between(1L, 20L));
		assertEquals(mutation3, converter.convert(converter.convert(mutation3)));
	}

	@Test
	@DisplayName("should refuse a delta result outside a required decimal range whose precision comes from its bounds")
	void shouldRefuseDeltaResultOutsideRequiredRangeOfImplicitPrecision() {
		// `[0.0,1.0]` compares at one decimal place, so `1.0 + 0.4 = 1.4` lies outside it - whereas at zero decimal
		// places both the bound and the result round to `1`
		final ApplyDeltaAttributeMutation<BigDecimal> sent = new ApplyDeltaAttributeMutation<>(
			"stock", new BigDecimal("0.4"),
			BigDecimalNumberRange.between(new BigDecimal("0.0"), new BigDecimal("1.0"))
		);
		final ApplyDeltaAttributeMutation<?> readBack = converter.convert(converter.convert(sent));
		final EntitySchema productSchema = EntitySchema._internalBuild("product");
		final AttributeValue existingValue = new AttributeValue(new AttributeKey("stock"), new BigDecimal("1.0"));

		assertThrows(
			InvalidMutationException.class, () -> sent.mutateLocal(productSchema, existingValue),
			"the sent mutation must refuse the result"
		);
		assertThrows(
			InvalidMutationException.class, () -> readBack.mutateLocal(productSchema, existingValue),
			"the mutation read back must refuse the very result the sent mutation refuses"
		);
		assertEquals(sent, readBack);
	}

}
