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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.api.requestResponse.data.structure.References.DEFAULT_CHUNK_TRANSFORMER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link References} verifying construction,
 * reference lookup, collection queries, and state methods.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("References")
class ReferencesTest extends AbstractBuilderTest {
	private static final String BRAND = "brand";
	private static final String CATEGORY = "category";

	/**
	 * Builds an entity schema with `brand` and `category`
	 * reference types defined.
	 *
	 * @return configured entity schema
	 */
	@Nonnull
	private static EntitySchemaContract schemaWithRefs() {
		return new InternalEntitySchemaBuilder(
			CATALOG_SCHEMA, PRODUCT_SCHEMA
		)
			.withReferenceToEntity(
				BRAND, BRAND, Cardinality.ZERO_OR_ONE
			)
			.withReferenceToEntity(
				CATEGORY, CATEGORY,
				Cardinality.ZERO_OR_MORE
			)
			.toInstance();
	}

	/**
	 * Creates a single {@link Reference} for the given schema,
	 * reference name, and referenced entity id.
	 *
	 * @param schema entity schema
	 * @param refName reference name
	 * @param refId referenced entity primary key
	 * @param cardinality reference cardinality
	 * @return new reference instance
	 */
	@Nonnull
	private static Reference createRef(
		@Nonnull EntitySchemaContract schema,
		@Nonnull String refName,
		int refId,
		@Nonnull Cardinality cardinality
	) {
		return new Reference(
			schema,
			ReferencesBuilder.createImplicitSchema(
				schema, refName, refName,
				cardinality, null
			),
			new ReferenceKey(refName, refId),
			null
		);
	}

	/**
	 * Creates a populated {@link References} container with
	 * one brand and two category references.
	 *
	 * @return pre-populated references container
	 */
	@Nonnull
	private static References createPopulated() {
		final EntitySchemaContract schema =
			schemaWithRefs();
		final Reference brandRef = createRef(
			schema, BRAND, 10,
			Cardinality.ZERO_OR_ONE
		);
		final Reference catRef1 = createRef(
			schema, CATEGORY, 20,
			Cardinality.ZERO_OR_MORE
		);
		final Reference catRef2 = createRef(
			schema, CATEGORY, 21,
			Cardinality.ZERO_OR_MORE
		);
		return new References(
			schema,
			new ReferenceContract[]{
				brandRef, catRef1, catRef2
			},
			Set.of(BRAND, CATEGORY),
			DEFAULT_CHUNK_TRANSFORMER
		);
	}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName(
			"should create empty state from schema-only"
		)
		void shouldCreateEmptyFromSchema() {
			final EntitySchemaContract schema =
				schemaWithRefs();

			final References refs =
				new References(schema);

			assertTrue(refs.getReferences().isEmpty());
			assertTrue(refs.referencesAvailable());
		}

		@Test
		@DisplayName(
			"should create empty state from empty array"
		)
		void shouldCreateEmptyFromArray() {
			final EntitySchemaContract schema =
				schemaWithRefs();

			final References refs = new References(
				schema,
				new ReferenceContract[0],
				Set.of(BRAND, CATEGORY),
				DEFAULT_CHUNK_TRANSFORMER
			);

			assertTrue(refs.getReferences().isEmpty());
		}

		@Test
		@DisplayName(
			"should store references from array"
		)
		void shouldStoreRefsFromArray() {
			final References refs = createPopulated();

			assertEquals(
				3, refs.getReferences().size()
			);
		}
	}

	@Nested
	@DisplayName("Reference lookup")
	class LookupTest {

		@Test
		@DisplayName(
			"should return unmodifiable collection"
		)
		void shouldReturnUnmodifiable() {
			final References refs = createPopulated();

			final Collection<ReferenceContract> all =
				refs.getReferences();

			assertThrows(
				UnsupportedOperationException.class,
				() -> all.add(null)
			);
		}

		@Test
		@DisplayName(
			"should filter references by name"
		)
		void shouldFilterByName() {
			final References refs = createPopulated();

			final Collection<ReferenceContract> cats =
				refs.getReferences(CATEGORY);

			assertEquals(2, cats.size());
		}

		@Test
		@DisplayName(
			"should find specific reference by key"
		)
		void shouldFindByKey() {
			final References refs = createPopulated();

			final Optional<ReferenceContract> brand =
				refs.getReference(BRAND, 10);

			assertTrue(brand.isPresent());
			assertEquals(
				10,
				brand.get().getReferenceKey().primaryKey()
			);
		}

		@Test
		@DisplayName(
			"should return empty for non-existent key"
		)
		void shouldReturnEmptyForMissingKey() {
			final References refs = createPopulated();

			final Optional<ReferenceContract> result =
				refs.getReference(BRAND, 999);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName(
			"should return all reference names"
		)
		void shouldReturnNames() {
			final References refs = createPopulated();

			final Set<String> names =
				refs.getReferenceNames();

			assertTrue(names.contains(BRAND));
			assertTrue(names.contains(CATEGORY));
			assertEquals(2, names.size());
		}
	}

	@Nested
	@DisplayName("Availability")
	class AvailabilityTest {

		@Test
		@DisplayName(
			"should report references as available"
		)
		void shouldReportAvailable() {
			final References refs = createPopulated();

			assertTrue(refs.referencesAvailable());
			assertTrue(
				refs.referencesAvailable(BRAND)
			);
		}
	}

	@Nested
	@DisplayName("toString")
	class ToStringTest {

		@Test
		@DisplayName(
			"should return non-null representation"
		)
		void shouldReturnNonNull() {
			final References refs = createPopulated();

			final String result = refs.toString();

			assertNotNull(result);
		}
	}
}
