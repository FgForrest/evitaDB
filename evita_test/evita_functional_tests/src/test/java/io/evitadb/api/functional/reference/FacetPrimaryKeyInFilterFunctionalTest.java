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
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that brand primary keys inside `facetHaving` cannot supply the product keys used by
 * `entityPrimaryKeyInFilter`. Products 1 and 4 reference brand 5; products 2 and 3 reference brand 3.
 * Brand 5 is absent from the product collection, while brand 3 overlaps a product key so that leaking
 * the brand order (5, 3) visibly changes the default product order.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Product primary-key ordering with brand facets")
@ExtendWith(EvitaParameterResolver.class)
@Tag(ENGINE)
@Tag(FACET)
@Tag(ORDER)
class FacetPrimaryKeyInFilterFunctionalTest implements EvitaTestSupport {

	private static final String DATA_SET = "facetPrimaryKeyInFilter";
	private static final String PRODUCT = "product";
	private static final String BRAND = "brand";

	/**
	 * Builds four products and two brands with deliberately different primary-key universes.
	 */
	@DataSet(DATA_SET)
	void setUp(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(BRAND).updateVia(session);
				session.defineEntitySchema(PRODUCT)
					.withAttribute("code", String.class, whichIs -> whichIs.filterable().sortable())
					.withReferenceToEntity(
						BRAND, BRAND, Cardinality.EXACTLY_ONE,
						whichIs -> whichIs.indexedForFiltering().faceted()
							.withAttribute("code", String.class, AttributeSchemaEditor::filterable)
					)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(BRAND, 5));
				session.upsertEntity(session.createNewEntity(BRAND, 3));
				for (int productKey = 1; productKey <= 4; productKey++) {
					session.upsertEntity(
						session.createNewEntity(PRODUCT, productKey)
							.setAttribute("code", "p" + productKey)
							.setReference(BRAND, productKey == 1 || productKey == 4 ? 5 : 3,
								reference -> reference.setAttribute("code", "p3"))
					);
				}
			}
		);
	}

	/**
	 * Exercises both placements of a facet and parity with reference filtering when no owner keys are present.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("entityPrimaryKeyInFilter")
	class Ordering {

		/**
		 * Keeps the requested product order after applying a user-selected brand.
		 */
		@Test
		@DisplayName("Should preserve product key order with a facet inside userFilter")
		@UseDataSet(DATA_SET)
		void shouldPreserveProductOrderWhenFacetIsInsideUserFilter(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertFixturePremise(session);
				assertEquals(
					List.of(4, 1),
					queryProductKeys(session, query(
						collection(PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(4, 1, 2),
							userFilter(facetHaving(BRAND, entityPrimaryKeyInSet(5)))
						),
						orderBy(entityPrimaryKeyInFilter())
					))
				);
				return null;
			});
		}

		/**
		 * Keeps the same product order when the brand facet is a mandatory filter.
		 */
		@Test
		@DisplayName("Should preserve product key order with a facet directly inside filterBy")
		@UseDataSet(DATA_SET)
		void shouldPreserveProductOrderWhenFacetIsDirectlyInFilterBy(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertFixturePremise(session);
				assertEquals(
					List.of(4, 1),
					queryProductKeys(session, query(
						collection(PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(4, 1, 2),
							facetHaving(BRAND, entityPrimaryKeyInSet(5))
						),
						orderBy(entityPrimaryKeyInFilter())
					))
				);
				return null;
			});
		}

		/** Keeps default owner ordering when only facet keys are supplied. */
		@Test
		@DisplayName("keeps referenceHaving ordering when only facet keys are present")
		@UseDataSet(DATA_SET)
		void shouldMatchReferenceOrderWhenOnlyFacetPrimaryKeysArePresent(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertFixturePremise(session);
				final List<Integer> expected = queryProductKeys(session, query(collection(PRODUCT),
					filterBy(referenceHaving(BRAND, entityPrimaryKeyInSet(5, 3))),
					orderBy(entityPrimaryKeyInFilter())));
				assertEquals(List.of(1, 2, 3, 4), expected);
				assertEquals(expected, queryProductKeys(session, query(collection(PRODUCT),
					filterBy(facetHaving(BRAND, entityPrimaryKeyInSet(5, 3))),
					orderBy(entityPrimaryKeyInFilter()))), "Facet keys must not supply owner ordering");
				return null;
			});
		}

		/** Reference attributes cannot supply the owner's attribute-set order. */
		@Test
		@DisplayName("keeps referenceHaving ordering when a facet filters reference attributes")
		@UseDataSet(DATA_SET)
		void shouldMatchReferenceOrderWhenFacetFiltersAttributeSet(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertFixturePremise(session);
				final List<Integer> expected = queryProductKeys(session, query(collection(PRODUCT),
					filterBy(attributeInSet("code", "p4", "p1", "p2", "p3"),
						referenceHaving(BRAND, attributeInSet("code", "p3"))),
					orderBy(attributeSetInFilter("code"))));
				assertEquals(List.of(4, 1, 2, 3), expected);
				assertEquals(expected, queryProductKeys(session, query(collection(PRODUCT),
					filterBy(attributeInSet("code", "p4", "p1", "p2", "p3"),
						facetHaving(BRAND, attributeInSet("code", "p3"))),
					orderBy(attributeSetInFilter("code")))), "Reference attributes must not supply owner ordering");
				return null;
			});
		}
	}

	/**
	 * Reads the stored bodies before each reproduction, proving that brand 5 is not a product key and that
	 * the facet removes product 2 from (4, 1, 2).
	 */
	private static void assertFixturePremise(@Nonnull EvitaSessionContract session) {
		assertEquals(
			List.of(3, 5),
			session.queryEntityReference(query(collection(BRAND))).getRecordData().stream()
				.map(EntityReferenceContract::getPrimaryKey).toList()
		);
		final List<SealedEntity> products = session.queryListOfSealedEntities(query(
			collection(PRODUCT), require(entityFetch(referenceContent(BRAND)))
		));
		final List<Integer> productKeys = products.stream().map(SealedEntity::getPrimaryKeyOrThrowException).toList();
		assertEquals(List.of(1, 2, 3, 4), productKeys);
		assertEquals(
			Map.of(1, 5, 2, 3, 3, 3, 4, 5),
			products.stream().collect(Collectors.toMap(
				SealedEntity::getPrimaryKeyOrThrowException,
				product -> product.getReferences(BRAND).iterator().next().getReferencedPrimaryKey()
			))
		);
	}

	/**
	 * Executes a product query and returns its primary keys in response order.
	 */
	@Nonnull
	private static List<Integer> queryProductKeys(@Nonnull EvitaSessionContract session, @Nonnull Query request) {
		return session.queryEntityReference(request).getRecordData().stream()
			.map(EntityReferenceContract::getPrimaryKey).toList();
	}
}
