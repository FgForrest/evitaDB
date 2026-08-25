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
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.ReferencesContract;
import io.evitadb.api.requestResponse.data.ReferencesEditor.ReferencesBuilder;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the duplicate-reference bookkeeping of the create-then-populate flow.
 *
 * {@link InitialReferencesBuilder#createReference(String, int)} registers a brand new - and therefore
 * still empty - reference in its {@link BuilderReferenceBundle} straight away, and only afterwards hands
 * the {@link ReferenceKey} back so the caller can populate the attributes. At registration time the
 * representative attribute values are consequently all {@code null}, so the reference is filed under the
 * representative key {@code [null]}.
 *
 * That key is provisional: it must be refreshed once the caller commits the populated builder back, or the
 * {@code [null]} slot stays occupied by a reference that no longer matches it. Until the refresh existed,
 * exactly two references per business key stayed healthy - the second one self-healed only its predecessor
 * through {@link BuilderReferenceBundle#convertToDuplicateReference(ReferenceContract, ReferenceContract)} -
 * and the third collided with the stale slot and was rejected with representative attributes {@code [null]}.
 *
 * The production trigger is the entity proxy: every {@code getOrCreate}-style reference method in
 * {@code SetReferenceMethodClassifier} routes to {@code createReference}, runs the caller's consumer and
 * only then propagates the builder back, which is precisely the order reproduced here.
 *
 * The tests pin both halves of the contract - the refresh has to vacate the stale slot, and it must not
 * weaken the detection of references that genuinely are indistinguishable.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("createReference duplicate bookkeeping")
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(ATTRIBUTE)
class CreateReferenceDuplicateBookkeepingTest extends AbstractBuilderTest {

	private static final String BRAND = "brand";
	private static final String STORE = "store";
	private static final String COUNTRY = "country";
	private static final int BRAND_PK = 143434;

	/**
	 * Builds a schema whose {@link #BRAND} reference allows duplicates and is discriminated by the
	 * representative {@link #COUNTRY} attribute.
	 *
	 * @return the entity schema used by all tests in this class
	 */
	@Nonnull
	private static EntitySchemaContract createSchemaWithDuplicableReference() {
		return new InternalEntitySchemaBuilder(CATALOG_SCHEMA, PRODUCT_SCHEMA)
			.withReferenceToEntity(
				STORE, STORE, Cardinality.ZERO_OR_MORE,
				r -> {}
			)
			.withReferenceToEntity(
				BRAND, BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				r -> r.withAttribute(COUNTRY, String.class, AttributeSchemaEditor::representative)
			)
			.toInstance();
	}

	/**
	 * Replays exactly what the entity proxy does for a single {@code addOrUpdateXxx(id, discriminator,
	 * consumer)} call: create the reference first, populate its representative attribute afterwards,
	 * then propagate the populated builder back into the entity.
	 *
	 * @param builder the references builder under test
	 * @param schema  the owning entity schema
	 * @param country the representative attribute value assigned after creation
	 * @return the key of the reference that was created
	 */
	@Nonnull
	private static ReferenceKey createThenPopulate(
		@Nonnull ReferencesBuilder builder,
		@Nonnull EntitySchemaContract schema,
		@Nonnull String country
	) {
		final ReferenceKey referenceKey = builder.createReference(BRAND, BRAND_PK);
		final ReferenceContract created = builder.getReference(referenceKey).orElseThrow();
		final ReferenceBuilder referenceBuilder = new ExistingReferenceBuilder(
			created, schema, new HashMap<>()
		);
		referenceBuilder.setAttribute(COUNTRY, country);
		builder.addOrReplaceReferenceMutations(referenceBuilder, true);
		return referenceKey;
	}

	/**
	 * Applies a single representative attribute change through the mutation replay path, which refreshes
	 * the reference in the collection without ever going through a {@link ReferenceBuilder}.
	 *
	 * @param builder      the references builder under test
	 * @param referenceKey the key of the reference to update
	 * @param country      the new representative attribute value
	 */
	private static void mutateCountry(
		@Nonnull ReferencesBuilder builder,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull String country
	) {
		builder.mutateReference(
			new ReferenceAttributeMutation(
				referenceKey,
				new UpsertAttributeMutation(new AttributeKey(COUNTRY), country)
			)
		);
	}

	/**
	 * Returns the representative attribute values of every {@link #BRAND} reference pointing at
	 * {@link #BRAND_PK}. The result is sorted, because {@link References} orders its references by
	 * {@link ReferenceContract#FULL_COMPARATOR} rather than by insertion order - which of the two
	 * orders comes out is irrelevant to what these tests assert.
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
	@DisplayName("InitialReferencesBuilder (new entity)")
	class InitialBuilderTest {

		@Test
		@DisplayName("two duplicates created via createReference are accepted")
		void shouldAcceptTwoDuplicatesCreatedViaCreateReference() {
			final EntitySchemaContract schema = createSchemaWithDuplicableReference();
			final InitialReferencesBuilder builder = new InitialReferencesBuilder(schema);

			createThenPopulate(builder, schema, "CZ");
			createThenPopulate(builder, schema, "DE");

			final References built = builder.build();
			assertEquals(2, built.getReferences(BRAND).size());
			assertEquals(List.of("CZ", "DE"), countriesOf(built));
		}

		@Test
		@DisplayName("three duplicates created via createReference are accepted")
		void shouldAcceptThreeDuplicatesCreatedViaCreateReference() {
			final EntitySchemaContract schema = createSchemaWithDuplicableReference();
			final InitialReferencesBuilder builder = new InitialReferencesBuilder(schema);

			createThenPopulate(builder, schema, "CZ");
			createThenPopulate(builder, schema, "DE");
			// this third call used to fail, because the second reference still occupied the `[null]`
			// representative slot it had been registered under before it was populated
			createThenPopulate(builder, schema, "SK");

			final References built = builder.build();
			assertEquals(3, built.getReferences(BRAND).size());
			assertEquals(List.of("CZ", "DE", "SK"), countriesOf(built));
		}

		@Test
		@DisplayName("arbitrarily many duplicates created via createReference are accepted")
		void shouldAcceptManyDuplicatesCreatedViaCreateReference() {
			final EntitySchemaContract schema = createSchemaWithDuplicableReference();
			final InitialReferencesBuilder builder = new InitialReferencesBuilder(schema);

			final List<String> countries = List.of("AT", "CZ", "DE", "HU", "PL", "SK");
			for (final String country : countries) {
				createThenPopulate(builder, schema, country);
			}

			final References built = builder.build();
			assertEquals(countries.size(), built.getReferences(BRAND).size());
			assertEquals(countries, countriesOf(built));
		}

		@Test
		@DisplayName("populate-then-register via setOrUpdateReference stays healthy")
		void shouldAcceptThreeDuplicatesCreatedViaSetOrUpdateReference() {
			final EntitySchemaContract schema = createSchemaWithDuplicableReference();
			final InitialReferencesBuilder builder = new InitialReferencesBuilder(schema);

			// setOrUpdateReference runs the consumer BEFORE registering in the bundle, so the
			// representative values are present at registration time - this is the control case
			builder.setOrUpdateReference(
				BRAND, BRAND_PK, Functions.alwaysFalse(), ref -> ref.setAttribute(COUNTRY, "CZ")
			);
			builder.setOrUpdateReference(
				BRAND, BRAND_PK, Functions.alwaysFalse(), ref -> ref.setAttribute(COUNTRY, "DE")
			);
			builder.setOrUpdateReference(
				BRAND, BRAND_PK, Functions.alwaysFalse(), ref -> ref.setAttribute(COUNTRY, "SK")
			);

			final References built = builder.build();
			assertEquals(3, built.getReferences(BRAND).size());
			assertEquals(List.of("CZ", "DE", "SK"), countriesOf(built));
		}

		@Test
		@DisplayName("duplicates sharing representative attributes are still rejected")
		void shouldThrowExceptionWhenDuplicatesShareRepresentativeAttributes() {
			final EntitySchemaContract schema = createSchemaWithDuplicableReference();
			final InitialReferencesBuilder builder = new InitialReferencesBuilder(schema);

			createThenPopulate(builder, schema, "CZ");

			// refreshing the provisional key must not weaken duplicate detection - these two references
			// really are indistinguishable and the second one has to be refused
			assertThrows(
				InvalidMutationException.class,
				() -> createThenPopulate(builder, schema, "CZ")
			);
		}

		@Test
		@DisplayName("representative attribute change is reflected in the bundle")
		void shouldRefreshRepresentativeKeyWhenAttributeMutationIsApplied() {
			final EntitySchemaContract schema = createSchemaWithDuplicableReference();
			final InitialReferencesBuilder builder = new InitialReferencesBuilder(schema);

			final ReferenceKey first = createThenPopulate(builder, schema, "CZ");
			createThenPopulate(builder, schema, "DE");

			// the mutation replay path refreshes the reference in the collection directly - the bundle
			// has to follow, otherwise `CZ` would stay reserved and `FR` would never be claimed
			mutateCountry(builder, first, "FR");

			final References built = builder.build();
			assertEquals(2, built.getReferences(BRAND).size());
			assertEquals(List.of("DE", "FR"), countriesOf(built));

			// the vacated `CZ` slot must be free for a brand new duplicate
			createThenPopulate(builder, schema, "CZ");
			assertEquals(List.of("CZ", "DE", "FR"), countriesOf(builder.build()));
		}

		@Test
		@DisplayName("attribute change colliding with a sibling duplicate is rejected")
		void shouldThrowExceptionWhenAttributeMutationCollidesWithSibling() {
			final EntitySchemaContract schema = createSchemaWithDuplicableReference();
			final InitialReferencesBuilder builder = new InitialReferencesBuilder(schema);

			final ReferenceKey first = createThenPopulate(builder, schema, "CZ");
			createThenPopulate(builder, schema, "DE");

			assertThrows(
				InvalidMutationException.class,
				() -> mutateCountry(builder, first, "DE")
			);

			// the rejected re-key must have left the builder exactly as it was
			final References built = builder.build();
			assertEquals(2, built.getReferences(BRAND).size());
			assertEquals(List.of("CZ", "DE"), countriesOf(built));
		}
	}

	@Nested
	@DisplayName("ExistingReferencesBuilder (existing entity)")
	class ExistingBuilderTest {

		/**
		 * Creates an {@link ExistingReferencesBuilder} on top of an entity that carries no references yet.
		 *
		 * @param schema the owning entity schema
		 * @return the builder under test
		 */
		@Nonnull
		private ExistingReferencesBuilder createBuilder(@Nonnull EntitySchemaContract schema) {
			final References base = new InitialReferencesBuilder(schema).build();
			return new ExistingReferencesBuilder(
				schema, base,
				ReferenceContractSerializablePredicate.DEFAULT_INSTANCE,
				key -> Optional.empty()
			);
		}

		@Test
		@DisplayName("three duplicates created via createReference are accepted")
		void shouldAcceptThreeDuplicatesCreatedViaCreateReference() {
			final EntitySchemaContract schema = createSchemaWithDuplicableReference();
			final ExistingReferencesBuilder builder = createBuilder(schema);

			createThenPopulate(builder, schema, "CZ");
			createThenPopulate(builder, schema, "DE");
			createThenPopulate(builder, schema, "SK");

			final References built = builder.build();
			assertNotNull(built);
			assertEquals(3, built.getReferences(BRAND).size());
			assertEquals(List.of("CZ", "DE", "SK"), countriesOf(built));
		}

		@Test
		@DisplayName("arbitrarily many duplicates created via createReference are accepted")
		void shouldAcceptManyDuplicatesCreatedViaCreateReference() {
			final EntitySchemaContract schema = createSchemaWithDuplicableReference();
			final ExistingReferencesBuilder builder = createBuilder(schema);

			final List<String> countries = List.of("AT", "CZ", "DE", "HU", "PL", "SK");
			for (final String country : countries) {
				createThenPopulate(builder, schema, country);
			}

			final References built = builder.build();
			assertEquals(countries.size(), built.getReferences(BRAND).size());
			assertEquals(countries, countriesOf(built));
		}
	}
}
