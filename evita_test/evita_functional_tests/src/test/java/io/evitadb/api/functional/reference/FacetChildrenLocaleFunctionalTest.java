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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FACET;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the category locale level of facet children expansion independently of the product locale.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Locales of hierarchical facet children")
@ExtendWith(EvitaParameterResolver.class)
@Tag(ENGINE)
@Tag(FACET)
class FacetChildrenLocaleFunctionalTest {
	private static final String DATA_SET = "facetChildrenLocales";
	private static final Locale CS = Locale.forLanguageTag("cs");

	/** Builds an English root, a bilingual child and an English-only child with opposite localized matches. */
	@DataSet(DATA_SET)
	void setUp(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Category").withoutGeneratedPrimaryKey().withHierarchy()
				.withLocale(CS, Locale.ENGLISH)
				.withAttribute("match", String.class, attribute -> attribute.localized().filterable())
				.updateVia(session);
			session.upsertEntity(session.createNewEntity("Category", 1)
				.setAttribute("match", Locale.ENGLISH, "root"));
			session.upsertEntity(session.createNewEntity("Category", 2).setParent(1)
				.setAttribute("match", Locale.ENGLISH, "no").setAttribute("match", CS, "yes"));
			session.upsertEntity(session.createNewEntity("Category", 3).setParent(1)
				.setAttribute("match", Locale.ENGLISH, "yes"));
			session.defineEntitySchema("Product").withoutGeneratedPrimaryKey().withLocale(CS, Locale.ENGLISH)
				.withAttribute("name", String.class, attribute -> attribute.localized())
				.withReferenceToEntity("category", "Category", Cardinality.EXACTLY_ONE,
					reference -> reference.indexedForFiltering().faceted()).updateVia(session);
			for (int pk = 1; pk <= 3; pk++) {
				session.upsertEntity(session.createNewEntity("Product", pk)
					.setAttribute("name", Locale.ENGLISH, "product-en")
					.setReference("category", pk == 3 ? 1 : pk + 1));
			}
		});
	}

	/** Groups child-level locale checks over the shared read-only catalog. */
	@Nested
	@DisplayName("Child category locale levels")
	class ChildLocales {
		/** An explicit child locale differs from the product locale without conflicting or filtering owners. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("filters Czech subcategories while fetching English products")
		void shouldKeepProductLocaleWhenIncludedChildrenNameDifferentLocale(@Nonnull Evita evita) {
			assertExpansion(evita, Locale.ENGLISH, includingChildrenHaving(entityLocaleEquals(CS)), List.of(1, 3));
		}

		/** A child opinion cannot flow upward to products with no query locale. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("keeps products without a locale when only children name one")
		void shouldKeepProductLocaleAbsentWhenOnlyIncludedChildrenNameLocale(@Nonnull Evita evita) {
			assertExpansion(evita, null, includingChildrenHaving(entityLocaleEquals(CS)), List.of(1, 3));
		}

		/** Exclusion evaluates the Czech predicate on category nodes, including English-only owners. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("excludes Czech subcategories while fetching English products")
		void shouldExcludeChildrenWhenTheirOwnLocaleMatches(@Nonnull Evita evita) {
			assertExpansion(evita, Locale.ENGLISH, includingChildrenExcept(entityLocaleEquals(CS)), List.of(2, 3));
		}

		/** Without a child opinion, a localized predicate reads the enclosing English locale. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("inherits English for a localized subcategory attribute")
		void shouldInheritProductLocaleWhenChildrenFilterLocalizedAttribute(@Nonnull Evita evita) {
			assertExpansion(evita, Locale.ENGLISH, includingChildrenHaving(attributeEquals("match", "yes")),
				List.of(2, 3));
		}

		/** Without a child opinion, an exclusion by a localized attribute reads the enclosing English locale too. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("inherits English for a localized subcategory exclusion")
		void shouldInheritProductLocaleWhenChildrenExcludeByLocalizedAttribute(@Nonnull Evita evita) {
			assertExpansion(evita, Locale.ENGLISH, includingChildrenExcept(attributeEquals("match", "yes")),
				List.of(1, 3));
		}
	}

	/** Asserts stored premises, the selected owners, and the product body's own locale. */
	private static void assertExpansion(
		@Nonnull Evita evita, @Nullable Locale ownerLocale, @Nonnull FilterConstraint expansion,
		@Nonnull List<Integer> expected
	) {
		try (final EvitaSessionContract session = evita.createReadOnlySession(TEST_CATALOG)) {
			final SealedEntity bilingual = session.getEntity("Category", 2, attributeContentAll(),
				dataInLocalesAll()).orElseThrow();
			assertEquals(Set.of(CS, Locale.ENGLISH), bilingual.getLocales());
			assertEquals("yes", bilingual.getAttribute("match", CS));
			assertEquals("no", bilingual.getAttribute("match", Locale.ENGLISH));
			assertEquals(Set.of(Locale.ENGLISH), session.getEntity("Category", 3, attributeContentAll(),
				dataInLocalesAll()).orElseThrow().getLocales());
			final List<SealedEntity> products = session.queryListOfSealedEntities(query(collection("Product"),
				filterBy(entityLocaleEquals(ownerLocale), facetHaving("category", entityPrimaryKeyInSet(1), expansion)),
				require(entityFetch(attributeContentAll()))));
			assertEquals(expected, products.stream().map(SealedEntity::getPrimaryKey).toList(),
				"Child locale must filter categories independently of their owners");
			for (final SealedEntity product : products) {
				assertEquals(ownerLocale == null ? Set.of() : Set.of(ownerLocale), product.getLocales(),
					"A child locale cannot become the product locale");
				if (ownerLocale == null) {
					assertThrows(ContextMissingException.class, () -> product.getAttribute("name", Locale.ENGLISH));
				} else {
					assertEquals("product-en", product.getAttribute("name"));
				}
			}
		}
	}
}
