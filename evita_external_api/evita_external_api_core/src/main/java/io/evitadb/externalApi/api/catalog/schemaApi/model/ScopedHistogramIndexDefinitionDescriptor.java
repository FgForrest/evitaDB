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
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullRef;

/**
 * Descriptor representing scope-specific bucketed histogram configuration of a reference.
 * It is used to represent both input ({@link ScopedHistogramIndexDefinition}) in mutations and output in schemas.
 *
 * The output variant additionally exposes {@code nameVariants} — server-generated name
 * translations in all supported naming conventions. Name variants are never accepted from
 * clients, so they do not appear on the input variant.
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

	PropertyDescriptor NAME_VARIANTS = PropertyDescriptor.builder()
		.name("nameVariants")
		.description("""
			Map contains the `nameOfTheIndex` variants in different naming conventions. The name
			is guaranteed to be unique among other histogram indexes in same convention. These names
			are used to quickly translate to / from names used in different protocols. Each API
			protocol prefers names in different naming conventions. Server-generated — never
			accepted from client input.
			""")
		.type(nonNullRef(NameVariantsDescriptor.THIS))
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

	PropertyDescriptor ASSIGNED_WHEN = PropertyDescriptor.builder()
		.name("assignedWhen")
		.description("""
			Partition selector. Among references already eligible per the reference- or
			scope-level `bucketedPartially` gate, this expression decides whether the referenced
			entity is assigned to this specific histogram. The expression is evaluated against the
			referenced entity and must return a boolean value — only entities for which the
			expression evaluates to `true` participate in this histogram. Multiple histograms
			on the same reference may declare overlapping or disjoint predicates; overlap is
			allowed but means a record participates in every histogram whose predicate evaluates
			to `true`. When null, no per-histogram restriction applies and the histogram contains
			every referenced entity already eligible per the gate.
			""")
		.type(nullable(String.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ScopedHistogramIndexDefinition")
		.description("""
			Represents combination of a bucketed histogram configuration and the entity scope it applies to.
			""")
		.staticProperties(List.of(SCOPE, NAME_OF_THE_INDEX, NAME_VARIANTS, VALUE_EXPRESSION, ASSIGNED_WHEN))
		.build();

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.builder()
		.name("InputScopedHistogramIndexDefinition")
		.description(THIS.description())
		.staticProperties(List.of(SCOPE, NAME_OF_THE_INDEX, VALUE_EXPRESSION, ASSIGNED_WHEN))
		.build();
}
