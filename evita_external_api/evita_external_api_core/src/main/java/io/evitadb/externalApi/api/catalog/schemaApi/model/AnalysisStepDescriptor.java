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

package io.evitadb.externalApi.api.catalog.schemaApi.model;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.dataType.Any;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Defines the shared schema representation of one declared analysis step.
 * Outputs use a stable provider name and a parameter object; writable APIs derive a typed selector from installed
 * providers. Ordered lists use this descriptor without exposing the separate built-with index policy.
 */
public interface AnalysisStepDescriptor {
	/** Stable installed provider name stored in the declared chain. */
	PropertyDescriptor STEP = PropertyDescriptor.builder()
		.name("step")
		.description("Stable installed analysis step name, such as `ascii-folding` or `remove-characters`.")
		.type(nonNull(String.class))
		.build();
	/** Typed evitaDB parameter values rendered as a JSON-compatible object. */
	PropertyDescriptor PARAMETERS = PropertyDescriptor.builder()
		.name("parameters")
		.description("Named step parameters as a JSON object; an empty object denotes a parameterless step.")
		.type(nonNull(Any.class))
		.build();
	/** Output object shared by schema and mutation serializers. */
	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("AnalysisStep")
		.description("One operation in the declared, ordered attribute normalization chain.")
		.staticProperties(List.of(STEP, PARAMETERS))
		.build();
	/** Installed-provider selector whose exclusivity is enforced by GraphQL and REST. */
	ObjectDescriptor THIS_INPUT = AnalysisStepInputDescriptors.getInputDescriptor();
}
