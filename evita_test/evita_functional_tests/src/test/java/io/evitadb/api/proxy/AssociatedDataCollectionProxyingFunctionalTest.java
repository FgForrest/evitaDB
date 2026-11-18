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
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.SealedInstance;
import io.evitadb.api.requestResponse.data.annotation.AssociatedDataRef;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.RemoveWhenExists;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.ReflectionLookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.associatedDataContentAll;
import static io.evitadb.api.query.QueryConstraints.dataInLocalesAll;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.PROXY;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies how the setters of an entity editor proxy hand an array or a collection over to associated data: a
 * collection of simple values becomes the typed array the schema declares, and an array or a collection of complex
 * objects becomes one {@link ComplexDataObject} whose root holds the whole array - never an array of complex data
 * objects. The removal methods of the proxy return the removed value in the type they declare, or null when nothing
 * is stored.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Entity editor proxy writing and removing array associated data")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(PROXY)
class AssociatedDataCollectionProxyingFunctionalTest {
	private static final String PRODUCT = "product";
	private static final String TITLE = "title";
	private static final String TAGS = "tags";
	private static final String LOCALIZED_TAGS = "localizedTags";
	private static final String DIMENSIONS = "dimensions";
	private static final String DIMENSION_ARRAY = "dimensionArray";
	private static final String LOCALIZED_DIMENSIONS = "localizedDimensions";
	private static final String LOCALIZED_DIMENSION_ARRAY = "localizedDimensionArray";
	private static final int PK = 1;

	@Nested
	@DisplayName("Setters")
	class SettersTest {

		@Test
		@DisplayName("should store a collection of simple values as the array the schema declares")
		void shouldStoreCollectionOfSimpleValuesAsDeclaredArray(Evita evita) {
			final SealedEntity product = storeThroughProxy(evita, editor -> editor.setTags(List.of("red", "blue")));

			final Serializable[] tags = product.getAssociatedDataArray(TAGS);
			assertAll(
				() -> assertArrayEquals(new String[]{"red", "blue"}, tags),
				() -> assertEquals(String[].class, tags.getClass())
			);
		}

		@Test
		@DisplayName("should store a localized collection of simple values as the array the schema declares")
		void shouldStoreLocalizedCollectionOfSimpleValuesAsDeclaredArray(Evita evita) {
			final SealedEntity product = storeThroughProxy(
				evita, editor -> editor.setLocalizedTags(List.of("red", "blue"), Locale.ENGLISH)
			);

			final Serializable[] tags = product.getAssociatedDataArray(LOCALIZED_TAGS, Locale.ENGLISH);
			assertAll(
				() -> assertArrayEquals(new String[]{"red", "blue"}, tags),
				() -> assertEquals(String[].class, tags.getClass())
			);
		}

		@Test
		@DisplayName("should store an array or a collection of complex objects as one complex data object")
		void shouldStoreArrayOrCollectionOfComplexObjectsAsOneComplexDataObject(Evita evita) {
			final Dimension[] dimensions = {new Dimension("width", 10), new Dimension("height", 20)};
			final SealedEntity product = storeThroughProxy(
				evita,
				editor -> editor
					.setDimensions(Arrays.asList(dimensions))
					.setDimensionArray(dimensions)
					.setLocalizedDimensions(Arrays.asList(dimensions), Locale.ENGLISH)
			);

			assertAll(
				() -> assertArrayEquals(dimensions, readDimensions(product, DIMENSIONS)),
				() -> assertArrayEquals(dimensions, readDimensions(product, DIMENSION_ARRAY)),
				() -> assertArrayEquals(dimensions, readDimensions(product, LOCALIZED_DIMENSIONS, Locale.ENGLISH)),
				() -> assertInstanceOf(
					DataItemArray.class, product.<ComplexDataObject>getAssociatedData(DIMENSIONS).root()
				),
				() -> assertInstanceOf(
					DataItemArray.class, product.<ComplexDataObject>getAssociatedData(DIMENSION_ARRAY).root()
				),
				() -> assertInstanceOf(
					DataItemArray.class,
					product.<ComplexDataObject>getAssociatedData(LOCALIZED_DIMENSIONS, Locale.ENGLISH).root()
				)
			);
		}

