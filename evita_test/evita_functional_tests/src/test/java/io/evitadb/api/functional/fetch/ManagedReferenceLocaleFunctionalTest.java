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

package io.evitadb.api.functional.fetch;

import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces https://github.com/FgForrest/evitaDB/issues/1343 - when a query defines a query-level
 * {@link io.evitadb.api.query.filter.EntityLocaleEquals} and requests {@code referenceContent} with
 * {@link ManagedReferencesBehaviour#EXISTING}, references pointing to entities that exist but have no
 * data in the requested locale must be omitted entirely, not returned without a body.
 *
 * It also pins what a locale named by the filter of a {@code referenceContent} means: a bare
 * {@link io.evitadb.api.query.filter.EntityLocaleEquals} keeps the references of an owner having data in that locale,
 * whatever the locales of the referenced entities, and is the locale the localized data of the fetched referenced
 * entities come in unless {@code dataInLocales} names another, while
 * {@code entityHaving(entityLocaleEquals(...))} keeps the references to entities having data in that locale, whatever
 * the locales of the owner.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Evita managed reference locale handling")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(REFERENCE)
class ManagedReferenceLocaleFunctionalTest {
	private static final String DATA_SET = "managedReferenceLocale";
	private static final String REFERENCE_CATEGORY = "category";
	private static final String REFERENCE_BRAND = "brand";
	private static final String REFERENCE_PARAMETER = "parameter";
	private static final Locale LOCALE_CZECH = new Locale("cs");

	@DataSet(DATA_SET)
	DataCarrier setUp(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// category schema is localized, but also carries a non-localized attribute so that an individual
				// category may end up with no localized data at all
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable())
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::nullable)
					.updateAndFetchVia(session);

				// brand is a non-localized entity type - no localized attribute, no `withLocale` declared at all,
				// so its schema reports `isLocalized() == false` and the locale check never applies to it
				session.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> {})
					.updateAndFetchVia(session);

				// parameter is localized in both Czech and English, its group only in English
				session.defineEntitySchema(Entities.PARAMETER)
					.withoutGeneratedPrimaryKey()
					.withLocale(LOCALE_CZECH, Locale.ENGLISH)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateAndFetchVia(session);

				session.defineEntitySchema(Entities.PARAMETER_GROUP)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateAndFetchVia(session);

				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(LOCALE_CZECH, Locale.GERMAN, Locale.ENGLISH)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.withReferenceToEntity(
						REFERENCE_CATEGORY,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_ONE,
						ReferenceSchemaEditor::indexedForFiltering
					)
					.withReferenceToEntity(
						REFERENCE_BRAND,
						Entities.BRAND,
						Cardinality.ZERO_OR_ONE,
						ReferenceSchemaEditor::indexedForFiltering
					)
					.withReferenceToEntity(
						REFERENCE_PARAMETER,
						Entities.PARAMETER,
						Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
							.indexedForFiltering()
					)
					.updateAndFetchVia(session);

				// category is localized only in English
				session.createNewEntity(Entities.CATEGORY, 1)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Cameras")
					.upsertVia(session);

				// this category belongs to a localized schema, yet holds no localized data at all
				session.createNewEntity(Entities.CATEGORY, 2)
					.setAttribute(ATTRIBUTE_CODE, "uncategorized")
					.upsertVia(session);

				// brand has no locale-specific data whatsoever
				session.createNewEntity(Entities.BRAND, 1)
					.setAttribute(ATTRIBUTE_NAME, "Sony")
					.upsertVia(session);

				// parameter has both Czech and English data, its group only English
				session.createNewEntity(Entities.PARAMETER, 1)
					.setAttribute(ATTRIBUTE_NAME, LOCALE_CZECH, "Rozlišení")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Resolution")
					.upsertVia(session);

				session.createNewEntity(Entities.PARAMETER_GROUP, 1)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Optics")
					.upsertVia(session);

				// parameters of the products 2 and 3 - one with English data only, one shared by both products and
				// one referenced by product 3 alone, both of the latter in Czech and English
				session.createNewEntity(Entities.PARAMETER, 2)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Zoom")
					.upsertVia(session);
				session.createNewEntity(Entities.PARAMETER, 3)
					.setAttribute(ATTRIBUTE_NAME, LOCALE_CZECH, "Clona")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Aperture")
					.upsertVia(session);
				session.createNewEntity(Entities.PARAMETER, 4)
					.setAttribute(ATTRIBUTE_NAME, LOCALE_CZECH, "Ohnisko")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Focal length")
					.upsertVia(session);

				// product is localized in Czech, German and English and references the category and brand above
				session.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(ATTRIBUTE_NAME, LOCALE_CZECH, "Foto")
					.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Kamera")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Camera")
					.setReference(REFERENCE_CATEGORY, 1)
					.setReference(REFERENCE_BRAND, 1)
					.setReference(REFERENCE_PARAMETER, 1, whichIs -> whichIs.setGroup(1))
					.upsertVia(session);

				// second product references the category that carries no localized data at all, the English-only
				// parameter and the shared one
				session.createNewEntity(Entities.PRODUCT, 2)
					.setAttribute(ATTRIBUTE_NAME, LOCALE_CZECH, "Objektiv")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Lens")
					.setReference(REFERENCE_CATEGORY, 2)
					.setReference(REFERENCE_PARAMETER, 2)
					.setReference(REFERENCE_PARAMETER, 3)
					.upsertVia(session);

				// third product is localized in English only and references the shared parameter and its own one,
				// both having Czech data
				session.createNewEntity(Entities.PRODUCT, 3)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Tripod")
					.setReference(REFERENCE_PARAMETER, 3)
					.setReference(REFERENCE_PARAMETER, 4)
					.upsertVia(session);
			}
		);
		return new DataCarrier();
	}

	@DisplayName("Should omit reference to an existing entity that has no data in the requested locale")
	@UseDataSet(DATA_SET)
	@Test
	void shouldOmitExistingReferenceWhenReferencedEntityMissingDataInQueryLocale(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(1),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch(
								referenceContent(
									ManagedReferencesBehaviour.EXISTING,
									REFERENCE_CATEGORY,
									entityFetch(attributeContentAll())
								)
							)
						)
					)
				).orElseThrow();

				final Collection<ReferenceContract> categoryReferences = productByPk.getReferences(REFERENCE_CATEGORY);
				assertTrue(
					categoryReferences.isEmpty(),
					"Reference to a category without data in the requested locale (cs) must be omitted entirely " +
						"when ManagedReferencesBehaviour.EXISTING is used, but was: " + categoryReferences
				);

				return null;
			}
		);
	}

	@DisplayName("Should include reference to an existing entity that has data in the requested locale")
	@UseDataSet(DATA_SET)
	@Test
	void shouldIncludeExistingReferenceWhenReferencedEntityHasDataInQueryLocale(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(1),
								entityLocaleEquals(Locale.ENGLISH)
							)
						),
						require(
							entityFetch(
								referenceContent(
									ManagedReferencesBehaviour.EXISTING,
									REFERENCE_CATEGORY,
									entityFetch(attributeContentAll())
								)
							)
						)
					)
				).orElseThrow();

				final Collection<ReferenceContract> categoryReferences = productByPk.getReferences(REFERENCE_CATEGORY);
				assertTrue(
					categoryReferences.size() == 1 &&
						categoryReferences.iterator().next().getReferencedEntity().isPresent(),
					"Reference to a category with data in the requested locale (en) must be included with its body."
				);

				return null;
			}
		);
	}

	@DisplayName("Should include reference to a non-localized entity type regardless of the requested locale")
	@UseDataSet(DATA_SET)
	@Test
	void shouldIncludeExistingReferenceWhenReferencedEntityTypeIsNotLocalized(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(1),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch(
								referenceContent(
									ManagedReferencesBehaviour.EXISTING,
									REFERENCE_BRAND,
									entityFetch(attributeContentAll())
								)
							)
						)
					)
				).orElseThrow();

				final Collection<ReferenceContract> brandReferences = productByPk.getReferences(REFERENCE_BRAND);
				// the brand schema declares no localized data at all, so the locale check does not apply to it -
				// contrast with the localized category schema, where a locale-less entity IS suppressed
				assertTrue(
					brandReferences.size() == 1 &&
						brandReferences.iterator().next().getReferencedEntity().isPresent(),
					"Reference to a brand of a non-localized entity type must never be dropped due to locale " +
						"filtering, but was: " + brandReferences
				);

				return null;
			}
		);
	}

	@DisplayName("Should omit reference even when the entity fetch explicitly requests all locales")
	@UseDataSet(DATA_SET)
	@Test
	void shouldOmitExistingReferenceEvenWhenEntityFetchRequestsAllLocales(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(1),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch(
								referenceContent(
									ManagedReferencesBehaviour.EXISTING,
									REFERENCE_CATEGORY,
									entityFetchAll()
								)
							)
						)
					)
				).orElseThrow();

				// the referenced entity body-fetch gate keys off the query's required/implicit locale regardless of
				// entityFetch's own requirements, so requesting all locales (dataInLocalesAll) does not make the
				// category body fetchable here - the reference must still be omitted, not returned without a body
				final Collection<ReferenceContract> categoryReferences = productByPk.getReferences(REFERENCE_CATEGORY);
				assertTrue(
					categoryReferences.isEmpty(),
					"Reference to a category without data in the requested locale (cs) must be omitted entirely " +
						"even when the entity fetch requests all locales, but was: " + categoryReferences
				);

				return null;
			}
		);
	}

	@DisplayName("Should omit reference to an entity of a localized schema that holds no localized data")
	@UseDataSet(DATA_SET)
	@Test
	void shouldOmitExistingReferenceWhenReferencedEntityOfLocalizedSchemaHasNoLocalizedData(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(2),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch(
								referenceContent(
									ManagedReferencesBehaviour.EXISTING,
									REFERENCE_CATEGORY,
									entityFetch(attributeContentAll())
								)
							)
						)
					)
				).orElseThrow();

				// the exemption from the locale check is driven by the schema, not by the particular entity - the
				// category schema is localized, so a category holding no localized data is not fetchable in `cs`
				// and its reference must be omitted rather than returned without a body
				final Collection<ReferenceContract> categoryReferences = productByPk.getReferences(REFERENCE_CATEGORY);
				assertTrue(
					categoryReferences.isEmpty(),
					"Reference to a category of a localized schema that holds no localized data must be omitted, " +
						"but was: " + categoryReferences
				);

				return null;
			}
		);
	}

	@DisplayName("Should omit reference even when no reference body is requested at all")
	@UseDataSet(DATA_SET)
	@Test
	void shouldOmitExistingReferenceEvenWhenNoBodyIsRequested(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(1),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch(
								referenceContent(ManagedReferencesBehaviour.EXISTING, REFERENCE_CATEGORY)
							)
						)
					)
				).orElseThrow();

				// reference visibility must not depend on whether the caller asked for the referenced body - an
				// entity missing data in the query locale is consistently treated as non-existing
				final Collection<ReferenceContract> categoryReferences = productByPk.getReferences(REFERENCE_CATEGORY);
				assertTrue(
					categoryReferences.isEmpty(),
					"Reference to a category without data in the requested locale (cs) must be omitted even when no " +
						"body was requested, but was: " + categoryReferences
				);

				return null;
			}
		);
	}

	@DisplayName("Should honour reference-level locale filter instead of the query-level one")
	@UseDataSet(DATA_SET)
	@Test
	void shouldIncludeExistingReferenceWhenReferenceLevelFilterOverridesQueryLocale(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(1),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch(
								referenceContent(
									ManagedReferencesBehaviour.EXISTING,
									REFERENCE_CATEGORY,
									filterBy(entityHaving(entityLocaleEquals(Locale.ENGLISH))),
									entityFetch(attributeContentAll())
								)
							)
						)
					)
				).orElseThrow();

				// the reference-level `entityLocaleEquals` overrides the query-level one for the body fetch, so the
				// English-only category is fetchable here and must not be excluded
				final Collection<ReferenceContract> categoryReferences = productByPk.getReferences(REFERENCE_CATEGORY);
				assertTrue(
					categoryReferences.size() == 1 &&
						categoryReferences.iterator().next().getReferencedEntity().isPresent(),
					"Reference-level locale filter (en) must take precedence over the query-level one (cs), but was: " +
						categoryReferences
				);

				return null;
			}
		);
	}

	@DisplayName("Should honour reference-level locale filter also for the referenced entity group")
	@UseDataSet(DATA_SET)
	@Test
	void shouldIncludeExistingReferenceGroupWhenReferenceLevelFilterOverridesQueryLocale(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(1),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch(
								referenceContent(
									ManagedReferencesBehaviour.EXISTING,
									REFERENCE_PARAMETER,
									filterBy(entityHaving(entityLocaleEquals(Locale.ENGLISH))),
									entityFetch(attributeContentAll()),
									entityGroupFetch(attributeContentAll())
								)
							)
						)
					)
				).orElseThrow();

				// the group body is fetched with the very same derived request as the referenced entity body, so the
				// English-only group must be resolved against the reference-level locale (en), not the query-level (cs)
				final Collection<ReferenceContract> parameterReferences = productByPk.getReferences(REFERENCE_PARAMETER);
				assertEquals(
					1, parameterReferences.size(),
					"Reference to a parameter having English data must be present, but was: " + parameterReferences
				);
				assertTrue(
					parameterReferences.iterator().next().getGroupEntity().isPresent(),
					"Group of the parameter reference must be resolved using the reference-level locale (en) - it is " +
						"fetchable and must not be dropped by the EXISTING pre-filter."
				);

				return null;
			}
		);
	}

	@DisplayName("Should compose a non-locale reference filter with the locale-driven existence check")
	@UseDataSet(DATA_SET)
	@Test
	void shouldCombineNonLocaleReferenceFilterWithLocaleExclusion(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// the non-locale reference filter matches the category, yet the query-level locale (cs) still applies
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(1),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch(
								referenceContent(
									ManagedReferencesBehaviour.EXISTING,
									REFERENCE_CATEGORY,
									filterBy(entityHaving(entityPrimaryKeyInSet(1))),
									entityFetch(attributeContentAll())
								)
							)
						)
					)
				).orElseThrow();

				final Collection<ReferenceContract> categoryReferences = productByPk.getReferences(REFERENCE_CATEGORY);
				assertTrue(
					categoryReferences.isEmpty(),
					"A non-locale reference filter must not suppress the locale-driven existence check, but was: " +
						categoryReferences
				);

				// the same non-locale reference filter under an English query keeps the reference with its body
				final SealedEntity productInEnglish = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(1),
								entityLocaleEquals(Locale.ENGLISH)
							)
						),
						require(
							entityFetch(
								referenceContent(
									ManagedReferencesBehaviour.EXISTING,
									REFERENCE_CATEGORY,
									filterBy(entityHaving(entityPrimaryKeyInSet(1))),
									entityFetch(attributeContentAll())
								)
							)
						)
					)
				).orElseThrow();

				final Collection<ReferenceContract> englishCategoryReferences = productInEnglish.getReferences(REFERENCE_CATEGORY);
				assertTrue(
					englishCategoryReferences.size() == 1 &&
						englishCategoryReferences.iterator().next().getReferencedEntity().isPresent(),
					"A matching non-locale reference filter must keep the reference with its body when the locale " +
						"matches, but was: " + englishCategoryReferences
				);

				return null;
			}
		);
	}

	/**
	 * A bare `entityLocaleEquals` in the filter of a `referenceContent` is evaluated against the locales of the owner:
	 * an owner without data in that locale keeps none of its references, although both entities they point to have
	 * data in it, while an owner having data in that locale keeps even a reference to an entity that has none.
	 *
	 * @param evita the evitaDB instance with the data set
	 */
	@DisplayName("Should evaluate a bare locale of the reference filter against the locales of the owner")
	@UseDataSet(DATA_SET)
	@Test
	void shouldEvaluateReferenceFilterLocaleAgainstOwnerLocales(Evita evita) {
		// product 3 has English data only, both parameters it references have Czech data as well
		assertEquals(
			List.of(),
			getReferencedParameters(
				evita, 3, null, referenceContent(REFERENCE_PARAMETER, filterBy(entityLocaleEquals(LOCALE_CZECH)))
			),
			"An owner without Czech data must keep no reference through a reference filter by the Czech locale."
		);
		assertEquals(
			List.of(3, 4),
			getReferencedParameters(
				evita, 3, null, referenceContent(REFERENCE_PARAMETER, filterBy(entityLocaleEquals(Locale.ENGLISH)))
			),
			"An owner with English data must keep its references through a reference filter by the English locale."
		);
		// product 2 has Czech data, parameter 2 has English data only
		assertEquals(
			List.of(2, 3),
			getReferencedParameters(
				evita, 2, null, referenceContent(REFERENCE_PARAMETER, filterBy(entityLocaleEquals(LOCALE_CZECH)))
			),
			"An owner with Czech data must keep even its reference to a parameter without Czech data."
		);
	}

	/**
	 * The locale of a bare `entityLocaleEquals` in the filter of a `referenceContent` is also the locale the localized
	 * data of the referenced entities fetched by it come in - even when the query itself filters by another locale -
	 * unless `dataInLocales` of the nested `entityFetch` names another one.
	 *
	 * @param evita the evitaDB instance with the data set
	 */
	@DisplayName("Should fetch the localized data of the referenced entities in the locale of the reference filter")
	@UseDataSet(DATA_SET)
	@Test
	void shouldFetchReferencedEntitiesInReferenceFilterLocale(Evita evita) {
		// with no locale named anywhere the body carries none of its localized data
		assertEquals(
			Map.of(),
			getNamesOfSharedParameter(
				evita, null, referenceContent(REFERENCE_PARAMETER, entityFetch(attributeContentAll()))
			),
			"A parameter fetched with no locale named anywhere must come without its localized name."
		);
		assertEquals(
			Map.of(LOCALE_CZECH, "Clona"),
			getNamesOfSharedParameter(
				evita, null,
				referenceContent(
					REFERENCE_PARAMETER,
					filterBy(entityLocaleEquals(LOCALE_CZECH)),
					entityFetch(attributeContentAll())
				)
			),
			"A parameter fetched through a reference filter by the Czech locale must come with its Czech name only."
		);
		assertEquals(
			Map.of(LOCALE_CZECH, "Clona"),
			getNamesOfSharedParameter(
				evita, Locale.ENGLISH,
				referenceContent(
					REFERENCE_PARAMETER,
					filterBy(entityLocaleEquals(LOCALE_CZECH)),
					entityFetch(attributeContentAll())
				)
			),
			"The locale of the reference filter must win over the English locale of the query."
		);
		assertEquals(
			Map.of(Locale.ENGLISH, "Aperture"),
			getNamesOfSharedParameter(
				evita, null,
				referenceContent(
					REFERENCE_PARAMETER,
					filterBy(entityLocaleEquals(LOCALE_CZECH)),
					entityFetch(attributeContentAll(), dataInLocales(Locale.ENGLISH))
				)
			),
			"`dataInLocales` of the nested fetch must win over the locale of the reference filter."
		);
	}

	/**
	 * `entityHaving(entityLocaleEquals(...))` in the filter of a `referenceContent` keeps the references to entities
	 * having data in that locale, whatever the locales of the owner.
	 *
	 * @param evita the evitaDB instance with the data set
	 */
	@DisplayName("Should filter references by the locale of the referenced entity through entityHaving")
	@UseDataSet(DATA_SET)
	@Test
	void shouldFilterReferencesByReferencedEntityLocaleThroughEntityHaving(Evita evita) {
		// product 3 has English data only, both parameters it references have Czech data as well
		assertEquals(
			List.of(3, 4),
			getReferencedParameters(
				evita, 3, null,
				referenceContent(REFERENCE_PARAMETER, filterBy(entityHaving(entityLocaleEquals(LOCALE_CZECH))))
			),
			"An owner without Czech data must keep its references to the parameters having Czech data."
		);
		// product 2 has Czech data, parameter 2 has English data only
		assertEquals(
			List.of(3),
			getReferencedParameters(
				evita, 2, null,
				referenceContent(REFERENCE_PARAMETER, filterBy(entityHaving(entityLocaleEquals(LOCALE_CZECH))))
			),
			"An owner with Czech data must lose its reference to the parameter without Czech data."
		);
	}

	/**
	 * Fetches a product with its parameter references and returns the primary keys of the parameters it keeps.
	 *
	 * @param evita            the evitaDB instance with the data set
	 * @param productPk        the primary key of the product
	 * @param queryLocale      the locale the query itself filters by, `null` for none
	 * @param referenceContent the requirement fetching the parameter references
	 * @return the primary keys of the referenced parameters, ascending
	 */
	@Nonnull
	private static List<Integer> getReferencedParameters(
		@Nonnull Evita evita,
		int productPk,
		@Nullable Locale queryLocale,
		@Nonnull ReferenceContent referenceContent
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productPk),
							queryLocale == null ? null : entityLocaleEquals(queryLocale)
						),
						require(entityFetch(referenceContent))
					)
				).orElseThrow();
				return product.getReferences(REFERENCE_PARAMETER)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.sorted()
					.toList();
			}
		);
	}

	/**
	 * Fetches product 2 with its parameter references and returns the localized names the body of the parameter 3 -
	 * the one product 2 shares with product 3 - comes with.
	 *
	 * @param evita            the evitaDB instance with the data set
	 * @param queryLocale      the locale the query itself filters by, `null` for none
	 * @param referenceContent the requirement fetching the parameter references with their bodies
	 * @return the name of the parameter by locale, empty when the body came with no localized name
	 */
	@Nonnull
	private static Map<Locale, Serializable> getNamesOfSharedParameter(
		@Nonnull Evita evita,
		@Nullable Locale queryLocale,
		@Nonnull ReferenceContent referenceContent
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2),
							queryLocale == null ? null : entityLocaleEquals(queryLocale)
						),
						require(entityFetch(referenceContent))
					)
				).orElseThrow();
				final SealedEntity parameter = product.getReference(REFERENCE_PARAMETER, 3)
					.flatMap(ReferenceContract::getReferencedEntity)
					.orElseThrow(() -> new AssertionError("Product 2 must come with the body of parameter 3."));
				return parameter.getAttributeValues()
					.stream()
					.filter(it -> ATTRIBUTE_NAME.equals(it.key().attributeName()))
					.collect(Collectors.toMap(it -> it.key().locale(), it -> Objects.requireNonNull(it.value())));
			}
		);
	}

}
