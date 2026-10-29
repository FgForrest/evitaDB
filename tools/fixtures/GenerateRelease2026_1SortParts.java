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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates the small sort-part resources with the actual 2026.1.20 writer and verifies their released round trip.
 * Compile and run against the released jars, as described beside the resources; never run with the current writer.
 */
public final class GenerateRelease2026_1SortParts {
	/** Utility entry point only. */
	private GenerateRelease2026_1SortParts() {
	}

	/**
	 * Writes four released parts, including sparse entries deliberately opposite to the distinct-value order.
	 *
	 * @param args the destination resource directory
	 * @throws IOException when a fixture cannot be written
	 */
	public static void main(@Nonnull String[] args) throws IOException {
		final Path destination = Path.of(args[0]);
		Files.createDirectories(destination);
		final DateTimeRange one = DateTimeRange.until(OffsetDateTime.parse("2026-01-01T10:00:00+01:00"));
		final DateTimeRange two = DateTimeRange.until(OffsetDateTime.parse("2026-01-01T11:00:00+02:00"));
		if (one.equals(two) || two.compareTo(one) >= 0) {
			throw new IllegalStateException("These fixtures require the released 2026.1 range semantics.");
		}
		final ComparatorSource[] scalar = {
			new ComparatorSource(DateTimeRange.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
		};
		final Map<Serializable, Integer> reversed = new LinkedHashMap<>();
		reversed.put(one, 3);
		reversed.put(two, 2);
		write(destination, "unequal", scalar, new Serializable[]{two, one}, reversed, new int[]{1, 2, 3, 4, 5});
		write(
			destination, "singleton", scalar, new Serializable[]{two, one}, Map.of(one, 3),
			new int[]{10, 20, 21, 22}
		);
		final ComparableArray nullElement = new ComparableArray(new Serializable[]{null, 1});
		final ComparableArray compoundTwo = new ComparableArray(new Serializable[]{two, 1});
		final ComparableArray compoundOne = new ComparableArray(new Serializable[]{one, 1});
		final Map<Serializable, Integer> compoundCardinalities = new LinkedHashMap<>();
		compoundCardinalities.put(compoundOne, 3);
		compoundCardinalities.put(nullElement, 2);
		write(
			destination, "compound",
			new ComparatorSource[]{
				new ComparatorSource(DateTimeRange.class, OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
				new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			},
			new Serializable[]{nullElement, compoundTwo, compoundOne}, compoundCardinalities,
			new int[]{4, 5, 7, 3, 9, 12}
		);
		final DateTimeRange later = DateTimeRange.until(OffsetDateTime.parse("2027-01-01T11:00:00+02:00"));
		final Map<Serializable, Integer> controlCardinalities = new LinkedHashMap<>();
		controlCardinalities.put(later, 3);
		controlCardinalities.put(two, 2);
		write(
			destination, "control", scalar, new Serializable[]{two, later}, controlCardinalities,
			new int[]{1, 2, 3, 4, 5}
		);
	}

	/**
	 * Serializes a real released part, verifies its records and cardinalities with the same engine, and saves it.
	 *
	 * @param destination the output directory
	 * @param name the fixture name
	 * @param comparators the released comparators
	 * @param values the distinct values in block order
	 * @param cardinalities the sparse map in its deliberately chosen iteration order
	 * @param records the exact record column
	 * @throws IOException when a fixture cannot be written
	 */
	private static void write(
		@Nonnull Path destination,
		@Nonnull String name,
		@Nonnull ComparatorSource[] comparators,
		@Nonnull Serializable[] values,
		@Nonnull Map<Serializable, Integer> cardinalities,
		@Nonnull int[] records
	) throws IOException {
		final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		final Kryo kryo = KryoFactory.createKryo(
			SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE)
				.andThen(SchemaKryoConfigurer.INSTANCE).andThen(new IndexStoragePartConfigurer(compressor))
				.andThen(new EntityStoragePartConfigurer(compressor))
		);
		final SortIndexStoragePart part = new SortIndexStoragePart(
			1, new AttributeIndexKey(null, "validity", null), comparators, records, values, cardinalities
		);
		part.setStoragePartPK(7L);
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (final Output output = new Output(bytes, 1024)) {
			kryo.writeObject(output, part);
		}
		final SortIndexStoragePart decoded = kryo.readObject(
			new Input(bytes.toByteArray()), SortIndexStoragePart.class
		);
		if (!Arrays.equals(records, decoded.getSortedRecords()) ||
			!cardinalities.equals(decoded.getValueCardinalities())) {
			throw new IllegalStateException("The released round trip changed " + name);
		}
		Files.write(destination.resolve(name + ".bin"), bytes.toByteArray());
		System.out.println("2026.1.20 " + name + ": records=" + Arrays.toString(records) +
			" sparse blocks=" + decoded.getValueCardinalities().values());
	}
}
