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

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.SealedInstance;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.CreateWhenMissing;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.ReferenceRef;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.PROXY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies localized null setters of entity and reference attributes through real editor proxies.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Localized null attribute setters")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(PROXY)
class LocalizedNullSetterProxyingFunctionalTest {

	/**
	 * Creates the schema shared by the entity and reference attribute scenarios.
	 */
	private static void defineSchema(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("product")
				.withLocale(Locale.ENGLISH)
				.withLocale(Locale.GERMAN)
				.withAttribute("title", String.class, whichIs -> whichIs.localized().nullable())
				.withAttribute("tags", String[].class, whichIs -> whichIs.localized().nullable())
				.withReferenceTo("brand", "brand", Cardinality.ZERO_OR_ONE, whichIs -> whichIs
					.withAttribute("title", String.class, attribute -> attribute.localized().nullable())
					.withAttribute("tags", String[].class, attribute -> attribute.localized().nullable()))
				.updateVia(session);
		});
	}

	@Test
	@DisplayName("should remove only the requested locale for null entity attribute setters")
	void shouldRemoveOnlyRequestedLocaleForNullEntityAttributeSetters(Evita evita) {
		defineSchema(evita);
		evita.updateCatalog(TEST_CATALOG, session -> {
			final ProductEditor editor = session.createNewEntity(ProductEditor.class, 1);
			editor.setTitle("English", Locale.ENGLISH);
			editor.setTitle("German", Locale.GERMAN);
			editor.setTags(new String[]{"English"}, Locale.ENGLISH);
			editor.setTags(new String[]{"German"}, Locale.GERMAN);
			editor.setTitle(null, Locale.ENGLISH);
			editor.setTags(null, Locale.ENGLISH);
			editor.upsertVia(session);
			final SealedEntity stored = session.getEntity("product", 1, entityFetchAllContent()).orElseThrow();
			assertAll(
				() -> assertNull(stored.getAttribute("title", Locale.ENGLISH)),
				() -> assertNull(stored.getAttributeArray("tags", Locale.ENGLISH)),
				() -> assertEquals("German", stored.getAttribute("title", Locale.GERMAN)),
				() -> assertArrayEquals(new String[]{"German"}, stored.getAttributeArray("tags", Locale.GERMAN))
			);
		});
	}

	@Test
	@DisplayName("should remove only the requested locale for null reference attribute setters")
	void shouldRemoveOnlyRequestedLocaleForNullReferenceAttributeSetters(Evita evita) {
		defineSchema(evita);
		evita.updateCatalog(TEST_CATALOG, session -> {
			final ProductEditor editor = session.createNewEntity(ProductEditor.class, 1);
			editor.setBrand(1, brand -> {
				brand.setTitle("English", Locale.ENGLISH);
				brand.setTitle("German", Locale.GERMAN);
				brand.setTags(new String[]{"English"}, Locale.ENGLISH);
				brand.setTags(new String[]{"German"}, Locale.GERMAN);
				brand.setTitle(null, Locale.ENGLISH);
				brand.setTags(null, Locale.ENGLISH);
			});
			editor.upsertVia(session);
			final SealedEntity stored = session.getEntity("product", 1, entityFetchAllContent()).orElseThrow();
			assertAll(
				() -> assertNull(stored.getReference("brand", 1).orElseThrow().getAttribute("title", Locale.ENGLISH)),
				() -> assertNull(stored.getReference("brand", 1).orElseThrow().getAttributeArray("tags", Locale.ENGLISH)),
				() -> assertEquals("German",
					stored.getReference("brand", 1).orElseThrow().getAttribute("title", Locale.GERMAN)),
				() -> assertArrayEquals(new String[]{"German"},
					stored.getReference("brand", 1).orElseThrow().getAttributeArray("tags", Locale.GERMAN))
			);
		});
	}

	/**
	 * Product identity used by the editor.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@EntityRef("product")
	public interface Product extends EntityClassifier {
	}

	/**
	 * Product editor exposing localized scalar and array attributes and a reference editor.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public interface ProductEditor extends Product, InstanceEditor<Product> {
		/**
		 * Sets or removes the title in the requested locale.
		 */
		@AttributeRef("title")
		void setTitle(@Nullable String title, @Nonnull Locale locale);

		/**
		 * Sets or removes the tags in the requested locale.
		 */
		@AttributeRef("tags")
		void setTags(@Nullable String[] tags, @Nonnull Locale locale);

		/**
		 * Creates or edits the brand reference.
		 */
		@ReferenceRef("brand")
		void setBrand(int primaryKey, @CreateWhenMissing @Nonnull Consumer<BrandEditor> editor);
	}

	/**
	 * Brand reference identity.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public interface Brand extends Serializable, SealedInstance<Brand, BrandEditor> {
		/**
		 * Returns the referenced brand primary key.
		 */
		@ReferencedEntity
		int getPrimaryKey();
	}

	/**
	 * Reference editor exposing localized scalar and array attributes.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public interface BrandEditor extends Brand, InstanceEditor<Brand> {
		/**
		 * Sets or removes the title in the requested locale.
		 */
		@AttributeRef("title")
		void setTitle(@Nullable String title, @Nonnull Locale locale);

		/**
		 * Sets or removes the tags in the requested locale.
		 */
		@AttributeRef("tags")
		void setTags(@Nullable String[] tags, @Nonnull Locale locale);
	}
}
