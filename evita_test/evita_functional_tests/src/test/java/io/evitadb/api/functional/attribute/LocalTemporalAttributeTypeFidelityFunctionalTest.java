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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeBetween;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeGreaterThanEquals;
import static io.evitadb.api.query.QueryConstraints.attributeLessThan;
import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.dataInLocales;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression coverage for the *local* temporal attribute types — `LocalDateTime`, `LocalDate` and
 * `LocalTime` — all three of which are listed by
 * {@link io.evitadb.dataType.EvitaDataTypes#getSupportedDataTypes()} and are therefore expected to
 * survive a write untouched, exactly as declared in the entity schema.
 *
 * The regression this class guards is specific to `LocalDateTime`: the value handed to
 * `UpsertAttributeMutation` used to be rewritten to an `OffsetDateTime` before the schema was ever
 * consulted, which made an attribute *declared* as `LocalDateTime` impossible to write —
 * `AttributeSchemaEvolvingMutation` rejected the rewritten value against the declared type. The
 * same rewrite silently derived an `OffsetDateTime` attribute when the schema was auto-evolved
 * instead of declared, which is the quieter half of the same defect.
 *
 * `LocalDate` and `LocalTime` are covered alongside it as confirming negatives — they were never
 * rewritten and are expected to pass both before and after the fix. Their passing is what pins the
 * defect to the single `LocalDateTime` branch rather than to local temporal types as a family.
 *
 * Every end-to-end assertion reads the value back and compares it to the *exact* instance written.
 * That is deliberate: an assertion that merely expected "no exception" would also pass a fix that
 * kept a wall-clock-shifting conversion in the write path, which on a `sortable` attribute would
 * silently reorder listings rather than fail.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Local temporal attribute types keep their declared type through the write path")
@Tag(ENGINE)
@Tag(ATTRIBUTE)
@Tag(DATA_TYPE)
@Tag(SCHEMA)
public class LocalTemporalAttributeTypeFidelityFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ATTR_LOCAL_DATE_TIME = "initialPublishedDate";
	private static final String ATTR_LOCAL_DATE = "initialPublishedDay";
	private static final String ATTR_LOCAL_TIME = "initialPublishedTime";

	/**
	 * Wall-clock instants deliberately expressed in a zone whose offset is neither zero nor equal
	 * to the JVM default in CI, so that any implicit offset coercion on the write path shows up as
	 * a changed value rather than an accidentally-matching one.
	 */
	private static final LocalDateTime FIRST_DATE_TIME = LocalDateTime.of(2026, 5, 20, 12, 19, 26);
	private static final LocalDateTime SECOND_DATE_TIME = LocalDateTime.of(2026, 5, 20, 14, 19, 26);
	private static final LocalDateTime THIRD_DATE_TIME = LocalDateTime.of(2026, 5, 21, 8, 5, 0);
	private static final LocalDate FIRST_DATE = LocalDate.of(2026, 5, 20);
	private static final LocalDate SECOND_DATE = LocalDate.of(2026, 5, 21);
	private static final LocalDate THIRD_DATE = LocalDate.of(2026, 5, 22);
	private static final LocalTime FIRST_TIME = LocalTime.of(12, 19, 26);
	private static final LocalTime SECOND_TIME = LocalTime.of(14, 19, 26);
	private static final LocalTime THIRD_TIME = LocalTime.of(18, 5, 0);

	/**
	 * Declares the product schema with one `sortable` + `filterable` attribute per local temporal
	 * type — the shape produced by a `@Attribute(sortable = true)` getter returning
	 * {@link LocalDateTime} on a client interface.
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(
				ATTR_LOCAL_DATE_TIME, LocalDateTime.class,
				whichIs -> whichIs.filterable().sortable().nullable()
			)
			.withAttribute(
				ATTR_LOCAL_DATE, LocalDate.class,
				whichIs -> whichIs.filterable().sortable().nullable()
			)
			.withAttribute(
				ATTR_LOCAL_TIME, LocalTime.class,
				whichIs -> whichIs.filterable().sortable().nullable()
			)
			.updateVia(session);
	}

	/**
	 * Seeds three products carrying ascending values of a *single* attribute, so a single ordering
	 * assertion is enough to prove the sortable path end-to-end.
	 *
	 * Seeding one attribute at a time is deliberate: it keeps each temporal type's scenario
	 * independent, so the `LocalDate` and `LocalTime` cases stand as genuine confirming-negatives
	 * rather than failing collaterally on the shared write of a broken `LocalDateTime` attribute.
	 */
	private static void seedAscending(
		@Nonnull EvitaSessionContract session,
		@Nonnull String attributeName,
		@Nonnull Serializable first,
		@Nonnull Serializable second,
		@Nonnull Serializable third
	) {
		createProduct(session, 1, attributeName, first);
		createProduct(session, 2, attributeName, second);
		createProduct(session, 3, attributeName, third);
	}

	/**
	 * Creates a single product carrying exactly one local temporal attribute.
	 */
	private static void createProduct(
		@Nonnull EvitaSessionContract session,
		int pk,
		@Nonnull String attributeName,
		@Nonnull Serializable value
	) {
		session.createNewEntity(ENTITY_PRODUCT, pk)
			.setAttribute(attributeName, value)
			.upsertVia(session);
	}

	/**
	 * Returns the primary keys matched by a query ordering ascending on the supplied attribute.
	 */
	@Nonnull
	private static List<Integer> primaryKeysOrderedBy(
		@Nonnull EvitaSessionContract session,
		@Nonnull String attributeName
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				orderBy(
					attributeNatural(attributeName, ASC)
				),
				require(
					page(1, Integer.MAX_VALUE)
				)
			),
			EntityReference.class
		);
		final List<Integer> primaryKeys = new ArrayList<>(result.getRecordData().size());
		for (final EntityReference reference : result.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		return primaryKeys;
	}

	/**
	 * Returns the primary keys matched by an equality filter on the supplied attribute.
	 *
	 * This is the counterpart to the write-path assertions: query values are still normalized at
	 * the query entry point (a `LocalDateTime` becomes an `OffsetDateTime` at UTC there), and it is
	 * the per-constraint coercion back to the attribute's declared type that has to make the two
	 * meet again. If either half of that round trip shifted the wall clock, this filter would
	 * silently match nothing.
	 */
	@Nonnull
	private static List<Integer> primaryKeysMatching(
		@Nonnull EvitaSessionContract session,
		@Nonnull String attributeName,
		@Nonnull Serializable value
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(
					attributeEquals(attributeName, value)
				),
				require(
					page(1, Integer.MAX_VALUE)
				)
			),
			EntityReference.class
		);
		final List<Integer> primaryKeys = new ArrayList<>(result.getRecordData().size());
		for (final EntityReference reference : result.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		return primaryKeys;
	}

	/**
	 * Returns the primary keys matched by the supplied filter constraint.
	 *
	 * Range and comparison constraints reach the index through the same `FilterIndex#getNormalizer` seam as equality
	 * does, so covering them is what proves the temporal encoding is applied consistently rather than only on the
	 * equality path.
	 */
	@Nonnull
	private static List<Integer> primaryKeysMatchingConstraint(
		@Nonnull EvitaSessionContract session,
		@Nonnull FilterConstraint constraint
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(constraint),
				require(
					page(1, Integer.MAX_VALUE)
				)
			),
			EntityReference.class
		);
		final List<Integer> primaryKeys = new ArrayList<>(result.getRecordData().size());
		for (final EntityReference reference : result.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		return primaryKeys;
	}

	/**
	 * Fetches a seeded product with all attributes loaded.
	 */
	@Nonnull
	private static SealedEntity fetchProduct(@Nonnull EvitaSessionContract session, int pk) {
		return session.getEntity(ENTITY_PRODUCT, pk, entityFetchAllContent())
			.orElseThrow(() -> new AssertionError("Product with primary key `" + pk + "` was not found!"));
	}

	/**
	 * Builds the standard test configuration with a disabled session inactivity timeout and
	 * per-test storage / export directories.
	 */
	@Nonnull
	private EvitaConfiguration createConfiguration(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Spins up a fresh Evita instance bound to per-test directories, hands an open read-write
	 * session to the caller and tears the instance down afterwards. The catalog is left in warm-up
	 * mode so a single session can both write and read.
	 */
	private void runWithCatalog(@Nonnull String storageSuffix, @Nonnull Consumer<EvitaSessionContract> scenario) {
		final TestPaths paths = createTestPaths(storageSuffix);
		try (
			Evita evita = new Evita(createConfiguration(paths))
		) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, scenario);
		} finally {
			cleanupTestPaths(paths);
		}
	}

	@DisplayName("Mutation contract")
	@Nested
	@Tag(CONTRACT)
	@Tag(ATTRIBUTE)
	@Tag(DATA_TYPE)
	class MutationContract {

		/**
		 * Asserts that the mutation exposes the value it was constructed with, unchanged in type
		 * and in value.
		 */
		private void assertValuePreserved(@Nonnull Serializable value) {
			final UpsertAttributeMutation mutation = new UpsertAttributeMutation(
				new AttributeKey(ATTR_LOCAL_DATE_TIME), value
			);
			assertEquals(value.getClass(), mutation.getAttributeValue().getClass());
			assertEquals(value, mutation.getAttributeValue());
		}

		@Test
		@DisplayName("should keep a LocalDateTime value as LocalDateTime")
		void shouldKeepLocalDateTimeValueWhenConstructingMutation() {
			assertValuePreserved(FIRST_DATE_TIME);
		}

		@Test
		@DisplayName("should keep a LocalDate value as LocalDate")
		void shouldKeepLocalDateValueWhenConstructingMutation() {
			assertValuePreserved(FIRST_DATE);
		}

		@Test
		@DisplayName("should keep a LocalTime value as LocalTime")
		void shouldKeepLocalTimeValueWhenConstructingMutation() {
			assertValuePreserved(FIRST_TIME);
		}

		@Test
		@DisplayName("should keep a LocalDateTime array as a LocalDateTime array")
		void shouldKeepLocalDateTimeArrayWhenConstructingMutation() {
			final LocalDateTime[] value = new LocalDateTime[]{FIRST_DATE_TIME, SECOND_DATE_TIME};
			final UpsertAttributeMutation mutation = new UpsertAttributeMutation(
				new AttributeKey(ATTR_LOCAL_DATE_TIME), value
			);
			final Serializable storedValue = mutation.getAttributeValue();
			assertInstanceOf(LocalDateTime[].class, storedValue);
			assertEquals(FIRST_DATE_TIME, ((LocalDateTime[]) storedValue)[0]);
			assertEquals(SECOND_DATE_TIME, ((LocalDateTime[]) storedValue)[1]);
		}

	}

	@DisplayName("Declared schema")
	@Nested
	@Tag(ENGINE)
	@Tag(ATTRIBUTE)
	@Tag(DATA_TYPE)
	class DeclaredSchema {

		/**
		 * Declares the schema, seeds three ascending values of the supplied attribute, then asserts
		 * that the first value reads back bit-identical, that the declared schema type is
		 * untouched, and that ascending natural ordering on the attribute yields the seeded order.
		 */
		private void assertRoundTripAndOrdering(
			@Nonnull String storageSuffix,
			@Nonnull String attributeName,
			@Nonnull Class<? extends Serializable> expectedType,
			@Nonnull Serializable first,
			@Nonnull Serializable second,
			@Nonnull Serializable third
		) {
			runWithCatalog(
				storageSuffix,
				session -> {
					defineSchema(session);
					seedAscending(session, attributeName, first, second, third);

					final SealedEntity product = fetchProduct(session, 1);
					final Serializable storedValue = product.getAttribute(attributeName);
					assertInstanceOf(expectedType, storedValue);
					// bit-identical read-back — a wall-clock shift introduced by an offset coercion
					// on the write path would surface here and nowhere else
					assertEquals(first, storedValue);

					// the declared schema type must survive the write untouched as well
					final SealedEntitySchema schema = session.getEntitySchema(ENTITY_PRODUCT).orElseThrow();
					assertEquals(
						expectedType,
						schema.getAttribute(attributeName).orElseThrow().getType()
					);

					// sortable path proven end-to-end
					assertEquals(List.of(1, 2, 3), primaryKeysOrderedBy(session, attributeName));

					// filterable path proven end-to-end, for each seeded value
					assertEquals(List.of(1), primaryKeysMatching(session, attributeName, first));
					assertEquals(List.of(2), primaryKeysMatching(session, attributeName, second));
					assertEquals(List.of(3), primaryKeysMatching(session, attributeName, third));

					// range and comparison constraints take the same normalizer seam as equality
					assertEquals(
						List.of(1, 2),
						primaryKeysMatchingConstraint(
							session, attributeBetween(attributeName, first, second)
						)
					);
					assertEquals(
						List.of(2, 3),
						primaryKeysMatchingConstraint(
							session, attributeGreaterThanEquals(attributeName, second)
						)
					);
					assertEquals(
						List.of(1),
						primaryKeysMatchingConstraint(
							session, attributeLessThan(attributeName, second)
						)
					);
				}
			);
		}

		@Test
		@DisplayName("should store and read back a LocalDateTime attribute unchanged")
		void shouldStoreAndReadBackLocalDateTimeAttribute() {
			assertRoundTripAndOrdering(
				"localTemporalDeclaredDateTime", ATTR_LOCAL_DATE_TIME, LocalDateTime.class,
				FIRST_DATE_TIME, SECOND_DATE_TIME, THIRD_DATE_TIME
			);
		}

		@Test
		@DisplayName("should store and read back a LocalDate attribute unchanged")
		void shouldStoreAndReadBackLocalDateAttribute() {
			assertRoundTripAndOrdering(
				"localTemporalDeclaredDate", ATTR_LOCAL_DATE, LocalDate.class,
				FIRST_DATE, SECOND_DATE, THIRD_DATE
			);
		}

		@Test
		@DisplayName("should store and read back a LocalTime attribute unchanged")
		void shouldStoreAndReadBackLocalTimeAttribute() {
			assertRoundTripAndOrdering(
				"localTemporalDeclaredTime", ATTR_LOCAL_TIME, LocalTime.class,
				FIRST_TIME, SECOND_TIME, THIRD_TIME
			);
		}

		@Test
		@DisplayName("should store and read back a localized LocalDateTime attribute unchanged")
		void shouldStoreAndReadBackLocalizedLocalDateTimeAttribute() {
			runWithCatalog(
				"localTemporalDeclaredLocalized",
				session -> {
					// a localized attribute reaches the third UpsertAttributeMutation constructor
					// and the `isLocalized` branch of the schema verification, neither of which the
					// non-localized scenarios above touch
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withLocale(Locale.ENGLISH)
						.withAttribute(
							ATTR_LOCAL_DATE_TIME, LocalDateTime.class,
							whichIs -> whichIs.localized().filterable().sortable().nullable()
						)
						.updateVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_LOCAL_DATE_TIME, Locale.ENGLISH, FIRST_DATE_TIME)
						.upsertVia(session);

					final SealedEntity product = session
						.getEntity(ENTITY_PRODUCT, 1, attributeContentAll(), dataInLocales(Locale.ENGLISH))
						.orElseThrow();
					final Serializable storedValue = product.getAttribute(ATTR_LOCAL_DATE_TIME, Locale.ENGLISH);
					assertInstanceOf(LocalDateTime.class, storedValue);
					assertEquals(FIRST_DATE_TIME, storedValue);
				}
			);
		}

		@Test
		@DisplayName("should keep a LocalDateTime attribute unchanged when the entity is updated")
		void shouldKeepLocalDateTimeAttributeWhenEntityIsUpdated() {
			runWithCatalog(
				"localTemporalDeclaredUpdate",
				session -> {
					defineSchema(session);
					seedAscending(
						session, ATTR_LOCAL_DATE_TIME, FIRST_DATE_TIME, SECOND_DATE_TIME, THIRD_DATE_TIME
					);

					// second write goes through ExistingEntityBuilder rather than the initial one
					fetchProduct(session, 1)
						.openForWrite()
						.setAttribute(ATTR_LOCAL_DATE_TIME, THIRD_DATE_TIME)
						.upsertVia(session);

					final Serializable storedValue = fetchProduct(session, 1).getAttribute(ATTR_LOCAL_DATE_TIME);
					assertInstanceOf(LocalDateTime.class, storedValue);
					assertEquals(THIRD_DATE_TIME, storedValue);
				}
			);
		}

	}

	@DisplayName("Auto-evolved schema")
	@Nested
	@Tag(ENGINE)
	@Tag(SCHEMA)
	@Tag(DATA_TYPE)
	class AutoEvolvedSchema {

		/**
		 * Upserts a single product carrying only the supplied attribute against a schema that does
		 * not declare it, then returns the attribute schema the engine derived from the value.
		 */
		@Nonnull
		private AttributeSchemaContract evolveAttributeSchemaFor(
			@Nonnull EvitaSessionContract session,
			@Nonnull String attributeName,
			@Nonnull Serializable value
		) {
			session.defineEntitySchema(ENTITY_PRODUCT).updateVia(session);
			session.createNewEntity(ENTITY_PRODUCT, 1)
				.setAttribute(attributeName, value)
				.upsertVia(session);

			final SealedEntitySchema schema = session.getEntitySchema(ENTITY_PRODUCT).orElseThrow();
			final AttributeSchemaContract attributeSchema = schema.getAttribute(attributeName).orElse(null);
			assertNotNull(attributeSchema, "Attribute `" + attributeName + "` was not evolved into the schema!");
			return attributeSchema;
		}

		@Test
		@DisplayName("should derive a LocalDateTime attribute from a LocalDateTime value")
		void shouldDeriveLocalDateTimeAttributeFromLocalDateTimeValue() {
			runWithCatalog(
				"localTemporalEvolvedDateTime",
				session -> {
					assertEquals(
						LocalDateTime.class,
						evolveAttributeSchemaFor(session, ATTR_LOCAL_DATE_TIME, FIRST_DATE_TIME).getType()
					);
					assertEquals(
						FIRST_DATE_TIME,
						session.getEntity(ENTITY_PRODUCT, 1, attributeContentAll())
							.orElseThrow()
							.getAttribute(ATTR_LOCAL_DATE_TIME)
					);
				}
			);
		}

		@Test
		@DisplayName("should derive a LocalDate attribute from a LocalDate value")
		void shouldDeriveLocalDateAttributeFromLocalDateValue() {
			runWithCatalog(
				"localTemporalEvolvedDate",
				session -> assertEquals(
					LocalDate.class,
					evolveAttributeSchemaFor(session, ATTR_LOCAL_DATE, FIRST_DATE).getType()
				)
			);
		}

		@Test
		@DisplayName("should derive a LocalTime attribute from a LocalTime value")
		void shouldDeriveLocalTimeAttributeFromLocalTimeValue() {
			runWithCatalog(
				"localTemporalEvolvedTime",
				session -> assertEquals(
					LocalTime.class,
					evolveAttributeSchemaFor(session, ATTR_LOCAL_TIME, FIRST_TIME).getType()
				)
			);
		}

	}

}
