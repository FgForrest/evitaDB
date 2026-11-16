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

package io.evitadb.externalApi.rest.api.catalog.schemaApi.builder;

import io.evitadb.externalApi.api.catalog.schemaApi.model.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepInputDescriptors.StepInput;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepInputDescriptors;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.dataType.GenericObject;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.rest.api.builder.RestBuildingContext;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiComplexType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Generates typed analysis input from phase-specific installed-provider metadata. Each closed branch requires
 * exactly its own step field; combining them with `oneOf` gives REST the same exclusive shape as GraphQL.
 */
public final class AnalysisStepInputObjectBuilder {

	/** Prevents construction of a stateless schema helper. */
	private AnalysisStepInputObjectBuilder() {
	}

	/**
	 * Registers the common output, typed parameter objects and exclusive step input of every phase in an API's own
	 * type registry, generated from the providers of the process-wide registry
	 * ({@link AnalysisStepInputDescriptors#getRegistry()}).
	 *
	 * @param buildingContext catalog or system API registry
	 * @param objectTransformer converter for the shared parameter descriptors
	 */
	public static void registerTypes(
		@Nonnull RestBuildingContext buildingContext,
		@Nonnull ObjectDescriptorToOpenApiObjectTransformer objectTransformer
	) {
		registerTypes(buildingContext, objectTransformer, AnalysisStepInputDescriptors::getSteps);
	}

	/**
	 * Registers the same types from the given provider metadata, which must be the metadata the server's input
	 * converter uses, so the generated types accept exactly what the converter parses.
	 *
	 * @param buildingContext registry receiving the OpenAPI types
	 * @param objectTransformer renders shared parameter descriptors as OpenAPI objects
	 * @param providers ordered metadata of the validated providers admitted in each phase
	 */
	public static void registerTypes(
		@Nonnull RestBuildingContext buildingContext,
		@Nonnull ObjectDescriptorToOpenApiObjectTransformer objectTransformer,
		@Nonnull Function<AnalysisStepInputDescriptors.Context, List<StepInput>> providers
	) {
		buildingContext.registerType(ObjectDescriptor.from(AnalysisStepDescriptor.THIS)
			.name(AnalysisStepDescriptor.THIS.name())
			.staticProperty(PropertyDescriptor.from(AnalysisStepDescriptor.PARAMETERS)
				.type(nonNull(GenericObject.class))
				.build())
			.build()
			.to(objectTransformer)
			.build());
		final Map<String, ObjectDescriptor> parameters = new HashMap<>();
		for (final AnalysisStepInputDescriptors.Context context : AnalysisStepInputDescriptors.Context.values()) {
			final List<StepInput> steps = providers.apply(context);
			for (final StepInput step : steps) {
				if (step.parameters() != null) {
					final ObjectDescriptor previous =
						parameters.putIfAbsent(step.parameters().name(), step.parameters());
					if (previous != null && !previous.equals(step.parameters())) {
						throw new ExternalApiInternalError("Conflicting analysis parameter type `" +
							step.parameters().name() + "`.");
					}
					if (previous == null) {
						buildingContext.registerType(step.parameters().to(objectTransformer)
							.forbidAdditionalProperties().build());
					}
				}
			}
			buildingContext.registerType(new AnalysisStepInputType(
				AnalysisStepInputDescriptors.getInputDescriptor(context).name(), steps));
		}
		for (final ObjectDescriptor descriptor : List.of(
			SynonymGroupDescriptor.THIS, SynonymGroupDescriptor.THIS_INPUT,
			FulltextResourceContentDescriptor.THIS, FulltextResourceContentDescriptor.THIS_INPUT,
			FulltextAnalysisPhasesDescriptor.THIS, FulltextAnalysisPhasesDescriptor.THIS_INPUT,
			FulltextLanguageAnalysisPhasesDescriptor.THIS, FulltextLanguageAnalysisPhasesDescriptor.THIS_INPUT,
			FulltextLanguageSchemaDescriptor.THIS, FulltextResourceSchemaDescriptor.THIS,
			FulltextAnalyzerSchemaDescriptor.THIS)) {
			buildingContext.registerType(descriptor.to(objectTransformer).build());
		}
	}

	/**
	 * Component schema for an ordered chain's single typed step. The providers are sorted by their stable step name.
	 *
	 * @param name component referenced by the phase-specific declaration input
	 * @param steps installed providers and their typed parameter descriptors
	 */
	private record AnalysisStepInputType(
		@Nonnull String name, @Nonnull List<StepInput> steps
	) implements OpenApiComplexType {

		/** Returns the shared component name referenced by mutation inputs. */
		@Nonnull
		@Override
		public String getName() {
			return this.name;
		}

		/** Builds exclusive closed objects, preserving the deterministic provider order. */
		@Nonnull
		@Override
		public Schema<Object> toSchema() {
			final Schema<Object> schema = new Schema<>();
			schema.name(getName());
			schema.description("Select exactly one installed " +
				(this.name.equals(AnalysisStepDescriptor.THIS_INPUT.name()) ? "normalization" : "fulltext") +
				" step. Parameterless steps take true.");
			for (final StepInput step : this.steps) {
				final ObjectSchema branch = new ObjectSchema();
				branch.addType("object");
				branch.setAdditionalProperties(Boolean.FALSE);
				branch.addRequiredItem(step.fieldName());
				final Schema<?> parameters;
				if (step.parameters() == null) {
					final BooleanSchema enabled = new BooleanSchema();
					enabled.addType("boolean");
					enabled.setEnum(List.of(Boolean.TRUE));
					parameters = enabled;
				} else {
					parameters = OpenApiTypeReference.typeRefTo(step.parameters().name()).toSchema();
				}
				branch.addProperty(step.fieldName(), parameters);
				schema.addOneOfItem(branch);
			}
			return schema;
		}
	}
}
