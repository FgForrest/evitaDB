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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.EntitySchemaContext;
import io.evitadb.spi.store.catalog.persistence.ReferenceNameFilterContext;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ReferencesStoragePartSerializer} can decode a subset of an entity's references - the narrowing
 * that lets a projection over an entity carrying tens of thousands of back-references cost only the references it
 * actually asked for.
 *
 * The delicate part is that the Kryo stream is not self-delimiting: a reference that is skipped must still be walked
 * over field for field, otherwise every byte after it is misread. That is why the tests below always write **two**
 * storage parts back to back and assert the second one as well - the second part is what proves the skip left the
 * stream exactly where a full decode would have.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(REFERENCE)
@DisplayName("References storage part reference-name narrowing")
class ReferencesStoragePartSerializerTest {
	private static final String BRAND = "brand";
	private static final String CATEGORY = "category";
	private static final String PARAMETER = "parameter";
	private static final String ATTRIBUTE_CODE = "code";

	private EntitySchema productSchema;
	private Kryo kryo;

	/**
	 * Builds a reference of the passed name with an optional group and an optional single attribute - the attribute
	 * matters because a skipped reference still has to decode its attribute values to get past them.
	 */
	@Nonnull
	private static Reference reference(
		@Nonnull EntitySchema schema,
		@Nonnull String referenceName,
		int referencedPrimaryKey,
		int internalPrimaryKey,
		@Nullable Integer groupId,
		@Nullable String attributeValue
	) {
		final Map<AttributeKey, AttributeValue> attributes = new LinkedHashMap<>(2);
		if (attributeValue != null) {
			final AttributeKey attributeKey = new AttributeKey(ATTRIBUTE_CODE);
			attributes.put(attributeKey, new AttributeValue(attributeKey, attributeValue));
		}
		final ReferenceSchema referenceSchema = schema.getReferenceOrThrowException(referenceName);
		return new Reference(
			schema, referenceSchema, 1,
			new ReferenceKey(referenceName, referencedPrimaryKey, internalPrimaryKey),
			groupId == null ?
				null :
				new GroupEntityReference(referenceSchema.getReferencedGroupType(), groupId, 1, false),
			attributes, false
		);
	}

