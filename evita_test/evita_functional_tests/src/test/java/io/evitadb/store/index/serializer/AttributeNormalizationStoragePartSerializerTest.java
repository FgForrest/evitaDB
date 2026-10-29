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


package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.index.analysis.AttributeNormalizationPolicy;
import io.evitadb.index.analysis.AttributeNormalizationPolicy.ElementBinding;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeNormalizationStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalAttributeNormalizationStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.lowercase;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.removeCharacters;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip and byte-stability coverage for {@link AttributeNormalizationStoragePartSerializer} and
 * {@link GlobalAttributeNormalizationStoragePartSerializer}. Both sort identities before writing, so equal parts
 * produce equal bytes whatever order their maps iterate in.
 */
@DisplayName("Normalization storage part serializers")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class AttributeNormalizationStoragePartSerializerTest {
	private static final AttributeIndexKey CODE = new AttributeIndexKey(null, "code", null);
	private static final AttributeIndexKey COMPOUND = new AttributeIndexKey(null, "compound", null);

	private Kryo kryo;

	@BeforeEach
	void setUp() {
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(new ReadWriteKeyCompressor(Map.of())));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("The two singleton parts round-trip through appended production Kryo registrations")
	void shouldPersistDescriptionsAndCompoundBindings(boolean populated) {
		final List<AnalysisStep> steps = populated ? List.of(lowercase(), removeCharacters("- ")) : List.of();
		final AttributeNormalizationPolicy policy = new AttributeNormalizationPolicy(Map.of(CODE, steps),
			Map.of(COMPOUND, List.of(new ElementBinding("code", String.class, false, false),
				new ElementBinding("global", String.class, true, true))), Set.of("global"));
		assertEquals(645, this.kryo.getRegistration(AttributeNormalizationStoragePart.class).getId());
		assertEquals(646, this.kryo.getRegistration(GlobalAttributeNormalizationStoragePart.class).getId());
		try (final Output output = new Output(4096, -1)) {
			this.kryo.writeObject(output, new AttributeNormalizationStoragePart(policy));
			this.kryo.writeObject(output, new GlobalAttributeNormalizationStoragePart(Map.of("global", steps)));
			try (final Input input = new Input(output.toBytes())) {
				assertEquals(policy, this.kryo.readObject(input, AttributeNormalizationStoragePart.class).policy());
				assertEquals(populated ? Map.of("global", steps) : Map.of(),
					this.kryo.readObject(input, GlobalAttributeNormalizationStoragePart.class).rows());
				assertEquals(output.position(), input.position());
			}
		}
	}

	@Test
	@DisplayName("Equal parts write identical bytes whatever order their maps were filled in")
	void shouldWriteIdenticalBytesRegardlessOfMapInsertionOrder() {
		final List<String> names = IntStream.range(0, 20).mapToObj(index -> "attribute" + index).toList();

		final byte[] policyBytes = write(new AttributeNormalizationStoragePart(policyOf(names)));
		final byte[] reversedPolicyBytes = write(new AttributeNormalizationStoragePart(policyOf(names.reversed())));
		final byte[] globalBytes = write(new GlobalAttributeNormalizationStoragePart(globalRowsOf(names)));
		final byte[] reversedGlobalBytes = write(
			new GlobalAttributeNormalizationStoragePart(globalRowsOf(names.reversed())));

		assertArrayEquals(policyBytes, reversedPolicyBytes);
		assertArrayEquals(globalBytes, reversedGlobalBytes);
	}

	@Test
	@DisplayName("Many rows, reference-scoped identities included, round-trip unchanged")
	void shouldRoundTripPolicyWithManyRowsAndLocaleFreeKeys() {
		final Map<AttributeIndexKey, List<AnalysisStep>> rows = new LinkedHashMap<>(
			policyOf(IntStream.range(0, 20).mapToObj(index -> "attribute" + index).toList()).rows());
		rows.put(new AttributeIndexKey("brand", "tags", null), List.of(lowercase()));
		final AttributeNormalizationPolicy policy = new AttributeNormalizationPolicy(rows,
			Map.of(new AttributeIndexKey("brand", "compound", null), List.of(
				new ElementBinding("tags", String.class, true, false))), Set.of("global"));

		final AttributeNormalizationStoragePart read = read(
			write(new AttributeNormalizationStoragePart(policy)), AttributeNormalizationStoragePart.class);

		assertEquals(policy, read.policy());
	}

	/**
	 * Serializes the part with the production registration.
	 *
	 * @param part storage part to serialize
	 * @return bytes of the serialized part
	 */
	@Nonnull
	private byte[] write(@Nonnull Object part) {
		try (final Output output = new Output(4096, -1)) {
			this.kryo.writeObject(output, part);
			return output.toBytes();
		}
	}

	/**
	 * Reads one part back and checks that it consumed exactly the written bytes.
	 *
	 * @param bytes serialized part, as produced by `write`
	 * @param type storage part type to deserialize
	 * @return the deserialized part
	 */
	@Nonnull
	private <T> T read(@Nonnull byte[] bytes, @Nonnull Class<T> type) {
		try (final Input input = new Input(bytes)) {
			final T part = this.kryo.readObject(input, type);
			assertEquals(bytes.length, input.position());
			return part;
		}
	}

	/**
	 * Builds a policy whose rows, compounds and global names are inserted in the order of `names`.
	 *
	 * @param names attribute names, in the insertion order of the rows, compounds and global names
	 * @return policy with one row, one compound and one global name per attribute name
	 */
	@Nonnull
	private static AttributeNormalizationPolicy policyOf(@Nonnull List<String> names) {
		final Map<AttributeIndexKey, List<AnalysisStep>> rows = new LinkedHashMap<>();
		final Map<AttributeIndexKey, List<ElementBinding>> compounds = new LinkedHashMap<>();
		final Set<String> globals = new LinkedHashSet<>();
		for (final String name : names) {
			rows.put(new AttributeIndexKey(null, name, null), List.of(lowercase(), removeCharacters(name)));
			compounds.put(new AttributeIndexKey(null, name + "Compound", null),
				List.of(new ElementBinding(name, String.class, false, false)));
			globals.add(name + "Global");
		}
		return new AttributeNormalizationPolicy(rows, compounds, globals);
	}

	/**
	 * Builds catalog rows inserted in the order of `names`.
	 *
	 * @param names attribute names, in the insertion order of the rows
	 * @return catalog normalization rows keyed by attribute name
	 */
	@Nonnull
	private static Map<String, List<AnalysisStep>> globalRowsOf(@Nonnull List<String> names) {
		final Map<String, List<AnalysisStep>> rows = new LinkedHashMap<>();
		for (final String name : names) {
			rows.put(name, List.of(lowercase(), removeCharacters(name)));
		}
		return rows;
	}
}
