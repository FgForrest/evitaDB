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

import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing scope-specific bucketed histogram configuration of a reference.
 * It is used to represent both input ({@link ScopedHistogramIndexDefinition}) in mutations and output in schemas.
 *
 * Note: this descriptor has static structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface ScopedHistogramIndexDefinitionDescriptor extends ScopedDataDescriptor {

	PropertyDescriptor NAME_OF_THE_INDEX = PropertyDescriptor.builder()
		.name("nameOfTheIndex")
		.description("""
			The name identifying the histogram index. This name is used to reference the histogram
			in queries and must be unique within the scope of the reference schema.
			""")
		.type(nonNull(String.class))
		.build();

	PropertyDescriptor VALUE_EXPRESSION = PropertyDescriptor.builder()
		.name("valueExpression")
		.description("""
			The expression computing the histogram bucket value for each referenced entity.
			The expression is evaluated against the entity data and must return a numeric value.
			When null, no value expression is applied.
			""")
		.type(nullable(String.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ScopedHistogramIndexDefinition")
		.description("""
			Represents combination of a bucketed histogram configuration and the entity scope it applies to.
			""")
		.staticProperties(List.of(SCOPE, NAME_OF_THE_INDEX, VALUE_EXPRESSION))
		.build();

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("InputScopedHistogramIndexDefinition")
		.build();
}
