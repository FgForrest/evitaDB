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

package io.evitadb.externalApi.graphql.dataType;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeReference;
import io.evitadb.dataType.trie.Trie;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter.ConvertedEnum;
import io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DataTypesConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class DataTypesConverterTest {

	@Test
	void shouldConvertScalarJavaTypeToCorrectGraphQLType() {
		assertScalarType(
			GraphQLScalars.STRING,
			DataTypesConverter.getGraphQLScalarType(String.class)
		);
		assertScalarType(
			GraphQLScalars.STRING,
			DataTypesConverter.getGraphQLScalarType(String.class, false)
		);
		assertRequiredScalarType(
			GraphQLScalars.STRING,
			DataTypesConverter.getGraphQLScalarType(String.class, true)
		);
	}

	@Test
	void shouldConvertArrayScalarJavaTypeToCorrectListGraphQLType() {
		assertListTypeOfScalarTypes(
			GraphQLScalars.STRING,
			DataTypesConverter.getGraphQLScalarType(String[].class)
		);
		assertListTypeOfScalarTypes(
			GraphQLScalars.STRING,
			DataTypesConverter.getGraphQLScalarType(String[].class, false)
		);
		assertRequiredListTypeOfScalarTypes(
			GraphQLScalars.STRING,
			DataTypesConverter.getGraphQLScalarType(String[].class, true)
		);
	}

	@Test
	void shouldConvertJavaTypeWithReplacementTypeToCorrectGraphQLType() {
		assertScalarType(
			GraphQLScalars.STRING,
			DataTypesConverter.getGraphQLScalarType(Serializable.class, String.class, false)
		);
		assertRequiredScalarType(
			GraphQLScalars.STRING,
			DataTypesConverter.getGraphQLScalarType(Serializable.class, String.class, true)
		);

		assertListTypeOfScalarTypes(
			GraphQLScalars.STRING,
			DataTypesConverter.getGraphQLScalarType(Serializable[].class, String.class, false)
		);
		assertRequiredListTypeOfScalarTypes(
			GraphQLScalars.STRING,
			DataTypesConverter.getGraphQLScalarType(Serializable[].class, String.class, true)
		);
	}

	@Test
	void shouldConvertCustomJavaEnumToGraphQLEnum() {
		assertConvertedEnumType(DataTypesConverter.getGraphQLEnumType(DummyEnum.class));
		assertConvertedEnumType(DataTypesConverter.getGraphQLEnumType(DummyEnum.class, false));
		assertRequiredConvertedEnumType(DataTypesConverter.getGraphQLEnumType(DummyEnum.class, true));
	}

	@Test
	void shouldConvertArrayOfCustomJavaEnumToGraphQLEnum() {
		assertListTypeOfConvertedEnumTypes(DataTypesConverter.getGraphQLEnumType(DummyEnum[].class));
		assertListTypeOfConvertedEnumTypes(DataTypesConverter.getGraphQLEnumType(DummyEnum[].class, false));
		assertRequiredListTypeOfConvertedEnumTypes(DataTypesConverter.getGraphQLEnumType(DummyEnum[].class, true));
	}


	@Test
	void shouldNotConvertUnsupportedJavaType() {
		assertThrows(EvitaInternalError.class, () -> DataTypesConverter.getGraphQLScalarType(Trie.class));
	}

	private void assertScalarType(@Nonnull GraphQLScalarType expectedScalarType, @Nonnull GraphQLScalarType actualScalarType) {
		assertEquals(expectedScalarType, actualScalarType);
	}

	private void assertRequiredScalarType(@Nonnull GraphQLScalarType expectedScalarType, @Nonnull GraphQLType actualResultType) {
		assertTrue(actualResultType instanceof GraphQLNonNull);
		final GraphQLNonNull actualRequiredScalarType = (GraphQLNonNull) actualResultType;
		assertEquals(expectedScalarType, actualRequiredScalarType.getOriginalWrappedType());
	}

	private void assertListTypeOfScalarTypes(@Nonnull GraphQLScalarType expectedScalarType, @Nonnull GraphQLType actualResultType) {
		assertTrue(actualResultType instanceof GraphQLList);

		final GraphQLList actualListResultType = (GraphQLList) actualResultType;
		assertTrue(actualListResultType.getOriginalWrappedType() instanceof GraphQLNonNull);

		final GraphQLNonNull actualRequiredScalarType = (GraphQLNonNull) actualListResultType.getOriginalWrappedType();
		assertEquals(expectedScalarType, actualRequiredScalarType.getOriginalWrappedType());
	}

	private void assertRequiredListTypeOfScalarTypes(@Nonnull GraphQLScalarType expectedScalarType, @Nonnull GraphQLType actualResultType) {
		assertTrue(actualResultType instanceof GraphQLNonNull);

		final GraphQLNonNull actualRequiredListType = (GraphQLNonNull) actualResultType;
		assertTrue(actualRequiredListType.getOriginalWrappedType() instanceof GraphQLList);

		final GraphQLList actualListResultType = (GraphQLList) actualRequiredListType.getOriginalWrappedType();
		assertTrue(actualListResultType.getOriginalWrappedType() instanceof GraphQLNonNull);

		final GraphQLNonNull actualRequiredScalarType = (GraphQLNonNull) actualListResultType.getOriginalWrappedType();
		assertEquals(expectedScalarType, actualRequiredScalarType.getOriginalWrappedType());
	}


	private void assertGraphQLEnumType(GraphQLEnumType graphQLEnumType) {
		assertEquals("DummyEnum", graphQLEnumType.getName());

		final List<GraphQLEnumValueDefinition> graphQLEnumTypeValues = graphQLEnumType.getValues();
		assertEquals(2, graphQLEnumTypeValues.size());

		assertEquals("ONE", graphQLEnumTypeValues.get(0).getName());
		assertEquals(DummyEnum.ONE, graphQLEnumTypeValues.get(0).getValue());

		assertEquals("TWO", graphQLEnumTypeValues.get(1).getName());
		assertEquals(DummyEnum.TWO, graphQLEnumTypeValues.get(1).getValue());
	}

	private void assertConvertedEnumType(@Nonnull ConvertedEnum<?> actualConvertedEnum) {
		assertTrue(actualConvertedEnum.resultType() instanceof GraphQLTypeReference);
		assertEquals("DummyEnum", ((GraphQLTypeReference) actualConvertedEnum.resultType()).getName());

		assertGraphQLEnumType(actualConvertedEnum.enumType());
	}

	private void assertRequiredConvertedEnumType(@Nonnull ConvertedEnum<?> actualConvertedEnum) {
		final GraphQLInputType actualResultType = actualConvertedEnum.resultType();

		assertTrue(actualResultType instanceof GraphQLNonNull);

		final GraphQLNonNull actualRequiredScalarType = (GraphQLNonNull) actualResultType;
		assertTrue(actualRequiredScalarType.getOriginalWrappedType() instanceof GraphQLTypeReference);

		final GraphQLTypeReference actualTypeReference = (GraphQLTypeReference) actualRequiredScalarType.getOriginalWrappedType();
		assertEquals("DummyEnum", actualTypeReference.getName());

		assertGraphQLEnumType(actualConvertedEnum.enumType());
	}

	private void assertListTypeOfConvertedEnumTypes(@Nonnull ConvertedEnum<?> actualConvertedEnum) {
		final GraphQLInputType actualResultType = actualConvertedEnum.resultType();

		assertTrue(actualResultType instanceof GraphQLList);

		final GraphQLList actualListResultType = (GraphQLList) actualResultType;
		assertTrue(actualListResultType.getOriginalWrappedType() instanceof GraphQLNonNull);

		final GraphQLNonNull actualRequiredType = (GraphQLNonNull) actualListResultType.getOriginalWrappedType();
		assertTrue(actualRequiredType.getOriginalWrappedType() instanceof GraphQLTypeReference);

		final GraphQLTypeReference actualTypeReference = (GraphQLTypeReference) actualRequiredType.getOriginalWrappedType();
		assertEquals("DummyEnum", actualTypeReference.getName());

		assertGraphQLEnumType(actualConvertedEnum.enumType());
	}

	private void assertRequiredListTypeOfConvertedEnumTypes(@Nonnull ConvertedEnum<?> actualConvertedEnum) {
		final GraphQLInputType actualResultType = actualConvertedEnum.resultType();

		assertTrue(actualResultType instanceof GraphQLNonNull);

		final GraphQLNonNull actualRequiredListType = (GraphQLNonNull) actualResultType;
		assertTrue(actualRequiredListType.getOriginalWrappedType() instanceof GraphQLList);

		final GraphQLList actualListResultType = (GraphQLList) actualRequiredListType.getOriginalWrappedType();
		assertTrue(actualListResultType.getOriginalWrappedType() instanceof GraphQLNonNull);

		final GraphQLNonNull actualRequiredType = (GraphQLNonNull) actualListResultType.getOriginalWrappedType();
		assertTrue(actualRequiredType.getOriginalWrappedType() instanceof GraphQLTypeReference);

		final GraphQLTypeReference actualTypeReference = (GraphQLTypeReference) actualRequiredType.getOriginalWrappedType();
		assertEquals("DummyEnum", actualTypeReference.getName());

		assertGraphQLEnumType(actualConvertedEnum.enumType());
	}

	enum DummyEnum {
		ONE, TWO
	}
}
