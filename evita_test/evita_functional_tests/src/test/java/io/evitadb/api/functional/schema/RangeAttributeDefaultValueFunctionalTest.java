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

package io.evitadb.api.functional.schema;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDefaultValueMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Verifies end to end that the default value of a range attribute is replaced by a range whose bounds encode to the
 * same comparable longs - `[10,20]` by `[1.0,2.0]` - although ranges compare equal by their encoded bounds, for an
 * attribute of an entity, a global attribute an entity uses or none does and an attribute of a reference - through
 * the schema builders, through the bare schema mutations, and through a removal and a creation of the attribute the
 * schema builders combine into the changes between the two; and that the very same range given again changes no
 * schema.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Range default value replaced by a range encoded to the same longs")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class RangeAttributeDefaultValueFunctionalTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_RANGE = "range";
	private static final String ATTRIBUTE_GLOBAL_RANGE = "globalRange";
	private static final String ATTRIBUTE_UNUSED_GLOBAL_RANGE = "unusedGlobalRange";
	private static final String REFERENCE_BRAND = "brand";

	private TestPaths paths;
	private Evita evita;

	/**
	 * Returns the range `[10,20]`, the default value every attribute starts with.
	 *
	 * @return a new instance of the range
	 */
	@Nonnull
	private static BigDecimalNumberRange tenToTwenty() {
		return BigDecimalNumberRange.between(new BigDecimal("10"), new BigDecimal("20"));
	}

	/**
	 * Returns the range `[1.0,2.0]`, which encodes to the same comparable longs as {@link #tenToTwenty()}.
	 *
	 * @return a new instance of the range
	 */
	@Nonnull
	private static BigDecimalNumberRange oneToTwo() {
		return BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"));
	}

	/**
	 * Returns checks that the default value is the range with the given textual form and the given effective number
	 * of retained decimal places.
	 *
	 * @param location              the attribute the default value belongs to, for the assertion message
	 * @param defaultValue          the default value as the schema reports it
	 * @param expectedText          the expected textual form of the range
	 * @param expectedDecimalPlaces the expected effective number of retained decimal places
	 * @return the checks
	 */
	@Nonnull
	private static Executable[] defaultValueIs(
		@Nonnull String location,
		@Nullable Serializable defaultValue,
		@Nonnull String expectedText,
		int expectedDecimalPlaces
	) {
		return new Executable[]{
			() -> assertEquals(expectedText, String.valueOf(defaultValue), location),
			() -> assertEquals(
				expectedDecimalPlaces,
				assertInstanceOf(BigDecimalNumberRange.class, defaultValue, location)
					.getEffectiveRetainedDecimalPlaces(),
				location
			)
		};
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("RangeAttributeDefaultValueFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(
						ATTRIBUTE_GLOBAL_RANGE, BigDecimalNumberRange.class,
						whichIs -> whichIs.withDefaultValue(tenToTwenty())
					)
					.withAttribute(
						ATTRIBUTE_UNUSED_GLOBAL_RANGE, BigDecimalNumberRange.class,
						whichIs -> whichIs.withDefaultValue(tenToTwenty())
					)
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_GLOBAL_RANGE)
					.withAttribute(
						ATTRIBUTE_RANGE, BigDecimalNumberRange.class, whichIs -> whichIs.withDefaultValue(tenToTwenty())
					)
					.withReferenceTo(
						REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withAttribute(
							ATTRIBUTE_RANGE, BigDecimalNumberRange.class,
							attribute -> attribute.withDefaultValue(tenToTwenty())
						)
					)
					.updateVia(session);
			}
		);
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		// a fixture fault must not pass for the change being swallowed
		assertAll(entityDefaultValueIs("[10,20]", 0));
		assertAll(globalDefaultValueIs("[10,20]", 0));
		assertAll(unusedGlobalDefaultValueIs("[10,20]", 0));
		assertAll(referenceDefaultValueIs("[10,20]", 0));
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * Applies the change in one transaction, waits until it is visible, checks it, restarts evitaDB and checks it
	 * again.
	 *
	 * @param change the change of the schema
	 * @param checks the checks of the changed schema, evaluated after the change and after the restart
	 */
	private void changeAndVerifyAfterRestart(
		@Nonnull Consumer<EvitaSessionContract> change,
		@Nonnull Supplier<Executable[]> checks
	) {
		this.evita.updateCatalog(TEST_CATALOG, change, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE);
		assertAll("after the change", checks.get());

		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();

		assertAll("after a restart", checks.get());
	}

	/**
	 * Applies the change in one transaction and checks that neither the catalog schema nor the product schema changed.
	 *
	 * @param change the change of the schema, which is to change nothing
	 */
	private void changeAndVerifyNothingChanged(@Nonnull Consumer<EvitaSessionContract> change) {
		final int catalogVersion = catalogSchema().version();

		changeAndVerifyEntitySchemaKept(change);

		assertEquals(catalogVersion, catalogSchema().version(), "The same range changed the catalog schema.");
	}

	/**
	 * Applies the change in one transaction and checks that the product schema and all the default values stayed as
	 * they were.
	 *
	 * @param change the change of the schema, which is to change nothing
	 */
	private void changeAndVerifyEntitySchemaKept(@Nonnull Consumer<EvitaSessionContract> change) {
		final int productVersion = productSchema().version();

		this.evita.updateCatalog(TEST_CATALOG, change, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE);

		assertAll(
			() -> assertEquals(productVersion, productSchema().version(), "The same range changed the entity schema."),
			() -> assertAll(entityDefaultValueIs("[10,20]", 0)),
			() -> assertAll(globalDefaultValueIs("[10,20]", 0)),
			() -> assertAll(unusedGlobalDefaultValueIs("[10,20]", 0)),
			() -> assertAll(referenceDefaultValueIs("[10,20]", 0))
		);
	}

	/**
	 * Returns checks of the default value of the attribute of the product.
	 *
	 * @param expectedText          the expected textual form of the range
	 * @param expectedDecimalPlaces the expected effective number of retained decimal places
	 * @return the checks
	 */
	@Nonnull
	private Executable[] entityDefaultValueIs(@Nonnull String expectedText, int expectedDecimalPlaces) {
		return defaultValueIs(
			"entity attribute",
			productSchema().getAttribute(ATTRIBUTE_RANGE).orElseThrow().getDefaultValue(),
			expectedText,
			expectedDecimalPlaces
		);
	}

	/**
	 * Returns checks of the default value of the global attribute, as the catalog schema holds it and as the product
	 * using it holds it.
	 *
	 * @param expectedText          the expected textual form of the range
	 * @param expectedDecimalPlaces the expected effective number of retained decimal places
	 * @return the checks
	 */
	@Nonnull
	private Executable[] globalDefaultValueIs(@Nonnull String expectedText, int expectedDecimalPlaces) {
		final Executable[] inCatalog = defaultValueIs(
			"global attribute in the catalog schema",
			catalogSchema().getAttribute(ATTRIBUTE_GLOBAL_RANGE).orElseThrow().getDefaultValue(),
			expectedText,
			expectedDecimalPlaces
		);
		final Executable[] inEntity = defaultValueIs(
			"global attribute in the entity schema",
			productSchema().getAttribute(ATTRIBUTE_GLOBAL_RANGE).orElseThrow().getDefaultValue(),
			expectedText,
			expectedDecimalPlaces
		);
		return new Executable[]{inCatalog[0], inCatalog[1], inEntity[0], inEntity[1]};
	}

	/**
	 * Returns checks of the default value of the global attribute no entity uses.
	 *
	 * @param expectedText          the expected textual form of the range
	 * @param expectedDecimalPlaces the expected effective number of retained decimal places
	 * @return the checks
	 */
	@Nonnull
	private Executable[] unusedGlobalDefaultValueIs(@Nonnull String expectedText, int expectedDecimalPlaces) {
		return defaultValueIs(
			"global attribute no entity uses",
			catalogSchema().getAttribute(ATTRIBUTE_UNUSED_GLOBAL_RANGE).orElseThrow().getDefaultValue(),
			expectedText,
			expectedDecimalPlaces
		);
	}

	/**
	 * Returns checks of the default value of the attribute of the reference of the product.
	 *
	 * @param expectedText          the expected textual form of the range
	 * @param expectedDecimalPlaces the expected effective number of retained decimal places
	 * @return the checks
	 */
	@Nonnull
	private Executable[] referenceDefaultValueIs(@Nonnull String expectedText, int expectedDecimalPlaces) {
		final AttributeSchemaContract attribute = productSchema()
			.getReference(REFERENCE_BRAND)
			.orElseThrow()
			.getAttribute(ATTRIBUTE_RANGE)
			.orElseThrow();
		return defaultValueIs("reference attribute", attribute.getDefaultValue(), expectedText, expectedDecimalPlaces);
	}

	/**
	 * Returns the creation of the attribute that has all properties of the stored one but the default value.
	 *
	 * @param storedAttribute the attribute as the schema stores it
	 * @param defaultValue    the default value of the created attribute
	 * @return the creation mutation
	 */
	@Nonnull
	private static CreateAttributeSchemaMutation createAgain(
		@Nonnull AttributeSchemaContract storedAttribute,
		@Nonnull BigDecimalNumberRange defaultValue
	) {
		return new CreateAttributeSchemaMutation(
			storedAttribute.getName(), null, null, AttributeUniquenessType.NOT_UNIQUE, false, false,
			storedAttribute.isLocalized(), storedAttribute.isNullable(), storedAttribute.isRepresentative(),
			BigDecimalNumberRange.class, defaultValue, storedAttribute.getIndexedDecimalPlaces()
		);
	}

	/**
	 * Returns the creation of the global attribute that has all properties of the stored one but the default value.
	 *
	 * @param storedAttribute the global attribute as the catalog schema stores it
	 * @param defaultValue    the default value of the created attribute
	 * @return the creation mutation
	 */
	@Nonnull
	private static CreateGlobalAttributeSchemaMutation createGlobalAgain(
		@Nonnull AttributeSchemaContract storedAttribute,
		@Nonnull BigDecimalNumberRange defaultValue
	) {
		return new CreateGlobalAttributeSchemaMutation(
			storedAttribute.getName(), null, null,
			AttributeUniquenessType.NOT_UNIQUE, GlobalAttributeUniquenessType.NOT_UNIQUE, false, false,
			storedAttribute.isLocalized(), storedAttribute.isNullable(), storedAttribute.isRepresentative(),
			BigDecimalNumberRange.class, defaultValue, storedAttribute.getIndexedDecimalPlaces()
		);
	}

	/**
	 * Removes the attribute of the product and creates it again with the default value, in one builder of the entity
	 * schema, which combines the two mutations.
	 *
	 * @param session      the session to update the schema in
	 * @param defaultValue the default value of the created attribute
	 */
	private static void recreateEntityAttribute(
		@Nonnull EvitaSessionContract session,
		@Nonnull BigDecimalNumberRange defaultValue
	) {
		final EntitySchemaContract productSchema = session.getEntitySchemaOrThrowException(Entities.PRODUCT);
		session.getEntitySchemaOrThrowException(Entities.PRODUCT)
			.withMutations(
				new RemoveAttributeSchemaMutation(ATTRIBUTE_RANGE),
				createAgain(productSchema.getAttribute(ATTRIBUTE_RANGE).orElseThrow(), defaultValue)
			)
			.updateVia(session);
	}

	/**
	 * Removes the attribute of the reference of the product and creates it again with the default value, in one
	 * builder of the entity schema, which combines the two mutations.
	 *
	 * @param session      the session to update the schema in
	 * @param defaultValue the default value of the created attribute
	 */
	private static void recreateReferenceAttribute(
		@Nonnull EvitaSessionContract session,
		@Nonnull BigDecimalNumberRange defaultValue
	) {
		final AttributeSchemaContract storedAttribute = session.getEntitySchemaOrThrowException(Entities.PRODUCT)
			.getReference(REFERENCE_BRAND)
			.orElseThrow()
			.getAttribute(ATTRIBUTE_RANGE)
			.orElseThrow();
		session.getEntitySchemaOrThrowException(Entities.PRODUCT)
			.withMutations(
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_BRAND, new RemoveAttributeSchemaMutation(ATTRIBUTE_RANGE)
				),
				new ModifyReferenceAttributeSchemaMutation(
					REFERENCE_BRAND, createAgain(storedAttribute, defaultValue)
				)
			)
			.updateVia(session);
	}

	/**
	 * Removes the global attribute no entity uses and creates it again with the default value, in one builder of the
	 * catalog schema, which combines the two mutations.
	 *
	 * @param session      the session to update the schema in
	 * @param defaultValue the default value of the created attribute
	 */
	private static void recreateUnusedGlobalAttribute(
		@Nonnull EvitaSessionContract session,
		@Nonnull BigDecimalNumberRange defaultValue
	) {
		final CatalogSchemaContract catalogSchema = session.getCatalogSchema();
		session.getCatalogSchema()
			.openForWriteWithMutations(
				new RemoveAttributeSchemaMutation(ATTRIBUTE_UNUSED_GLOBAL_RANGE),
				createGlobalAgain(
					catalogSchema.getAttribute(ATTRIBUTE_UNUSED_GLOBAL_RANGE).orElseThrow(), defaultValue
				)
			)
			.updateVia(session);
	}

	/**
	 * Returns the catalog schema as the catalog holds it now.
	 *
	 * @return the catalog schema
	 */
	@Nonnull
	private CatalogSchemaContract catalogSchema() {
		return query(EvitaSessionContract::getCatalogSchema);
	}

	/**
	 * Returns the product entity schema as the catalog holds it now.
	 *
	 * @return the product entity schema
	 */
	@Nonnull
	private EntitySchemaContract productSchema() {
		return query(session -> session.getEntitySchemaOrThrowException(Entities.PRODUCT));
	}

	/**
	 * Runs the query in a read-only session of the test catalog.
	 *
	 * @param query the query
	 * @param <T>   the type of the result
	 * @return the result of the query
	 */
	@Nonnull
	private <T> T query(@Nonnull Function<EvitaSessionContract, T> query) {
		return this.evita.queryCatalog(TEST_CATALOG, query);
	}

	@Nested
	@DisplayName("through the schema builders")
	class SchemaBuilderTest {

		@Test
		@DisplayName("should replace the default value of an entity attribute")
		void shouldReplaceDefaultValueOfEntityAttribute() {
			changeAndVerifyAfterRestart(
				session -> session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.openForWrite()
					.withAttribute(
						ATTRIBUTE_RANGE, BigDecimalNumberRange.class, whichIs -> whichIs.withDefaultValue(oneToTwo())
					)
					.updateVia(session),
				() -> entityDefaultValueIs("[1.0,2.0]", 1)
			);
		}

		@Test
		@DisplayName("should replace the default value of a global attribute in the catalog and in the entity using it")
		void shouldReplaceDefaultValueOfGlobalAttribute() {
			changeAndVerifyAfterRestart(
				session -> session.getCatalogSchema()
					.openForWrite()
					.withAttribute(
						ATTRIBUTE_GLOBAL_RANGE, BigDecimalNumberRange.class,
						whichIs -> whichIs.withDefaultValue(oneToTwo())
					)
					.updateVia(session),
				() -> globalDefaultValueIs("[1.0,2.0]", 1)
			);
		}

		@Test
		@DisplayName("should replace the default value of a global attribute no entity uses")
		void shouldReplaceDefaultValueOfUnusedGlobalAttribute() {
			changeAndVerifyAfterRestart(
				session -> session.getCatalogSchema()
					.openForWrite()
					.withAttribute(
						ATTRIBUTE_UNUSED_GLOBAL_RANGE, BigDecimalNumberRange.class,
						whichIs -> whichIs.withDefaultValue(oneToTwo())
					)
					.updateVia(session),
				() -> unusedGlobalDefaultValueIs("[1.0,2.0]", 1)
			);
		}

		@Test
		@DisplayName("should replace the default value of a reference attribute")
		void shouldReplaceDefaultValueOfReferenceAttribute() {
			changeAndVerifyAfterRestart(
				session -> session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.openForWrite()
					.withReferenceTo(
						REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withAttribute(
							ATTRIBUTE_RANGE, BigDecimalNumberRange.class,
							attribute -> attribute.withDefaultValue(oneToTwo())
						)
					)
					.updateVia(session),
				() -> referenceDefaultValueIs("[1.0,2.0]", 1)
			);
		}

		@Test
		@DisplayName("should change no schema when the very same range comes again")
		void shouldChangeNoSchemaWhenSameRangeComesAgain() {
			changeAndVerifyNothingChanged(
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withAttribute(
							ATTRIBUTE_GLOBAL_RANGE, BigDecimalNumberRange.class,
							whichIs -> whichIs.withDefaultValue(tenToTwenty())
						)
						.withAttribute(
							ATTRIBUTE_UNUSED_GLOBAL_RANGE, BigDecimalNumberRange.class,
							whichIs -> whichIs.withDefaultValue(tenToTwenty())
						)
						.updateVia(session);
					session.getEntitySchemaOrThrowException(Entities.PRODUCT)
						.openForWrite()
						.withAttribute(
							ATTRIBUTE_RANGE, BigDecimalNumberRange.class,
							whichIs -> whichIs.withDefaultValue(tenToTwenty())
						)
						.withReferenceTo(
							REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs.withAttribute(
								ATTRIBUTE_RANGE, BigDecimalNumberRange.class,
								attribute -> attribute.withDefaultValue(tenToTwenty())
							)
						)
						.updateVia(session);
				}
			);
		}

	}

	@Nested
	@DisplayName("through the schema mutations")
	class SchemaMutationTest {

		@Test
		@DisplayName("should replace the default value of an entity attribute")
		void shouldReplaceDefaultValueOfEntityAttribute() {
			changeAndVerifyAfterRestart(
				session -> session.updateEntitySchema(
					new ModifyEntitySchemaMutation(
						Entities.PRODUCT,
						new ModifyAttributeSchemaDefaultValueMutation(ATTRIBUTE_RANGE, oneToTwo())
					)
				),
				() -> entityDefaultValueIs("[1.0,2.0]", 1)
			);
		}

		@Test
		@DisplayName("should replace the default value of a global attribute in the catalog and in the entity using it")
		void shouldReplaceDefaultValueOfGlobalAttribute() {
			changeAndVerifyAfterRestart(
				session -> session.updateCatalogSchema(
					new ModifyAttributeSchemaDefaultValueMutation(ATTRIBUTE_GLOBAL_RANGE, oneToTwo())
				),
				() -> globalDefaultValueIs("[1.0,2.0]", 1)
			);
		}

		@Test
		@DisplayName("should replace the default value of a reference attribute")
		void shouldReplaceDefaultValueOfReferenceAttribute() {
			changeAndVerifyAfterRestart(
				session -> session.updateEntitySchema(
					new ModifyEntitySchemaMutation(
						Entities.PRODUCT,
						new ModifyReferenceAttributeSchemaMutation(
							REFERENCE_BRAND,
							new ModifyAttributeSchemaDefaultValueMutation(ATTRIBUTE_RANGE, oneToTwo())
						)
					)
				),
				() -> referenceDefaultValueIs("[1.0,2.0]", 1)
			);
		}

		@Test
		@DisplayName("should change no schema when the very same range of a global attribute comes again")
		void shouldChangeNoSchemaWhenSameRangeOfGlobalAttributeComesAgain() {
			changeAndVerifyNothingChanged(
				session -> session.updateCatalogSchema(
					new ModifyAttributeSchemaDefaultValueMutation(ATTRIBUTE_GLOBAL_RANGE, tenToTwenty())
				)
			);
		}

		@Test
		@DisplayName("should change no entity schema when the very same range of its attributes comes again")
		void shouldChangeNoEntitySchemaWhenSameRangeOfEntityAndReferenceAttributeComesAgain() {
			// a modification of an entity schema raises the version of the catalog schema whatever it changes
			changeAndVerifyEntitySchemaKept(
				session -> session.updateEntitySchema(
					new ModifyEntitySchemaMutation(
						Entities.PRODUCT,
						new ModifyAttributeSchemaDefaultValueMutation(ATTRIBUTE_RANGE, tenToTwenty()),
						new ModifyReferenceAttributeSchemaMutation(
							REFERENCE_BRAND,
							new ModifyAttributeSchemaDefaultValueMutation(ATTRIBUTE_RANGE, tenToTwenty())
						)
					)
				)
			);
		}

	}

	@Nested
	@DisplayName("through a removal and a creation the schema builders combine")
	class RemovalAndCreationTest {

		@Test
		@DisplayName("should replace the default value of an entity attribute")
		void shouldReplaceDefaultValueOfEntityAttribute() {
			changeAndVerifyAfterRestart(
				session -> recreateEntityAttribute(session, oneToTwo()),
				() -> entityDefaultValueIs("[1.0,2.0]", 1)
			);
		}

		@Test
		@DisplayName("should replace the default value of a global attribute")
		void shouldReplaceDefaultValueOfGlobalAttribute() {
			changeAndVerifyAfterRestart(
				session -> recreateUnusedGlobalAttribute(session, oneToTwo()),
				() -> unusedGlobalDefaultValueIs("[1.0,2.0]", 1)
			);
		}

		@Test
		@DisplayName("should replace the default value of a reference attribute")
		void shouldReplaceDefaultValueOfReferenceAttribute() {
			changeAndVerifyAfterRestart(
				session -> recreateReferenceAttribute(session, oneToTwo()),
				() -> referenceDefaultValueIs("[1.0,2.0]", 1)
			);
		}

		@Test
		@DisplayName("should change no schema when the attributes are created again with the very same range")
		void shouldChangeNoSchemaWhenAttributesAreCreatedAgainWithSameRange() {
			changeAndVerifyNothingChanged(
				session -> {
					recreateUnusedGlobalAttribute(session, tenToTwenty());
					recreateEntityAttribute(session, tenToTwenty());
					recreateReferenceAttribute(session, tenToTwenty());
				}
			);
		}

	}

}
