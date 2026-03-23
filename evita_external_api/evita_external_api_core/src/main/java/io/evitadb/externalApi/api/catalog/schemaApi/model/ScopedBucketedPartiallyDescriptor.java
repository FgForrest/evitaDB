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

import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing scope-specific partial-bucketing expression of a reference.
 * It is used to represent both input ({@link ScopedBucketedPartially}) in mutations and output in schemas.
 *
 * Note: this descriptor has static structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface ScopedBucketedPartiallyDescriptor extends ScopedDataDescriptor {

	PropertyDescriptor EXPRESSION = PropertyDescriptor.builder()
		.name("expression")
		.description("""
			The expression that narrows which entities participate in bucketed histogram computation
			for this scope. The expression is evaluated against the entity data and must return a boolean
			value. When null, the partial-bucketing constraint is being cleared for the given scope.
			""")
		.type(nullable(String.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ScopedBucketedPartially")
		.description("""
			Represents combination of a partial-bucketing expression and the entity scope it applies to.
			""")
		.staticProperties(List.of(SCOPE, EXPRESSION))
		.build();

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("InputScopedBucketedPartially")
		.build();
}