	@Nonnull
	private static ReferenceSchema referenceSchema(@Nonnull String name, @Nullable String groupType) {
		return ReferenceSchema._internalBuild(
			name, NamingConvention.generate(name),
			null, null,
			Cardinality.ZERO_OR_MORE,
			name, NamingConvention.generate(name), false,
			groupType, groupType == null ? Collections.emptyMap() : NamingConvention.generate(groupType), false,
			Collections.emptyMap(),
			Collections.emptyMap(),
			Collections.emptySet(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Map.of(
				ATTRIBUTE_CODE,
				AttributeSchema._internalBuild(
					ATTRIBUTE_CODE, String.class, false, ConflictResolutionOverride.INHERITED
				)
			),
			Collections.emptyMap(),
			ConflictResolutionOverride.INHERITED
		);
	}

	@BeforeEach
	void setUp() {
		this.productSchema = EntitySchema._internalBuild(
			1, "PRODUCT",
			null, null, null,
			true,
			false, Scope.NO_SCOPE,
			true, new Scope[]{Scope.LIVE}, 0,
			Collections.emptySet(),
			Collections.emptySet(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Map.of(
				BRAND, referenceSchema(BRAND, null),
				CATEGORY, referenceSchema(CATEGORY, "categoryGroup"),
				PARAMETER, referenceSchema(PARAMETER, null)
			),
			Collections.emptySet(),
			Collections.emptyMap()
		);
		this.kryo = KryoFactory.createKryo(
			SharedClassesConfigurer.INSTANCE
				.andThen(new EntityStoragePartConfigurer(new ReadWriteKeyCompressor(new ConcurrentHashMap<>())))
		);
	}

	/**
	 * Writes the two storage parts of {@link #firstPart()} and {@link #secondPart()} into a single stream, exactly the
	 * way two records of an OffsetIndex follow one another.
	 */
	@Nonnull
	private byte[] writeTwoParts() {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(4_096);
		try (final ByteBufferOutput output = new ByteBufferOutput(baos)) {
			EntitySchemaContext.executeWithSchemaContext(
				this.productSchema,
				() -> {
					this.kryo.writeObject(output, firstPart());
					this.kryo.writeObject(output, secondPart());
					return null;
				}
			);
		}
		return baos.toByteArray();
	}

	@Nonnull
	private ReferencesStoragePart firstPart() {
		// sorted by ReferenceContract#FULL_COMPARATOR, i.e. by name and then by referenced primary key
		return new ReferencesStoragePart(
			10, 5,
			new Reference[]{
				reference(this.productSchema, BRAND, 100, 1, null, "brand-code"),
				reference(this.productSchema, CATEGORY, 200, 2, 900, "category-code"),
				reference(this.productSchema, CATEGORY, 201, 3, 901, null),
				reference(this.productSchema, PARAMETER, 300, 4, null, null),
				reference(this.productSchema, PARAMETER, 301, 5, null, "parameter-code")
			},
			-1
		);
	}

	@Nonnull
	private ReferencesStoragePart secondPart() {
		return new ReferencesStoragePart(
			11, 2,
			new Reference[]{
				reference(this.productSchema, BRAND, 111, 1, null, null),
				reference(this.productSchema, PARAMETER, 333, 2, null, "second-parameter-code")
			},
			-1
		);
	}

	/**
	 * Reads both parts from the stream under the passed filter and hands them back in order.
	 */
	@Nonnull
	private ReferencesStoragePart[] readTwoParts(@Nonnull byte[] serialized, @Nullable Set<String> filter) {
		try (final ByteBufferInput input = new ByteBufferInput(new ByteArrayInputStream(serialized))) {
			return EntitySchemaContext.executeWithSchemaContext(
				this.productSchema,
				() -> ReferenceNameFilterContext.executeWithReferenceNameFilter(
					filter,
					() -> new ReferencesStoragePart[]{
						this.kryo.readObject(input, ReferencesStoragePart.class),
						this.kryo.readObject(input, ReferencesStoragePart.class)
					}
				)
			);
		}
	}

	@Nonnull
	private static String[] referenceNamesOf(@Nonnull ReferencesStoragePart part) {
		return Arrays.stream(part.getReferences())
			.map(ReferenceContract::getReferenceName)
			.toArray(String[]::new);
	}

	@Test
	@DisplayName("unfiltered read decodes every reference and yields a complete part")
	void shouldDecodeAllReferencesWhenNoFilterIsBound() {
		final ReferencesStoragePart[] parts = readTwoParts(writeTwoParts(), null);

		assertTrue(parts[0].isComplete());
		assertNull(parts[0].getDecodedReferenceNames());
		assertArrayEquals(
			new String[]{BRAND, CATEGORY, CATEGORY, PARAMETER, PARAMETER},
			referenceNamesOf(parts[0])
		);
		assertEquals(10, parts[0].getEntityPrimaryKey());
		assertEquals(5, parts[0].getLastUsedPrimaryKey());

		assertTrue(parts[1].isComplete());
		assertArrayEquals(new String[]{BRAND, PARAMETER}, referenceNamesOf(parts[1]));
		assertEquals(11, parts[1].getEntityPrimaryKey());
	}

	@Test
	@DisplayName("filtered read keeps only the requested names and leaves the stream aligned")
	void shouldDecodeOnlyRequestedReferences() {
		final ReferencesStoragePart[] parts = readTwoParts(writeTwoParts(), Set.of(PARAMETER));

		assertFalse(parts[0].isComplete());
		assertEquals(Set.of(PARAMETER), parts[0].getDecodedReferenceNames());
		assertArrayEquals(new String[]{PARAMETER, PARAMETER}, referenceNamesOf(parts[0]));
		assertArrayEquals(new int[]{300, 301}, parts[0].getReferencedIds(PARAMETER));
		// the header is read before any reference and therefore survives the narrowing untouched
		assertEquals(10, parts[0].getEntityPrimaryKey());
		assertEquals(5, parts[0].getLastUsedPrimaryKey());

		// the second part is the proof that skipping the first part's brand and category references - one of which
		// carries an attribute and a group - consumed exactly as many bytes as decoding them would have
		assertFalse(parts[1].isComplete());
		assertArrayEquals(new String[]{PARAMETER}, referenceNamesOf(parts[1]));
		assertEquals(11, parts[1].getEntityPrimaryKey());
		assertArrayEquals(new int[]{333}, parts[1].getReferencedIds(PARAMETER));
		assertEquals(
			"second-parameter-code",
			parts[1].getReferences()[0].getAttribute(ATTRIBUTE_CODE)
		);
	}

	@Test
	@DisplayName("filtered read keeps the groups and attributes of the references it does decode")
	void shouldKeepGroupsAndAttributesOfDecodedReferences() {
		final ReferencesStoragePart[] parts = readTwoParts(writeTwoParts(), Set.of(CATEGORY));

		assertArrayEquals(new String[]{CATEGORY, CATEGORY}, referenceNamesOf(parts[0]));
		assertArrayEquals(new int[]{900, 901}, parts[0].getDistinctReferencedGroupIds(CATEGORY));
		assertEquals("category-code", parts[0].getReferences()[0].getAttribute(ATTRIBUTE_CODE));

		// the second part has no category reference at all - an empty result, not a desynchronized stream
		assertEquals(0, parts[1].getReferences().length);
		assertEquals(11, parts[1].getEntityPrimaryKey());
	}

	@Test
	@DisplayName("a narrowed part refuses to answer about a reference name it did not decode")
	void shouldRefuseToAnswerAboutUndecodedReferenceName() {
		final ReferencesStoragePart narrowed = readTwoParts(writeTwoParts(), Set.of(PARAMETER))[0];

		assertThrows(GenericEvitaInternalError.class, () -> narrowed.getReferencedIds(BRAND));
		assertThrows(GenericEvitaInternalError.class, () -> narrowed.getDistinctReferencedIds(CATEGORY));
		assertThrows(GenericEvitaInternalError.class, () -> narrowed.contains(new ReferenceKey(BRAND, 100)));
		// whole-part questions cannot be answered from a subset either
		assertThrows(GenericEvitaInternalError.class, narrowed::isEmpty);
	}

	@Test
	@DisplayName("a narrowed part refuses to be written back to the storage")
	void shouldRefuseToSerializeNarrowedPart() {
		final ReferencesStoragePart narrowed = readTwoParts(writeTwoParts(), Set.of(PARAMETER))[0];

		try (final ByteBufferOutput output = new ByteBufferOutput(new ByteArrayOutputStream(1_024))) {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> EntitySchemaContext.executeWithSchemaContext(
					this.productSchema,
					() -> {
						this.kryo.writeObject(output, narrowed);
						return null;
					}
				)
			);
		}
	}

	@Test
	@DisplayName("a filter matching nothing yields an empty part rather than a broken stream")
	void shouldDecodeNothingWhenFilterMatchesNoReference() {
		final ReferencesStoragePart[] parts = readTwoParts(writeTwoParts(), Set.of("nonExistingReference"));

		assertEquals(0, parts[0].getReferences().length);
		assertEquals(10, parts[0].getEntityPrimaryKey());
		assertEquals(0, parts[1].getReferences().length);
		assertEquals(11, parts[1].getEntityPrimaryKey());
	}

}
