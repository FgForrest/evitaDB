/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.store.wal;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.NamingConvention;
import lombok.Data;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test verifies behavior of {@link WalKryoConfigurer} and subsequent serialization.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class WalSerializationServiceTest {

	@Nonnull
	@SuppressWarnings("Convert2MethodRef")
	private static EntitySchemaBuilder constructSomeSchema(@Nonnull InternalEntitySchemaBuilder schemaBuilder) {
		return schemaBuilder
			/* all is strictly verified but associated data and facets can be added on the fly */
			.verifySchemaButAllow(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_REFERENCES)
			/* product are not organized in the tree */
			.withHierarchy()
			/* prices are referencing another entity stored in Evita */
			.withPrice()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute("code", String.class, whichIs -> whichIs.unique())
			.withAttribute("url", String.class, whichIs -> whichIs.unique().localized())
			.withAttribute("oldEntityUrls", String[].class, whichIs -> whichIs.filterable().localized())
			.withAttribute("name", String.class, whichIs -> whichIs.filterable().sortable())
			.withAttribute("ean", String.class, whichIs -> whichIs.filterable())
			.withAttribute("priority", Long.class, whichIs -> whichIs.sortable())
			.withAttribute("validity", DateTimeRange.class, whichIs -> whichIs.filterable())
			.withAttribute("quantity", BigDecimal.class, whichIs -> whichIs.filterable().indexDecimalPlaces(2))
			.withAttribute("alias", Boolean.class, whichIs -> whichIs.filterable())
			/* here we define set of associated data, that can be stored along with entity */
			.withAssociatedData("referencedFiles", ReferencedFileSet.class)
			.withAssociatedData("labels", Labels.class, whichIs -> whichIs.localized())
			/* here we define facets that relate to another entities stored in Evita */
			.withReferenceToEntity(
				Entities.CATEGORY,
				Entities.CATEGORY,
				Cardinality.ZERO_OR_MORE,
				whichIs ->
					/* we can specify special attributes on relation */
					whichIs.indexedForFilteringAndPartitioning().withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
			)
			/* for indexed facets we can compute "counts" */
			.withReferenceToEntity(
				Entities.BRAND,
				Entities.BRAND,
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.faceted()
			)
			/* facets may be also represented be entities unknown to Evita */
			.withReferenceTo(
				"stock",
				"stock",
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.faceted()
			)
			/* we can create reflected references to other entities */
			.withReflectedReferenceToEntity(
				"referencedInCategories",
				Entities.CATEGORY,
				"productsInCategory",
				whichIs -> {
					whichIs.withAttributesInheritedExcept("categoryPriority");
				}
			);
	}

	@Test
	void shouldSerializeAndDeserializeSchemaMutations() {
		final EntitySchema productSchema = EntitySchema._internalBuild(Entities.PRODUCT);
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
		final Kryo walKryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		final ModifyEntitySchemaMutation mutation = constructSomeSchema(
			new InternalEntitySchemaBuilder(
				CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, NamingConvention.generate(TestConstants.TEST_CATALOG), EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE),
				productSchema
			)
		)
			.toMutation()
			.orElseThrow();

		try (final Output output = new Output(baos)) {
			walKryo.writeClassAndObject(output, mutation);
		}

		final byte[] serializedWal = baos.toByteArray();
		assertNotNull(serializedWal);
		assertTrue(serializedWal.length > 0);

		final ModifyEntitySchemaMutation deserializedMutation;
		try (final Input input = new Input(new ByteArrayInputStream(serializedWal))) {
			deserializedMutation = (ModifyEntitySchemaMutation) walKryo.readClassAndObject(input);
		}
		assertEquals(mutation, deserializedMutation);
	}

	@Test
	void shouldSerializeAndDeserializeDataMutations() {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(4084);
		final Kryo walKryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);

		final EntitySchemaContract productSchema = constructSomeSchema(
			new InternalEntitySchemaBuilder(
				CatalogSchema._internalBuild(TestConstants.TEST_CATALOG, NamingConvention.generate(TestConstants.TEST_CATALOG), EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE),
				EntitySchema._internalBuild(Entities.PRODUCT)
			)
		)
			.toInstance();

		final DataGenerator dataGenerator = new DataGenerator();
		final List<EntityMutation> mutations = dataGenerator.generateEntities(
				productSchema,
				(s, faker) -> null,
				42
			)
			.limit(5)
			.map(it -> it.toMutation().orElseThrow())
			.toList();

		try (final Output output = new Output(baos)) {
			for (final EntityMutation mutation : mutations) {
				walKryo.writeClassAndObject(output, mutation);
			}
		}

		final byte[] serializedWal = baos.toByteArray();
		assertNotNull(serializedWal);
		assertTrue(serializedWal.length > 0);

		final List<EntityMutation> deserializedMutations = new ArrayList<>(5);
		try (final Input input = new Input(new ByteArrayInputStream(serializedWal))) {
			for (int i = 0; i < 5; i++) {
				try {
					deserializedMutations.add(
						(EntityMutation) walKryo.readClassAndObject(input)
					);
				} catch (Exception ex) {
					fail("Failed to deserialized mutation no #" + i, ex);
				}
			}
		}

		for (int i = 0; i < mutations.size(); i++) {
			final EntityMutation mutation = mutations.get(i);
			final EntityMutation deserializedMutation = deserializedMutations.get(i);
			assertEquals(mutation, deserializedMutation, "Mutation at position " + i + " differs");
		}
	}

	@Data
	public static class ReferencedFileSet implements Serializable {
		@Serial private static final long serialVersionUID = -1355676966187183143L;
		private String someField = "someValue";

	}

	@Data
	public static class Labels implements Serializable {
		@Serial private static final long serialVersionUID = 1121150156843379388L;
		private String someField = "someValue";

	}

}