		@Test
		@DisplayName("should remove only the requested locale when associated data setters receive null")
		void shouldRemoveOnlyRequestedLocaleWhenSettersReceiveNull(Evita evita) {
			final Dimension[] dimensions = {new Dimension("width", 10)};
			final SealedEntity product = storeThroughProxy(evita, editor -> {
				editor.setTitle("English", Locale.ENGLISH);
				editor.setTitle("German", Locale.GERMAN);
				editor.setLocalizedDimensionArray(dimensions, Locale.ENGLISH);
				editor.setLocalizedDimensionArray(dimensions, Locale.GERMAN);
				editor.setLocalizedDimensions(Arrays.asList(dimensions), Locale.ENGLISH);
				editor.setLocalizedDimensions(Arrays.asList(dimensions), Locale.GERMAN);
				editor.setTitle(null, Locale.ENGLISH);
				editor.setLocalizedDimensionArray(null, Locale.ENGLISH);
				editor.setLocalizedDimensions(null, Locale.ENGLISH);
			});
			assertAll(
				() -> assertNull(product.getAssociatedData(TITLE, Locale.ENGLISH)),
				() -> assertNull(product.getAssociatedData(LOCALIZED_DIMENSION_ARRAY, Locale.ENGLISH)),
				() -> assertNull(product.getAssociatedData(LOCALIZED_DIMENSIONS, Locale.ENGLISH)),
				() -> assertEquals("German", product.getAssociatedData(TITLE, Locale.GERMAN)),
				() -> assertArrayEquals(
					dimensions, readDimensions(product, LOCALIZED_DIMENSION_ARRAY, Locale.GERMAN)
				),
				() -> assertArrayEquals(dimensions, readDimensions(product, LOCALIZED_DIMENSIONS, Locale.GERMAN))
			);
		}
	}

	@Nested
	@DisplayName("Removal methods")
	class RemovalMethodsTest {

		@Test
		@DisplayName("should return the collection of simple values on removal and null when absent")
		void shouldReturnCollectionOfSimpleValuesOnRemoval(Evita evita) {
			final SealedEntity product = storeThroughProxy(evita, editor -> {
				assertNull(editor.removeTags());
				editor.setTags(List.of("red", "blue"));
				assertEquals(List.of("red", "blue"), editor.removeTags());
			});
			assertNull(product.getAssociatedData(TAGS));
		}

		@Test
		@DisplayName("should return the localized collection of simple values on removal and null when absent")
		void shouldReturnLocalizedCollectionOfSimpleValuesOnRemoval(Evita evita) {
			final SealedEntity product = storeThroughProxy(evita, editor -> {
				assertNull(editor.removeLocalizedTags(Locale.ENGLISH));
				editor.setLocalizedTags(List.of("red", "blue"), Locale.ENGLISH);
				assertEquals(List.of("red", "blue"), editor.removeLocalizedTags(Locale.ENGLISH));
			});
			assertNull(product.getAssociatedData(LOCALIZED_TAGS, Locale.ENGLISH));
		}

		@Test
		@DisplayName("should return the array of complex objects on removal and null when absent")
		void shouldReturnArrayOfComplexObjectsOnRemoval(Evita evita) {
			final Dimension[] dimensions = {new Dimension("width", 10), new Dimension("height", 20)};
			final SealedEntity product = storeThroughProxy(evita, editor -> {
				assertNull(editor.removeDimensionArray());
				editor.setDimensionArray(dimensions);
				assertArrayEquals(dimensions, editor.removeDimensionArray());
			});
			assertNull(product.getAssociatedData(DIMENSION_ARRAY));
		}

