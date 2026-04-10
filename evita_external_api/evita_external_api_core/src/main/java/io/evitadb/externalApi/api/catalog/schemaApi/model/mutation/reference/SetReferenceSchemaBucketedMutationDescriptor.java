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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference;

import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedHistogramIndexDefinitionDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedBucketedPartiallyDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableListRef;

/**
 * Descriptor representing {@link SetReferenceSchemaBucketedMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface SetReferenceSchemaBucketedMutationDescriptor extends ReferenceSchemaMutationDescriptor {

	PropertyDescriptor BUCKETED_IN_SCOPES = PropertyDescriptor.builder()
		.name("bucketedInScopes")
		.description("""
			Per-scope bucketed histogram configuration for this reference. Each entry associates
			a scope with a histogram index name and an optional value expression that computes
			the histogram bucket value for each referenced entity.

			This array defines in which scopes the reference will be bucketed. It will not be
			bucketed in not-specified scopes.

			When null (for reflected references), the bucketed configuration is inherited from
			the reflected reference.
			""")
		.type(nullableListRef(ScopedHistogramIndexDefinitionDescriptor.THIS))
		.build();
	PropertyDescriptor BUCKETED_IN_SCOPES_INPUT = PropertyDescriptor.from(BUCKETED_IN_SCOPES)
		.type(nullableListRef(ScopedHistogramIndexDefinitionDescriptor.THIS_INPUT))
		.build();

	PropertyDescriptor BUCKETED_PARTIALLY_IN_SCOPES = PropertyDescriptor.builder()
		.name("bucketedPartiallyInScopes")
		.description("""
			Per-scope expressions that narrow which entities participate in bucketed histogram
			computation. Each entry associates a scope with a boolean expression that is evaluated
			against the entity data. Only entities for which the expression evaluates to true will
			participate in histogram computation for the given scope.

			When null (for reflected references), the expressions are inherited from the reflected
			reference.
			""")
		.type(nullableListRef(ScopedBucketedPartiallyDescriptor.THIS))
		.build();
	PropertyDescriptor BUCKETED_PARTIALLY_IN_SCOPES_INPUT = PropertyDescriptor.from(BUCKETED_PARTIALLY_IN_SCOPES)
		.type(nullableListRef(ScopedBucketedPartiallyDescriptor.THIS_INPUT))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(SetReferenceSchemaBucketedMutation.class)
		.description("""
			Mutation is responsible for setting bucketed histogram configuration on a
			`ReferenceSchema` in `EntitySchema`.
			Mutation can be used for altering also the existing `ReferenceSchema` alone.
			""")
		.staticProperty(NAME)
		.staticProperty(BUCKETED_IN_SCOPES)
		.staticProperty(BUCKETED_PARTIALLY_IN_SCOPES)
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("SetReferenceSchemaBucketedMutationInput")
		.staticProperty(BUCKETED_IN_SCOPES_INPUT)
		.staticProperty(BUCKETED_PARTIALLY_IN_SCOPES_INPUT)
		.build();
}
