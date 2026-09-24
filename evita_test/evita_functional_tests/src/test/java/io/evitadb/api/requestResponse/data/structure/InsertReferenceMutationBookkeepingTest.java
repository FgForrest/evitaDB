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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferencesContract;
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a reference inserted through {@link InsertReferenceMutation} is registered in the
 * {@link BuilderReferenceBundle}, exactly like one created through
 * {@link InitialReferencesBuilder#createReference(String, int)}.
 *
 * The mutation replay path used to add the reference straight to the reference collection without
 * telling the bundle about it. Everything the bundle is responsible for was therefore skipped for such
 * a reference:
 *
 * - removing it failed outright, because `removeNonDuplicateReference` looks the reference up in the
 *   bundle and rejects what it cannot find with "is not present in the structure";
 * - duplicate detection never ran, so two references with identical representative attributes were
 *   accepted into the entity without complaint.
 *
 * The path is reachable through {@code InitialEntityBuilder.mutate(...)}, which is how a mutation
 * stream is replayed - from the WAL, from gRPC, or from an entity's own {@code toMutation()} output.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("InsertReferenceMutation bundle bookkeeping")
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(ATTRIBUTE)
class InsertReferenceMutationBookkeepingTest extends AbstractBuilderTest {

	private static final String BRAND = "brand";
	private static final String COUNTRY = "country";
	private static final int BRAND_PK = 100;

	/**
	 * Builds a schema whose {@link #BRAND} reference allows duplicates discriminated by the
	 * representative {@link #COUNTRY} attribute.
	 *
	 * @return the entity schema used by all tests in this class
	 */
	@Nonnull
	private static EntitySchemaContract createSchemaWithDuplicableReference() {
		return new InternalEntitySchemaBuilder(CATALOG_SCHEMA, PRODUCT_SCHEMA)
			.withReferenceToEntity(
				BRAND, BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				r -> r.withAttribute(COUNTRY, String.class, AttributeSchemaEditor::representative)
			)
			.toInstance();
	}

	/**
	 * Replays the pair of mutations a builder emits for one populated reference: the insert followed by
	 * its own representative attribute.
	 *
	 * @param builder     the references builder under test
	 * @param internalPk  the internal primary key the reference is inserted under
	 * @param country     the representative attribute value assigned to it
	 */
	private static void insertThenPopulate(
		@Nonnull ReferencesBuilder builder,
		int internalPk,
		@Nonnull String country
	) {
		final ReferenceKey referenceKey = new ReferenceKey(BRAND, BRAND_PK, internalPk);
		builder.mutateReference(
			new InsertReferenceMutation(referenceKey, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, BRAND)
		);
		builder.mutateReference(
			new ReferenceAttributeMutation(
				referenceKey,
				new UpsertAttributeMutation(new AttributeKey(COUNTRY), country)
			)
		);
	}

	/**
	 * Returns the sorted representative attribute values of every {@link #BRAND} reference.
	 *
	 * @param references the built references
	 * @return sorted list of {@link #COUNTRY} values
	 */
	@Nonnull
	private static List<String> countriesOf(@Nonnull ReferencesContract references) {
		return references.getReferences(BRAND)
			.stream()
			.map(it -> it.getAttributeValue(COUNTRY).map(av -> (String) av.value()).orElse(null))
			.sorted(Comparator.nullsFirst(Comparator.naturalOrder()))
			.toList();
	}

	@Nested
	@DisplayName("removal of a mutation-inserted reference")
	class RemovalTest {

		@Test
		@DisplayName("removeReference finds a reference inserted through a mutation")
		void shouldRemoveReferenceInsertedThroughMutation() {
			final EntitySchemaContract schema = createSchemaWithDuplicableReference();
			final InitialReferencesBuilder builder = new InitialReferencesBuilder(schema);

			builder.mutateReference(
				new InsertReferenceMutation(
					new ReferenceKey(BRAND, BRAND_PK), Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, BRAND
				)
			);
			// used to fail with GenericEvitaInternalError "is not present in the structure", because the
			// mutation path never registered the reference in the bundle the removal looks it up in
			builder.removeReference(BRAND, BRAND_PK);

			assertEquals(0, builder.build().getReferences(BRAND).size());
		}

		@Test
		@DisplayName("RemoveReferenceMutation undoes a preceding InsertReferenceMutation")
		void shouldRemoveReferenceThroughRemoveReferenceMutation() {
			final EntitySchemaContract schema = createSchemaWithDuplicableReference();
			final InitialReferencesBuilder builder = new InitialReferencesBuilder(schema);

			final ReferenceKey referenceKey = new ReferenceKey(BRAND, BRAND_PK);
			builder.mutateReference(
				new InsertReferenceMutation(referenceKey, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, BRAND)
			);
			// an add-then-remove pair inside one replayed stream is ordinary, and used to abort the replay
			builder.mutateReference(new RemoveReferenceMutation(referenceKey));

			assertEquals(0, builder.build().getReferences(BRAND).size());
		}
	}

	@Nested
	@DisplayName("duplicate detection for mutation-inserted references")
	class DuplicateDetectionTest {

		@Test
		@DisplayName("distinguishable duplicates are accepted")
		void shouldAcceptDistinguishableReferencesInsertedThroughMutations() {
			final EntitySchemaContract schema = createSchemaWithDuplicableReference();
			final InitialReferencesBuilder builder = new InitialReferencesBuilder(schema);

			insertThenPopulate(builder, -1, "CZ");
			insertThenPopulate(builder, -2, "DE");
			insertThenPopulate(builder, -3, "SK");

			final References built = builder.build();
			assertEquals(3, built.getReferences(BRAND).size());
			assertEquals(List.of("CZ", "DE", "SK"), countriesOf(built));
		}

		@Test
		@DisplayName("indistinguishable duplicates are rejected")
		void shouldRejectIndistinguishableReferencesInsertedThroughMutations() {
			final EntitySchemaContract schema = createSchemaWithDuplicableReference();
			final InitialReferencesBuilder builder = new InitialReferencesBuilder(schema);

			insertThenPopulate(builder, -1, "CZ");

			// these two carry the very same representative attributes - without bundle registration the
			// collision went undetected and both were written into the entity
			assertThrows(
				InvalidMutationException.class,
				() -> insertThenPopulate(builder, -2, "CZ")
			);
		}
	}

	@Nested
	@DisplayName("mutation stream round-trip")
	class RoundTripTest {

		@Test
		@DisplayName("an entity replays its own mutation stream")
		void shouldReplayItsOwnMutationStream() {
			final EntitySchemaContract schema = createSchemaWithDuplicableReference();
			final InitialEntityBuilder source = new InitialEntityBuilder(schema, 1);
			source.setOrUpdateReference(
				BRAND, BRAND_PK, Functions.alwaysFalse(), r -> r.setAttribute(COUNTRY, "CZ")
			);
			source.setOrUpdateReference(
				BRAND, BRAND_PK, Functions.alwaysFalse(), r -> r.setAttribute(COUNTRY, "DE")
			);
			source.setOrUpdateReference(
				BRAND, BRAND_PK, Functions.alwaysFalse(), r -> r.setAttribute(COUNTRY, "SK")
			);

			final Collection<? extends LocalMutation<?, ?>> mutations = source.toMutation()
				.orElseThrow()
				.getLocalMutations();

			// registering the reference eagerly makes the emission order load-bearing: each insert must be
			// followed by its own attributes before the next insert, or two still-empty references would
			// collide on their identical default representative values. buildChangeSet emits exactly that,
			// and this test is what keeps it that way.
			final InitialEntityBuilder target = new InitialEntityBuilder(schema, 1);
			for (final LocalMutation<?, ?> mutation : mutations) {
				target.mutate(mutation);
			}

			final ReferencesContract replayed = target.toInstance();
			assertEquals(3, replayed.getReferences(BRAND).size());
			assertEquals(List.of("CZ", "DE", "SK"), countriesOf(replayed));
			assertTrue(
				source.toInstance().getReferences(BRAND).stream().map(ReferenceContract::getReferenceName).allMatch(BRAND::equals)
			);
		}
	}
}
