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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
	public Object serialize(@Nonnull Object input) throws CoercingSerializeException {
		return input;
	}

	@Nonnull
	@Override
	public Object parseValue(@Nonnull Object input) throws CoercingParseValueException {
		return input;
	}

	@Nonnull
	@Override
	public Object parseLiteral(@Nonnull Object input) throws CoercingParseLiteralException {
		return parseLiteral(input, Collections.emptyMap());
	}

	@Nonnull
	@Override
	public Object parseLiteral(Object input, Map<String, Object> variables) throws CoercingParseLiteralException {
		if (!(input instanceof Value)) {
			throw new CoercingParseLiteralException(
				"Expected AST type `Value` but was '" + input + "'."
			);
		}
		if (input instanceof FloatValue) {
			return ((FloatValue) input).getValue();
		}
		if (input instanceof StringValue) {
			return ((StringValue) input).getValue();
		}
		if (input instanceof IntValue) {
			return ((IntValue) input).getValue().longValueExact();
		}
		if (input instanceof BooleanValue) {
			return ((BooleanValue) input).isValue();
		}
		if (input instanceof EnumValue) {
			return ((EnumValue) input).getName();
		}
		if (input instanceof VariableReference) {
			String varName = ((VariableReference) input).getName();
			return variables.get(varName);
		}
		if (input instanceof ArrayValue) {
			//noinspection rawtypes
			List<Value> values = ((ArrayValue) input).getValues();
			return values.stream()
				.map(v -> parseLiteral(v, variables))
				.collect(Collectors.toList());
		}
		if (input instanceof ObjectValue) {
			List<ObjectField> values = ((ObjectValue) input).getObjectFields();
			Map<String, Object> parsedValues = new LinkedHashMap<>();
			values.forEach(fld -> {
				Object parsedValue = parseLiteral(fld.getValue(), variables);
				parsedValues.put(fld.getName(), parsedValue);
			});
			return parsedValues;
		}
		return Assert.assertShouldNeverHappen("We have covered all Value types");
	}

	@Nonnull
	@Override
	public Value<?> valueToLiteral(@Nonnull Object input) {
		if (input instanceof String) {
			return new StringValue((String) input);
		}
		if (input instanceof Float) {
			return new FloatValue(BigDecimal.valueOf((Float) input));
		}
		if (input instanceof Double) {
			return new FloatValue(BigDecimal.valueOf((Double) input));
		}
		if (input instanceof BigDecimal) {
			return new FloatValue((BigDecimal) input);
		}
		if (input instanceof BigInteger) {
			return new IntValue((BigInteger) input);
		}
		if (input instanceof Number) {
			long l = ((Number) input).longValue();
			return new IntValue(BigInteger.valueOf(l));
		}
		if (input instanceof Boolean) {
			return new BooleanValue((Boolean) input);
		}
		if (FpKit.isIterable(input)) {
			return handleIterable(FpKit.toIterable(input));
		}
		if (input instanceof Map) {
			return handleMap((Map<?, ?>) input);
		}
		throw new UnsupportedOperationException("The ObjectScalar cant handle values of type `" + input.getClass() + "`.");
	}

	private Value<?> handleMap(Map<?, ?> map) {
		ObjectValue.Builder builder = ObjectValue.newObjectValue();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			String name = String.valueOf(entry.getKey());
			Value<?> value = valueToLiteral(entry.getValue());

			builder.objectField(
				newObjectField().name(name).value(value).build()
			);
		}
		return builder.build();
	}

	@SuppressWarnings("rawtypes")
	private Value<?> handleIterable(Iterable<?> input) {
		List<Value> values = new ArrayList<>();
		for (Object val : input) {
			values.add(valueToLiteral(val));
		}
		return ArrayValue.newArrayValue().values(values).build();
	}
}
