/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.store.service;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.serializers.DefaultArraySerializers.BooleanArraySerializer;
import com.esotericsoftware.kryo.serializers.DefaultArraySerializers.ByteArraySerializer;
import com.esotericsoftware.kryo.serializers.DefaultArraySerializers.CharArraySerializer;
import com.esotericsoftware.kryo.serializers.DefaultArraySerializers.IntArraySerializer;
import com.esotericsoftware.kryo.serializers.DefaultArraySerializers.LongArraySerializer;
import com.esotericsoftware.kryo.serializers.DefaultArraySerializers.ShortArraySerializer;
import com.esotericsoftware.kryo.serializers.DefaultArraySerializers.StringArraySerializer;
import com.esotericsoftware.kryo.serializers.DefaultSerializers.EnumSetSerializer;
import com.esotericsoftware.kryo.serializers.DefaultSerializers.*;
import com.esotericsoftware.kryo.serializers.TimeSerializers.InstantSerializer;
import com.esotericsoftware.kryo.serializers.TimeSerializers.LocalDateSerializer;
import com.esotericsoftware.kryo.serializers.TimeSerializers.LocalDateTimeSerializer;
import com.esotericsoftware.kryo.serializers.TimeSerializers.LocalTimeSerializer;
import com.esotericsoftware.kryo.serializers.TimeSerializers.OffsetDateTimeSerializer;
import com.esotericsoftware.kryo.util.DefaultClassResolver;
import io.evitadb.dataType.*;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import io.evitadb.dataType.trie.Trie;
import io.evitadb.dataType.trie.TrieNode;
import io.evitadb.store.dataType.serializer.*;
import io.evitadb.store.dataType.serializer.data.DataItemArraySerializer;
import io.evitadb.store.dataType.serializer.data.DataItemMapSerializer;
import io.evitadb.store.dataType.serializer.data.DataItemValueSerializer;
import io.evitadb.store.dataType.serializer.trie.TrieNodeSerializer;
import io.evitadb.store.dataType.serializer.trie.TrieSerializer;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Consumer;

