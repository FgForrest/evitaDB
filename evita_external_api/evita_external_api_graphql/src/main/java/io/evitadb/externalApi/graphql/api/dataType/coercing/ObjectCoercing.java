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

package io.evitadb.externalApi.graphql.api.dataType.coercing;

import graphql.Assert;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.*;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.util.FpKit;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static graphql.language.ObjectField.newObjectField;

/**
 * Generic {@link Coercing} for converting between Java values and basic GraphQL values (with support for whole objects).
 *
 * Original code comes from <a href="https://github.com/graphql-java/graphql-java-extended-scalars">extended scalars library</a>.
 * Specifically from {@code ObjectScalar} class.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class ObjectCoercing implements Coercing<Object, Object> {

	@Override
	public Object serialize(
		@Nonnull Object dataFetcherResult,
		@Nonnull GraphQLContext graphQLContext,
		@Nonnull Locale locale
	) throws CoercingSerializeException {
		return dataFetcherResult;
	}

	@Nonnull
	@Override
	public Object parseValue(
		@Nonnull Object input,
		@Nonnull GraphQLContext graphQLContext,
		@Nonnull Locale locale
	) throws CoercingParseValueException {
		return input;
	}

	@Nonnull
	@Override
	public Object parseLiteral(
		@Nonnull Value<?> input,
		@Nonnull CoercedVariables variables,
		@Nonnull GraphQLContext graphQLContext,
		@Nonnull Locale locale
	) throws CoercingParseLiteralException {
		if (input instanceof FloatValue floatValue) {
			return floatValue.getValue();
		}
		if (input instanceof StringValue stringValue) {
			return stringValue.getValue();
		}
		if (input instanceof IntValue intValue) {
			return intValue.getValue().longValueExact();
		}
		if (input instanceof BooleanValue booleanValue) {
			return booleanValue.isValue();
		}
		if (input instanceof EnumValue enumValue) {
			return enumValue.getName();
		}
		if (input instanceof VariableReference variableReference) {
			String varName = variableReference.getName();
			return variables.get(varName);
		}
		if (input instanceof ArrayValue arrayValue) {
			//noinspection rawtypes
			List<Value> values = arrayValue.getValues();
			return values.stream()
				.map(v -> parseLiteral(v, variables, graphQLContext, locale))
				.collect(Collectors.toList());
		}
		if (input instanceof ObjectValue objectValue) {
			List<ObjectField> values = objectValue.getObjectFields();
			Map<String, Object> parsedValues = new LinkedHashMap<>();
			values.forEach(fld -> {
				Object parsedValue = parseLiteral(fld.getValue(), variables, graphQLContext, locale);
				parsedValues.put(fld.getName(), parsedValue);
			});
			return parsedValues;
		}
		return Assert.assertShouldNeverHappen("We have covered all Value types");
	}

	@Nonnull
	@Override
	public Value<?> valueToLiteral(
		@Nonnull Object input,
		@Nonnull GraphQLContext graphQLContext,
		@Nonnull Locale locale
	) {
		if (input instanceof String stringInput) {
			return new StringValue(stringInput);
		}
		if (input instanceof Float floatInput) {
			return new FloatValue(BigDecimal.valueOf(floatInput));
		}
		if (input instanceof Double doubleInput) {
			return new FloatValue(BigDecimal.valueOf(doubleInput));
		}
		if (input instanceof BigDecimal bigDecimal) {
			return new FloatValue(bigDecimal);
		}
		if (input instanceof BigInteger bigInteger) {
			return new IntValue(bigInteger);
		}
		if (input instanceof Number number) {
			long l = number.longValue();
			return new IntValue(BigInteger.valueOf(l));
		}
		if (input instanceof Boolean booleanInput) {
			return new BooleanValue(booleanInput);
		}
		if (FpKit.isIterable(input)) {
			return handleIterable(FpKit.toIterable(input), graphQLContext, locale);
		}
		if (input instanceof Map<?, ?> map) {
			return handleMap(map, graphQLContext, locale);
		}
		throw new UnsupportedOperationException("The ObjectScalar cant handle values of type `" + input.getClass() + "`.");
	}

	private Value<?> handleMap(
		Map<?, ?> map,
		@Nonnull GraphQLContext graphQLContext,
		@Nonnull Locale locale
	) {
		ObjectValue.Builder builder = ObjectValue.newObjectValue();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			String name = String.valueOf(entry.getKey());
			Value<?> value = valueToLiteral(entry.getValue(), graphQLContext, locale);

			builder.objectField(
				newObjectField().name(name).value(value).build()
			);
		}
		return builder.build();
	}

	@SuppressWarnings("rawtypes")
	private Value<?> handleIterable(
		Iterable<?> input,
		@Nonnull GraphQLContext graphQLContext,
		@Nonnull Locale locale
	) {
		List<Value> values = new ArrayList<>();
		for (Object val : input) {
			values.add(valueToLiteral(val, graphQLContext, locale));
		}
		return ArrayValue.newArrayValue().values(values).build();
	}
}
