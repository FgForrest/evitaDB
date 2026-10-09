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

import io.evitadb.api.CatalogState;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the `searchable()` declaration of associated data end to end on an embedded evitaDB: that it is accepted on a
 * localized `String`, `String[]` and complex data object (with a text expression), that the scopes and the expression
 * survive a restart through the catalog storage, and that it is refused - for reasons that have nothing to do with the
 * data - on associated data that cannot carry it. A declaration over stored entities is accepted (#409 owns the
 * reindexing).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Searchable associated data declared by a schema change")
@Tag(ENGINE)
@Tag(SCHEMA)
@Tag(FULLTEXT)
class SearchableAssociatedDataSchemaTest implements EvitaTestSupport {

	private static final String BODY = "body";
	private static final String TAGS = "tags";
	private static final String ARTICLE = "article";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("SearchableAssociatedDataSchema");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("declared before the data goes in")
	class DeclaredUpFront {

		@Test
		@DisplayName("should keep String, String[] and complex data object searchable across a restart")
		void shouldKeepSearchableAssociatedDataAcrossRestart() {
			final SearchableAssociatedDataSchemaTest test = SearchableAssociatedDataSchemaTest.this;
			test.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withLocale(Locale.ENGLISH)
						// searchable before localized - only the assembled schema has to make sense
						.withAssociatedData(BODY, String.class, whichIs -> whichIs.searchable().localized())
						.withAssociatedData(
							TAGS, String[].class,
							whichIs -> whichIs.localized().searchableInScope(Scope.LIVE, Scope.ARCHIVED)
						)
						.withAssociatedData(
							ARTICLE, ComplexDataObject.class,
							whichIs -> whichIs.localized()
								.searchable(ExpressionFactory.parse("$value.blocks.*[$.text]"))
						)
						.updateVia(session);
				}
			);
			test.reopenOverTheSameStorage();

			final EntitySchemaContract schema = test.productSchema();
			final AssociatedDataSchemaContract body = schema.getAssociatedData(BODY).orElseThrow();
			assertTrue(body.isSearchable());
			assertNull(body.getTextExpression());
			assertEquals(
				EnumSet.of(Scope.LIVE, Scope.ARCHIVED),
				schema.getAssociatedData(TAGS).orElseThrow().getSearchableInScopes()
			);
			final AssociatedDataSchemaContract article = schema.getAssociatedData(ARTICLE).orElseThrow();
			assertEquals(EnumSet.of(Scope.LIVE), article.getSearchableInScopes());
			assertEquals(ExpressionFactory.parse("$value.blocks.*[$.text]"), article.getTextExpression());
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.functional.schema.SearchableAssociatedDataSchemaTest#unfitDeclarations")
		@DisplayName("should refuse associated data that cannot be searchable and leave the schema untouched")
		void shouldRefuseUnfitAssociatedData(
			@Nonnull String description,
			@Nonnull Class<? extends Serializable> type,
			@Nonnull UnaryOperator<AssociatedDataSchemaEditor> setup
		) {
			final SearchableAssociatedDataSchemaTest test = SearchableAssociatedDataSchemaTest.this;
			test.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.updateVia(session);
				}
			);
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> test.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.openForWrite()
							.withAssociatedData(BODY, type, setup::apply)
							.updateVia(session);
					}
				),
				description
			);
			assertTrue(test.productSchema().getAssociatedData(BODY).isEmpty(), description);
		}

	}

	@Nested
	@DisplayName("declared over stored entities")
	class DeclaredOverStoredEntities {

		@Test
		@DisplayName("should accept and then withdraw searchability on a live collection holding entities")
		void shouldAcceptAndWithdrawSearchabilityOverStoredEntities() {
			final SearchableAssociatedDataSchemaTest test = SearchableAssociatedDataSchemaTest.this;
			test.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withLocale(Locale.ENGLISH)
						.withAssociatedData(BODY, String.class, AssociatedDataSchemaEditor::localized)
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1)
							.setAssociatedData(BODY, Locale.ENGLISH, "<p>Black shoes</p>")
					);
					session.goLiveAndClose();
				}
			);

			// never refused because of the stored entity - the value written before stays unindexed until #409
			test.updateBody(AssociatedDataSchemaEditor::searchable);
			assertTrue(test.productSchema().getAssociatedData(BODY).orElseThrow().isSearchable());

			test.updateBody(AssociatedDataSchemaEditor::nonSearchable);
			test.reopenOverTheSameStorage();
			assertFalse(test.productSchema().getAssociatedData(BODY).orElseThrow().isSearchableInAnyScope());
		}

	}

	/**
	 * Declarations that cannot be searchable, each with the type of the associated data.
	 *
	 * @return the description, the type and the declaration
	 */
	@Nonnull
	static Stream<Arguments> unfitDeclarations() {
		return Stream.of(
			Arguments.of(
				"not a text type", Integer.class,
				(UnaryOperator<AssociatedDataSchemaEditor>) whichIs -> whichIs.localized().searchable()
			),
			Arguments.of(
				"not localized", String.class,
				(UnaryOperator<AssociatedDataSchemaEditor>) AssociatedDataSchemaEditor::searchable
			),
			Arguments.of(
				"complex data object without an expression", ComplexDataObject.class,
				(UnaryOperator<AssociatedDataSchemaEditor>) whichIs -> whichIs.localized().searchable()
			),
			Arguments.of(
				"String with an expression", String.class,
				(UnaryOperator<AssociatedDataSchemaEditor>) whichIs -> whichIs.localized()
					.searchable(ExpressionFactory.parse("$value.title"))
			),
			Arguments.of(
				"expression reading another variable", ComplexDataObject.class,
				(UnaryOperator<AssociatedDataSchemaEditor>) whichIs -> whichIs.localized()
					.searchable(ExpressionFactory.parse("$entity.title"))
			),
			Arguments.of(
				"expression computing a size", ComplexDataObject.class,
				(UnaryOperator<AssociatedDataSchemaEditor>) whichIs -> whichIs.localized()
					.searchable(ExpressionFactory.parse("$value.blocks.size()"))
			)
		);
	}

	/**
	 * Alters the `body` associated data of the product schema.
	 *
	 * @param setup the alteration
	 */
	private void updateBody(@Nonnull UnaryOperator<AssociatedDataSchemaEditor> setup) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchemaOrThrow(Entities.PRODUCT)
					.openForWrite()
					.withAssociatedData(BODY, String.class, setup::apply)
					.updateVia(session);
			}
		);
	}

	/**
	 * Reads the current product entity schema in a fresh read-only session.
	 *
	 * @return the product entity schema
	 */
	@Nonnull
	private EntitySchemaContract productSchema() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchemaOrThrow(Entities.PRODUCT);
			}
		);
	}

	/**
	 * Closes the embedded evitaDB and opens a new one over the very same storage, so that everything asserted
	 * afterwards was read back from disk.
	 */
	private void reopenOverTheSameStorage() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
		if (this.evita.getCatalogState(TEST_CATALOG).orElse(null) == CatalogState.INACTIVE) {
			CatalogUnpublishableBarrierAssertions.activateWithConflictRetry(this.evita, TEST_CATALOG);
		}
	}

}
