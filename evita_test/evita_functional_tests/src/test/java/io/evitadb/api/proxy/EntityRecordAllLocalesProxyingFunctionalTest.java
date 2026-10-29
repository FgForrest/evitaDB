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

package io.evitadb.api.proxy;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKeyRef;
import io.evitadb.api.requestResponse.data.annotation.ReferenceRef;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.PROXY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a record proxy collects one value per locale into an array or list component mapped to a scalar
 * localized attribute when the entity is fetched in all locales. Only constructor components reach this collection:
 * a getter of an interface proxy returns its default value when no single locale was requested.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Record proxies of localized data fetched in all locales")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(PROXY)
class EntityRecordAllLocalesProxyingFunctionalTest {
	private static final String DATA_SET = "entityRecordAllLocalesProxying";
	private static final String NAME = "name";
	private static final String BRAND = "brand";
	private static final String LABEL = "label";

	/**
	 * Stores two translations of the entity attribute `name` and of the reference attribute `label`.
	 */
	@DataSet(DATA_SET)
	@Nonnull
	DataCarrier setUp(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH, Locale.GERMAN)
				.withAttribute(NAME, String.class, AttributeSchemaEditor::localized)
				.withReferenceTo(
					BRAND, BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs.withAttribute(LABEL, String.class, AttributeSchemaEditor::localized)
				)
				.updateVia(session);
			session.createNewEntity(Entities.PRODUCT, 1)
				.setAttribute(NAME, Locale.ENGLISH, "English name")
				.setAttribute(NAME, Locale.GERMAN, "Deutscher Name")
				.setReference(
					BRAND, 1,
					thatIs -> thatIs
						.setAttribute(LABEL, Locale.ENGLISH, "English label")
						.setAttribute(LABEL, Locale.GERMAN, "Deutsches Label")
				)
				.upsertVia(session);
		});
		return new DataCarrier();
	}

	/**
	 * Fetches the product in all locales as an instance of the given record.
	 */
	@Nonnull
	private static <T extends Serializable> T fetchInAllLocales(@Nonnull Evita evita, @Nonnull Class<T> recordType) {
		try (final EvitaSessionContract session = evita.createReadOnlySession(TEST_CATALOG)) {
			return session.queryOne(
				query(
					collection(Entities.PRODUCT),
					filterBy(entityPrimaryKeyInSet(1)),
					require(
						entityFetch(attributeContentAll(), referenceContentWithAttributes(BRAND), dataInLocalesAll())
					)
				),
				recordType
			).orElseThrow();
		}
	}

	/**
	 * Covers array components mapped to a localized entity attribute and to a localized reference attribute.
	 */
	@Test
	@DisplayName("Collects one entity and one reference attribute value per locale into arrays")
	@UseDataSet(DATA_SET)
	void shouldCollectOneAttributeValuePerLocaleIntoArrays(@Nonnull Evita evita) {
		final LocalizedProduct product = fetchInAllLocales(evita, LocalizedProduct.class);

		assertEquals(2, product.names().length);
		assertEquals(Set.of("English name", "Deutscher Name"), new HashSet<>(Arrays.asList(product.names())));
		assertEquals(2, product.brand().labels().length);
		assertEquals(
			Set.of("English label", "Deutsches Label"), new HashSet<>(Arrays.asList(product.brand().labels()))
		);
	}

	/**
	 * Covers a list component mapped to a localized reference attribute.
	 */
	@Test
	@DisplayName("Collects one reference attribute value per locale into a list")
	@UseDataSet(DATA_SET)
	void shouldCollectOneReferenceAttributeValuePerLocaleIntoList(@Nonnull Evita evita) {
		final LocalizedLabelListProduct product = fetchInAllLocales(evita, LocalizedLabelListProduct.class);

		assertEquals(2, product.brand().labels().size());
		assertEquals(Set.of("English label", "Deutsches Label"), new HashSet<>(product.brand().labels()));
	}

	/**
	 * Product projection with array components over the localized entity and reference attributes.
	 *
	 * @param id    the primary key of the product
	 * @param names one `name` value per fetched locale
	 * @param brand the projection of the `brand` reference
	 */
	@EntityRef(Entities.PRODUCT)
	public record LocalizedProduct(
		@PrimaryKeyRef int id,
		@AttributeRef(NAME) String[] names,
		@ReferenceRef(BRAND) LocalizedBrand brand
	) implements Serializable {
	}

	/**
	 * Reference projection with an array component over the localized reference attribute.
	 *
	 * @param primaryKey the primary key of the referenced brand
	 * @param labels     one `label` value per fetched locale
	 */
	public record LocalizedBrand(
		@ReferencedEntity int primaryKey,
		@AttributeRef(LABEL) String[] labels
	) implements Serializable {
	}

	/**
	 * Product projection whose reference collects the localized reference attribute into a list.
	 *
	 * @param id    the primary key of the product
	 * @param brand the projection of the `brand` reference
	 */
	@EntityRef(Entities.PRODUCT)
	public record LocalizedLabelListProduct(
		@PrimaryKeyRef int id,
		@ReferenceRef(BRAND) LocalizedLabelListBrand brand
	) implements Serializable {
	}

	/**
	 * Reference projection with a list component over the localized reference attribute.
	 *
	 * @param primaryKey the primary key of the referenced brand
	 * @param labels     one `label` value per fetched locale
	 */
	public record LocalizedLabelListBrand(
		@ReferencedEntity int primaryKey,
		@AttributeRef(LABEL) List<String> labels
	) implements Serializable {
	}
}
