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

import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.NumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcApplyDeltaAttributeMutation;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * Converts between {@link ApplyDeltaAttributeMutation} and {@link GrpcApplyDeltaAttributeMutation} in both directions.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ApplyDeltaAttributeMutationConverter extends AttributeMutationConverter<ApplyDeltaAttributeMutation<?>, GrpcApplyDeltaAttributeMutation> {
	public static final ApplyDeltaAttributeMutationConverter INSTANCE = new ApplyDeltaAttributeMutationConverter();

	@Override
	@Nonnull
	public ApplyDeltaAttributeMutation<?> convert(@Nonnull GrpcApplyDeltaAttributeMutation mutation) {
		final AttributesContract.AttributeKey key = buildAttributeKey(mutation.getAttributeName(), mutation.getAttributeLocale());

		final Number delta = switch (mutation.getDeltaCase()) {
			case INTEGERDELTA -> mutation.getIntegerDelta();
			case LONGDELTA -> mutation.getLongDelta();
			case BIGDECIMALDELTA -> EvitaDataTypesConverter.toBigDecimal(mutation.getBigDecimalDelta());
			default -> throw new EvitaInvalidUsageException("Delta value has to be provided when applying `GrpcApplyDeltaAttributeMutation`!");
		};

		final NumberRange<?> requiredRange = switch (mutation.getRequiredRangeAfterApplicationCase()) {
			case INTEGERREQUIREDRANGEAFTERAPPLICATION -> EvitaDataTypesConverter.toIntegerNumberRange(mutation.getIntegerRequiredRangeAfterApplication());
			case LONGREQUIREDRANGEAFTERAPPLICATION -> EvitaDataTypesConverter.toLongNumberRange(mutation.getLongRequiredRangeAfterApplication());
			case BIGDECIMALREQUIREDRANGEAFTERAPPLICATION -> EvitaDataTypesConverter.toBigDecimalNumberRange(mutation.getBigDecimalRequiredRangeAfterApplication());
			case REQUIREDRANGEAFTERAPPLICATION_NOT_SET -> null;
		};

		//noinspection unchecked,rawtypes
		return new ApplyDeltaAttributeMutation(key, delta, requiredRange);
	}

	@Nonnull
	@Override
	public GrpcApplyDeltaAttributeMutation convert(@Nonnull ApplyDeltaAttributeMutation<?> mutation) {
		final GrpcApplyDeltaAttributeMutation.Builder builder = GrpcApplyDeltaAttributeMutation.newBuilder()
			.setAttributeName(mutation.getAttributeKey().attributeName());

		final Locale locale = mutation.getAttributeKey().locale();
		if (locale != null) {
			builder.setAttributeLocale(EvitaDataTypesConverter.toGrpcLocale(locale));
		}

		final Number delta = mutation.getDelta();
		if (delta instanceof Integer integerDelta) {
			builder.setIntegerDelta(integerDelta);
		} else if (delta instanceof Long longDelta) {
			builder.setLongDelta(longDelta);
		} else if (delta instanceof BigDecimal bigDecimalDelta) {
			builder.setBigDecimalDelta(EvitaDataTypesConverter.toGrpcBigDecimal(bigDecimalDelta));
		}

		final NumberRange<?> requiredRangeAfterApplication = mutation.getRequiredRangeAfterApplication();
		if (requiredRangeAfterApplication != null) {
			if (requiredRangeAfterApplication instanceof IntegerNumberRange integerNumberRange) {
				builder.setIntegerRequiredRangeAfterApplication(EvitaDataTypesConverter.toGrpcIntegerNumberRange(integerNumberRange));
			} else if (requiredRangeAfterApplication instanceof LongNumberRange longNumberRange) {
				builder.setLongRequiredRangeAfterApplication(EvitaDataTypesConverter.toGrpcLongNumberRange(longNumberRange));
			} else if (requiredRangeAfterApplication instanceof BigDecimalNumberRange bigDecimalNumberRange) {
				builder.setBigDecimalRequiredRangeAfterApplication(EvitaDataTypesConverter.toGrpcBigDecimalNumberRange(bigDecimalNumberRange));
			}
		}

		return builder.build();
	}
}
