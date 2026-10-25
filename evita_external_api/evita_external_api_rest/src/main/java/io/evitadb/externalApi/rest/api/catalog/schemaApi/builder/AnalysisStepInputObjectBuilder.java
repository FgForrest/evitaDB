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

import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepInputDescriptors.StepInput;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepInputDescriptors;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.dataType.GenericObject;
import io.evitadb.externalApi.rest.api.builder.RestBuildingContext;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiComplexType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Generates the typed normalization input from installed normalizing-step metadata. Each closed branch requires
 * exactly its own step field; combining them with `oneOf` gives REST the same exclusive shape as GraphQL.
 */
public final class AnalysisStepInputObjectBuilder {

	/** Prevents construction of a stateless schema helper. */
	private AnalysisStepInputObjectBuilder() {
	}

	/**
	 * Registers the common output, typed parameter objects and exclusive step input in an API's own type registry.
	 *
	 * @param buildingContext catalog or system API registry
	 * @param objectTransformer converter for the shared parameter descriptors
	 */
	public static void registerTypes(
		@Nonnull RestBuildingContext buildingContext,
		@Nonnull ObjectDescriptorToOpenApiObjectTransformer objectTransformer
	) {
		buildingContext.registerType(ObjectDescriptor.from(AnalysisStepDescriptor.THIS)
			.name(AnalysisStepDescriptor.THIS.name())
			.staticProperty(PropertyDescriptor.from(AnalysisStepDescriptor.PARAMETERS)
				.type(nonNull(GenericObject.class))
				.build())
			.build()
			.to(objectTransformer)
			.build());
		final List<StepInput> steps = AnalysisStepInputDescriptors.getSteps();
		for (final StepInput step : steps) {
			if (step.parameters() != null) {
				buildingContext.registerType(step.parameters().to(objectTransformer).forbidAdditionalProperties().build());
			}
		}
		buildingContext.registerType(new AnalysisStepInputType(steps));
	}

	/**
	 * Component schema for an ordered chain's single typed step. The providers are sorted by their stable step name.
	 *
	 * @param steps installed normalizing providers and their typed parameter descriptors
	 */
	private record AnalysisStepInputType(@Nonnull List<StepInput> steps) implements OpenApiComplexType {

		/** Returns the shared component name referenced by mutation inputs. */
		@Nonnull
		@Override
		public String getName() {
			return AnalysisStepDescriptor.THIS_INPUT.name();
		}

		/** Builds exclusive closed objects, preserving the deterministic provider order. */
		@Nonnull
		@Override
		public Schema<Object> toSchema() {
			final Schema<Object> schema = new Schema<>();
			schema.name(getName());
			schema.description("Select exactly one installed normalization step. Parameterless steps take true.");
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
