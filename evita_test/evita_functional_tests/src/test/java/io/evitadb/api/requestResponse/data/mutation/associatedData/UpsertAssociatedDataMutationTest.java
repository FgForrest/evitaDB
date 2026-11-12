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

package io.evitadb.api.requestResponse.data.mutation.associatedData;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.mutation.AbstractMutationTest;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;

/**
 * This test verifies contract of {@link UpsertAssociatedDataMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(CONTRACT)
@Tag(QUERY)
class UpsertAssociatedDataMutationTest extends AbstractMutationTest {

	@Test
	void shouldCreateNewAssociatedData() {
		final UpsertAssociatedDataMutation mutation = new UpsertAssociatedDataMutation(new AssociatedDataKey("a"), (byte) 5);
		final AssociatedDataValue newValue = mutation.mutateLocal(this.productSchema, null);
		assertEquals((byte) 5, newValue.value());
		assertEquals(1L, newValue.version());
	}

	@Test
	void shouldIncrementVersionByUpdatingAssociatedData() {
		final UpsertAssociatedDataMutation mutation = new UpsertAssociatedDataMutation(new AssociatedDataKey("a"), (byte) 5);
		final AssociatedDataValue newValue = mutation.mutateLocal(this.productSchema, new AssociatedDataValue(new AssociatedDataKey("a"), (byte) 3));
		assertEquals((byte) 5, newValue.value());
		assertEquals(2L, newValue.version());
	}

	@Test
	void shouldReturnSameSkipToken() {
		assertEquals(
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc"), "B").getSkipToken(this.catalogSchema, this.productSchema),
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc"), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
		assertEquals(
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc", Locale.ENGLISH), "B").getSkipToken(this.catalogSchema, this.productSchema),
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc", Locale.ENGLISH), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

	@Test
	void shouldReturnDifferentSkipToken() {
		assertNotEquals(
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc"), "B").getSkipToken(this.catalogSchema, this.productSchema),
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abe"), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
		assertNotEquals(
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc", Locale.ENGLISH), "B").getSkipToken(this.catalogSchema, this.productSchema),
			new UpsertAssociatedDataMutation(new AssociatedDataKey("abc", Locale.GERMAN), "C").getSkipToken(this.catalogSchema, this.productSchema)
		);
	}

	@Nested
	@DisplayName("Schema verification of the upserted value")
	class SchemaVerificationTest {
		private static final String OBJECTS = "objects";

		@Test
		@DisplayName("should refuse an array of complex data objects when the schema does not declare it")
		void shouldRefuseArrayOfComplexDataObjectsWhenSchemaIsMissing() {
			final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
				UpsertAssociatedDataMutationTest.this.catalogSchema, UpsertAssociatedDataMutationTest.this.productSchema
			);
			final UpsertAssociatedDataMutation mutation = new UpsertAssociatedDataMutation(
				OBJECTS, new ComplexDataObject[]{createComplexDataObject("first")}
			);

			final InvalidMutationException exception = assertThrows(
				InvalidMutationException.class,
				() -> mutation.verifyOrEvolveSchema(UpsertAssociatedDataMutationTest.this.catalogSchema, schemaBuilder)
			);

			assertArrayOfComplexDataObjectsRefused(exception);
			assertTrue(schemaBuilder.getAssociatedData(OBJECTS).isEmpty());
		}

		@Test
		@DisplayName("should refuse an array of complex data objects when the schema declares a complex data object")
		void shouldRefuseArrayOfComplexDataObjectsWhenSchemaDeclaresComplexDataObject() {
			final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
				UpsertAssociatedDataMutationTest.this.catalogSchema, UpsertAssociatedDataMutationTest.this.productSchema
			).withAssociatedData(OBJECTS, ComplexDataObject.class);
			final UpsertAssociatedDataMutation mutation = new UpsertAssociatedDataMutation(
				OBJECTS, new ComplexDataObject[]{createComplexDataObject("first")}
			);

			final InvalidMutationException exception = assertThrows(
				InvalidMutationException.class,
				() -> mutation.verifyOrEvolveSchema(UpsertAssociatedDataMutationTest.this.catalogSchema, schemaBuilder)
			);

			assertArrayOfComplexDataObjectsRefused(exception);
		}

		@Test
		@DisplayName("should refuse a value that is not an instance of the type the schema evolves to")
		void shouldRefuseValueThatIsNotInstanceOfEvolvedType() {
			final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
				UpsertAssociatedDataMutationTest.this.catalogSchema, UpsertAssociatedDataMutationTest.this.productSchema
			);
			// the schema declares the wrapped form `Integer[]` for a primitive array
			final UpsertAssociatedDataMutation mutation = new UpsertAssociatedDataMutation("numbers", new int[]{1, 2});

			final InvalidMutationException exception = assertThrows(
				InvalidMutationException.class,
				() -> mutation.verifyOrEvolveSchema(UpsertAssociatedDataMutationTest.this.catalogSchema, schemaBuilder)
			);

			assertTrue(exception.getMessage().contains("`numbers`"), exception.getMessage());
		}

		@Test
		@DisplayName("should evolve the schema for one complex data object holding an array")
		void shouldEvolveSchemaForOneComplexDataObjectHoldingArray() {
			final EntitySchemaBuilder schemaBuilder = new InternalEntitySchemaBuilder(
				UpsertAssociatedDataMutationTest.this.catalogSchema, UpsertAssociatedDataMutationTest.this.productSchema
			);
			final UpsertAssociatedDataMutation mutation = new UpsertAssociatedDataMutation(
				OBJECTS,
				new ComplexDataObject(
					new DataItemArray(
						new DataItem[]{
							createComplexDataObject("first").root(),
							createComplexDataObject("second").root()
						}
					)
				)
			);

			mutation.verifyOrEvolveSchema(UpsertAssociatedDataMutationTest.this.catalogSchema, schemaBuilder);

			assertEquals(ComplexDataObject.class, schemaBuilder.getAssociatedData(OBJECTS).orElseThrow().getType());
		}

		/**
		 * Asserts the refusal names the associated data and tells how an array of complex objects must be passed.
		 *
		 * @param exception the exception the verification threw
		 */
		private static void assertArrayOfComplexDataObjectsRefused(@Nonnull InvalidMutationException exception) {
			final String message = exception.getMessage();
			assertTrue(message.contains("`" + OBJECTS + "`"), message);
			assertTrue(message.contains("one `ComplexDataObject`"), message);
		}

		/**
		 * Creates a complex data object with a single `name` property.
		 *
		 * @param name the value of the property
		 * @return the complex data object
		 */
		@Nonnull
		private static ComplexDataObject createComplexDataObject(@Nonnull String name) {
			return new ComplexDataObject(new DataItemMap(Map.of("name", new DataItemValue(name))));
		}
	}

}