/**
 * KryoFactory class encapsulates {@link Kryo} instantiation and configuration for particular tasks. Kryo library was
 * chosen as a serialization engine because it represents currently the most performant way of (de)serialization of
 * Java projects.
 *
 * See:
 * - https://aaltodoc.aalto.fi/bitstream/handle/123456789/39869/master_Hagberg_Henri_2019.pdf?sequence=1&isAllowed=y
 * - https://www.javacodegeeks.com/2013/09/optimizing-java-serialization-java-vs-xml-vs-json-vs-kryo-vs-pof.html
 * - https://www.slideshare.net/Strannik_2013/serialization-and-performance-in-java
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class KryoFactory {
	private static final int BASE_START = 100;

	static {
		System.setProperty("kryo.unsafe", "false");
	}

	private KryoFactory() {
	}

	/**
	 * Method creates default Kryo instance ({@link #createKryo()} and created instance hands to passed consumer
	 * implementation.
	 */
	@Nonnull
	public static Kryo createKryo(@Nonnull Consumer<Kryo> andThen) {
		final Kryo kryo = createKryo();
		andThen.accept(kryo);
		return kryo;
	}

	/**
	 * Method creates default Kryo instance with all default serializers registered. This instance of the Kryo
	 * should be able to (de)serialize all {@link EvitaDataTypes#getSupportedDataTypes()} data types.
	 */
	@Nonnull
	public static Kryo createKryo() {
		return initializeKryo(
			new Kryo(new DefaultClassResolver(), null)
		);
	}

	/**
	 * Method creates default Kryo instance with all default serializers registered. This instance of the Kryo
	 * should be able to (de)serialize all {@link EvitaDataTypes#getSupportedDataTypes()} data types.
	 */
	@Nonnull
	@SuppressWarnings("RedundantUnmodifiable")
	public static <T extends Kryo> T initializeKryo(@Nonnull T kryoInstance) {
		kryoInstance.setRegistrationRequired(true);
		kryoInstance.setDefaultSerializer(DefaultSerializer.class);
		kryoInstance.setReferences(false);
		int index = BASE_START;
		kryoInstance.register(Class.class, new ClassSerializer(), index++);
		kryoInstance.register(Serializable.class, new SerializableSerializer(), index++);
		kryoInstance.register(Serializable[].class, new SerializableArraySerializer(), index++);
		kryoInstance.register(String.class, new StringSerializer(), index++);
		kryoInstance.register(String[].class, new StringArraySerializer(), index++);
		kryoInstance.register(Byte.class, new ByteSerializer(), index++);
		kryoInstance.register(byte[].class, new ByteArraySerializer(), index++);
		kryoInstance.register(Byte[].class, new GenericArraySerializer<>(Byte.class), index++);
		kryoInstance.register(Short.class, new ShortSerializer(), index++);
		kryoInstance.register(short[].class, new ShortArraySerializer(), index++);
		kryoInstance.register(Short[].class, new GenericArraySerializer<>(Short.class), index++);
		kryoInstance.register(Integer.class, new IntSerializer(), index++);
		kryoInstance.register(int[].class, new IntArraySerializer(), index++);
		kryoInstance.register(Integer[].class, new GenericArraySerializer<>(Integer.class), index++);
		kryoInstance.register(Long.class, new LongSerializer(), index++);
		kryoInstance.register(long[].class, new LongArraySerializer(), index++);
		kryoInstance.register(Long[].class, new GenericArraySerializer<>(Long.class), index++);
		kryoInstance.register(Boolean.class, new BooleanSerializer(), index++);
		kryoInstance.register(boolean[].class, new BooleanArraySerializer(), index++);
		kryoInstance.register(Boolean[].class, new GenericArraySerializer<>(Boolean.class), index++);
		kryoInstance.register(Character.class, new CharSerializer(), index++);
		kryoInstance.register(char[].class, new CharArraySerializer(), index++);
		kryoInstance.register(Character[].class, new GenericArraySerializer<>(Character.class), index++);
		kryoInstance.register(BigDecimal.class, new BigDecimalSerializer(), index++);
		kryoInstance.register(BigDecimal[].class, new GenericArraySerializer<>(BigDecimal.class), index++);
		kryoInstance.register(OffsetDateTime.class, new OffsetDateTimeSerializer(), index++);
		kryoInstance.register(OffsetDateTime[].class, new GenericArraySerializer<>(OffsetDateTime.class), index++);
		kryoInstance.register(LocalDateTime.class, new LocalDateTimeSerializer(), index++);
		kryoInstance.register(LocalDateTime[].class, new GenericArraySerializer<>(LocalDateTime.class), index++);
		kryoInstance.register(LocalDate.class, new LocalDateSerializer(), index++);
		kryoInstance.register(LocalDate[].class, new GenericArraySerializer<>(LocalDate.class), index++);
		kryoInstance.register(LocalTime.class, new LocalTimeSerializer(), index++);
		kryoInstance.register(LocalTime[].class, new GenericArraySerializer<>(LocalTime.class), index++);
		kryoInstance.register(DateTimeRange.class, new DateTimeRangeSerializer(), index++);
		kryoInstance.register(DateTimeRange[].class, new GenericArraySerializer<>(DateTimeRange.class), index++);
		kryoInstance.register(BigDecimalNumberRange.class, new BigDecimalNumberRangeSerializer(), index++);
		kryoInstance.register(BigDecimalNumberRange[].class, new GenericArraySerializer<>(BigDecimalNumberRange.class), index++);
		kryoInstance.register(LongNumberRange.class, new LongNumberRangeSerializer(), index++);
		kryoInstance.register(LongNumberRange[].class, new GenericArraySerializer<>(LongNumberRange.class), index++);
		kryoInstance.register(IntegerNumberRange.class, new IntegerNumberRangeSerializer(), index++);
		kryoInstance.register(IntegerNumberRange[].class, new GenericArraySerializer<>(IntegerNumberRange.class), index++);
		kryoInstance.register(ShortNumberRange.class, new ShortNumberRangeSerializer(), index++);
		kryoInstance.register(ShortNumberRange[].class, new GenericArraySerializer<>(ShortNumberRange.class), index++);
		kryoInstance.register(ByteNumberRange.class, new ByteNumberRangeSerializer(), index++);
		kryoInstance.register(ByteNumberRange[].class, new GenericArraySerializer<>(ByteNumberRange.class), index++);
		kryoInstance.register(Locale.class, new LocaleSerializer(), index++);
		kryoInstance.register(Locale[].class, new GenericArraySerializer<>(Locale.class), index++);
		kryoInstance.register(EnumSet.class, new EnumSetSerializer(), index++);
		kryoInstance.register(Currency.class, new CurrencySerializer(), index++);
		kryoInstance.register(Currency[].class, new GenericArraySerializer<>(Currency.class), index++);
		kryoInstance.register(UUID.class, new UuidSerializer(), index++);
		kryoInstance.register(UUID[].class, new GenericArraySerializer<>(UUID.class), index++);
		kryoInstance.register(Predecessor.class, new PredecessorSerializer(), index++);
		kryoInstance.register(ComplexDataObject.class, new ComplexDataObjectSerializer(), index++);
		kryoInstance.register(ComplexDataObject[].class, new GenericArraySerializer<>(ComplexDataObject.class), index++);
		kryoInstance.register(Set.class, new SetSerializer<>(count -> new HashSet<>((int) Math.ceil(count / .75f), .75f)), index++);
		kryoInstance.register(HashSet.class, new SetSerializer<>(count -> new HashSet<>((int) Math.ceil(count / .75f), .75f)), index++);
		kryoInstance.register(LinkedHashSet.class, new SetSerializer<>(count -> new LinkedHashSet<>((int) Math.ceil(count / .75f), .75f)), index++);
		kryoInstance.register(Map.class, new MapSerializer<>(count -> new HashMap<>((int) Math.ceil(count / .75f), .75f)), index++);
		kryoInstance.register(HashMap.class, new MapSerializer<>(count -> new HashMap<>((int) Math.ceil(count / .75f), .75f)), index++);
		kryoInstance.register(LinkedHashMap.class, new MapSerializer<>(count -> new LinkedHashMap<>((int) Math.ceil(count / .75f), .75f)), index++);
		kryoInstance.register(DataItemValue.class, new DataItemValueSerializer(), index++);
		kryoInstance.register(DataItemArray.class, new DataItemArraySerializer(), index++);
		kryoInstance.register(DataItemMap.class, new DataItemMapSerializer(), index++);
		kryoInstance.register(Trie.class, new TrieSerializer<>(), index++);
		kryoInstance.register(TrieNode.class, new TrieNodeSerializer<>(), index++);
		kryoInstance.register(OffsetDateTime.class, new OffsetDateTimeSerializer(), index++);
		kryoInstance.register(Collections.emptyList().getClass(), new CollectionsEmptyListSerializer(), index++);
		kryoInstance.register(Collections.unmodifiableList(Collections.EMPTY_LIST).getClass(), new ListSerializer<>(count -> Collections.unmodifiableList(new ArrayList<>(count))), index++);
		kryoInstance.register(Collections.emptyMap().getClass(), new CollectionsEmptyMapSerializer(), index++);
		kryoInstance.register(Collections.unmodifiableMap(Collections.EMPTY_MAP).getClass(), new MapSerializer<>(count -> Collections.unmodifiableMap(new HashMap<>((int) Math.ceil(count / .75f), .75f))), index++);
		kryoInstance.register(Collections.emptySet().getClass(), new CollectionsEmptySetSerializer(), index++);
		kryoInstance.register(Collections.unmodifiableSet(Collections.EMPTY_SET).getClass(), new SetSerializer<>(count -> Collections.unmodifiableSet(new HashSet<>((int) Math.ceil(count / .75f), .75f))), index++);
		kryoInstance.register(Instant.class, new InstantSerializer(), index++);
		kryoInstance.register(ReferencedEntityPredecessor.class, new ReferencedEntityPredecessorSerializer(), index++);
		Assert.isPremiseValid(index < 200, "Index count overflow.");
		return kryoInstance;
	}

}