		@Test
		@DisplayName("should return the localized array of complex objects on removal and null when absent")
		void shouldReturnLocalizedArrayOfComplexObjectsOnRemoval(Evita evita) {
			final Dimension[] dimensions = {new Dimension("width", 10), new Dimension("height", 20)};
			final SealedEntity product = storeThroughProxy(evita, editor -> {
				assertNull(editor.removeLocalizedDimensionArray(Locale.ENGLISH));
				editor.setLocalizedDimensionArray(dimensions, Locale.ENGLISH);
				assertArrayEquals(dimensions, editor.removeLocalizedDimensionArray(Locale.ENGLISH));
			});
			assertNull(product.getAssociatedData(LOCALIZED_DIMENSION_ARRAY, Locale.ENGLISH));
		}

		@Test
		@DisplayName("should return the complex collection on removal and null when absent")
		void shouldReturnComplexCollectionOnRemoval(Evita evita) {
			final List<Dimension> dimensions = List.of(new Dimension("width", 10), new Dimension("height", 20));
			final SealedEntity product = storeThroughProxy(evita, editor -> {
				assertNull(editor.removeDimensions());
				editor.setDimensions(dimensions);
				assertEquals(dimensions, editor.removeDimensions());
				assertNull(editor.removeDimensions());
			});
			assertNull(product.getAssociatedData(DIMENSIONS));
		}

		@Test
		@DisplayName("should return the localized complex collection on removal and null when absent")
		void shouldReturnLocalizedComplexCollectionOnRemoval(Evita evita) {
			final List<Dimension> dimensions = List.of(new Dimension("width", 10), new Dimension("height", 20));
			final SealedEntity product = storeThroughProxy(evita, editor -> {
				assertNull(editor.removeLocalizedDimensions(Locale.ENGLISH));
				editor.setLocalizedDimensions(dimensions, Locale.ENGLISH);
				editor.setLocalizedDimensions(dimensions, Locale.GERMAN);
				assertEquals(dimensions, editor.removeLocalizedDimensions(Locale.ENGLISH));
				assertNull(editor.removeLocalizedDimensions(Locale.ENGLISH));
			});
			assertNull(product.getAssociatedData(LOCALIZED_DIMENSIONS, Locale.ENGLISH));
			assertArrayEquals(
				dimensions.toArray(Dimension[]::new), readDimensions(product, LOCALIZED_DIMENSIONS, Locale.GERMAN)
			);
		}

		@Test
		@DisplayName("should return a stored complex collection on removal through the editor of an existing entity")
		void shouldRemoveStoredComplexCollectionThroughEditorOfExistingEntity(Evita evita) {
			final List<Dimension> dimensions = List.of(new Dimension("width", 10), new Dimension("height", 20));
			storeThroughProxy(evita, editor -> {
				editor.setDimensions(dimensions);
				editor.setLocalizedDimensions(dimensions, Locale.ENGLISH);
				editor.setLocalizedDimensions(dimensions, Locale.GERMAN);
			});

			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final ProductWithArraysEditor editor = session.getEntity(
						SealedProductWithArrays.class, PK, entityFetchAllContent()
					).orElseThrow().openForWrite();
					assertEquals(dimensions, editor.removeLocalizedDimensions(Locale.ENGLISH));
					assertEquals(dimensions, editor.removeDimensions());
					editor.upsertVia(session);
				}
			);

