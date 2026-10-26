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
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNormalizedMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises server-installed step validation through public embedded evitaDB schema mutation APIs. */
@DisplayName("Normalization schema validation")
@Tag(ENGINE)
@Tag(SCHEMA)
class NormalizationSchemaValidationTest implements EvitaTestSupport {
	private TestPaths paths;
	private Evita evita;

	/** Creates isolated storage for this test's catalog. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("NormalizationSchemaValidationTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	/** Closes the embedded engine and removes its isolated fixture. */
	@AfterEach
	void tearDown() {
		try {
			this.evita.close();
		} finally {
			cleanupTestPaths(this.paths);
		}
	}

	/** Exercises declaration validation at every public schema mutation boundary. */
	@Nested
	@DisplayName("Declared normalizations")
	class DeclaredNormalizations {
		/** An unknown provider is invalid even for an empty collection and an attribute with no indexing capability. */
		@Test
		@DisplayName("should reject an unknown step while creating an unindexed attribute")
		void shouldRejectUnknownStepOnCreate() {
			assertInvalid("name", "absent", () ->
				NormalizationSchemaValidationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
					session.defineEntitySchema("Product")
						.withAttribute("name", String.class, attribute -> attribute.normalized(step("absent")))
						.updateVia(session);
				}));
		}

		/** Set mutations reach the same pre-exchange validation boundary as creation. */
		@Test
		@DisplayName("should reject an unknown step in a Set mutation")
		void shouldRejectUnknownStepOnSet() {
			NormalizationSchemaValidationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.defineEntitySchema("Product").withAttribute("name", String.class).updateVia(session);
			});
			assertInvalid("name", "absent", () ->
				NormalizationSchemaValidationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
					session.updateEntitySchema(new ModifyEntitySchemaMutation("Product", new SetAttributeSchemaNormalizedMutation(
						"name", new AnalysisStep[]{step("absent")}
					)));
				}));
			NormalizationSchemaValidationTest.this.evita.queryCatalog(TEST_CATALOG, session -> {
				assertTrue(session.getEntitySchemaOrThrow("Product").getAttribute("name").orElseThrow()
					.getNormalization().isEmpty());
			});
		}

		/** Reference attributes are validated even when unindexed and empty. */
		@Test
		@DisplayName("should reject unknown steps on reference attributes")
		void shouldRejectUnknownReferenceStep() {
			assertInvalid("referenceCode", "absent", () ->
				NormalizationSchemaValidationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
					session.defineEntitySchema("Product")
						.withReferenceTo("brand", "Brand", Cardinality.ZERO_OR_MORE, reference -> reference
							.withAttribute("referenceCode", String.class, attribute -> attribute.normalized(step("absent"))))
						.updateVia(session);
				}));
		}

		/** Global declarations are checked without waiting for a collection to adopt them. */
		@Test
		@DisplayName("should reject unknown steps on unused global attributes")
		void shouldRejectUnknownGlobalStep() {
			assertInvalid("globalCode", "absent", () ->
				NormalizationSchemaValidationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
					session.getCatalogSchema().openForWrite()
						.withAttribute("globalCode", String.class, attribute -> attribute.normalized(step("absent")))
						.updateVia(session);
				}));
		}

		/** Invalid parameters are rejected at DDL time with both attribute and step in the message. */
		@Test
		@DisplayName("should reject invalid parameters at schema change")
		void shouldRejectInvalidParameters() {
			assertInvalid("name", "remove-characters", () ->
				NormalizationSchemaValidationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
					session.defineEntitySchema("Product")
						.withAttribute("name", String.class, attribute -> attribute.normalized(step("remove-characters")))
						.updateVia(session);
				}));
		}

		/** A global Set must fail before the invalid declaration can cascade to any participating collection. */
		@Test
		@DisplayName("should reject invalid global changes before cascading")
		void shouldRejectInvalidGlobalCascade() {
			NormalizationSchemaValidationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite().withAttribute("globalCode", String.class).updateVia(session);
				session.defineEntitySchema("Product").withGlobalAttribute("globalCode").updateVia(session);
			});
			assertInvalid("globalCode", "absent", () ->
				NormalizationSchemaValidationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
					session.getCatalogSchema().openForWrite()
						.withAttribute("globalCode", String.class, attribute -> attribute.normalized(step("absent")))
						.updateVia(session);
				}));
			NormalizationSchemaValidationTest.this.evita.queryCatalog(TEST_CATALOG, session -> {
				assertTrue(session.getCatalogSchema().getAttribute("globalCode").orElseThrow().getNormalization().isEmpty());
				assertTrue(session.getEntitySchemaOrThrow("Product").getAttribute("globalCode").orElseThrow()
					.getNormalization().isEmpty());
			});
		}

		/** All declaration locations accept installed steps; global updates propagate their descriptions unchanged. */
		@Test
		@DisplayName("should accept valid entity reference and global declarations")
		void shouldAcceptValidDeclarations() {
			final AnalysisStep[] steps = {lowercase(), asciiFolding(), trim(), removeCharacters("-😀")};
			NormalizationSchemaValidationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withAttribute("globalCode", String.class, attribute -> attribute.normalized(steps)).updateVia(session);
				session.defineEntitySchema("Product").withGlobalAttribute("globalCode")
					.withAttribute("name", String[].class, attribute -> attribute.normalized(steps))
					.withReferenceTo("brand", "Brand", Cardinality.ZERO_OR_MORE, reference -> reference
						.withAttribute("referenceCode", String.class, attribute -> attribute.normalized(steps)))
					.updateVia(session);
				session.getCatalogSchema().openForWrite()
					.withAttribute("globalCode", String.class, attribute -> attribute.normalized(trim())).updateVia(session);
			});
			NormalizationSchemaValidationTest.this.evita.queryCatalog(TEST_CATALOG, session -> {
				assertEquals(List.of(trim()), session.getEntitySchemaOrThrow("Product").getAttribute("globalCode")
					.orElseThrow().getNormalization());
				assertEquals(List.of(steps), session.getEntitySchemaOrThrow("Product").getAttribute("name")
					.orElseThrow().getNormalization());
				assertEquals(List.of(steps), session.getEntitySchemaOrThrow("Product").getReference("brand").orElseThrow()
					.getAttribute("referenceCode").orElseThrow().getNormalization());
			});
		}

		/** Unresolved reflected references may declare valid steps and later inherit validated target descriptions. */
		@Test
		@DisplayName("should validate reflected declarations without refusing unresolved schemas")
		void shouldAllowUnresolvedReflectionAndRebinding() {
			NormalizationSchemaValidationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.defineEntitySchema("Category").withReflectedReferenceToEntity(
					"products", "Product", "categories", reference -> reference.withAttributesInheritedExcept("excluded")
						.withAttribute("ownCode", String.class, attribute -> attribute.normalized(trim()).withDefaultValue("x"))
				).updateVia(session);
				session.defineEntitySchema("Product").withReferenceToEntity(
					"categories", "Category", Cardinality.ZERO_OR_MORE, reference -> reference.indexedForFilteringAndPartitioning()
						.withAttribute("inheritedCode", String.class, attribute -> attribute.normalized(lowercase())
							.withDefaultValue("x"))
				).updateVia(session);
			});
			NormalizationSchemaValidationTest.this.evita.queryCatalog(TEST_CATALOG, session -> {
				assertEquals(
					List.of(lowercase()), session.getEntitySchemaOrThrow("Category").getReference("products").orElseThrow()
						.getAttribute("inheritedCode").orElseThrow().getNormalization()
				);
			});
		}

		/** Own attributes of an unresolved reflection must still be validated immediately. */
		@Test
		@DisplayName("should reject an invalid own attribute on an unresolved reflection")
		void shouldRejectInvalidUnresolvedReflection() {
			assertInvalid("ownCode", "absent", () ->
				NormalizationSchemaValidationTest.this.evita.updateCatalog(TEST_CATALOG, session -> {
					session.defineEntitySchema("Category").withReflectedReferenceToEntity(
						"products", "Product", "categories", reference -> reference.withAttributesInheritedExcept("excluded")
							.withAttribute("ownCode", String.class, attribute -> attribute.normalized(step("absent")))
					).updateVia(session);
				}));
		}
	}

	/** The provider set survives go-live and committed updates; a restart discovers a fresh registry. */
	@Test
	@DisplayName("should share one registry across snapshots and recreate it on reload")
	void shouldShareRegistryAcrossSnapshotsAndRecreateOnReload() {
		final AnalysisStepRegistry registry = ((Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG))
			.getAnalysisStepRegistry();
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Product")
				.withAttribute("name", String.class, attribute -> attribute.normalized(lowercase())).updateVia(session);
		});
		assertSame(registry, ((Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG))
			.getAnalysisStepRegistry());
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		assertNotSame(registry, ((Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG))
			.getAnalysisStepRegistry());
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertEquals(List.of(lowercase()), session.getEntitySchemaOrThrow("Product").getAttribute("name")
				.orElseThrow().getNormalization());
		});
	}

	/**
	 * Checks that diagnostics identify both the attribute and its offending step.
	 *
	 * @param attribute name of the attribute expected in the diagnostic
	 * @param step name of the offending step expected in the diagnostic
	 * @param action schema change expected to be rejected with `InvalidSchemaMutationException`
	 */
	private static void assertInvalid(@Nonnull String attribute, @Nonnull String step, @Nonnull Runnable action) {
		final String message = assertThrows(InvalidSchemaMutationException.class, action::run).getMessage();
		assertTrue(message.contains(attribute), message);
		assertTrue(message.contains(step), message);
	}
}
