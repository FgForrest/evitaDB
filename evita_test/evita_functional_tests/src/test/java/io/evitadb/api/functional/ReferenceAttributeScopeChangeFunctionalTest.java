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

package io.evitadb.api.functional;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.MandatoryAttributesNotProvidedException;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that a reference an existing entity gets in the same upsert that changes its scope is stored with the
 * default value of its attribute - the same value the reference gets when the upsert leaves the scope as it is. The
 * stored reference is read back through the entity body, in the engine that applied the upsert and after the engine
 * restarts on the stored data. No histogram, facet or other index takes part in the check. A reference missing a
 * mandatory attribute is refused alike, with or without the scope change.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A reference inserted together with a scope change gets the default values and the mandatory check")
@Tag(CONTRACT)
@Tag(REFERENCE)
class ReferenceAttributeScopeChangeFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String REFERENCE_BRAND = "brand";
	private static final String ATTRIBUTE_WEIGHT = "weight";
	private static final Scope[] ALL_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	private static final int PRODUCT_PK = 1;
	private static final int BRAND_PK = 10;
	private static final int DEFAULT_WEIGHT = 5;

	/**
	 * The ways the upsert giving the product its reference to the brand is made.
	 */
	enum UpsertForm {
		/** the local mutations are listed by hand, the scope change first */
		MUTATIONS,
		/** the entity builder of the stored product emits the mutations */
		BUILDER
	}

	/**
	 * Every combination of the catalog state, the form of the upsert and whether the upsert moves the product to the
	 * archive.
	 *
	 * @return arguments of {@link #shouldStoreDefaultReferenceAttributeWhenReferenceIsInsertedWithScopeChange} and of
	 * {@link #shouldRejectReferenceWhenMandatoryAttributeIsMissing}
	 */
	@Nonnull
	static Stream<Arguments> variants() {
		final List<Arguments> result = new ArrayList<>(8);
		for (final CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (final UpsertForm form : UpsertForm.values()) {
				for (final boolean scopeChange : new boolean[]{false, true}) {
					result.add(Arguments.of(state, form, scopeChange));
				}
			}
		}
		return result.stream();
	}

	/**
	 * Gives the stored product a reference to the brand without its `weight` attribute, whose schema declares the
	 * default value 5 - with or without a move of the product to the archive in the same upsert - and checks that
	 * the stored reference carries the weight 5 in the scope the product ends in, in memory and after a restart.
	 *
	 * @param state       the state the catalog is in when the upsert is applied
	 * @param form        the form of the upsert
	 * @param scopeChange TRUE when the upsert also moves the product to the archive, FALSE for the control
	 */
	@ParameterizedTest(name = "{0} {1} scopeChange={2}")
	@MethodSource("variants")
	@DisplayName("a reference inserted with a scope change stores the default value of its attribute")
	void shouldStoreDefaultReferenceAttributeWhenReferenceIsInsertedWithScopeChange(
		@Nonnull CatalogState state,
		@Nonnull UpsertForm form,
		boolean scopeChange
	) {
		final TestPaths paths = createTestPaths(
			"DefaultReferenceAttributeAfterScopeChange_" + state + "_" + form + "_" + scopeChange
		);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		final Scope expectedScope = scopeChange ? Scope.ARCHIVED : Scope.LIVE;
		final List<Executable> checks = new ArrayList<>(8);
		try {
			try (final Evita evita = new Evita(configuration)) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					setUpCatalog(session);
					if (state == CatalogState.ALIVE) {
						session.goLiveAndClose();
					}
				});
				evita.updateCatalog(TEST_CATALOG, session -> {
					insertReference(session, form, scopeChange);
				});
				recordStoredReference(evita, "in memory", expectedScope, checks);
			}

			// the reference read from the stored data must carry the default value as well
			try (final Evita evita = new Evita(configuration)) {
				evita.waitUntilFullyInitialized();
				recordStoredReference(evita, "after restart", expectedScope, checks);
			}
		} finally {
			cleanupTestPaths(paths);
		}
		assertAll(checks);
	}

	/**
	 * Refuses a missing mandatory reference attribute regardless of whether the owner changes scope, naming the
	 * attribute, and leaves the stored product as it was.
	 *
	 * @param state       the state the catalog is in when the upsert is applied
	 * @param form        the form of the upsert
	 * @param scopeChange TRUE when the upsert also moves the product to the archive, FALSE for the control
	 */
	@ParameterizedTest(name = "{0} {1} scopeChange={2}")
	@MethodSource("variants")
	@DisplayName("rejects an inserted reference without its mandatory attribute")
	void shouldRejectReferenceWhenMandatoryAttributeIsMissing(
		@Nonnull CatalogState state, @Nonnull UpsertForm form, boolean scopeChange
	) {
		final TestPaths paths = createTestPaths("mandatoryReference_" + state + "_" + form + "_" + scopeChange);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		try (final Evita evita = new Evita(configuration)) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, session -> {
				setUpCatalog(session, true);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});
			final MandatoryAttributesNotProvidedException exception = assertThrows(
				MandatoryAttributesNotProvidedException.class,
				() -> evita.updateCatalog(TEST_CATALOG, session -> {
					insertReference(session, form, scopeChange);
				})
			);
			final String message = exception.getMessage();
			assertTrue(message.contains("reference `" + REFERENCE_BRAND + "`"), message);
			assertTrue(message.contains("`" + ATTRIBUTE_WEIGHT + "`"), message);

			// the refused upsert must leave the stored product as it was
			final SealedEntity product = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, ALL_SCOPES, entityFetchAllContent()).orElseThrow();
			});
			assertEquals(Scope.LIVE, product.getScope());
			assertTrue(product.getReference(REFERENCE_BRAND, BRAND_PK).isEmpty(), "the refused reference was stored");
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Applies the upsert giving the stored product a reference to the brand without its `weight` attribute.
	 *
	 * @param session     the read-write session
	 * @param form        the form of the upsert
	 * @param scopeChange TRUE when the upsert also moves the product to the archive
	 */
	private static void insertReference(
		@Nonnull EvitaSessionContract session,
		@Nonnull UpsertForm form,
		boolean scopeChange
	) {
		switch (form) {
			case MUTATIONS -> {
				final InsertReferenceMutation insertion =
					new InsertReferenceMutation(new ReferenceKey(REFERENCE_BRAND, BRAND_PK));
				session.upsertEntity(
					scopeChange ?
						new EntityUpsertMutation(
							ENTITY_PRODUCT, PRODUCT_PK, EntityExistence.MUST_EXIST,
							new SetEntityScopeMutation(Scope.ARCHIVED), insertion
						) :
						new EntityUpsertMutation(ENTITY_PRODUCT, PRODUCT_PK, EntityExistence.MUST_EXIST, insertion)
				);
			}
			case BUILDER -> {
				final EntityBuilder product = session
					.getEntity(ENTITY_PRODUCT, PRODUCT_PK, ALL_SCOPES, entityFetchAllContent())
					.orElseThrow()
					.openForWrite();
				if (scopeChange) {
					product.setScope(Scope.ARCHIVED);
				}
				product.setReference(REFERENCE_BRAND, BRAND_PK).upsertVia(session);
			}
			default -> throw new IllegalStateException("Unexpected upsert form: " + form);
		}
	}

	/**
	 * Records the checks that the stored product sits in `expectedScope` and that its reference to the brand carries
	 * the default weight.
	 *
	 * @param evita         the engine
	 * @param stage         names the moment of the check
	 * @param expectedScope the scope the product must be stored in
	 * @param checks        the collected checks
	 */
	private static void recordStoredReference(
		@Nonnull Evita evita,
		@Nonnull String stage,
		@Nonnull Scope expectedScope,
		@Nonnull List<Executable> checks
	) {
		final SealedEntity product = evita.queryCatalog(TEST_CATALOG, session -> {
			return session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, ALL_SCOPES, entityFetchAllContent()).orElseThrow();
		});
		final Optional<ReferenceContract> reference = product.getReference(REFERENCE_BRAND, BRAND_PK);
		final Serializable weight = reference.isPresent() ? reference.get().getAttribute(ATTRIBUTE_WEIGHT) : null;
		checks.add(() -> assertEquals(expectedScope, product.getScope(), stage + ": scope of the stored product"));
		checks.add(() -> assertTrue(reference.isPresent(), stage + ": the stored product references the brand"));
		checks.add(() -> assertEquals(DEFAULT_WEIGHT, weight, stage + ": weight of the stored reference"));
	}

	/**
	 * Defines the schemas - the reference of a product to a brand, indexed in both scopes, with a nullable `weight`
	 * attribute whose default value is 5 - and stores the brand and the product, live and without any reference.
	 *
	 * @param session the session of the catalog in warm-up
	 */
	private static void setUpCatalog(@Nonnull EvitaSessionContract session) {
		setUpCatalog(session, false);
	}

	/**
	 * Defines the default-valued and mandatory variants of the reference fixture.
	 */
	private static void setUpCatalog(@Nonnull EvitaSessionContract session, boolean mandatory) {
		session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringInScope(ALL_SCOPES)
					.withAttribute(
						ATTRIBUTE_WEIGHT, Integer.class, thatIs -> {
							if (mandatory) {
								thatIs.nonNullable();
							} else {
								thatIs.nullable().withDefaultValue(DEFAULT_WEIGHT);
							}
						}
					)
			)
			.updateVia(session);
		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK));
		session.upsertEntity(session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK));
	}

}
