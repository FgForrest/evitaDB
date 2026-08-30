/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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


package io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.dataFetcher;

import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the GraphQL rendering of an attribute's filter accelerators.
 *
 * The catalog GraphQL schema reaches this through a registered data fetcher; the accelerator-specific logic is all in
 * the fetcher itself, so it is tested directly rather than through a booted server.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(EXTERNAL_API)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class AttributeSchemaAcceleratorsDataFetcherTest {

	/**
	 * Runs the singleton fetcher over one attribute schema.
	 *
	 * @param attributeSchema the schema the fetcher should read
	 * @return the scoped carriers the fetcher emitted
	 */
	@Nonnull
	private static List<ScopedAttributeFilterAccelerators> fetch(
		@Nonnull AttributeSchemaContract attributeSchema
	) throws Exception {
		final DataFetchingEnvironment environment = Mockito.mock(DataFetchingEnvironment.class);
		// doReturn rather than when(...).thenReturn(...): getSource() is generic, so the inferred type argument
		// would make the stubbing call ambiguous at compile time
		Mockito.doReturn(attributeSchema).when(environment).getSource();
		return AttributeSchemaAcceleratorsDataFetcher.getInstance().get(environment);
	}

	@Test
	@DisplayName("emits the accelerators of a unique-only attribute")
	void shouldEmitAcceleratorsOfUniqueOnlyAttribute() throws Exception {
		// the attribute is never filterable - `unique()` alone supplies the filter index, and the fetcher reads the
		// accelerator map rather than the filterability flag, so the carrier must come out regardless
		final AttributeSchemaContract attributeSchema = AttributeSchema._internalBuild(
			"code", null, null,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			},
			Scope.NO_SCOPE,
			new ScopedAttributeFilterAccelerators[]{
				new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
			},
			Scope.NO_SCOPE,
			false, false, false,
			String.class, null, 0,
			ConflictResolutionOverride.INHERITED
		);

		assertEquals(
			List.of(
				new ScopedAttributeFilterAccelerators(Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)
			),
			fetch(attributeSchema)
		);
	}

	@Test
	@DisplayName("emits an empty list for an attribute declaring no accelerator")
	void shouldEmitEmptyListForAttributeWithoutAccelerators() throws Exception {
		// the shape every attribute written before this axis existed produces
		final AttributeSchemaContract attributeSchema = AttributeSchema._internalBuild(
			"ean", null, null,
			// the untyped nulls would leave the array- and map-shaped overloads equally applicable
			(ScopedAttributeUniquenessType[]) null,
			Scope.DEFAULT_SCOPES,
			(ScopedAttributeFilterAccelerators[]) null,
			Scope.NO_SCOPE,
			false, false, false,
			String.class, null, 0,
			ConflictResolutionOverride.INHERITED
		);

		assertTrue(fetch(attributeSchema).isEmpty());
	}
}
