/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ExceptionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reflected reference must expose every attribute used by its sortable compounds. Validation waits until session
 * close or go-live, so that the two references may be declared in either order and an invalid intermediate state
 * can be repaired within the same session.
 *
 * Every scenario except go-live, which only a warm-up catalog can perform, runs in both the warm-up and the
 * transactional catalog state.
 */
@DisplayName("Reflected reference compounds whose attributes are not all inherited")
@Tag(CONTRACT)
@Tag(REFERENCE)
class ReflectedReferencePartiallyInheritedCompoundTest implements EvitaTestSupport {
	/** Name of the catalog every scenario writes into. */
	@Nonnull
	private static final String CATALOG = "partialCompound";
	/** Shared validity makes the label decide the compound ordering. */
	@Nonnull
	private static final DateTimeRange VALIDITY =
		DateTimeRange.since(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
	/** Directories of the engine under test. */
	@Nonnull
	private TestPaths paths;
	/** Engine under test. */
	@Nonnull
	private Evita evita;

	/** Opens an isolated engine without defining a catalog. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReflectedReferencePartiallyInheritedCompound");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	/** Closes the engine and removes its directories. */
	@AfterEach
	void tearDown() {
		if (this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/** A reflected reference declared before its source is refused only when the session closes. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("refuses the compound at session close when the reflected reference is declared first")
	void shouldRejectPartiallyInheritedCompoundWhenReflectedReferenceIsDeclaredFirst(boolean alive) {
		startCatalog(alive);
		final InvalidSchemaMutationException exception = assertRejectedAtClose(session -> {
			defineReflected(session, whichIs -> whichIs.withAttributesInheritedExcept("label"));
			defineOriginal(session, true);
		});
		assertTrue(
			exception.getMessage().contains("`withAttributesInheritedExcept(\"label\", \"validityLabel\")`"),
			exception::getMessage
		);
	}

	/** Declaring the original reference first must reach the same deferred validation. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("refuses the compound at session close when the original reference is declared first")
	void shouldRejectPartiallyInheritedCompoundWhenOriginalReferenceIsDeclaredFirst(boolean alive) {
		startCatalog(alive);
		assertRejectedAtClose(session -> {
			defineOriginal(session, true);
			defineReflected(session, whichIs -> whichIs.withAttributesInheritedExcept("label"));
		});
	}

	/** An allow list naming the compound must also include all of its attributes. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("refuses an allow list naming the compound without all its attributes")
	void shouldRejectCompoundInheritedWithoutAllItsAttributesUsingAllowList(boolean alive) {
		startCatalog(alive);
		final InvalidSchemaMutationException exception = assertRejectedAtClose(session -> {
			defineOriginal(session, true);
			defineReflected(session, whichIs -> whichIs.withAttributesInherited("validity", "validityLabel"));
		});
		assertTrue(
			exception.getMessage().contains("`withAttributesInherited(\"validity\", \"validityLabel\", \"label\")`"),
			exception::getMessage
		);
	}

	/** The diagnostic lists all absent elements rather than stopping at the first one. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("lists every missing compound attribute")
	void shouldReportEveryMissingCompoundAttribute(boolean alive) {
		startCatalog(alive);
		final InvalidSchemaMutationException exception = assertRejectedAtClose(session -> {
			defineOriginal(session, true);
			defineReflected(session, whichIs -> whichIs.withAttributesInherited("validityLabel"));
		});
		assertTrue(
			exception.getMessage().contains("missing its attributes: `validity`, `label`."), exception::getMessage
		);
	}

	/** Adding a compound in a later session must revalidate the already bound reflected reference. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("refuses a compound added later over an attribute the reflected reference excludes")
	void shouldRejectCompoundAddedLaterWhenReflectedReferenceExcludesItsAttribute(boolean alive) {
		startCatalog(alive);
		this.evita.updateCatalog(CATALOG, session -> {
			defineOriginal(session, false);
			defineReflected(session, whichIs -> whichIs.withAttributesInheritedExcept("label"));
		});
		assertRejectedAtClose(session -> defineOriginal(session, true));
	}

	/** Fully inherited compounds retain localized ordering through the reflected reference. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("orders by a fully inherited compound")
	void shouldOrderByFullyInheritedCompound(boolean alive) {
		startCatalog(alive);
		this.evita.updateCatalog(CATALOG, session -> {
			defineReflected(session, ReflectedReferenceSchemaBuilder::withAttributesInherited);
			defineOriginal(session, true);
		});
		writeReferences();
		assertOrdering("validityLabel", true, List.of(3, 2, 1));
	}

	/** An allow list naming the compound together with all of its attributes keeps the compound ordering. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("accepts an allow list naming the compound and all its attributes")
	void shouldAcceptAllowListNamingCompoundAndAllItsAttributes(boolean alive) {
		startCatalog(alive);
		this.evita.updateCatalog(CATALOG, session -> {
			defineOriginal(session, true);
			defineReflected(session, whichIs -> whichIs.withAttributesInherited("validity", "label", "validityLabel"));
		});
		this.evita.queryCatalog(CATALOG, session -> {
			final ReferenceSchemaContract reference = session.getEntitySchemaOrThrowException("brand")
				.getReferenceOrThrowException("products");
			assertTrue(reference.getSortableAttributeCompounds().containsKey("validityLabel"));
		});
		writeReferences();
		assertOrdering("validityLabel", true, List.of(3, 2, 1));
	}

	/** The inheritance filter applies to compound names too, leaving the other attributes sortable. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("accepts the compound excluded by name and orders by the remaining attribute")
	void shouldAllowCompoundExcludedByNameAndOrderByRemainingAttribute(boolean alive) {
		startCatalog(alive);
		this.evita.updateCatalog(CATALOG, session -> {
			defineOriginal(session, true);
			defineReflected(
				session, whichIs -> whichIs.withAttributesInheritedExcept("label", "validityLabel")
			);
		});
		this.evita.queryCatalog(CATALOG, session -> {
			final ReferenceSchemaContract reference = session.getEntitySchemaOrThrowException("brand")
				.getReferenceOrThrowException("products");
			assertTrue(reference.getSortableAttributeCompounds().isEmpty());
			assertTrue(reference.getAttribute("label").isEmpty());
			assertTrue(reference.getAttribute("validity").isPresent());
		});
		writeReferences();
		assertOrdering("validity", false, List.of(3, 1, 2));
	}

	/** A compound declared on the reflected reference is repaired by removing it, not by an inheritance filter. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("advises removing a declared compound that lost an inherited attribute")
	void shouldAdviseRemovingDeclaredCompoundThatLostItsAttribute(boolean alive) {
		startCatalog(alive);
		this.evita.updateCatalog(CATALOG, session -> {
			defineOriginal(session, false);
			defineReflected(session, ReflectedReferenceSchemaBuilder::withAttributesInherited);
		});
		// a compound over inherited attributes can be declared only once the reflected reference is bound
		this.evita.updateCatalog(CATALOG, session -> {
			defineReflected(
				session, whichIs -> whichIs.withSortableAttributeCompound(
					"localOrder",
					AttributeElement.attributeElement("validity"), AttributeElement.attributeElement("label")
				)
			);
		});
		final RuntimeException failure = assertThrows(
			RuntimeException.class,
			() -> this.evita.updateCatalog(CATALOG, session -> {
				defineReflected(session, whichIs -> whichIs.withAttributesInheritedExcept("label"));
			})
		);
		final InvalidSchemaMutationException cause =
			ExceptionUtils.findInCauseChain(failure, InvalidSchemaMutationException.class);
		assertNotNull(cause, () -> "Expected schema validation failure: " + failure);
		assertTrue(cause.getMessage().contains("`localOrder`"), cause::getMessage);
		assertTrue(
			cause.getMessage().contains("`withoutSortableAttributeCompound(\"localOrder\")`"), cause::getMessage
		);
	}

	/** Invalid intermediate states may be repaired before closing either kind of session. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("accepts a repair within the same schema-changing session")
	void shouldAllowRepairWithinSchemaChangingSession(boolean alive) {
		startCatalog(alive);
		this.evita.updateCatalog(CATALOG, session -> {
			defineOriginal(session, true);
			defineReflected(session, whichIs -> whichIs.withAttributesInheritedExcept("label"));
			defineReflected(
				session, whichIs -> whichIs.withAttributesInheritedExcept("label", "validityLabel")
			);
		});
		writeReferences();
		assertOrdering("validity", false, List.of(3, 1, 2));
	}

	/** Go-live must refuse the same invalid schema before publishing it. */
	@Test
	@DisplayName("refuses the compound at go-live")
	void shouldRejectPartiallyInheritedCompoundAtGoLive() {
		startCatalog(false);
		final EvitaSessionContract session = this.evita.createReadWriteSession(CATALOG);
		defineOriginal(session, true);
		defineReflected(session, whichIs -> whichIs.withAttributesInheritedExcept("label"));
		assertValidationMessage(assertThrows(RuntimeException.class, session::goLiveAndClose));
	}

	/** Defines the catalog, optionally moving it to transactional mode before schema changes. */
	private void startCatalog(boolean alive) {
		this.evita.defineCatalog(CATALOG);
		if (alive) {
			this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		}
	}

	/** Defines the original reference with independently sortable validity and an optional localized compound. */
	private static void defineOriginal(@Nonnull EvitaSessionContract session, boolean compound) {
		session.defineEntitySchema("product")
			.withLocale(Locale.ENGLISH)
			.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE, whichIs -> {
				whichIs.indexedForFiltering()
					.withAttribute("validity", DateTimeRange.class, thatIs -> thatIs.sortable())
					.withAttribute("label", String.class, thatIs -> thatIs.localized());
				if (compound) {
					whichIs.withSortableAttributeCompound(
						"validityLabel",
						AttributeElement.attributeElement("validity"), AttributeElement.attributeElement("label")
					);
				}
			})
			.updateVia(session);
	}

	/** Defines the reflected reference with the requested inheritance and local attributes. */
	private static void defineReflected(
		@Nonnull EvitaSessionContract session,
		@Nonnull Consumer<ReflectedReferenceSchemaBuilder> configuration
	) {
		session.defineEntitySchema("brand")
			.withLocale(Locale.ENGLISH)
			.withReflectedReferenceToEntity("products", "product", "brand", configuration)
			.updateVia(session);
	}

	/** Verifies schema construction finishes and the refusal comes from session-close validation. */
	@Nonnull
	private InvalidSchemaMutationException assertRejectedAtClose(@Nonnull Consumer<EvitaSessionContract> writer) {
		final AtomicBoolean schemaUpdated = new AtomicBoolean();
		final RuntimeException exception = assertThrows(RuntimeException.class, () ->
			this.evita.updateCatalog(CATALOG, session -> {
				writer.accept(session);
				schemaUpdated.set(true);
			})
		);
		assertTrue(schemaUpdated.get(), "Schema construction must finish before validation refuses the session");
		return assertValidationMessage(exception);
	}

	/** Checks the diagnostic identifies the broken compound and both supported inheritance repairs. */
	@Nonnull
	private static InvalidSchemaMutationException assertValidationMessage(@Nonnull Throwable failure) {
		final InvalidSchemaMutationException cause =
			ExceptionUtils.findInCauseChain(failure, InvalidSchemaMutationException.class);
		assertNotNull(cause, () -> "Expected schema validation failure: " + failure);
		final String message = cause.getMessage();
		for (final String fragment : List.of(
			"`products`", "`validityLabel`", "`label`", "inherit", "exclude", "inheritance filter"
		)) {
			assertTrue(message.contains(fragment), () -> "Missing `" + fragment + "` in: " + message);
		}
		return cause;
	}

	/** Writes tied validities with reversed labels and an earlier validity on the highest primary key. */
	private void writeReferences() {
		this.evita.updateCatalog(CATALOG, session -> {
			for (final int primaryKey : new int[]{1, 2, 3}) {
				session.createNewEntity("brand", primaryKey).upsertVia(session);
				session.createNewEntity("product", primaryKey)
					.setReference("brand", primaryKey, whichIs -> whichIs
						.setAttribute("validity", primaryKey == 3 ?
							DateTimeRange.since(OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)) : VALIDITY)
						.setAttribute("label", Locale.ENGLISH, primaryKey == 1 ? "second" : "first"))
					.upsertVia(session);
			}
		});
	}

	/**
	 * Asserts ordering through the reflected reference actually plans and returns the expected primary keys. Ordering
	 * by a compound with a localized element needs the query locale, hence `localized`.
	 */
	private void assertOrdering(@Nonnull String attribute, boolean localized, @Nonnull List<Integer> expected) {
		this.evita.queryCatalog(CATALOG, session -> {
			final List<Integer> primaryKeys = session.queryListOfSealedEntities(query(
				collection("brand"),
				filterBy(localized ? entityLocaleEquals(Locale.ENGLISH) : null),
				orderBy(referenceProperty("products", attributeNatural(attribute)))
			)).stream().map(SealedEntity::getPrimaryKeyOrThrowException).toList();
			assertEquals(expected, primaryKeys);
		});
	}
}
