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

package io.evitadb.store.dataType.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.store.dataType.serializer.trie.TrieSerializer;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.utils.ReflectionLookup;
import lombok.Data;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies contract of {@link ComplexDataObjectSerializer} and {@link TrieSerializer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ComplexDataObjectSerializerTest {
	private final ReflectionLookup reflectionLookup = new ReflectionLookup(ReflectionCachingBehaviour.NO_CACHE);

	@Test
	void shouldSerializeAndDeserializeJavaClass() {
		final Map<String, Integer> integerMap = new HashMap<>();
		integerMap.put("A", 89);
		integerMap.put("B", 93);
		final SomeDto dtoToStore = new SomeDto(
			1,
			Arrays.asList("abc", "def"),
			integerMap,
			new Long[]{7L, 9L, 10L}
		);

		final ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
		final Kryo kryo = KryoFactory.createKryo();
		final Serializable serializableForm = ComplexDataObjectConverter.getSerializableForm(dtoToStore);
		try (final ByteBufferOutput output = new ByteBufferOutput(baos)) {
			kryo.writeObject(output, serializableForm);
		}

		final byte[] serializedSchema = baos.toByteArray();
		assertNotNull(serializedSchema);
		assertTrue(serializedSchema.length > 0);

		final ComplexDataObject deserializedForm;
		try (final ByteBufferInput input = new ByteBufferInput(serializedSchema)) {
			deserializedForm = kryo.readObject(input, ComplexDataObject.class);
		}
		final SomeDto deserializedDto = ComplexDataObjectConverter.getOriginalForm(deserializedForm, SomeDto.class, this.reflectionLookup);

		assertEquals(dtoToStore, deserializedDto);
	}

	@Test
	void shouldSerializeAndDeserializeJavaRecord() {
		final Map<String, Integer> integerMap = new HashMap<>();
		integerMap.put("A", 89);
		integerMap.put("B", 93);
		final SomeDtoRecord dtoToStore = new SomeDtoRecord(
			1,
			Arrays.asList("abc", "def"),
			integerMap,
			new Long[]{7L, 9L, 10L}
		);

		final ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
		final Kryo kryo = KryoFactory.createKryo();
		final Serializable serializableForm = ComplexDataObjectConverter.getSerializableForm(dtoToStore);
		try (final ByteBufferOutput output = new ByteBufferOutput(baos)) {
			kryo.writeObject(output, serializableForm);
		}

		final byte[] serializedSchema = baos.toByteArray();
		assertNotNull(serializedSchema);
		assertTrue(serializedSchema.length > 0);

		final ComplexDataObject deserializedForm;
		try (final ByteBufferInput input = new ByteBufferInput(serializedSchema)) {
			deserializedForm = kryo.readObject(input, ComplexDataObject.class);
		}
		final SomeDtoRecord deserializedDto = ComplexDataObjectConverter.getOriginalForm(deserializedForm, SomeDtoRecord.class, this.reflectionLookup);

		assertEquals(dtoToStore, deserializedDto);
	}

	@Data
	public static class SomeDto implements Serializable {
		@Serial private static final long serialVersionUID = -2733408526337234240L;
		private final int id;
		private final List<String> name;
		private final Map<String, Integer> counts;
		private final Long[] numbers;

	}

	public record SomeDtoRecord(
		int id, List<String> name, Map<String, Integer> counts, Long[] numbers
	) implements Serializable {
		@Serial private static final long serialVersionUID = -2733408526337234240L;

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SomeDtoRecord that = (SomeDtoRecord) o;
			return this.id == that.id && Objects.equals(this.name, that.name) && Objects.equals(this.counts, that.counts) && Arrays.equals(this.numbers, that.numbers);
		}

		@Override
		public int hashCode() {
			int result = Objects.hash(this.id, this.name, this.counts);
			result = 31 * result + Arrays.hashCode(this.numbers);
			return result;
		}

		@Override
		public String toString() {
			return "SomeDtoRecord{" +
				"id=" + this.id +
				", name=" + this.name +
				", counts=" + this.counts +
				", numbers=" + Arrays.toString(this.numbers) +
				'}';
		}
	}

}
