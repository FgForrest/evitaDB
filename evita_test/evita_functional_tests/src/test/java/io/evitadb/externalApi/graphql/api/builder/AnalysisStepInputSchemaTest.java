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

import graphql.Directives;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLSchema;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.DualPurposeAnalysisStepTestSupport;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepInputDescriptors;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepInputDescriptors.StepInput;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.AnalysisStepConverter;
import io.evitadb.externalApi.api.resolver.mutation.Input;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies graphql-java's native one-of validation using the production descriptor registration path. The input types
 * are registered into a real building context, which assembles the executable schema the same way the API does.
 */
@Tag(EXTERNAL_API)
@Tag(GRAPHQL)
@Tag(SCHEMA)
@DisplayName("Typed GraphQL normalization input")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalysisStepInputSchemaTest implements EvitaTestSupport {
	private GraphQLSchema schema;
	private GraphQL executor;

	/**
	 * Builds a tiny executable schema around the production input types without starting an API server. The engine
	 * only satisfies the building context; nothing is read from it, so it is closed as soon as the schema exists.
	 */
	@BeforeAll
	void setUp() {
		final TestPaths paths = createTestPaths("AnalysisStepInputSchema");
		try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			this.schema = createSchema(evita);
		} finally {
			cleanupTestPaths(paths);
		}
		this.executor = GraphQL.newGraphQL(this.schema).build();
	}

	/**
	 * Registers the production input types and one probing query field into a real building context.
	 *
	 * @param evita engine the building context is created for
	 * @return schema whose `accept(step: InputAnalysisStep)` query reports whether the input was coerced
	 */
	@Nonnull
	private static GraphQLSchema createSchema(@Nonnull Evita evita) {
		final GraphQLSchemaBuildingContext context = new GraphQLSchemaBuildingContext(new GraphQLOptions(), evita);
		final GraphQLSchemaBuilder<GraphQLSchemaBuildingContext> builder = new GraphQLSchemaBuilder<>(context) { };
		builder.registerAnalysisStepInputTypes();
		final DataFetcher<Boolean> fetcher = environment -> environment.getArgument("step") != null;
		context.registerQueryField(new BuiltFieldDescriptor(
			newFieldDefinition().name("accept").type(GraphQLBoolean)
				.argument(newArgument().name("step").type(typeRef("InputAnalysisStep")))
				.build(),
			fetcher
		));
		return context.buildGraphQLSchema();
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
		final ExecutionResult result = this.executor.execute("{ accept(step: " + input + ") }");
		assertEquals(valid, result.getErrors().isEmpty(), result.getErrors().toString());
	}

	@ParameterizedTest
	@CsvSource({"true,true", "false,false"})
	@DisplayName("enforces one-of selection for variables")
	void shouldValidateVariables(boolean single, boolean valid) {
		final Map<String, Object> value = single ? Map.of("trim", true) : Map.of("trim", true, "lowercase", true);
		final ExecutionResult result = this.executor.execute(ExecutionInput.newExecutionInput()
			.query("query($step: InputAnalysisStep) { accept(step: $step) }")
			.variables(Map.of("step", value)).build());
		assertEquals(valid, result.getErrors().isEmpty(), result.getErrors().toString());
	}

	@Test
	@DisplayName("exposes every installed normalizer as a nullable one-of choice")
	void shouldDescribeInstalledSteps() {
		final GraphQLInputObjectType input = (GraphQLInputObjectType) this.schema.getType("InputAnalysisStep");
		assertTrue(input.isOneOf());
		assertEquals(AnalysisStepInputDescriptors.getSteps().stream().map(StepInput::fieldName).toList(),
			input.getFields().stream().map(GraphQLInputObjectField::getName).toList());
		input.getFields().forEach(field -> assertFalse(field.getType() instanceof GraphQLNonNull));
		final GraphQLInputObjectType parameters =
			(GraphQLInputObjectType) this.schema.getType("InputRemoveCharactersAnalysisStepParameters");
		assertEquals("String!", parameters.getField("characters").getType().toString());
	}
	/** A dual-purpose provider must retain normalization spelling and expose a distinct fulltext parameter type. */
	@Test
	@DisplayName("keeps dual-purpose provider parameter types distinct")
	void shouldGenerateDistinctDualPurposeParameterTypes() {
		final AnalysisStepRegistry registry = DualPurposeAnalysisStepTestSupport.registry("KeepOriginal");
		final TestPaths paths = createTestPaths("DualPurposeGraphQLInput");
		try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			final GraphQLSchemaBuildingContext context = new GraphQLSchemaBuildingContext(new GraphQLOptions(), evita);
			final GraphQLSchemaBuilder<GraphQLSchemaBuildingContext> builder = new GraphQLSchemaBuilder<>(context) { };
			builder.registerAnalysisStepInputTypes(phase -> AnalysisStepInputDescriptors.getSteps(registry, phase));
			context.registerQueryField(new BuiltFieldDescriptor(newFieldDefinition().name("accept").type(GraphQLBoolean)
				.argument(newArgument().name("step").type(typeRef("InputFulltextWordsStep")))
				.argument(newArgument().name("normalization").type(typeRef("InputAnalysisStep"))).build(),
				environment -> {
					final AnalysisStep step = AnalysisStepConverter.fromInput(new Input("step",
						environment.getArgument("step"), TestMutationResolvingExceptionFactory.INSTANCE, Map.of()),
						TestMutationResolvingExceptionFactory.INSTANCE,
						AnalysisStepInputDescriptors.Context.WORDS, registry);
					assertEquals(Map.of("KeepOriginal", "x"), step.parameters());
					final AnalysisStep normalization = AnalysisStepConverter.fromInput(new Input("normalization",
						environment.getArgument("normalization"), TestMutationResolvingExceptionFactory.INSTANCE,
						Map.of()), TestMutationResolvingExceptionFactory.INSTANCE,
						AnalysisStepInputDescriptors.Context.NORMALIZATION, registry);
					assertEquals(AnalysisStep.of("custom", Map.of("KeepOriginal", "x")), normalization);
					return true;
				}));
			final GraphQLSchema customSchema = context.buildGraphQLSchema();
			final GraphQLInputObjectType normalization =
				(GraphQLInputObjectType) customSchema.getType("InputCustomAnalysisStepParameters");
			final GraphQLInputObjectType fulltext =
				(GraphQLInputObjectType) customSchema.getType("InputFulltextCustomAnalysisStepParameters");
			assertEquals(List.of("KeepOriginal"), normalization.getFields().stream()
				.map(GraphQLInputObjectField::getName).toList());
			assertEquals(List.of("keepOriginal"), fulltext.getFields().stream()
				.map(GraphQLInputObjectField::getName).toList());
			final ExecutionResult result = GraphQL.newGraphQL(customSchema).build().execute(
				"{ accept(step: {custom: {keepOriginal: \"x\"}}, normalization: {custom: {KeepOriginal: \"x\"}}) }");
			assertEquals(List.of(), result.getErrors());
			assertEquals(Map.of("accept", true), result.getData());
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Two provider parameters that camel-case to the same fulltext input name would merge into one field, and the
	 * value sent for it would reach only one of them; the metadata must refuse the provider instead.
	 */
	@Test
	@DisplayName("refuses fulltext parameters whose camel-case input names collide")
	void shouldRefuseFulltextParametersCollidingAfterCamelCase() {
		final AnalysisStepRegistry registry =
			DualPurposeAnalysisStepTestSupport.registry("keep-original", "keepOriginal");

		assertThrows(ExternalApiInternalError.class, () -> AnalysisStepInputDescriptors.getSteps(
			registry, AnalysisStepInputDescriptors.Context.WORDS));
	}

	/**
	 * Exercises a hyphenated provider key on the fulltext GraphQL input. The normalization metadata retains the raw
	 * key; GraphQL's identifier grammar cannot expose that normalization field without changing its existing contract.
	 */
	@Test
	@DisplayName("generates and parses camel-case fulltext input for a hyphenated dual-purpose provider key")
	void shouldGenerateHyphenatedDualPurposeFulltextInput() {
		final AnalysisStepRegistry registry = DualPurposeAnalysisStepTestSupport.registry("keep-original");
		final StepInput normalization = AnalysisStepInputDescriptors.getSteps(registry).stream()
			.filter(step -> step.stepName().equals("custom")).findFirst().orElseThrow();
		final StepInput fulltext = AnalysisStepInputDescriptors.getSteps(registry,
			AnalysisStepInputDescriptors.Context.WORDS).stream()
			.filter(step -> step.stepName().equals("custom")).findFirst().orElseThrow();
		assertEquals("InputCustomAnalysisStepParameters", normalization.parameters().name());
		assertEquals(List.of("keep-original"), normalization.parameters().staticProperties().stream()
			.map(property -> property.name()).toList());
		assertEquals("InputFulltextCustomAnalysisStepParameters", fulltext.parameters().name());
		final TestPaths paths = createTestPaths("HyphenatedGraphQLInput");
		try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			final GraphQLSchemaBuildingContext context = new GraphQLSchemaBuildingContext(new GraphQLOptions(), evita);
			final GraphQLSchemaBuilder<GraphQLSchemaBuildingContext> builder = new GraphQLSchemaBuilder<>(context) { };
			final GraphQLInputObjectType parameters =
				fulltext.parameters().to(builder.inputObjectBuilderTransformer).build();
			assertEquals(List.of("keepOriginal"), parameters.getFields().stream()
				.map(GraphQLInputObjectField::getName).toList());
			assertEquals("String!", parameters.getField("keepOriginal").getType().toString());
			context.registerType(parameters);
			context.registerType(AnalysisStepInputDescriptors.getInputDescriptor(
				AnalysisStepInputDescriptors.Context.WORDS, List.of(fulltext))
				.to(builder.inputObjectBuilderTransformer)
				.withAppliedDirective(Directives.OneOfDirective.toAppliedDirective()).build());
			context.registerQueryField(new BuiltFieldDescriptor(newFieldDefinition().name("accept").type(GraphQLBoolean)
				.argument(newArgument().name("step").type(typeRef("InputFulltextWordsStep"))).build(), environment -> {
					final AnalysisStep step = AnalysisStepConverter.fromInput(new Input("step",
						environment.getArgument("step"), TestMutationResolvingExceptionFactory.INSTANCE, Map.of()),
						TestMutationResolvingExceptionFactory.INSTANCE,
						AnalysisStepInputDescriptors.Context.WORDS, registry);
					assertEquals(Map.of("keep-original", "x"), step.parameters());
					registry.resolveFulltext(step);
					return true;
				}));
			final ExecutionResult result = GraphQL.newGraphQL(context.buildGraphQLSchema()).build()
				.execute("{ accept(step: {custom: {keepOriginal: \"x\"}}) }");
			assertEquals(List.of(), result.getErrors());
			assertEquals(Map.of("accept", true), result.getData());
		} finally {
			cleanupTestPaths(paths);
		}
	}

}