			final SealedEntity product = loadProduct(evita);
			assertAll(
				() -> assertNull(product.getAssociatedData(DIMENSIONS)),
				() -> assertNull(product.getAssociatedData(LOCALIZED_DIMENSIONS, Locale.ENGLISH)),
				() -> assertArrayEquals(
					dimensions.toArray(Dimension[]::new), readDimensions(product, LOCALIZED_DIMENSIONS, Locale.GERMAN)
				)
			);
		}
	}

	/**
	 * Defines the product schema, creates the product through the editor proxy and returns the stored product.
	 *
	 * @param evita  the evitaDB instance
	 * @param setter the logic setting associated data through the proxy
	 * @return the stored product with all associated data in all locales
	 */
	@Nonnull
	private static SealedEntity storeThroughProxy(
		@Nonnull Evita evita,
		@Nonnull Consumer<ProductWithArraysEditor> setter
	) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// every associated data is optional so that each test sets only those it verifies
				session.defineEntitySchema(PRODUCT)
					.withLocale(Locale.ENGLISH)
					.withLocale(Locale.GERMAN)
					.withAssociatedData(TITLE, String.class, whichIs -> whichIs.localized().nullable())
					.withAssociatedData(
						LOCALIZED_DIMENSION_ARRAY, Dimension[].class, whichIs -> whichIs.localized().nullable()
					)
					.withAssociatedData(TAGS, String[].class, AssociatedDataSchemaEditor::nullable)
					.withAssociatedData(LOCALIZED_TAGS, String[].class, whichIs -> whichIs.localized().nullable())
					.withAssociatedData(DIMENSIONS, Dimension[].class, AssociatedDataSchemaEditor::nullable)
					.withAssociatedData(DIMENSION_ARRAY, Dimension[].class, AssociatedDataSchemaEditor::nullable)
					.withAssociatedData(
						LOCALIZED_DIMENSIONS, Dimension[].class, whichIs -> whichIs.localized().nullable()
					)
					.updateVia(session);
				final ProductWithArraysEditor product = session.createNewEntity(ProductWithArraysEditor.class, PK);
				setter.accept(product);
				product.upsertVia(session);
			}
		);
		return loadProduct(evita);
	}

	/**
	 * Loads the stored product with all associated data in all locales.
	 *
	 * @param evita the evitaDB instance
	 * @return the stored product
	 */
	@Nonnull
	private static SealedEntity loadProduct(@Nonnull Evita evita) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(PRODUCT, PK, associatedDataContentAll(), dataInLocalesAll()).orElseThrow();
			}
		);
	}

	/**
	 * Reads the stored associated data back as the array of dimensions it was written from.
	 *
	 * @param product            the stored product
	 * @param associatedDataName the name of the associated data
	 * @return the dimensions, or null when the associated data is absent
	 */
	@Nullable
	private static Dimension[] readDimensions(@Nonnull SealedEntity product, @Nonnull String associatedDataName) {
		return product.getAssociatedData(associatedDataName, Dimension[].class, ReflectionLookup.NO_CACHE_INSTANCE);
	}

	/**
	 * Reads the stored localized associated data back as the array of dimensions it was written from.
	 *
	 * @param product            the stored product
	 * @param associatedDataName the name of the associated data
	 * @param locale             the locale of the associated data
	 * @return the dimensions, or null when the associated data is absent
	 */
	@Nullable
	private static Dimension[] readDimensions(
		@Nonnull SealedEntity product,
		@Nonnull String associatedDataName,
		@Nonnull Locale locale
	) {
		return product.getAssociatedData(
			associatedDataName, locale, Dimension[].class, ReflectionLookup.NO_CACHE_INSTANCE
		);
	}

	/**
	 * Product entity mapped by the editor proxy.
	 */
	@EntityRef(PRODUCT)
	public interface ProductWithArrays extends EntityClassifier {
	}

	/**
	 * Stored product that opens the editor proxy for write.
	 */
	public interface SealedProductWithArrays
		extends ProductWithArrays, SealedInstance<ProductWithArrays, ProductWithArraysEditor> {
	}

	/**
	 * Editor of the product setting array associated data from arrays and collections.
	 */
	public interface ProductWithArraysEditor extends ProductWithArrays, InstanceEditor<ProductWithArrays> {

		/**
		 * Sets or removes the title in the requested locale.
		 */
		@AssociatedDataRef(TITLE)
		void setTitle(@Nullable String title, @Nonnull Locale locale);

		/**
		 * Sets or removes the dimension array in the requested locale.
		 */
		@AssociatedDataRef(LOCALIZED_DIMENSION_ARRAY)
		void setLocalizedDimensionArray(@Nullable Dimension[] dimensions, @Nonnull Locale locale);

		/**
		 * Removes and returns the tags, or null when absent.
		 */
		@AssociatedDataRef(TAGS)
		@RemoveWhenExists
		@Nullable
		List<String> removeTags();

		/**
		 * Removes and returns the tags in the requested locale, or null when absent.
		 */
		@AssociatedDataRef(LOCALIZED_TAGS)
		@RemoveWhenExists
		@Nullable
		List<String> removeLocalizedTags(@Nonnull Locale locale);

		/**
		 * Removes and returns the dimension array, or null when absent.
		 */
		@AssociatedDataRef(DIMENSION_ARRAY)
		@RemoveWhenExists
		@Nullable
		Dimension[] removeDimensionArray();

		/**
		 * Removes and returns the dimension array in the requested locale, or null when absent.
		 */
		@AssociatedDataRef(LOCALIZED_DIMENSION_ARRAY)
		@RemoveWhenExists
		@Nullable
		Dimension[] removeLocalizedDimensionArray(@Nonnull Locale locale);

		/**
		 * Removes and returns the dimensions, or null when absent.
		 */
		@AssociatedDataRef(DIMENSIONS)
		@RemoveWhenExists
		@Nullable
		List<Dimension> removeDimensions();

		/**
		 * Removes and returns the dimensions in the requested locale, or null when absent.
		 */
		@AssociatedDataRef(LOCALIZED_DIMENSIONS)
		@RemoveWhenExists
		@Nullable
		List<Dimension> removeLocalizedDimensions(@Nonnull Locale locale);

		/**
		 * Sets the array of tags from a collection.
		 *
		 * @param tags the tags
		 * @return this editor
		 */
		@AssociatedDataRef(TAGS)
		@Nonnull
		ProductWithArraysEditor setTags(@Nonnull List<String> tags);

		/**
		 * Sets the localized array of tags from a collection.
		 *
		 * @param tags   the tags
		 * @param locale the locale of the tags
		 * @return this editor
		 */
		@AssociatedDataRef(LOCALIZED_TAGS)
		@Nonnull
		ProductWithArraysEditor setLocalizedTags(@Nonnull List<String> tags, @Nonnull Locale locale);

		/**
		 * Sets the dimensions from a collection.
		 *
		 * @param dimensions the dimensions
		 * @return this editor
		 */
		@AssociatedDataRef(DIMENSIONS)
		@Nonnull
		ProductWithArraysEditor setDimensions(@Nonnull List<Dimension> dimensions);

		/**
		 * Sets the dimensions from an array.
		 *
		 * @param dimensions the dimensions
		 * @return this editor
		 */
		@AssociatedDataRef(DIMENSION_ARRAY)
		@Nonnull
		ProductWithArraysEditor setDimensionArray(@Nonnull Dimension[] dimensions);

		/**
		 * Sets the localized dimensions from a collection.
		 *
		 * @param dimensions the dimensions
		 * @param locale     the locale of the dimensions
		 * @return this editor
		 */
		@AssociatedDataRef(LOCALIZED_DIMENSIONS)
		@Nonnull
		ProductWithArraysEditor setLocalizedDimensions(@Nullable List<Dimension> dimensions, @Nonnull Locale locale);
	}

	/**
	 * Complex object stored as an item of an array associated data.
	 *
	 * @param name  name of the dimension
	 * @param value size of the dimension
	 */
	public record Dimension(
		@Nonnull String name,
		@Nonnull Integer value
	) implements Serializable {
	}

}
