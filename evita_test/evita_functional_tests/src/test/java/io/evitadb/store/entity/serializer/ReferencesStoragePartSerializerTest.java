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
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceDecodeCoverage;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.EntitySchemaContext;
import io.evitadb.spi.store.catalog.persistence.ReferenceDecodeCoverageContext;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadOnlyKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_LABEL = "label";
	/**
	 * `serialVersionUID` a `ReferencesStoragePart` record carried before the internal primary keys were introduced,
	 * i.e. the discriminator {@link ReferencesStoragePartSerializer_2025_6} is registered under.
	 */
	private static final long LEGACY_REFERENCES_STORAGE_PART_UID = -4113353795728768940L;
	/**
	 * `serialVersionUID` a `Reference` record carried in the same layout, i.e. the discriminator
	 * {@link ReferenceSerializer_2025_6} is registered under.
	 */
	private static final long LEGACY_REFERENCE_UID = -2624502273901281240L;

	private EntitySchema productSchema;
	private Kryo kryo;
	/** Compressor shared by the production writer and the hand-built legacy attribute writer. */
	private ReadWriteKeyCompressor keyCompressor;

	@Nonnull
	private static ReferenceSchema referenceSchema(@Nonnull String name, @Nullable String groupType) {
		return ReferenceSchema._internalBuild(
			name, NamingConvention.generate(name),
			null, null,
			Cardinality.ZERO_OR_MORE,
			"item", NamingConvention.generate("item"), false,
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
					ATTRIBUTE_CODE, String.class, false, ConflictResolutionOverride.INHERITED,
					null
				),
				ATTRIBUTE_NAME,
				AttributeSchema._internalBuild(
					ATTRIBUTE_NAME, String.class, false, ConflictResolutionOverride.INHERITED,
					null
				),
				ATTRIBUTE_LABEL,
				AttributeSchema._internalBuild(
					ATTRIBUTE_LABEL, String.class, true, ConflictResolutionOverride.INHERITED,
					null
				)
			),
			Collections.emptyMap(),
			ConflictResolutionOverride.INHERITED,
			null, ReferenceSchema.DEFAULT_SEARCHABLE_THROUGH_WEIGHT
		);
	}

	@Nonnull
	private static String[] referenceNamesOf(@Nonnull ReferencesStoragePart part) {
		return Arrays.stream(part.getReferences())
			.map(ReferenceContract::getReferenceName)
			.toArray(String[]::new);
	}

	@Nonnull
	private static int[] referencedIdsOf(@Nonnull ReferencesStoragePart part) {
		return Arrays.stream(part.getReferences())
			.mapToInt(it -> it.getReferenceKey().primaryKey())
			.toArray();
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
				PARAMETER, referenceSchema(PARAMETER, null),
				"categoryLonger", referenceSchema("categoryLonger", "categoryGroup"),
				"žluťoučký", referenceSchema("žluťoučký", "categoryGroup"),
				"longName".repeat(80), referenceSchema("longName".repeat(80), "categoryGroup")
			),
			Collections.emptySet(),
			Collections.emptyMap()
		);
		this.keyCompressor = new ReadWriteKeyCompressor(new ConcurrentHashMap<>());
		this.kryo = KryoFactory.createKryo(
			SharedClassesConfigurer.INSTANCE
				.andThen(new EntityStoragePartConfigurer(this.keyCompressor))
		);
	}

	/**
	 * Builds a reference of the passed name with an optional group and an optional single attribute - the attribute
	 * matters because a skipped reference still has to decode its attribute values to get past them.
	 */
	@Nonnull
	private Reference reference(
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
		return reference(referenceName, referencedPrimaryKey, internalPrimaryKey, groupId, attributes, false);
	}

	/**
	 * Builds a reference with full control over its attributes and its dropped flag, so that a single record can mix
	 * live and dropped references and references whose skipped body is anything but trivial.
	 *
	 * @param referenceName        name of the reference
	 * @param referencedPrimaryKey primary key of the referenced entity
	 * @param internalPrimaryKey   internal primary key assigned to the reference
	 * @param groupId              primary key of the reference group, NULL when the reference has none
	 * @param attributes           attribute values the reference carries
	 * @param dropped              whether the reference is marked as removed
	 * @return the assembled reference
	 */
	@Nonnull
	private Reference reference(
		@Nonnull String referenceName,
		int referencedPrimaryKey,
		int internalPrimaryKey,
		@Nullable Integer groupId,
		@Nonnull Map<AttributeKey, AttributeValue> attributes,
		boolean dropped
	) {
		final ReferenceSchema referenceSchema = this.productSchema.getReferenceOrThrowException(referenceName);
		return new Reference(
			this.productSchema, referenceSchema, 1,
			new ReferenceKey(referenceName, referencedPrimaryKey, internalPrimaryKey),
			groupId == null ?
				null :
				new GroupEntityReference(referenceSchema.getReferencedGroupType(), groupId, 1, false),
			attributes, dropped
		);
	}

	@Nonnull
	private ReferencesStoragePart firstPart() {
		// sorted by ReferenceContract#FULL_COMPARATOR, i.e. by name and then by referenced primary key
		return new ReferencesStoragePart(
			10, 5,
			new Reference[]{
				reference(BRAND, 100, 1, null, "brand-code"),
				reference(CATEGORY, 200, 2, 900, "category-code"),
				reference(CATEGORY, 201, 3, 901, null),
				reference(PARAMETER, 300, 4, null, null),
				reference(PARAMETER, 301, 5, null, "parameter-code")
			},
			-1
		);
	}

	@Nonnull
	private ReferencesStoragePart secondPart() {
		return new ReferencesStoragePart(
			11, 2,
			new Reference[]{
				reference(BRAND, 111, 1, null, null),
				reference(PARAMETER, 333, 2, null, "second-parameter-code")
			},
			-1
		);
	}

	/**
	 * Writes the passed storage parts into a single stream, exactly the way records of an OffsetIndex follow one
	 * another.
	 */
	@Nonnull
	private byte[] writeParts(@Nonnull ReferencesStoragePart... parts) {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(4_096);
		try (final ByteBufferOutput output = new ByteBufferOutput(baos)) {
			EntitySchemaContext.executeWithSchemaContext(
				this.productSchema,
				() -> {
					for (final ReferencesStoragePart part : parts) {
						this.kryo.writeObject(output, part);
					}
					return null;
				}
			);
		}
		return baos.toByteArray();
	}

	/**
	 * Writes the two storage parts of {@link #firstPart()} and {@link #secondPart()} into a single stream.
	 */
	@Nonnull
	private byte[] writeTwoParts() {
		return writeParts(firstPart(), secondPart());
	}

	/**
	 * Opens the serialized bytes under the entity schema context every reference decode needs and hands the input to
	 * the passed reader, which decides for itself which filters the individual records are read under.
	 */
	private <T> T readWithSchema(@Nonnull byte[] serialized, @Nonnull Function<ByteBufferInput, T> reader) {
		try (final ByteBufferInput input = new ByteBufferInput(new ByteArrayInputStream(serialized))) {
			return EntitySchemaContext.executeWithSchemaContext(
				this.productSchema,
				() -> reader.apply(input)
			);
		}
	}

	/**
	 * Reads `count` parts from the stream under the passed filter and hands them back in order.
	 */
	@Nonnull
	private ReferencesStoragePart[] readParts(
		@Nonnull byte[] serialized,
		@Nullable Set<String> filter,
		int count
	) {
		return readWithSchema(
			serialized,
			input -> ReferenceDecodeCoverageContext.executeWithCoverage(
				filter == null ? null : ReferenceDecodeCoverage.ofNames(filter),
				() -> {
					final ReferencesStoragePart[] parts = new ReferencesStoragePart[count];
					for (int i = 0; i < count; i++) {
						parts[i] = this.kryo.readObject(input, ReferencesStoragePart.class);
					}
					return parts;
				}
			)
		);
	}

	/**
	 * Reads both parts from the stream under the passed filter and hands them back in order.
	 */
	@Nonnull
	private ReferencesStoragePart[] readTwoParts(@Nonnull byte[] serialized, @Nullable Set<String> filter) {
		return readParts(serialized, filter, 2);
	}

	/**
	 * Reads both parts from the stream under a coverage that may narrow by key as well as by name.
	 *
	 * Kept apart from {@link #readTwoParts(byte[], Set)} rather than folded into it, because the name-only
	 * overload is what the reference *name* narrowing exercises and the two axes are asserted separately.
	 */
	@Nonnull
	private ReferencesStoragePart[] readTwoPartsUnderCoverage(
		@Nonnull byte[] serialized,
		@Nullable ReferenceDecodeCoverage coverage
	) {
		return readWithSchema(
			serialized,
			input -> ReferenceDecodeCoverageContext.executeWithCoverage(
				coverage,
				() -> new ReferencesStoragePart[]{
					this.kryo.readObject(input, ReferencesStoragePart.class),
					this.kryo.readObject(input, ReferencesStoragePart.class)
				}
			)
		);
	}

	/**
	 * Reads a single storage part from the already opened input, under whatever filter is currently bound.
	 */
	@Nonnull
	private ReferencesStoragePart readPart(@Nonnull ByteBufferInput input) {
		return this.kryo.readObject(input, ReferencesStoragePart.class);
	}

	/**
	 * Writes a single storage part into a throw-away buffer, which is what a record rewrite does.
	 */
	private void serialize(@Nonnull ReferencesStoragePart part) {
		try (final ByteBufferOutput output = new ByteBufferOutput(new ByteArrayOutputStream(1_024))) {
			EntitySchemaContext.executeWithSchemaContext(
				this.productSchema,
				() -> {
					this.kryo.writeObject(output, part);
					return null;
				}
			);
		}
	}

	/**
	 * Writes a storage part in the layout a 2025.6 catalogue carries: the record and every reference in it announce
	 * the `serialVersionUID` they had before internal primary keys were introduced, which is what routes the read to
	 * {@link ReferencesStoragePartSerializer_2025_6}.
	 */
	private void writeLegacyPart(@Nonnull ByteBufferOutput output, @Nonnull ReferencesStoragePart part) {
		output.writeLong(LEGACY_REFERENCES_STORAGE_PART_UID);
		output.writeInt(part.getEntityPrimaryKey());
		final Reference[] references = part.getReferences();
		output.writeVarInt(references.length, true);
		for (final Reference reference : references) {
			writeLegacyReference(output, reference);
		}
	}

	/**
	 * Writes a single reference in the 2025.6 layout - the current one with the internal primary key removed.
	 */
	private void writeLegacyReference(@Nonnull ByteBufferOutput output, @Nonnull Reference reference) {
		output.writeLong(LEGACY_REFERENCE_UID);
		output.writeVarInt(reference.version(), true);
		output.writeString(reference.getReferenceName());
		output.writeInt(reference.getReferenceKey().primaryKey());
		output.writeBoolean(reference.dropped());
		final Optional<GroupEntityReference> group = reference.getGroup();
		output.writeBoolean(group.isPresent());
		group.ifPresent(
			it -> {
				output.writeVarInt(it.version(), true);
				output.writeInt(it.getPrimaryKey());
				output.writeBoolean(it.dropped());
			}
		);
		final Collection<AttributeValue> attributes = reference.getAttributeValues();
		output.writeVarInt(attributes.size(), true);
		attributes.stream()
			.sorted(Comparator.comparing(AttributeValue::key))
			.forEach(it -> this.kryo.writeObject(output, it));
	}

	/**
	 * Writes {@link #firstPart()} in the 2025.6 layout followed by {@link #secondPart()} in the current one, which is
	 * what a catalogue looks like while it is being rewritten record by record.
	 */
	@Nonnull
	private byte[] writeLegacyFirstPart() {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(4_096);
		try (final ByteBufferOutput output = new ByteBufferOutput(baos)) {
			EntitySchemaContext.executeWithSchemaContext(
				this.productSchema,
				() -> {
					writeLegacyPart(output, firstPart());
					this.kryo.writeObject(output, secondPart());
					return null;
				}
			);
		}
		return baos.toByteArray();
	}

	/**
	 * Writes a record in the 2025.6 layout whose **references** announce the current `serialVersionUID`, which is what
	 * a catalogue looks like when a rewrite has reached the references of a record but not the record itself. The
	 * reference decoder of the current layout is the one that honours a filter, so this is the shape that puts
	 * a skipped (NULL) reference inside a part the previous layout has to number by position.
	 */
	@Nonnull
	private byte[] writeMixedLayoutFirstPart() {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(4_096);
		try (final ByteBufferOutput output = new ByteBufferOutput(baos)) {
			EntitySchemaContext.executeWithSchemaContext(
				this.productSchema,
				() -> {
					final ReferencesStoragePart part = firstPart();
					output.writeLong(LEGACY_REFERENCES_STORAGE_PART_UID);
					output.writeInt(part.getEntityPrimaryKey());
					final Reference[] references = part.getReferences();
					output.writeVarInt(references.length, true);
					for (final Reference reference : references) {
						this.kryo.writeObject(output, reference);
					}
					this.kryo.writeObject(output, secondPart());
					return null;
				}
			);
		}
		return baos.toByteArray();
	}

	/**
	 * Uses a complete ordinary decode as the admission oracle, then compares every retained field and exact offset.
	 */
	@Test
	@DisplayName("current attributes preserve every admitted reference and byte offset for all coverage shapes")
	void shouldPreserveReferencesAndOffsetsWhenSkippingCurrentAttributes() {
		verifySkipMatrix(false);
	}

	/**
	 * Exercises the same matrix with version-prefixed attributes hand-written in the 2026.2 layout.
	 */
	@Test
	@DisplayName("2026.2 attributes preserve every admitted reference and byte offset for all coverage shapes")
	void shouldPreserveReferencesAndOffsetsWhenSkippingLegacyAttributes() {
		verifySkipMatrix(true);
	}

	/**
	 * Covers direct invocations outside a part and restoration after nested and failed coverage bindings.
	 */
	@Test
	@DisplayName("direct reference reads use context and restore nested and failed part bindings")
	void shouldRestoreCoverageForDirectReferenceReads() {
		final byte[] bytes;
		try (final Output output = new Output(128, -1)) {
			this.kryo.writeObject(output, reference(CATEGORY, 201, 1, null, Map.of(), false));
			bytes = output.toBytes();
		}
		final SerialVersionBasedSerializer<?> versioned =
			(SerialVersionBasedSerializer<?>) this.kryo.getSerializer(Reference.class);
		final ReferenceSerializer serializer = (ReferenceSerializer) versioned.getCurrentVersionSerializer();
		final ReferenceDecodeCoverage none = ReferenceDecodeCoverage.ofNames(Set.of());
		EntitySchemaContext.executeWithSchemaContext(this.productSchema, () ->
			ReferenceDecodeCoverageContext.executeWithCoverage(null, () -> {
				serializer.withDecodeCoverage(none, () -> {
					assertNull(this.kryo.readObject(new Input(bytes), Reference.class));
					serializer.withDecodeCoverage(null, () -> {
						assertEquals(201, this.kryo.readObject(new Input(bytes), Reference.class).getReferenceKey().primaryKey());
						return null;
					});
					assertNull(this.kryo.readObject(new Input(bytes), Reference.class));
					assertThrows(IllegalStateException.class, () -> serializer.withDecodeCoverage(null, () -> {
						throw new IllegalStateException("failed read");
					}));
					assertNull(this.kryo.readObject(new Input(bytes), Reference.class));
					return null;
				});
				assertEquals(201, this.kryo.readObject(new Input(bytes), Reference.class).getReferenceKey().primaryKey());
				ReferenceDecodeCoverageContext.executeWithCoverage(none, () -> {
					assertNull(this.kryo.readObject(new Input(bytes), Reference.class));
					return null;
				});
				return null;
			})
		);
	}

	/**
	 * A truncated real part fails inside the reference loop; later reads on the same Kryo must use fresh coverage.
	 */
	@Test
	@DisplayName("releases part coverage after a truncated reference payload")
	void shouldReleaseCoverageAfterTruncatedPartRead() {
		final byte[] complete = writeParts(firstPart());
		final byte[] truncated = Arrays.copyOf(complete, complete.length - 1);
		final Reference expected = reference(CATEGORY, 201, 7, null, Map.of(), false);
		final byte[] direct;
		try (final Output output = new Output(128, -1)) {
			this.kryo.writeObject(output, expected);
			direct = output.toBytes();
		}
		EntitySchemaContext.executeWithSchemaContext(this.productSchema, () -> {
			ReferenceDecodeCoverageContext.executeWithCoverage(ReferenceDecodeCoverage.ofNames(Set.of()), () -> {
				try (final Input input = new Input(truncated)) {
					assertThrows(KryoException.class,
						() -> this.kryo.readObject(input, ReferencesStoragePart.class));
					assertEquals(truncated.length, input.total(), "failure must reach the truncated payload end");
				}
				return null;
			});
			ReferenceDecodeCoverageContext.executeWithCoverage(ReferenceDecodeCoverage.ofNames(Set.of(CATEGORY)), () -> {
				// A direct read cannot overwrite a stale part binding, so it proves the failed read unbound it.
				try (final Input input = new Input(direct)) {
					assertReferenceContents(
						new Reference[]{expected}, new Reference[]{this.kryo.readObject(input, Reference.class)},
						"direct read after the failed part read"
					);
				}
				try (final Input input = new Input(complete)) {
					assertArrayEquals(new int[]{200, 201}, referencedIdsOf(
						this.kryo.readObject(input, ReferencesStoragePart.class)));
				}
				return null;
			});
			return null;
		});
	}

	/**
	 * Writes two rich parts and a sentinel, then compares complete decoding with all name/key coverage variants using
	 * small and large buffers. Repeating each name exercises memo hits; prefix names and Unicode exercise misses.
	 */
	private void verifySkipMatrix(boolean legacyAttributes) {
		final String[] names = {BRAND, CATEGORY, "categoryLonger", "žluťoučký", "longName".repeat(80)};
		final ReferencesStoragePart part = skipMatrixPart(names);
		if (legacyAttributes) {
			// The old release wrote the same payload following its UID; no fixtures or byte patching are involved.
			this.kryo.getRegistration(AttributeValue.class).setSerializer(
				new LegacyAttributeValueWriter(this.keyCompressor)
			);
		}
		final byte[] bytes;
		final int partEnd;
		try (final Output output = new Output(4_096, -1)) {
			this.kryo.writeObject(output, part);
			partEnd = output.position();
			this.kryo.writeObject(output, part);
			output.writeInt(0x12345678);
			bytes = output.toBytes();
		}
		// Restore the normal version dispatcher, also for legacy data, before establishing the oracle.
		new EntityStoragePartConfigurer(this.keyCompressor).accept(this.kryo);
		EntitySchemaContext.executeWithSchemaContext(this.productSchema, () -> {
			final Input ordinary = new Input(bytes);
			final ReferencesStoragePart complete = this.kryo.readObject(ordinary, ReferencesStoragePart.class);
			assertEquals(partEnd, ordinary.total());
			// the oracle must hold the fixture: five live references of every name, the sixth one is dropped
			assertEquals(names.length * 5, complete.getReferences().length);
			final List<ReferenceDecodeCoverage> coverages = skipMatrixCoverages(names);
			int narrowingCoverages = 0;
			for (final int bufferSize : new int[]{32, 16_384}) {
				for (final ReferenceDecodeCoverage coverage : coverages) {
					final String scenario = "coverage=" + coverage + ", buffer=" + bufferSize;
					final Reference[] expected = Arrays.stream(complete.getReferences())
						.filter(it -> coverage == null || coverage.isReferenceDecoded(
							it.getReferenceName(), it.getReferenceKey().primaryKey()))
						.toArray(Reference[]::new);
					if (expected.length > 0 && expected.length < complete.getReferences().length) {
						narrowingCoverages++;
					}
					try (final ObservableInput<?> input = new ObservableInput<>(
						new ByteArrayInputStream(bytes), bufferSize, Checksum.NO_OP, null
					)) {
						ReferenceDecodeCoverageContext.executeWithCoverage(coverage, () -> {
							for (int record = 1; record <= 2; record++) {
								final ReferencesStoragePart decoded = this.kryo.readObject(input, ReferencesStoragePart.class);
								assertReferenceContents(
									expected, decoded.getReferences(), scenario + ", record=" + record
								);
								assertEquals(complete.sizeInBytes(), decoded.sizeInBytes(), scenario);
								assertEquals((long) partEnd * record, input.total(), scenario);
								assertEquals(coverage, decoded.getDecodeCoverage(), scenario);
							}
							return null;
						});
						assertEquals(0x12345678, input.readInt(), scenario);
						assertEquals(bytes.length, input.total(), scenario);
					}
				}
			}
			// without a coverage that keeps some references and drops others, matching the oracle proves no skip
			assertTrue(narrowingCoverages > 0, "no coverage both admitted and rejected references");
			assertKeylessReaderSkipsEveryReference(bytes, partEnd);
			return null;
		});
	}

	/**
	 * Builds the part the skip matrix writes twice: six references of every passed name, without attributes, with
	 * one attribute and with every value shape in turn, every other one grouped (never a brand), the last one dropped.
	 *
	 * @param names reference names, each spread over six consecutive references
	 * @return the part holding all the references
	 */
	@Nonnull
	private ReferencesStoragePart skipMatrixPart(@Nonnull String[] names) {
		final Serializable[] values = {
			"žluťoučký " + "value".repeat(80), 42, 1234567890123L, new BigDecimal("123.456"),
			LocalDate.of(2030, 1, 2), LocalDateTime.of(2030, 1, 2, 3, 4), OffsetDateTime.MAX,
			IntegerNumberRange.between(-10, 30),
			DateTimeRange.between(OffsetDateTime.parse("2030-01-01T00:00:00Z"),
				OffsetDateTime.parse("2031-01-01T00:00:00Z")),
			new String[]{"a", "český", "漢字"}, new Integer[]{1, 2, 3}, new int[]{4, 5},
			new LocalDateTime[]{LocalDateTime.MIN, LocalDateTime.of(2030, 1, 2, 3, 4)}, null
		};
		final List<Reference> references = new ArrayList<>(names.length * 6);
		for (final String name : names) {
			for (int row = 0; row < 6; row++) {
				final Map<AttributeKey, AttributeValue> attributes = CollectionUtils.createLinkedHashMap(values.length);
				final int count = row % 3 == 0 ? 0 : row % 3 == 1 ? 1 : values.length;
				for (int attribute = 0; attribute < count; attribute++) {
					final AttributeKey key = new AttributeKey("value" + attribute, attribute % 2 == 0 ? Locale.ENGLISH : null);
					attributes.put(key, new AttributeValue(3, key, values[attribute], attribute == values.length - 1));
				}
				references.add(reference(name, 100 + row, references.size() + 1,
					row % 2 == 0 && !name.equals(BRAND) ? 900 : null, attributes, row == 5));
			}
		}
		return new ReferencesStoragePart(
			10, references.size(), references.toArray(Reference[]::new), -1
		);
	}

	/**
	 * Lists the coverages the skip matrix decodes under: none at all, one rejecting everything, each name admitted
	 * whole, each name narrowed by key, and a whole name mixed with a key-narrowed one.
	 *
	 * @param names reference names the matrix part holds
	 * @return the coverages, `null` standing for no coverage
	 */
	@Nonnull
	private static List<ReferenceDecodeCoverage> skipMatrixCoverages(@Nonnull String[] names) {
		final List<ReferenceDecodeCoverage> coverages = new ArrayList<>();
		coverages.add(null);
		coverages.add(ReferenceDecodeCoverage.ofNames(Set.of()));
		for (final String name : names) {
			coverages.add(ReferenceDecodeCoverage.ofNames(Set.of(name)));
			coverages.add(ReferenceDecodeCoverage.of(Set.of(), Map.of(name, new int[]{101, 102, 105})));
		}
		coverages.add(ReferenceDecodeCoverage.of(Set.of(BRAND), Map.of(CATEGORY, new int[]{102})));
		return coverages;
	}

	/**
	 * A missing-key compressor proves the reference skip actually reaches the key-free attribute path: a reader that
	 * resolved any attribute key of a rejected reference would fail.
	 *
	 * @param bytes   the matrix part written twice, followed by the sentinel
	 * @param partEnd the stream offset at which the first part ends
	 */
	private static void assertKeylessReaderSkipsEveryReference(@Nonnull byte[] bytes, int partEnd) {
		final Kryo keyless = KryoFactory.createKryo(
			SharedClassesConfigurer.INSTANCE.andThen(
				new EntityStoragePartConfigurer(new ReadOnlyKeyCompressor(Map.of()))
			)
		);
		ReferenceDecodeCoverageContext.executeWithCoverage(ReferenceDecodeCoverage.ofNames(Set.of()), () -> {
			try (final ObservableInput<?> input = new ObservableInput<>(
				new ByteArrayInputStream(bytes), 32, Checksum.NO_OP, null
			)) {
				for (int record = 1; record <= 2; record++) {
					assertEquals(0, keyless.readObject(input, ReferencesStoragePart.class).getReferences().length);
					assertEquals((long) partEnd * record, input.total());
				}
				assertEquals(0x12345678, input.readInt());
			}
			return null;
		});
	}

	/**
	 * Compares the complete persisted state, including array-valued attributes by contents rather than identity.
	 *
	 * @param expected the references a complete decode admitted
	 * @param actual   the references the decode under test returned
	 * @param scenario what was decoded, reported when an assertion fails
	 */
	private static void assertReferenceContents(
		@Nonnull Reference[] expected,
		@Nonnull Reference[] actual,
		@Nonnull String scenario
	) {
		assertEquals(expected.length, actual.length, scenario);
		for (int i = 0; i < expected.length; i++) {
			final String reference = scenario + ", reference #" + i;
			assertEquals(expected[i].getReferenceKey(), actual[i].getReferenceKey(), reference);
			assertEquals(
				expected[i].getReferenceKey().internalPrimaryKey(), actual[i].getReferenceKey().internalPrimaryKey(),
				reference
			);
			assertEquals(expected[i].version(), actual[i].version(), reference);
			assertEquals(expected[i].dropped(), actual[i].dropped(), reference);
			assertEquals(expected[i].getGroup(), actual[i].getGroup(), reference);
			final List<AttributeValue> expectedAttributes = new ArrayList<>(expected[i].getAttributeValues());
			final List<AttributeValue> actualAttributes = new ArrayList<>(actual[i].getAttributeValues());
			expectedAttributes.sort(Comparator.comparing(AttributeValue::key));
			actualAttributes.sort(Comparator.comparing(AttributeValue::key));
			assertEquals(expectedAttributes.size(), actualAttributes.size(), reference);
			for (int j = 0; j < expectedAttributes.size(); j++) {
				final AttributeValue wanted = expectedAttributes.get(j);
				final AttributeValue found = actualAttributes.get(j);
				assertEquals(wanted.key(), found.key(), reference);
				assertEquals(wanted.version(), found.version(), reference);
				assertEquals(wanted.dropped(), found.dropped(), reference);
				assertArrayEquals(new Object[]{wanted.value()}, new Object[]{found.value()}, reference);
			}
		}
	}

	@Nested
	@DisplayName("Unfiltered decode")
	class UnfilteredDecodeTest {

		@Test
		@DisplayName("decodes every reference and yields a complete part")
		void shouldDecodeAllReferencesWhenNoFilterIsBound() {
			final ReferencesStoragePart[] parts = readTwoParts(writeTwoParts(), null);

			assertTrue(parts[0].isComplete());
			assertNull(parts[0].getDecodeCoverage());
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
	}

	@Nested
	@DisplayName("Filtered decode")
	class FilteredDecodeTest {

		@Test
		@DisplayName("keeps only the requested names and leaves the stream aligned")
		void shouldDecodeOnlyRequestedReferences() {
			final ReferencesStoragePart[] parts = readTwoParts(writeTwoParts(), Set.of(PARAMETER));

			assertFalse(parts[0].isComplete());
			assertEquals(Set.of(PARAMETER), parts[0].getDecodeCoverage().getNamesDecodedWhole());
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
		@DisplayName("keeps the groups and attributes of the references it does decode")
		void shouldKeepGroupsAndAttributesOfDecodedReferences() {
			final ReferencesStoragePart[] parts = readTwoParts(writeTwoParts(), Set.of(CATEGORY));

			assertArrayEquals(new String[]{CATEGORY, CATEGORY}, referenceNamesOf(parts[0]));
			assertEquals(900, parts[0].getReferences()[0].getGroup().orElseThrow().getPrimaryKeyOrThrowException());
			assertEquals(901, parts[0].getReferences()[1].getGroup().orElseThrow().getPrimaryKeyOrThrowException());
			assertEquals("category-code", parts[0].getReferences()[0].getAttribute(ATTRIBUTE_CODE));

			// the second part has no category reference at all - an empty result, not a desynchronized stream
			assertEquals(0, parts[1].getReferences().length);
			assertEquals(11, parts[1].getEntityPrimaryKey());
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

		@Test
		@DisplayName("grows the target array when the kept references outnumber its initial capacity")
		void shouldGrowTargetArrayWhenFilteredReferencesExceedInitialCapacity() {
			// the filtered read sizes its target array by a small constant rather than by the record's reference
			// count - which is the whole point, the record may carry orders of magnitude more references than the
			// projection asked for - so everything past that constant goes through the growth branch
			final List<Reference> references = new ArrayList<>(46);
			int internalPrimaryKey = 1;
			for (int i = 0; i < 3; i++) {
				references.add(reference(BRAND, 100 + i, internalPrimaryKey++, null, "brand-code-" + i));
			}
			for (int i = 0; i < 3; i++) {
				references.add(reference(CATEGORY, 200 + i, internalPrimaryKey++, 900 + i, null));
			}
			final int[] expectedParameterIds = new int[40];
			for (int i = 0; i < 40; i++) {
				expectedParameterIds[i] = 300 + i;
				references.add(reference(PARAMETER, 300 + i, internalPrimaryKey++, null, "parameter-code-" + i));
			}
			final ReferencesStoragePart wide = new ReferencesStoragePart(
				10, internalPrimaryKey - 1, references.toArray(Reference[]::new), -1
			);

			final ReferencesStoragePart[] parts = readTwoParts(
				writeParts(wide, secondPart()), Set.of(PARAMETER)
			);

			assertEquals(40, parts[0].getReferences().length);
			assertArrayEquals(expectedParameterIds, referencedIdsOf(parts[0]));
			// no trailing nulls survived the growth and the final trim
			assertTrue(Arrays.stream(parts[0].getReferences()).allMatch(it -> PARAMETER.equals(it.getReferenceName())));
			// and the record that follows still decodes, so every skip consumed exactly its own bytes
			assertArrayEquals(new String[]{PARAMETER}, referenceNamesOf(parts[1]));
			assertEquals(11, parts[1].getEntityPrimaryKey());
		}

		@Test
		@DisplayName("grows the target array exactly at the capacity boundary")
		void shouldGrowTargetArrayExactlyAtCapacityBoundary() {
			// seventeen kept references out of eighteen is the single shape where the doubled capacity overshoots the
			// record's own reference count and has to be clamped to it - the only input that tells a correct clamp
			// apart from an off-by-one
			final List<Reference> references = new ArrayList<>(18);
			references.add(reference(BRAND, 100, 1, null, "brand-code"));
			final int[] expectedParameterIds = new int[17];
			for (int i = 0; i < 17; i++) {
				expectedParameterIds[i] = 300 + i;
				references.add(reference(PARAMETER, 300 + i, i + 2, null, null));
			}
			final ReferencesStoragePart wide = new ReferencesStoragePart(
				10, 18, references.toArray(Reference[]::new), -1
			);

			final ReferencesStoragePart[] parts = readTwoParts(
				writeParts(wide, secondPart()), Set.of(PARAMETER)
			);

			assertEquals(17, parts[0].getReferences().length);
			assertArrayEquals(expectedParameterIds, referencedIdsOf(parts[0]));
			assertArrayEquals(new String[]{PARAMETER}, referenceNamesOf(parts[1]));
		}

		@Test
		@DisplayName("discards dropped references of the filtered name as well")
		void shouldDiscardDroppedReferencesUnderFilter() {
			// the filter and the dropped flag share one `continue`, and together they are what drives the number of
			// kept references out of step with the loop index
			final ReferencesStoragePart mixed = new ReferencesStoragePart(
				10, 6,
				new Reference[]{
					reference(BRAND, 100, 1, null, Collections.emptyMap(), true),
					reference(BRAND, 101, 2, null, Collections.emptyMap(), false),
					reference(PARAMETER, 300, 3, null, Collections.emptyMap(), true),
					reference(PARAMETER, 301, 4, null, Collections.emptyMap(), false),
					reference(PARAMETER, 302, 5, null, Collections.emptyMap(), true),
					reference(PARAMETER, 303, 6, null, Collections.emptyMap(), false)
				},
				-1
			);

			final ReferencesStoragePart[] parts = readTwoParts(
				writeParts(mixed, secondPart()), Set.of(PARAMETER)
			);

			assertArrayEquals(new String[]{PARAMETER, PARAMETER}, referenceNamesOf(parts[0]));
			assertArrayEquals(new int[]{301, 303}, referencedIdsOf(parts[0]));
			assertArrayEquals(new String[]{PARAMETER}, referenceNamesOf(parts[1]));
			assertEquals(11, parts[1].getEntityPrimaryKey());
		}

		@Test
		@DisplayName("skips a reference carrying several attributes, one of them localized")
		void shouldSkipReferenceCarryingSeveralAndLocalizedAttributes() {
			// a skipped reference is walked over field for field, and attribute values are the one part that has to
			// be decoded to get past them - a wrong field order only shows up once the skipped body is non-trivial
			final Map<AttributeKey, AttributeValue> richAttributes = new LinkedHashMap<>(4);
			final AttributeKey codeKey = new AttributeKey(ATTRIBUTE_CODE);
			final AttributeKey nameKey = new AttributeKey(ATTRIBUTE_NAME);
			final AttributeKey localizedLabelKey = new AttributeKey(ATTRIBUTE_LABEL, Locale.ENGLISH);
			richAttributes.put(codeKey, new AttributeValue(codeKey, "skipped-code"));
			richAttributes.put(nameKey, new AttributeValue(nameKey, "skipped-name"));
			richAttributes.put(localizedLabelKey, new AttributeValue(localizedLabelKey, "skipped-label"));

			final ReferencesStoragePart mixed = new ReferencesStoragePart(
				10, 3,
				new Reference[]{
					reference(CATEGORY, 200, 1, 900, richAttributes, false),
					reference(CATEGORY, 201, 2, null, Collections.emptyMap(), false),
					reference(PARAMETER, 300, 3, null, Collections.emptyMap(), false)
				},
				-1
			);

			final ReferencesStoragePart[] parts = readTwoParts(
				writeParts(mixed, secondPart()), Set.of(PARAMETER)
			);

			assertArrayEquals(new String[]{PARAMETER}, referenceNamesOf(parts[0]));
			assertArrayEquals(new int[]{300}, referencedIdsOf(parts[0]));
			assertArrayEquals(new String[]{PARAMETER}, referenceNamesOf(parts[1]));
			assertArrayEquals(new int[]{333}, referencedIdsOf(parts[1]));
		}

		@Test
		@DisplayName("a narrowed part still reports the size of the whole record")
		void shouldReportFullRecordSizeForNarrowedPart() {
			// the size feeds the I/O statistics of the execution, which describe what the storage handed over - not
			// what the projection kept out of it. A skip that stopped consuming bytes would silently deflate them.
			final byte[] serialized = writeTwoParts();

			final OptionalInt completeSize = readTwoParts(serialized, null)[0].sizeInBytes();
			final OptionalInt narrowedSize = readTwoParts(serialized, Set.of(PARAMETER))[0].sizeInBytes();

			assertTrue(completeSize.isPresent());
			assertTrue(narrowedSize.isPresent());
			assertEquals(completeSize.getAsInt(), narrowedSize.getAsInt());
		}

		@Test
		@DisplayName("an unrestricted read nested inside a narrowed one leaves the narrowing intact")
		void shouldRestoreOuterFilterAfterNestedUnrestrictedRead() {
			// the fetch pipeline nests reads, and the filter binding is restored in a `finally` - a regression here
			// hands the record that follows either an unrestricted view or a stale narrow one
			final byte[] serialized = writeParts(firstPart(), secondPart(), firstPart());

			final ReferencesStoragePart[] parts = readWithSchema(
				serialized,
				input -> ReferenceDecodeCoverageContext.executeWithCoverage(
					ReferenceDecodeCoverage.ofNames(Set.of(BRAND)),
					() -> {
						final ReferencesStoragePart narrowed = readPart(input);
						final ReferencesStoragePart complete =
							ReferenceDecodeCoverageContext.executeWithCoverage(
								null, () -> readPart(input)
							);
						final ReferencesStoragePart narrowedAgain = readPart(input);
						return new ReferencesStoragePart[]{narrowed, complete, narrowedAgain};
					}
				)
			);

			assertArrayEquals(new String[]{BRAND}, referenceNamesOf(parts[0]));
			assertTrue(parts[1].isComplete());
			assertArrayEquals(new String[]{BRAND, PARAMETER}, referenceNamesOf(parts[1]));
			assertFalse(parts[2].isComplete());
			assertEquals(Set.of(BRAND), parts[2].getDecodeCoverage().getNamesDecodedWhole());
			assertArrayEquals(new String[]{BRAND}, referenceNamesOf(parts[2]));
		}
	}

	@Nested
	@DisplayName("Narrowed view refusals")
	class NarrowedPartRefusalsTest {

		@Test
		@DisplayName("refuses to answer about a reference name it did not decode")
		void shouldRefuseToAnswerAboutUndecodedReferenceName() {
			final ReferencesStoragePart narrowed = readTwoParts(writeTwoParts(), Set.of(PARAMETER))[0];

			assertThrows(GenericEvitaInternalError.class, () -> narrowed.getReferencedIds(BRAND));
			assertThrows(GenericEvitaInternalError.class, () -> narrowed.contains(new ReferenceKey(BRAND, 100)));
			// whole-part questions cannot be answered from a subset either
			assertThrows(GenericEvitaInternalError.class, narrowed::isEmpty);
		}

		@Test
		@DisplayName("refuses to be written back to the storage")
		void shouldRefuseToSerializeNarrowedPart() {
			final ReferencesStoragePart narrowed = readTwoParts(writeTwoParts(), Set.of(PARAMETER))[0];

			assertThrows(GenericEvitaInternalError.class, () -> serialize(narrowed));
		}
	}

	@Nested
	@DisplayName("Backward compatible decode")
	class LegacyDecodeTest {

		@Test
		@DisplayName("a record in the previous layout comes back complete even under a filter")
		void shouldDecodeLegacyRecordCompletelyEvenUnderFilter() {
			// the previous layout carries no internal primary keys and assigns them by position, so it can never
			// honour a filter - skipping a reference would shift every key after it. Such a record must therefore
			// report itself as the entity's complete reference set, or the guards would fire for names it does carry.
			final ReferencesStoragePart[] parts = readTwoParts(writeLegacyFirstPart(), Set.of(PARAMETER));

			assertTrue(parts[0].isComplete());
			assertNull(parts[0].getDecodeCoverage());
			assertArrayEquals(
				new String[]{BRAND, CATEGORY, CATEGORY, PARAMETER, PARAMETER},
				referenceNamesOf(parts[0])
			);
			assertEquals(10, parts[0].getEntityPrimaryKey());
			// every guarded question is answerable, including the ones that span all names
			assertArrayEquals(new int[]{100}, parts[0].getReferencedIds(BRAND));
			assertFalse(parts[0].isEmpty());
			assertTrue(parts[0].contains(new ReferenceKey(BRAND, 100, 1)));
		}

		@Test
		@DisplayName("internal primary keys are assigned by position")
		void shouldAssignInternalPrimaryKeysByPositionForLegacyRecord() {
			final ReferencesStoragePart legacy = readTwoParts(writeLegacyFirstPart(), null)[0];

			assertArrayEquals(
				new int[]{1, 2, 3, 4, 5},
				Arrays.stream(legacy.getReferences())
					.mapToInt(it -> it.getReferenceKey().internalPrimaryKey())
					.toArray()
			);
			assertEquals(5, legacy.getLastUsedPrimaryKey());
		}

		@Test
		@DisplayName("a record in the previous layout holding current-layout references also comes back complete")
		void shouldDecodeMixedLayoutRecordCompletelyEvenUnderFilter() {
			// the reference decoder of the current layout is the one that honours a filter, so a record numbering its
			// references by position has to unbind the filter for the whole of its own read - otherwise a skipped
			// reference arrives as NULL inside a decoder that has nowhere to put a hole
			final ReferencesStoragePart[] parts = readTwoParts(writeMixedLayoutFirstPart(), Set.of(PARAMETER));

			assertTrue(parts[0].isComplete());
			assertNull(parts[0].getDecodeCoverage());
			assertArrayEquals(
				new String[]{BRAND, CATEGORY, CATEGORY, PARAMETER, PARAMETER},
				referenceNamesOf(parts[0])
			);
			assertArrayEquals(
				new int[]{1, 2, 3, 4, 5},
				Arrays.stream(parts[0].getReferences())
					.mapToInt(it -> it.getReferenceKey().internalPrimaryKey())
					.toArray()
			);
			// and the filter is put back for the record that follows
			assertFalse(parts[1].isComplete());
			assertArrayEquals(new String[]{PARAMETER}, referenceNamesOf(parts[1]));
		}

		@Test
		@DisplayName("a record in the current layout that follows is still narrowed")
		void shouldKeepNarrowingTheCurrentLayoutRecordFollowingALegacyOne() {
			final ReferencesStoragePart[] parts = readTwoParts(writeLegacyFirstPart(), Set.of(PARAMETER));

			assertFalse(parts[1].isComplete());
			assertEquals(Set.of(PARAMETER), parts[1].getDecodeCoverage().getNamesDecodedWhole());
			assertArrayEquals(new String[]{PARAMETER}, referenceNamesOf(parts[1]));
			assertEquals(11, parts[1].getEntityPrimaryKey());
		}
	}

	@Nested
	@DisplayName("Key narrowed decode")
	class KeyNarrowedDecodeTest {

		@Test
		@DisplayName("keeps only the requested keys of a name and leaves the stream aligned")
		void shouldDecodeOnlyRequestedKeys() {
			final ReferencesStoragePart[] parts = readTwoPartsUnderCoverage(
				writeTwoParts(),
				ReferenceDecodeCoverage.of(Set.of(), Map.of(CATEGORY, new int[]{201}))
			);

			assertFalse(parts[0].isComplete());
			assertArrayEquals(new String[]{CATEGORY}, referenceNamesOf(parts[0]));
			assertArrayEquals(new int[]{201}, referencedIdsOf(parts[0]));
			assertEquals(10, parts[0].getEntityPrimaryKey());
			assertEquals(5, parts[0].getLastUsedPrimaryKey());

			// the second part proves the skipped brand, the skipped category 200 - which carries a group AND an
			// attribute - and both parameters consumed exactly the bytes decoding them would have
			assertEquals(11, parts[1].getEntityPrimaryKey());
			assertEquals(0, parts[1].getReferences().length);
		}

		@Test
		@DisplayName("a key set naming keys the record does not hold yields an empty part, not a broken stream")
		void shouldDecodeNothingWhenNoKeyMatches() {
			final ReferencesStoragePart[] parts = readTwoPartsUnderCoverage(
				writeTwoParts(),
				ReferenceDecodeCoverage.of(Set.of(), Map.of(CATEGORY, new int[]{777, 888}))
			);

			assertEquals(0, parts[0].getReferences().length);
			assertEquals(10, parts[0].getEntityPrimaryKey());
			assertEquals(11, parts[1].getEntityPrimaryKey());
		}

		@Test
		@DisplayName("skips a key-excluded reference carrying a group and a localized attribute")
		void shouldSkipKeyExcludedReferenceCarryingGroupAndLocalizedAttributes() {
			// this is the nastiest failure mode of the key axis: a mis-mirrored skip does not fail at the reference,
			// it DESYNCHRONIZES the stream, and the damage only surfaces on the record that follows. The skipped
			// body therefore has to be non-trivial - a group plus several attributes, one of them localized.
			final Map<AttributeKey, AttributeValue> richAttributes = new LinkedHashMap<>(4);
			final AttributeKey codeKey = new AttributeKey(ATTRIBUTE_CODE);
			final AttributeKey nameKey = new AttributeKey(ATTRIBUTE_NAME);
			final AttributeKey localizedLabelKey = new AttributeKey(ATTRIBUTE_LABEL, Locale.ENGLISH);
			richAttributes.put(codeKey, new AttributeValue(codeKey, "skipped-code"));
			richAttributes.put(nameKey, new AttributeValue(nameKey, "skipped-name"));
			richAttributes.put(localizedLabelKey, new AttributeValue(localizedLabelKey, "skipped-label"));

			final ReferencesStoragePart mixed = new ReferencesStoragePart(
				10, 3,
				new Reference[]{
					// excluded by key, and expensive to walk over
					reference(CATEGORY, 200, 1, 900, richAttributes, false),
					// admitted by key
					reference(CATEGORY, 201, 2, 901, Collections.emptyMap(), false),
					reference(PARAMETER, 300, 3, null, Collections.emptyMap(), false)
				},
				-1
			);

			// the record that follows carries an admitted key of its own, so the alignment is proved by what it
			// decodes rather than by an empty result - an empty part would look identical to a desynchronized read
			// that simply matched nothing
			final ReferencesStoragePart follower = new ReferencesStoragePart(
				11, 2,
				new Reference[]{
					reference(CATEGORY, 201, 1, null, "follower-code"),
					reference(PARAMETER, 333, 2, null, Collections.emptyMap(), false)
				},
				-1
			);

			final ReferencesStoragePart[] parts = readTwoPartsUnderCoverage(
				writeParts(mixed, follower),
				ReferenceDecodeCoverage.of(Set.of(), Map.of(CATEGORY, new int[]{201}))
			);

			assertArrayEquals(new String[]{CATEGORY}, referenceNamesOf(parts[0]));
			assertArrayEquals(new int[]{201}, referencedIdsOf(parts[0]));
			// the group of the reference that WAS admitted survives the neighbouring skip intact - read off the
			// materialized reference, because a key-narrowed name may not be asked name-scoped questions
			assertEquals(
				901,
				parts[0].getReferences()[0].getGroup().orElseThrow().getPrimaryKey()
			);
			// and the record that follows decodes its own admitted reference, attribute and all, which is what
			// proves the skip consumed exactly the bytes decoding the skipped body would have
			assertEquals(11, parts[1].getEntityPrimaryKey());
			assertArrayEquals(new int[]{201}, referencedIdsOf(parts[1]));
			assertEquals("follower-code", parts[1].getReferences()[0].getAttribute(ATTRIBUTE_CODE));
		}

		@Test
		@DisplayName("a dropped reference inside the key set is discarded like any other")
		void shouldDiscardDroppedReferenceInsideTheKeySet() {
			final ReferencesStoragePart withDropped = new ReferencesStoragePart(
				10, 3,
				new Reference[]{
					reference(CATEGORY, 200, 1, null, Collections.emptyMap(), true),
					reference(CATEGORY, 201, 2, null, Collections.emptyMap(), false),
					reference(PARAMETER, 300, 3, null, Collections.emptyMap(), false)
				},
				-1
			);

			final ReferencesStoragePart[] parts = readTwoPartsUnderCoverage(
				writeParts(withDropped, secondPart()),
				ReferenceDecodeCoverage.of(Set.of(), Map.of(CATEGORY, new int[]{200, 201}))
			);

			// 200 is admitted by key but dropped, so it never materializes - and the stream stays aligned
			assertArrayEquals(new int[]{201}, referencedIdsOf(parts[0]));
			assertEquals(11, parts[1].getEntityPrimaryKey());
		}

		@Test
		@DisplayName("mixes a whole-decoded name with a key-narrowed one in a single read")
		void shouldMixWholeAndKeyNarrowedNames() {
			final ReferencesStoragePart[] parts = readTwoPartsUnderCoverage(
				writeTwoParts(),
				ReferenceDecodeCoverage.of(Set.of(PARAMETER), Map.of(CATEGORY, new int[]{200}))
			);

			assertArrayEquals(new String[]{CATEGORY, PARAMETER, PARAMETER}, referenceNamesOf(parts[0]));
			assertArrayEquals(new int[]{200, 300, 301}, referencedIdsOf(parts[0]));
			// the whole-decoded name still answers absence questions...
			assertArrayEquals(new int[]{300, 301}, parts[0].getReferencedIds(PARAMETER));
			// ...while the key-narrowed one must not
			assertThrows(GenericEvitaInternalError.class, () -> parts[0].getReferencedIds(CATEGORY));
		}

		@Test
		@DisplayName("refuses to answer about absence for a name it decoded only by key")
		void shouldRefuseAbsenceQuestionsForAKeyNarrowedName() {
			final ReferencesStoragePart narrowed = readTwoPartsUnderCoverage(
				writeTwoParts(),
				ReferenceDecodeCoverage.of(Set.of(), Map.of(CATEGORY, new int[]{201}))
			)[0];

			// category 200 was SKIPPED, not found missing - reading the difference as absence is the whole hazard
			assertThrows(GenericEvitaInternalError.class, () -> narrowed.getReferencedIds(CATEGORY));
			assertThrows(GenericEvitaInternalError.class, () -> narrowed.contains(new ReferenceKey(CATEGORY, 200)));
			assertThrows(GenericEvitaInternalError.class, narrowed::isEmpty);
		}

		@Test
		@DisplayName("refuses to be written back to the storage")
		void shouldRefuseToSerializeKeyNarrowedPart() {
			final ReferencesStoragePart narrowed = readTwoPartsUnderCoverage(
				writeTwoParts(),
				ReferenceDecodeCoverage.of(Set.of(), Map.of(CATEGORY, new int[]{201}))
			)[0];

			assertThrows(GenericEvitaInternalError.class, () -> serialize(narrowed));
		}

		@Test
		@DisplayName("a narrowed part still reports the size of the whole record")
		void shouldReportFullRecordSizeForKeyNarrowedPart() {
			final byte[] serialized = writeTwoParts();

			final OptionalInt completeSize = readTwoParts(serialized, null)[0].sizeInBytes();
			final OptionalInt narrowedSize = readTwoPartsUnderCoverage(
				serialized, ReferenceDecodeCoverage.of(Set.of(), Map.of(CATEGORY, new int[]{201}))
			)[0].sizeInBytes();

			assertTrue(completeSize.isPresent());
			assertTrue(narrowedSize.isPresent());
			assertEquals(completeSize.getAsInt(), narrowedSize.getAsInt());
		}

		@Test
		@DisplayName("a record in the previous layout comes back complete even under a key coverage")
		void shouldFullyDecodeLegacyRecordUnderPkCoverage() {
			// the previous layout assigns internal primary keys by position, so skipping ANY reference - by name or
			// by key - shifts every key after it. Such a record must report itself complete, exactly as it does
			// under a name-only coverage.
			final ReferencesStoragePart[] parts = readTwoPartsUnderCoverage(
				writeLegacyFirstPart(),
				ReferenceDecodeCoverage.of(Set.of(), Map.of(CATEGORY, new int[]{201}))
			);

			assertTrue(parts[0].isComplete());
			assertNull(parts[0].getDecodeCoverage());
			assertArrayEquals(
				new String[]{BRAND, CATEGORY, CATEGORY, PARAMETER, PARAMETER},
				referenceNamesOf(parts[0])
			);
			// every guarded question is answerable again, including the ones the key coverage would have refused
			assertArrayEquals(new int[]{200, 201}, parts[0].getReferencedIds(CATEGORY));
			assertFalse(parts[0].isEmpty());
		}

		@Test
		@DisplayName("an unrestricted read nested inside a key-narrowed one leaves the narrowing intact")
		void shouldRestoreOuterKeyCoverageAfterNestedUnrestrictedRead() {
			final byte[] serialized = writeParts(firstPart(), secondPart(), firstPart());
			final ReferenceDecodeCoverage coverage =
				ReferenceDecodeCoverage.of(Set.of(), Map.of(CATEGORY, new int[]{201}));

			final ReferencesStoragePart[] parts = readWithSchema(
				serialized,
				input -> ReferenceDecodeCoverageContext.executeWithCoverage(
					coverage,
					() -> {
						final ReferencesStoragePart narrowed = readPart(input);
						final ReferencesStoragePart complete =
							ReferenceDecodeCoverageContext.executeWithCoverage(null, () -> readPart(input));
						final ReferencesStoragePart narrowedAgain = readPart(input);
						return new ReferencesStoragePart[]{narrowed, complete, narrowedAgain};
					}
				)
			);

			assertArrayEquals(new int[]{201}, referencedIdsOf(parts[0]));
			assertTrue(parts[1].isComplete());
			assertFalse(parts[2].isComplete());
			assertArrayEquals(new int[]{201}, referencedIdsOf(parts[2]));
			assertEquals(coverage, parts[2].getDecodeCoverage());
		}
	}

	/**
	 * Writes the historical UID and the unchanged payload without binary fixtures or byte patching.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static class LegacyAttributeValueWriter extends Serializer<AttributeValue> {
		/** Current payload writer shared by the unchanged historical attribute layout. */
		private final AttributeValueSerializer payloadWriter;

		/** Creates the legacy writer sharing the real compressor used by the current writer. */
		private LegacyAttributeValueWriter(@Nonnull ReadWriteKeyCompressor keyCompressor) {
			this.payloadWriter = new AttributeValueSerializer(keyCompressor);
		}

		/** Writes the historical discriminator and its unchanged attribute payload. */
		@Override
		public void write(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull AttributeValue value) {
			output.writeLong(AttributeValueSerializer_2026_2Test.LEGACY_ATTRIBUTE_VALUE_UID);
			this.payloadWriter.write(kryo, output, value);
		}

		/** This writer is replaced with production readers before any decoding. */
		@Nonnull
		@Override
		public AttributeValue read(@Nonnull Kryo kryo, @Nonnull Input input, @Nonnull Class<? extends AttributeValue> type) {
			throw new UnsupportedOperationException("writer only");
		}
	}

}
