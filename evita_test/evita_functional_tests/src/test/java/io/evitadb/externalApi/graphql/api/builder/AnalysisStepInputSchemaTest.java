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


package io.evitadb.externalApi.graphql.api.builder;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepInputDescriptors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.schema.FieldCoordinates.coordinates;
import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLCodeRegistry.newCodeRegistry;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies graphql-java's native one-of validation using the production descriptor registration path. */
@Tag(EXTERNAL_API)
@Tag(GRAPHQL)
@Tag(SCHEMA)
@DisplayName("Typed GraphQL normalization input")
class AnalysisStepInputSchemaTest {
	private static final GraphQLSchema SCHEMA = createSchema();
	private static final GraphQL EXECUTOR = GraphQL.newGraphQL(SCHEMA).build();

	/** Builds a tiny executable schema around the production input types without starting an API server. */
	private static GraphQLSchema createSchema() {
		final GraphQLSchemaBuildingContext context = Mockito.mock(GraphQLSchemaBuildingContext.class);
		final GraphQLSchemaBuilder<GraphQLSchemaBuildingContext> builder = new GraphQLSchemaBuilder<>(context) { };
		builder.buildAnalysisStepTypes(true);
		final ArgumentCaptor<GraphQLType> types = ArgumentCaptor.forClass(GraphQLType.class);
		Mockito.verify(context, Mockito.atLeastOnce()).registerType(types.capture());
		final GraphQLSchema.Builder schema = GraphQLSchema.newSchema().query(newObject().name("Query")
			.field(newFieldDefinition().name("accept").type(GraphQLBoolean)
				.argument(newArgument().name("step").type(typeRef("InputAnalysisStep")))));
		for (final GraphQLType type : types.getAllValues()) {
			if (type instanceof GraphQLInputObjectType) {
				schema.additionalType(type);
			}
		}
		final DataFetcher<Boolean> fetcher = environment -> environment.getArgument("step") != null;
		return schema.codeRegistry(newCodeRegistry()
			.dataFetcher(coordinates("Query", "accept"), fetcher).build()).build();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
		{lowercase: true}|true
		{asciiFolding: true}|true
		{trim: true}|true
		{removeCharacters: {characters: "- "}}|true
		{}|false
		{lowercase: null}|false
		{lowercase: true, trim: true}|false
		{removeCharacters: {}}|false
		{removeCharacters: {characters: 42}}|false
		{unknown: true}|false
		""")
	@DisplayName("enforces one selected field and typed required parameters for literals")
	void shouldValidateLiteral(String input, boolean valid) {
		final ExecutionResult result = EXECUTOR.execute("{ accept(step: " + input + ") }");
		assertEquals(valid, result.getErrors().isEmpty(), result.getErrors().toString());
	}

	@ParameterizedTest
	@CsvSource({"true,true", "false,false"})
	@DisplayName("enforces one-of selection for variables")
	void shouldValidateVariables(boolean single, boolean valid) {
		final Map<String, Object> value = single ? Map.of("trim", true) : Map.of("trim", true, "lowercase", true);
		final ExecutionResult result = EXECUTOR.execute(ExecutionInput.newExecutionInput()
			.query("query($step: InputAnalysisStep) { accept(step: $step) }")
			.variables(Map.of("step", value)).build());
		assertEquals(valid, result.getErrors().isEmpty(), result.getErrors().toString());
	}

	@Test
	@DisplayName("exposes every installed normalizer as a nullable one-of choice")
	void shouldDescribeInstalledSteps() {
		final GraphQLInputObjectType input = (GraphQLInputObjectType) SCHEMA.getType("InputAnalysisStep");
		assertTrue(input.isOneOf());
		assertEquals(AnalysisStepInputDescriptors.getSteps().stream().map(it -> it.fieldName()).toList(),
			input.getFields().stream().map(it -> it.getName()).toList());
		input.getFields().forEach(field -> assertFalse(field.getType() instanceof GraphQLNonNull));
		final GraphQLInputObjectType parameters =
			(GraphQLInputObjectType) SCHEMA.getType("InputRemoveCharactersAnalysisStepParameters");
		assertEquals("String!", parameters.getField("characters").getType().toString());
	}
}
