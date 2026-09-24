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

package io.evitadb.spike;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.function.Functions;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.entity.serializer.EnumNameSerializer;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.index.SharedIndexStoragePartConfigurer;
import io.evitadb.store.index.serializer.SortIndexLeafPagePartSerializer;
import io.evitadb.store.index.serializer.SortIndexStoragePartSerializer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.OffsetIndex.NonFlushedBlock;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.OffsetIndexSerializationService.FileLocationAndWrittenBytes;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.store.shared.kryo.VersionedKryoFactory;
import io.evitadb.utils.IOUtils;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Shared, deterministic building blocks for the OWNER-mode {@link OwnerSortIndex} granular-persistence measurements on
 * branch #760, reused by both the plain-`main` report ({@link SortIndexChurnReport}) and the JMH timing benchmark
 * ({@link SortIndexTimingBenchmark}). All logic is centralized here so a sibling `dev`-branch mirror can be lined up
 * cell-for-cell: parsing the real anchor, generating shape-replicating synthetic distributions, building the owner,
 * draining its full / incremental emissions, serializing parts directly through their Kryo serializers, and running the
 * real {@link OffsetIndex} persist + reload that restores the page-stream change-detection baseline (the steady state
 * required for a correct incremental-commit measurement).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class SortIndexBenchSupport {

	/**
	 * The real production-catalog ean anchor exported by {@link SortAnchorExtractor}.
	 */
	static final Path ANCHOR_FILE = Path.of("/var/tmp/catalog-bench/sort-anchor.txt");
	/**
	 * The owning entity index primary key (mirrors the round-trip test fixture so stream ids line up).
	 */
	static final int ENTITY_INDEX_PK = 7;
	/**
	 * Entity type of the persistence fixture.
	 */
	static final String ENTITY_TYPE = "product";
	/**
	 * Indexed attribute identity (single scalar attribute, no locale, no reference).
	 */
	static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "code", null);
	/**
	 * The catalog version every persisted record is written under.
	 */
	static final long PERSISTED_VERSION = 1L;
	private static final Consumer<NonFlushedBlock> NO_OP_NON_FLUSHED_BLOCK_CALLBACK = Functions.noOpConsumer();
	private static final Consumer<Optional<OffsetDateTime>> NO_OP_OLDEST_RECORD_CALLBACK = Functions.noOpConsumer();

	private SortIndexBenchSupport() {
	}

	/**
	 * One distinct sort value together with its ascending block of owning record ids.
	 *
	 * @param value     the (typed) distinct sort value
	 * @param recordIds the ascending record ids that carry this value
	 */
	record ValueBlock(@Nonnull Serializable value, @Nonnull int[] recordIds) {
	}

	/*
		SCENARIO CONSTRUCTION
	 */

	/**
	 * Returns the value blocks for the named scenario: `anchor` (real ean distribution), `synth_<n>` (synthetic shape
	 * replica at N distinct values, e.g. `synth_10k`, `synth_100k`, `synth_1m`), or
	 * `uniform_<distinctValues>_<recordCount>` (evenly-sized blocks, e.g. `uniform_1k_1m`, `uniform_1k_10m`).
	 */
	@Nonnull
	static List<ValueBlock> blocksFor(@Nonnull String scenario) {
		try {
			if ("anchor".equals(scenario)) {
				return parseAnchor(ANCHOR_FILE);
			}
			if (scenario.startsWith("synth_")) {
				return synthetic(parseScenarioCount(scenario.substring("synth_".length())));
			}
			if (scenario.startsWith("uniform_")) {
				final String[] parts = scenario.substring("uniform_".length()).split("_");
				if (parts.length != 2) {
					throw new IllegalArgumentException(
						"Uniform scenario must be `uniform_<distinctValues>_<recordCount>`, got: " + scenario
					);
				}
				return uniform(parseScenarioCount(parts[0]), parseScenarioCount(parts[1]));
			}
			throw new IllegalArgumentException("Unknown scenario: " + scenario);
		} catch (Exception ex) {
			throw new IllegalStateException("Failed to build blocks for scenario '" + scenario + "': " + ex.getMessage(), ex);
		}
	}

	/**
	 * Parses a synthetic scenario suffix such as `10k`, `100k` or `1m` into its distinct-value count.
	 */
	static int parseScenarioCount(@Nonnull String suffix) {
		final String lower = suffix.toLowerCase();
		if (lower.endsWith("m")) {
			return Integer.parseInt(lower.substring(0, lower.length() - 1)) * 1_000_000;
		}
		if (lower.endsWith("k")) {
			return Integer.parseInt(lower.substring(0, lower.length() - 1)) * 1_000;
		}
		return Integer.parseInt(lower);
	}

	/**
	 * Generates a synthetic distribution replicating the anchor's cardinality SHAPE at `distinctValues` distinct values:
	 * one big bucket of size ~0.3*N (mimicking the empty-EAN block) plus the rest singletons, so R ~= 1.3*N. Values are
	 * zero-padded 13-char digit strings (EAN-like), record ids assigned sequentially and uniquely.
	 *
	 * @param distinctValues the number of distinct values N
	 * @return the synthetic blocks in ascending value order
	 */
	@Nonnull
	static List<ValueBlock> synthetic(int distinctValues) {
		final int bigBucketSize = Math.round(0.3f * distinctValues);
		final List<ValueBlock> blocks = new ArrayList<>(distinctValues);
		int nextRecordId = 1;
		// the big bucket sits first in ascending order (value "0000000000000")
		final int[] bigBucket = new int[bigBucketSize];
		for (int i = 0; i < bigBucketSize; i++) {
			bigBucket[i] = nextRecordId++;
		}
		blocks.add(new ValueBlock(String.format("%013d", 0), bigBucket));
		// the remaining N-1 values are singletons
		for (int v = 1; v < distinctValues; v++) {
			blocks.add(new ValueBlock(String.format("%013d", v), new int[]{nextRecordId++}));
		}
		return blocks;
	}

	/**
	 * Generates a UNIFORM distribution: `distinctValues` values, each owning a block of `recordCount / distinctValues`
	 * records (the leading blocks absorb the division remainder, so widths stay within one of each other).
	 *
	 * This is the low-cardinality e-commerce attribute shape — rating 1-5, stock level, discount band, brand id,
	 * priority, availability flag — which {@link #synthetic(int)} deliberately does NOT produce. `synthetic` emits one
	 * `0.3 * N` bucket plus `N - 1` singletons, so ~99.999 % of its inserts land in a width-1 block, take the
	 * single-probe `else` branch of `SortIndexChanges.computePreviousRecord`, and never enter the block binary search
	 * at all — the branch that dominates the WARM_UP profile behind issue #1332.
	 *
	 * @param distinctValues the number of distinct values
	 * @param recordCount    the total number of records spread evenly across them
	 * @return the uniform blocks in ascending value order
	 */
	@Nonnull
	static List<ValueBlock> uniform(int distinctValues, int recordCount) {
		final int baseWidth = recordCount / distinctValues;
		final int remainder = recordCount % distinctValues;
		final List<ValueBlock> blocks = new ArrayList<>(distinctValues);
		int nextRecordId = 1;
		for (int v = 0; v < distinctValues; v++) {
			final int width = baseWidth + (v < remainder ? 1 : 0);
			final int[] recordIds = new int[width];
			for (int i = 0; i < width; i++) {
				recordIds[i] = nextRecordId++;
			}
			blocks.add(new ValueBlock(String.format("%013d", v), recordIds));
		}
		return blocks;
	}

	/**
	 * Parses the anchor file into typed value blocks. Header lines (`#key=value`) drive the value type; each subsequent
	 * line is `<base64(UTF-8 value)>\t<id1>,<id2>,...` in ascending value order (the first value is the empty string).
	 *
	 * @param file the anchor file
	 * @return the parsed blocks in sort order
	 */
	@Nonnull
	static List<ValueBlock> parseAnchor(@Nonnull Path file) throws Exception {
		final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		String valueType = "java.lang.String";
		final List<ValueBlock> blocks = new ArrayList<>();
		for (final String line : lines) {
			if (line.isEmpty()) {
				continue;
			}
			if (line.charAt(0) == '#') {
				final int eq = line.indexOf('=');
				if (eq > 1 && "valueType".equals(line.substring(1, eq))) {
					valueType = line.substring(eq + 1);
				}
				continue;
			}
			final int tab = line.indexOf('\t');
			final String token = tab < 0 ? line : line.substring(0, tab);
			final String idList = tab < 0 ? "" : line.substring(tab + 1);
			final String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
			blocks.add(new ValueBlock(parseValue(valueType, decoded), parseIds(idList)));
		}
		return blocks;
	}

	@Nonnull
	private static int[] parseIds(@Nonnull String idList) {
		if (idList.isEmpty()) {
			return new int[0];
		}
		final String[] tokens = idList.split(",");
		final int[] ids = new int[tokens.length];
		for (int i = 0; i < tokens.length; i++) {
			ids[i] = Integer.parseInt(tokens[i].trim());
		}
		return ids;
	}

	/**
	 * Converts a decoded textual value to its typed form, dispatched by the anchor's declared `#valueType` so the same
	 * parser serves the dev mirror's Integer / Long / BigDecimal reuse.
	 */
	@Nonnull
	private static Serializable parseValue(@Nonnull String valueType, @Nonnull String decoded) {
		return switch (valueType) {
			case "java.lang.String" -> decoded;
			case "java.lang.Integer" -> Integer.valueOf(decoded);
			case "java.lang.Long" -> Long.valueOf(decoded);
			case "java.math.BigDecimal" -> new BigDecimal(decoded);
			default -> throw new IllegalArgumentException("Unsupported anchor valueType: " + valueType);
		};
	}

	/*
		OWNER + EMISSION
	 */

	/**
	 * Builds an owner sort index (single ascending value attribute, NULLS_LAST) by adding every record of every block.
	 */
	@Nonnull
	static OwnerSortIndex buildOwner(@Nonnull List<ValueBlock> blocks) {
		final OwnerSortIndex owner = new OwnerSortIndex(blocks.get(0).value().getClass(), ATTRIBUTE_KEY);
		for (final ValueBlock block : blocks) {
			final Serializable value = block.value();
			for (final int recordId : block.recordIds()) {
				owner.addRecord(value, recordId);
			}
		}
		return owner;
	}

	static int totalRecords(@Nonnull List<ValueBlock> blocks) {
		int total = 0;
		for (final ValueBlock block : blocks) {
			total += block.recordIds().length;
		}
		return total;
	}

	static int maxRecordId(@Nonnull List<ValueBlock> blocks) {
		int max = 0;
		for (final ValueBlock block : blocks) {
			for (final int id : block.recordIds()) {
				if (id > max) {
					max = id;
				}
			}
		}
		return max;
	}

	/**
	 * Picks a deterministic existing value to use as the churn target — the value grown by one record to produce an
	 * incremental-commit emission. Prefers a singleton block (cardinality one, grown to two), scanning from the middle
	 * ordinal forward and then from the start, which is what the `anchor` and `synth_*` distributions always provide.
	 *
	 * A `uniform_*` distribution has NO singleton block by construction — every value owns exactly
	 * `recordCount / distinctValues` records — so this falls back to the NARROWEST block available. Growing a block of
	 * width `w` to `w + 1` is the same single-record incremental churn; only the starting width differs. Throwing here
	 * instead (the previous behaviour) made every uniform scenario fail in the trial `@Setup` that `churnSerialize`
	 * shares, taking `insertRecord` down with it even though `insertRecord` never touches the churn fixture.
	 *
	 * @param blocks the scenario's value blocks, never empty
	 * @return the value to grow for the churn measurement
	 */
	@Nonnull
	static Serializable singletonValue(@Nonnull List<ValueBlock> blocks) {
		final int start = blocks.size() / 2;
		for (int i = start; i < blocks.size(); i++) {
			if (blocks.get(i).recordIds().length == 1) {
				return blocks.get(i).value();
			}
		}
		for (final ValueBlock block : blocks) {
			if (block.recordIds().length == 1) {
				return block.value();
			}
		}
		// no singleton (a uniform distribution) - grow the narrowest block instead
		ValueBlock narrowest = null;
		for (final ValueBlock block : blocks) {
			if (narrowest == null || block.recordIds().length < narrowest.recordIds().length) {
				narrowest = block;
			}
		}
		if (narrowest == null) {
			throw new IllegalStateException("No value present to grow for churn measurement — empty distribution!");
		}
		return narrowest.value();
	}

	/**
	 * Drains an {@link OwnerSortIndex} commit emission into a list keeping EVERY part.
	 */
	@Nonnull
	static List<StoragePart> emit(@Nonnull OwnerSortIndex index) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		index.appendStorageParts(ENTITY_INDEX_PK, trappedChanges);
		final List<StoragePart> parts = new ArrayList<>();
		final var iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			parts.add(iterator.next());
		}
		return parts;
	}

	@Nonnull
	static List<StoragePart> stripRemovals(@Nonnull List<StoragePart> parts) {
		final List<StoragePart> result = new ArrayList<>(parts.size());
		for (final StoragePart part : parts) {
			if (!(part instanceof DeferredRemovalStoragePart)) {
				result.add(part);
			}
		}
		return result;
	}

	@Nonnull
	static List<SortIndexLeafPagePart> leafPages(@Nonnull List<StoragePart> parts) {
		final List<SortIndexLeafPagePart> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof SortIndexLeafPagePart leafPage) {
				result.add(leafPage);
			}
		}
		return result;
	}

	@Nonnull
	static List<SortIndexLeafPageRemoval> removals(@Nonnull List<StoragePart> parts) {
		final List<SortIndexLeafPageRemoval> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof SortIndexLeafPageRemoval removal) {
				result.add(removal);
			}
		}
		return result;
	}

	@Nonnull
	static SortIndexStoragePart root(@Nonnull List<StoragePart> parts) {
		for (final StoragePart part : parts) {
			if (part instanceof SortIndexStoragePart rootPart) {
				return rootPart;
			}
		}
		throw new IllegalStateException("The emission carries no SortIndexStoragePart root!");
	}

	/*
		DIRECT SERIALIZATION
	 */

	/**
	 * A reusable index Kryo bundle (a shared {@link ReadWriteKeyCompressor} + Kryo + the two part serializers) suitable
	 * for serializing every {@link SortIndexLeafPagePart} and {@link SortIndexStoragePart} root of an emission. The
	 * comparator-base enums are registered exactly as the production schema configurer registers them.
	 */
	static final class SerializerBundle {
		final ReadWriteKeyCompressor keyCompressor;
		final Kryo kryo;
		final SortIndexLeafPagePartSerializer leafSerializer;
		final SortIndexStoragePartSerializer rootSerializer;

		SerializerBundle() {
			this.keyCompressor = new ReadWriteKeyCompressor(new LinkedHashMap<>());
			this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
			this.kryo.register(OrderDirection.class, new EnumNameSerializer<>());
			this.kryo.register(OrderBehaviour.class, new EnumNameSerializer<>());
			this.leafSerializer = new SortIndexLeafPagePartSerializer();
			this.rootSerializer = new SortIndexStoragePartSerializer(this.keyCompressor);
		}
	}

	/**
	 * Serializes EVERY {@link SortIndexLeafPagePart} and the {@link SortIndexStoragePart} root in the emission DIRECTLY
	 * through their Kryo serializers, summing the byte sizes. Removal parts carry no payload bytes and are skipped.
	 */
	static long serializedBytes(@Nonnull List<StoragePart> parts) {
		final SerializerBundle bundle = new SerializerBundle();
		long total = 0L;
		for (final StoragePart part : parts) {
			if (part instanceof SortIndexLeafPagePart leafPage) {
				leafPage.computeUniquePartIdAndSet(bundle.keyCompressor);
				total += writeBytes(bundle.kryo, bundle.leafSerializer, leafPage);
			} else if (part instanceof SortIndexStoragePart rootPart) {
				rootPart.computeUniquePartIdAndSet(bundle.keyCompressor);
				total += writeBytes(bundle.kryo, bundle.rootSerializer, rootPart);
			}
		}
		return total;
	}

	static <T extends StoragePart> int writeBytes(
		@Nonnull Kryo kryo, @Nonnull com.esotericsoftware.kryo.Serializer<T> serializer, @Nonnull T part
	) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			serializer.write(kryo, output, part);
		}
		return os.size();
	}

	/**
	 * The full initial emit serialized to bytes, retaining the bundle so the bytes can be deserialized back into a live
	 * {@link OwnerSortIndex} (the cold-load `loadDeserialize` path) without the {@link OffsetIndex} layer.
	 */
	static final class SerializedFull {
		private final byte[] rootBytes;
		private final List<byte[]> leafBytes;
		private final SerializerBundle bundle;

		private SerializedFull(@Nonnull byte[] rootBytes, @Nonnull List<byte[]> leafBytes, @Nonnull SerializerBundle bundle) {
			this.rootBytes = rootBytes;
			this.leafBytes = leafBytes;
			this.bundle = bundle;
		}

		/**
		 * Deserializes the root and every leaf page from the captured bytes and rebuilds a live PAGED owner via
		 * {@link OwnerSortIndex#fromPersistedPages} (which also reconstructs the positional `sortedRecords` façade).
		 */
		@Nonnull
		OwnerSortIndex deserialize() {
			final SortIndexStoragePart deserializedRoot;
			try (final Input input = new Input(this.rootBytes)) {
				deserializedRoot = this.bundle.rootSerializer.read(this.bundle.kryo, input, SortIndexStoragePart.class);
			}
			final int[] orderedPageSequences = deserializedRoot.getLeafPageSequences();
			final Map<Integer, ValueToRecord[]> bucketsBySequence = new LinkedHashMap<>(this.leafBytes.size());
			for (final byte[] leaf : this.leafBytes) {
				final SortIndexLeafPagePart page;
				try (final Input input = new Input(leaf)) {
					page = this.bundle.leafSerializer.read(this.bundle.kryo, input, SortIndexLeafPagePart.class);
				}
				bucketsBySequence.put(page.getPageSequence(), page.getBuckets());
			}
			final ValueToRecord[][] perPageBuckets = new ValueToRecord[orderedPageSequences.length][];
			for (int i = 0; i < orderedPageSequences.length; i++) {
				perPageBuckets[i] = bucketsBySequence.get(orderedPageSequences[i]);
			}
			return OwnerSortIndex.fromPersistedPages(
				deserializedRoot.getComparatorBase(), null, deserializedRoot.getAttributeIndexKey(),
				deserializedRoot.getIndexedDecimalPlaces(), orderedPageSequences, perPageBuckets,
				deserializedRoot.getHighWaterPageSequence()
			);
		}
	}

	/**
	 * Serializes the full emission's leaf pages + root into a reusable {@link SerializedFull} fixture.
	 */
	@Nonnull
	static SerializedFull serializeFull(@Nonnull List<StoragePart> fullEmission) {
		final SerializerBundle bundle = new SerializerBundle();
		final List<byte[]> leafBytes = new ArrayList<>();
		byte[] rootBytes = null;
		for (final StoragePart part : fullEmission) {
			if (part instanceof SortIndexLeafPagePart leafPage) {
				leafPage.computeUniquePartIdAndSet(bundle.keyCompressor);
				leafBytes.add(toBytes(bundle.kryo, bundle.leafSerializer, leafPage));
			} else if (part instanceof SortIndexStoragePart rootPart) {
				rootPart.computeUniquePartIdAndSet(bundle.keyCompressor);
				rootBytes = toBytes(bundle.kryo, bundle.rootSerializer, rootPart);
			}
		}
		if (rootBytes == null) {
			throw new IllegalStateException("The full emission carries no SortIndexStoragePart root!");
		}
		return new SerializedFull(rootBytes, leafBytes, bundle);
	}

	@Nonnull
	private static <T extends StoragePart> byte[] toBytes(
		@Nonnull Kryo kryo, @Nonnull com.esotericsoftware.kryo.Serializer<T> serializer, @Nonnull T part
	) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			serializer.write(kryo, output, part);
		}
		return os.toByteArray();
	}

	/*
		STEADY STATE (real OffsetIndex persist + reload) -> incremental churn parts
	 */

	/**
	 * Brings the index to a persisted steady state through a real {@link OffsetIndex} round-trip (which restores the
	 * page-stream change-detection baseline on reload, exactly as a cold load does), then mutates one existing value's
	 * cardinality from 1 to 2 with a brand-new record id and returns the resulting INCREMENTAL emission parts (the
	 * changed leaf page(s), any freed-leaf removals, and the re-written root). The temporary file and offset index are
	 * cleaned up before returning.
	 *
	 * A FRESH owner and full emission are built internally on purpose: direct serialization caches part primary keys
	 * against a private key compressor, so the parts handed to other measurements must never be the ones persisted here.
	 *
	 * @param blocks the scenario blocks (also used to build the steady-state baseline + pick the churn target)
	 * @return the incremental-commit parts
	 */
	@Nonnull
	static List<StoragePart> incrementalChurnParts(@Nonnull List<ValueBlock> blocks) {
		final List<StoragePart> fullEmission = emit(buildOwner(blocks));
		final ObservableOutputKeeper outputKeeper = ObservableOutputKeeper._internalBuild(Mockito.mock(Scheduler.class));
		final OffsetIndexRecordTypeRegistry recordRegistry = new OffsetIndexRecordTypeRegistry();
		final StorageSettings storageSettings = new StorageSettings(
			StorageOptions.temporary(), TransactionOptions.builder().build()
		);
		Path targetFile = null;
		OffsetIndex reloaded = null;
		try {
			targetFile = Files.createTempFile("sortIndexChurn", ".kryo");
			final OffsetIndexDescriptor descriptor = persist(
				stripRemovals(fullEmission), targetFile, storageSettings, recordRegistry, outputKeeper
			);
			reloaded = loadOffsetIndex(descriptor, targetFile, storageSettings, recordRegistry, outputKeeper);
			final SortIndexStoragePart reloadedRoot = readRoot(reloaded);
			final OwnerSortIndex restored = loadPagedOwner(reloaded, reloadedRoot);

			restored.addRecord(singletonValue(blocks), maxRecordId(blocks) + 1);
			return emit(restored);
		} catch (Exception ex) {
			throw new IllegalStateException("Churn steady-state failed: " + ex.getMessage(), ex);
		} finally {
			if (reloaded != null) {
				final OffsetIndex toClose = reloaded;
				IOUtils.closeQuietly(toClose::close);
			}
			outputKeeper.close();
			if (targetFile != null) {
				targetFile.toFile().delete();
			}
		}
	}

	@Nonnull
	private static SortIndexStoragePart readRoot(@Nonnull OffsetIndex offsetIndex) {
		final long rootPK = AttributeIndexStoragePart.computeUniquePartId(
			ENTITY_INDEX_PK, AttributeIndexType.SORT, ATTRIBUTE_KEY, offsetIndex.getReadOnlyKeyCompressor()
		);
		return offsetIndex.get(PERSISTED_VERSION, rootPK, SortIndexStoragePart.class);
	}

	private static int streamId(@Nonnull OffsetIndex offsetIndex) {
		return offsetIndex.getReadOnlyKeyCompressor().getId(
			new LeafStreamKey(ENTITY_INDEX_PK, new AttributeKeyWithIndexType(ATTRIBUTE_KEY, AttributeIndexType.SORT))
		);
	}

	@Nonnull
	private static OwnerSortIndex loadPagedOwner(@Nonnull OffsetIndex offsetIndex, @Nonnull SortIndexStoragePart root) {
		final int streamId = streamId(offsetIndex);
		final int[] orderedPageSequences = root.getLeafPageSequences();
		final ValueToRecord[][] perPageBuckets = new ValueToRecord[orderedPageSequences.length][];
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final SortIndexLeafPagePart leafPage = offsetIndex.get(
				PERSISTED_VERSION, SortIndexLeafPagePart.computeUniquePartId(streamId, orderedPageSequences[i]),
				SortIndexLeafPagePart.class
			);
			perPageBuckets[i] = leafPage.getBuckets();
		}
		return OwnerSortIndex.fromPersistedPages(
			root.getComparatorBase(), null, root.getAttributeIndexKey(), root.getIndexedDecimalPlaces(),
			orderedPageSequences, perPageBuckets, root.getHighWaterPageSequence()
		);
	}

	private static void writeEmission(@Nonnull OffsetIndex offsetIndex, @Nonnull List<StoragePart> parts) {
		for (final StoragePart part : parts) {
			if (part instanceof DeferredRemovalStoragePart deferredRemoval) {
				final long removedPartPK = deferredRemoval.computeUniquePartIdAndSet(offsetIndex.getReadOnlyKeyCompressor());
				offsetIndex.remove(PERSISTED_VERSION, removedPartPK, deferredRemoval.removedContainerType());
			} else {
				offsetIndex.put(PERSISTED_VERSION, part);
			}
		}
	}

	@Nonnull
	private static OffsetIndex openWritableOffsetIndex(
		@Nonnull Path targetFile, @Nonnull StorageSettings storageSettings,
		@Nonnull OffsetIndexRecordTypeRegistry recordRegistry, @Nonnull ObservableOutputKeeper outputKeeper
	) {
		return new OffsetIndex(
			0L,
			new OffsetIndexDescriptor(new EntityCollectionFileHeader(ENTITY_TYPE, 1, 0), createKryo(), 1.0, 0L),
			storageSettings.outputBufferSize(),
			storageSettings.maxOpenedReadHandlesOrDefault(),
			storageSettings.lockTimeoutSeconds(),
			storageSettings.waitOnCloseSeconds(),
			storageSettings,
			storageSettings,
			recordRegistry,
			createWriteHandle(targetFile, storageSettings, outputKeeper),
			NO_OP_NON_FLUSHED_BLOCK_CALLBACK,
			NO_OP_OLDEST_RECORD_CALLBACK
		);
	}

	@Nonnull
	private static OffsetIndexDescriptor persist(
		@Nonnull List<StoragePart> parts, @Nonnull Path targetFile, @Nonnull StorageSettings storageSettings,
		@Nonnull OffsetIndexRecordTypeRegistry recordRegistry, @Nonnull ObservableOutputKeeper outputKeeper
	) {
		final OffsetIndex offsetIndex = openWritableOffsetIndex(targetFile, storageSettings, recordRegistry, outputKeeper);
		try {
			writeEmission(offsetIndex, parts);
			return offsetIndex.flush(PERSISTED_VERSION);
		} finally {
			IOUtils.closeQuietly(offsetIndex::close);
		}
	}

	@Nonnull
	private static OffsetIndex loadOffsetIndex(
		@Nonnull OffsetIndexDescriptor descriptor, @Nonnull Path targetFile, @Nonnull StorageSettings storageSettings,
		@Nonnull OffsetIndexRecordTypeRegistry recordRegistry, @Nonnull ObservableOutputKeeper outputKeeper
	) {
		return new OffsetIndex(
			PERSISTED_VERSION,
			new OffsetIndexDescriptor(
				new FileLocationAndWrittenBytes(descriptor.fileLocation(), 0),
				descriptor,
				1.0,
				descriptor.getFileSize()
			),
			storageSettings.outputBufferSize(),
			storageSettings.maxOpenedReadHandlesOrDefault(),
			storageSettings.lockTimeoutSeconds(),
			storageSettings.waitOnCloseSeconds(),
			storageSettings,
			storageSettings,
			recordRegistry,
			createWriteHandle(targetFile, storageSettings, outputKeeper),
			NO_OP_NON_FLUSHED_BLOCK_CALLBACK,
			NO_OP_OLDEST_RECORD_CALLBACK
		);
	}

	@Nonnull
	private static WriteOnlyFileHandle createWriteHandle(
		@Nonnull Path targetFile, @Nonnull StorageSettings storageSettings, @Nonnull ObservableOutputKeeper outputKeeper
	) {
		return new WriteOnlyFileHandle(
			targetFile,
			storageSettings.outputBufferSize(),
			storageSettings.syncWrites(),
			storageSettings,
			storageSettings,
			outputKeeper
		);
	}

	@Nonnull
	private static Function<VersionedKryoKeyInputs, VersionedKryo> createKryo() {
		return keyInputs -> VersionedKryoFactory.createKryo(
			keyInputs.version(),
			SchemaKryoConfigurer.INSTANCE
				.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
				.andThen(SharedClassesConfigurer.INSTANCE)
				.andThen(SharedIndexStoragePartConfigurer.INSTANCE)
				.andThen(new IndexStoragePartConfigurer(keyInputs.keyCompressor()))
		);
	}
}
