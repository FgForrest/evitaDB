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

package io.evitadb.dataType.expression;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.expression.evaluate.MultiVariableEvaluationContext;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.utils.ListBuilder.list;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Evaluation of expressions with entity data using entity-specific accessors.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@DisplayName("Expression evaluation with entity data")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
public class ExpressionWithEntityDataTest {

	public static final String BRAND = "brand";
	public static final String STOCK = "stock";
	public static final String PRODUCT = "product";
	public static final String CATEGORIES = "categories";
	public static final Locale LOCALE_CZECH = new Locale("cs");

	@DisplayName("Should evaluate expression accessing entity data")
	@Test
	void shouldEvaluateExpressionAccessingEntityData(Evita evita) {
		prepareEntitySchema(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityReferenceContract brand = createBrand(session, 1).upsertVia(session);
				final EntityReferenceContract category1 = createCategory(session, 1).upsertVia(session);
				final EntityReferenceContract category2 = createCategory(session, 2).upsertVia(session);
				createProduct(session, 1, brand, category1, category2).upsertVia(session);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session.getEntity(
					PRODUCT,
					1,
					dataInLocalesAll(),
					attributeContentAll(),
					referenceContentWithAttributes(CATEGORIES, attributeContentAll()),
					referenceContentWithAttributes(BRAND, attributeContentAll(), entityFetch()),
					referenceContent(STOCK)
				)
					.orElseThrow();

				assertEquals(
					"SX87Y800BE",
					evaluate("$entity.attributes['code']", product)
				);
				// accessing global attributes via localized attributes
				assertThrows(
					ExpressionEvaluationException.class,
					() -> evaluate("$entity.localizedAttributes['code']", product)
				);
				assertArrayEquals(
					new String[] {"new", "sale"},
					(String[]) evaluate("$entity.attributes['tags']", product)
				);
				assertEquals(
					"new",
					evaluate("$entity.attributes['tags'][0]", product)
				);
				assertEquals(
					1000L,
					evaluate("$entity.attributes['priority']", product)
				);
				assertNull(
					evaluate("$entity.attributes['ean']", product)
				);
				assertEquals(
					"1234",
					evaluate("$entity.attributes['ean'] ?? '1234'", product)
				);
				assertEquals(
					new HashMap<>() {{
						put(LOCALE_CZECH, "https://example.com/cs/SX87Y800BE");
						put(Locale.ENGLISH, "https://example.com/en/SX87Y800BE");
					}},
					evaluate("$entity.localizedAttributes['url']", product) // localized attributes are not supported
				);
				assertEquals(
					new HashMap<>() {{
						put(LOCALE_CZECH, "https://old.example.com/cs/SX87Y800BE");
						put(Locale.ENGLISH, null);
					}},
					evaluate("$entity.localizedAttributes['prevUrl']", product) // localized attributes are not supported
				);
				// accessing localized attributes via global attributes
				assertThrows(
					ExpressionEvaluationException.class,
					() -> evaluate("$entity.attributes['url']", product)
				);
				assertEquals(
					new HashMap<>() {{
						put(LOCALE_CZECH, "https://old.example.com/cs/SX87Y800BE");
					}},
					evaluate("$entity.localizedAttributes['prevUrl'].*![$]", product) // localized attributes are not supported
				);
				assertEquals(
					new HashMap<>() {{
						put(LOCALE_CZECH, "https://old.example.com/cs/SX87Y800BE");
						put(Locale.ENGLISH, "https://example.com");
					}},
					evaluate("$entity.localizedAttributes['prevUrl'] *? 'https://example.com'", product) // localized attributes are not supported
				);
				assertEquals(
					1,
					evaluate("$entity.references['brand'].referencedPrimaryKey", product)
				);
				assertEquals(
					"Siemens GmBH",
					evaluate("$entity.references['brand'].attributes['distributor']", product)
				);
				assertNull(
					evaluate("$entity.references['brand'].attributes['brandTag']", product)
				);
				assertEquals(
					"new",
					evaluate("$entity.references['brand'].attributes['brandTag'] ?? 'new'", product)
				);
				assertEquals(
					List.of(1, 2),
					evaluate("$entity.references['categories'].*[$.referencedPrimaryKey]", product)
				);
				assertEquals(
					List.of(16L, 17L),
					evaluate("$entity.references['categories'].*[$.attributes['categoryPriority']]", product)
				);
				assertEquals(
					list().i(null).i(null).build(),
					evaluate("$entity.references['categories'].*[$.attributes['categoryTag']]", product)
				);
				assertEquals(
					List.of(),
					evaluate("$entity.references['categories'].*![$.attributes['categoryTag']]", product)
				);
				assertEquals(
					List.of("new", "new"),
					evaluate("$entity.references['categories'].*[$.attributes['categoryTag']] *? 'new'", product)
				);
				assertEquals(
					List.of(BigDecimal.valueOf(-16L), BigDecimal.valueOf(-17L)),
					evaluate("$entity.references['categories'].*[-$.attributes['categoryPriority']]", product)
				);
				assertEquals(
					1,
					evaluate("$entity.references['brand'].referencedEntity.primaryKey", product)
				);
				assertThrows(
					ExpressionEvaluationException.class,
					() -> evaluate("$entity.references['stock'].referencedEntity.attributes['distributor']", product)
				);
				assertNull(
					evaluate("$entity.references['stock'].referencedEntity?.attributes['distributor']", product)
				);
			}
		);
	}

	private static Serializable evaluate(@Nonnull String predicate, @Nonnull EntityContract entity) {
		final ExpressionNode operator = ExpressionFactory.parse(predicate);
		return operator.compute(
			new MultiVariableEvaluationContext(
				42,
				Map.of("entity", entity)
			),
			Serializable.class
		);
	}

	private static void prepareEntitySchema(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(CATEGORIES);
				session.defineEntitySchema(BRAND);

				session.defineEntitySchema(PRODUCT)
					.withoutHierarchy()
					.withLocale(Locale.ENGLISH, LOCALE_CZECH)
					.withAttribute("code", String.class, whichIs -> whichIs.unique())
					.withAttribute("url", String.class, whichIs -> whichIs.unique().localized())
					.withAttribute("prevUrl", String.class, whichIs -> whichIs.nullable().unique().localized())
					.withAttribute("tags", String[].class, whichIs -> whichIs.filterable())
					.withAttribute("priority", Long.class, whichIs -> whichIs.sortable())
					.withAttribute("ean", String.class, whichIs -> whichIs.nullable())
					.withReferenceToEntity(
						CATEGORIES,
						CATEGORIES,
						Cardinality.ZERO_OR_MORE,
						whichIs ->
							/* we can specify special attributes on relation */
							whichIs.indexedForFilteringAndPartitioning()
								.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
								.withAttribute("categoryTag", String.class, thatIs -> thatIs.nullable())
					)
					.withReferenceToEntity(
						BRAND,
						BRAND,
						Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs.faceted()
							.withAttribute("brandTag", String.class, thatIs -> thatIs.nullable()))
					.withReferenceTo(
						STOCK,
						STOCK,
						Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs.faceted()
					)
					/* finally apply schema changes */
					.updateVia(session);
			}
		);
	}

	private static EntityBuilder createBrand(@Nonnull EvitaSessionContract session, @Nullable Integer primaryKey) {
		final EntityBuilder newBrand = primaryKey == null
			? session.createNewEntity(BRAND)
			: session.createNewEntity(BRAND, primaryKey);
		newBrand.setAttribute("code", "siemens");
		return newBrand;
	}

	private static EntityBuilder createCategory(EvitaSessionContract session, Integer primaryKey) {
		final EntityBuilder newCategory = session.createNewEntity(CATEGORIES, primaryKey);
		newCategory.setAttribute("code", "builtin-dishwashers");
		newCategory.setAttribute("priority", 456L);
		return newCategory;
	}

	private static EntityBuilder createProduct(
		EvitaSessionContract session, int primaryKey, EntityReferenceContract brand,
		EntityReferenceContract... categories
	) {
		final EntityBuilder newProduct = session.createNewEntity(PRODUCT, primaryKey);

		newProduct.setAttribute("code", "SX87Y800BE");
		newProduct.setAttribute("url", LOCALE_CZECH, "https://example.com/cs/SX87Y800BE");
		newProduct.setAttribute("url", Locale.ENGLISH, "https://example.com/en/SX87Y800BE");
		newProduct.setAttribute("prevUrl", LOCALE_CZECH, "https://old.example.com/cs/SX87Y800BE");
		newProduct.setAttribute("tags", new String[] {"new", "sale"});
		newProduct.setAttribute("priority", 1000L);

		newProduct.setReference(
			BRAND,
			BRAND,
			Cardinality.ZERO_OR_ONE,
			Objects.requireNonNull(brand.getPrimaryKey()),
			with -> {
				with.setGroup("BRAND_GROUP", 89);
				with.setAttribute("distributor", "Siemens GmBH");
			}
		);
		for (final EntityReferenceContract category : categories) {
			newProduct.setReference(
				CATEGORIES,
				CATEGORIES,
				Cardinality.ZERO_OR_MORE,
				Objects.requireNonNull(category.getPrimaryKey()),
				whichIs -> {
					whichIs.setAttribute("categoryPriority", category.getPrimaryKey() + 15L);
				}
			);
		}
		newProduct.setReference(
			STOCK,
			STOCK,
			Cardinality.ZERO_OR_ONE,
			10000
		);

		return newProduct;
	}
}
