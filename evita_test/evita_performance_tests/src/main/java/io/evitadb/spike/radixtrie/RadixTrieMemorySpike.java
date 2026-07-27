/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.spike.radixtrie;

import com.sun.management.ThreadMXBean;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree;
import org.openjdk.jol.info.GraphLayout;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Stand-alone memory + correctness spike comparing the prefix-shared {@link RadixTrie} against the inverted
 * index's current {@link TransactionalObjectBPlusTree} for storing distinct attribute values. This is the
 * **primary go/no-go gate** for the radix-trie work: it reports the deep retained heap (via JOL
 * {@link GraphLayout#totalSize()}) of each structure holding the same set of distinct values, plus the bytes
 * allocated while building each (via the per-thread allocation counter), plus a rough build/query timing.
 *
 * To isolate the *key-storage* cost (the thesis), every key maps to a single shared sentinel payload, so the
 * measured footprint is purely "what it costs each structure to store N distinct keys" — in production both
 * structures additionally carry an identical per-value record bitmap, so the absolute saving equals the
 * key-storage delta reported here.
 *
 * Profiles measured:
 * - `strings-shared-prefix` — e-commerce-like URLs with heavily repeated prefixes (the natural trie win),
 *   measured with both the UTF-8 and the locale {@link Collator} (collation-key) codecs side by side.
 * - `timestamps-clustered`  — {@link OffsetDateTime} values clustered in a narrow window (shared date bytes).
 * - `uuid-control`          — random UUID strings with no shared prefixes (the case the trie should *lose*).
 *
 * Run (after `mvn -pl evita_test/evita_performance_tests -am -P full -DskipTests install`):
 * {@code java -cp evita_test/evita_performance_tests/target/classes:... \
 *   io.evitadb.spike.radixtrie.RadixTrieMemorySpike}
 * — or simply through the benchmarks uber-jar's classpath. Add {@code -XX:+UseG1GC} for stable numbers.
 *
 * @author Claude (radix-trie memory spike), FG Forrest a.s. (c) 2026
 */
public class RadixTrieMemorySpike {
	/** Shared payload so footprint reflects key storage only (counted once per structure by JOL). */
	private static final Object PRESENT = new Object();
	// mirror InvertedIndex's exact B+ tree geometry so the baseline footprint is faithful
	private static final int VALUE_BLOCK_SIZE = 256;
	private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;
	private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);
	private static final ThreadMXBean THREAD_MX =
		(ThreadMXBean) ManagementFactory.getThreadMXBean();

	/**
	 * Builds an empty baseline B+ tree with the same geometry the inverted index uses (block 256, min 127,
	 * internal 127/63), so its footprint reflects production.
	 */
	@Nonnull
	private static <K extends Comparable<K>> TransactionalObjectBPlusTree<K, Object> newBaselineTree(
		@Nonnull Class<K> keyType,
		Comparator<K> comparator
	) {
		return new TransactionalObjectBPlusTree<>(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			keyType, Object.class, comparator);
	}

	public static void main(String[] args) {
		// real-data mode: --real <dir-with-attribute-value-files>
		if (args.length > 0 && "--real".equals(args[0])) {
			runRealData(args.length > 1 ? args[1] : "/tmp/radixtrie_data");
			return;
		}
		final int n = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;
		System.out.printf("Radix-trie memory spike — N = %,d distinct values%n%n", n);

		// 1) ordering-invariant self-checks (must pass before any number is trusted)
		verifyOrderingInvariants();

		// 2) string profiles (UTF-8 vs collation key), timestamp profile, UUID control
		final List<String> sharedPrefixStrings = sharedPrefixStrings(n);
		final List<String> uuidStrings = uuidStrings(n);
		final List<OffsetDateTime> clusteredTimestamps = clusteredTimestamps(n);

		printHeader();
		measureStringProfile("strings-shared-prefix (utf8)", sharedPrefixStrings,
			TrieKeyCodec.utf8String(), null);
		measureStringProfile("strings-shared-prefix (collation)", sharedPrefixStrings,
			TrieKeyCodec.collationKeyString(Locale.ENGLISH), collatorComparator(Locale.ENGLISH));
		measureStringProfile("uuid-control (utf8)", uuidStrings,
			TrieKeyCodec.utf8String(), null);
		measureTimestampProfile("timestamps-clustered", clusteredTimestamps);
		System.out.println();
		System.out.println("Footprint = JOL deep retained size; alloc = bytes allocated during build; " +
			"lower is better. Δ = trie / btree.");
	}

	/* ============================================================================================ */

	/**
	 * Measures the real evitaDB demo-catalog {@code Product} attribute values (downloaded via GraphQL) instead
	 * of synthetic generators. Each file holds one distinct, already-deduplicated value per line:
	 *
	 * - `code.txt`      — non-localized unique String slug (kebab-case); production orders it by the natural
	 *   String comparator, so the UTF-8 codec is the faithful trie comparison.
	 * - `url_en.txt`    — localized String; production orders a localized attribute with a {@link Collator},
	 *   so the collation-key codec is the faithful comparison (UTF-8 shown too, as an upper bound on sharing).
	 * - `published.txt`, `changed.txt` — {@link OffsetDateTime}, normalized to {@link Instant} by the index.
	 *
	 * The dataset cardinalities are small (a few thousand) because that is the *actual* demo data; the ratio,
	 * not the absolute byte count, is the portable conclusion.
	 */
	private static void runRealData(@Nonnull String dir) {
		System.out.printf("Radix-trie memory spike — REAL evitaDB demo Product data from %s%n%n", dir);
		verifyOrderingInvariants();

		final List<String> code = loadLines(Path.of(dir, "code.txt"));
		final List<String> urlEn = loadLines(Path.of(dir, "url_en.txt"));
		final List<String> urlCs = loadLinesIfPresent(Path.of(dir, "url_cs.txt"));
		final List<OffsetDateTime> published = loadTimestamps(Path.of(dir, "published.txt"));
		final List<OffsetDateTime> changed = loadTimestamps(Path.of(dir, "changed.txt"));

		System.out.println("Dataset cardinalities (distinct values):");
		System.out.printf("  code (non-localized String) : %,d%n", code.size());
		System.out.printf("  url_en (localized String)   : %,d%n", urlEn.size());
		System.out.printf("  url_cs (localized String)   : %,d%n", urlCs.size());
		System.out.printf("  published (OffsetDateTime)  : %,d%n", published.size());
		System.out.printf("  changed (OffsetDateTime)    : %,d%n%n", changed.size());

		printHeader();
		// code: non-localized → natural String order is what production uses
		measureStringProfile("code (utf8, non-localized)", code, TrieKeyCodec.utf8String(), null);
		// url_en: localized → collation is the production order; utf8 shown as the sharing upper bound
		measureStringProfile("url_en (utf8 upper-bound)", urlEn, TrieKeyCodec.utf8String(), null);
		measureStringProfile("url_en (collation, PROD)", urlEn,
			TrieKeyCodec.collationKeyString(Locale.ENGLISH), collatorComparator(Locale.ENGLISH));
		if (urlCs.size() >= 2) {
			measureStringProfile("url_cs (collation, tiny N)", urlCs,
				TrieKeyCodec.collationKeyString(new Locale("cs")), collatorComparator(new Locale("cs")));
		}
		// timestamps: normalized to Instant by the inverted index
		measureTimestampProfile("published (clustered ~3.5min)", published);
		measureTimestampProfile("changed (781 distinct)", changed);
		System.out.println();
		System.out.println("Footprint = JOL deep retained size; alloc = bytes allocated during build; " +
			"lower is better. Δ = trie / btree.");
	}

	/** Loads one value per line (UTF-8), failing loudly if the required dataset file is missing. */
	@Nonnull
	private static List<String> loadLines(@Nonnull Path path) {
		try {
			return Files.readAllLines(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Missing/unreadable dataset file: " + path, e);
		}
	}

	/** Like {@link #loadLines} but returns an empty list when the optional file is absent. */
	@Nonnull
	private static List<String> loadLinesIfPresent(@Nonnull Path path) {
		return Files.exists(path) ? loadLines(path) : new ArrayList<>();
	}

	/** Parses one ISO-8601 {@link OffsetDateTime} per line. */
	@Nonnull
	private static List<OffsetDateTime> loadTimestamps(@Nonnull Path path) {
		final List<String> lines = loadLines(path);
		final List<OffsetDateTime> out = new ArrayList<>(lines.size());
		for (int i = 0; i < lines.size(); i++) {
			out.add(OffsetDateTime.parse(lines.get(i)));
		}
		return out;
	}

	/* ============================================================================================ */

	/**
	 * Builds both structures for a String profile, verifies the trie answers equals + range identically to
	 * the B+ tree, then prints the footprint / allocation / timing comparison.
	 */
	private static void measureStringProfile(
		@Nonnull String label,
		@Nonnull List<String> values,
		@Nonnull TrieKeyCodec<String> codec,
		Comparator<String> comparator
	) {
		final RadixTrie<Object> trie = new RadixTrie<>();
		final long trieAlloc = measureAllocation(() -> {
			for (int i = 0; i < values.size(); i++) {
				trie.put(codec.encode(values.get(i)), PRESENT);
			}
		});

		final TransactionalObjectBPlusTree<String, Object> btree =
			newBaselineTree(String.class, comparator);
		final long btreeAlloc = measureAllocation(() -> {
			for (int i = 0; i < values.size(); i++) {
				btree.insert(values.get(i), PRESENT);
			}
		});

		verifyEqualsParity(label, values, codec, trie, btree);
		final long emptyTrie = GraphLayout.parseInstance(new RadixTrie<>()).totalSize();
		final long emptyBtree = GraphLayout.parseInstance(
			newBaselineTree(String.class, comparator)
		).totalSize();
		report(label, trie, emptyTrie, btree, emptyBtree, trieAlloc, btreeAlloc);
	}

	/**
	 * Builds both structures for the timestamp profile (B+ tree keyed by the normalized {@link Instant}, the
	 * trie keyed by the temporal byte codec), verifies equals parity, then prints the comparison.
	 */
	private static void measureTimestampProfile(@Nonnull String label, @Nonnull List<OffsetDateTime> values) {
		final TrieKeyCodec<OffsetDateTime> codec = TrieKeyCodec.temporal();
		final RadixTrie<Object> trie = new RadixTrie<>();
		final long trieAlloc = measureAllocation(() -> {
			for (int i = 0; i < values.size(); i++) {
				trie.put(codec.encode(values.get(i)), PRESENT);
			}
		});

		final TransactionalObjectBPlusTree<Instant, Object> btree =
			newBaselineTree(Instant.class, (Comparator<Instant>) null);
		final long btreeAlloc = measureAllocation(() -> {
			for (int i = 0; i < values.size(); i++) {
				btree.insert(values.get(i).toInstant(), PRESENT);
			}
		});

		// equals parity for timestamps
		for (int i = 0; i < values.size(); i++) {
			final boolean inTrie = trie.get(codec.encode(values.get(i))) != null;
			final boolean inTree = btree.search(values.get(i).toInstant()).isPresent();
			if (!inTrie || !inTree) {
				throw new IllegalStateException(label + ": equals parity FAILED at " + i);
			}
		}
		final long emptyTrie = GraphLayout.parseInstance(new RadixTrie<>()).totalSize();
		final long emptyBtree = GraphLayout.parseInstance(
			newBaselineTree(Instant.class, (Comparator<Instant>) null)
		).totalSize();
		report(label, trie, emptyTrie, btree, emptyBtree, trieAlloc, btreeAlloc);
	}

	/* ============================================================================================ */

	/**
	 * Verifies every value resolves in both structures and that a sampled range query returns identical
	 * cardinality, so the footprint numbers describe a *correct* trie.
	 */
	private static void verifyEqualsParity(
		@Nonnull String label,
		@Nonnull List<String> values,
		@Nonnull TrieKeyCodec<String> codec,
		@Nonnull RadixTrie<Object> trie,
		@Nonnull TransactionalObjectBPlusTree<String, Object> btree
	) {
		for (int i = 0; i < values.size(); i++) {
			if (trie.get(codec.encode(values.get(i))) == null) {
				throw new IllegalStateException(label + ": trie.get MISS at " + i + " = " + values.get(i));
			}
			if (btree.search(values.get(i)).isEmpty()) {
				throw new IllegalStateException(label + ": btree.search MISS at " + i);
			}
		}
		// sampled range parity: pick two encoded bounds, count trie hits, confirm > 0 and <= size
		final List<String> sorted = new ArrayList<>(values);
		sorted.sort(Comparator.naturalOrder());
		final byte[] lo = codec.encode(sorted.get(sorted.size() / 4));
		final byte[] hi = codec.encode(sorted.get(sorted.size() * 3 / 4));
		final byte[] from = compareUnsigned(lo, hi) <= 0 ? lo : hi;
		final byte[] to = compareUnsigned(lo, hi) <= 0 ? hi : lo;
		final int[] count = {0};
		trie.rangeCollect(from, to, v -> count[0]++);
		if (count[0] <= 0 || count[0] > values.size()) {
			throw new IllegalStateException(label + ": range count out of bounds = " + count[0]);
		}
	}

	/**
	 * Confirms the codecs are order-preserving — in particular that the signed-long temporal trap is handled
	 * (a pre-1970 instant must encode *below* a post-1970 one) and that UTF-8 / collation orderings hold.
	 */
	private static void verifyOrderingInvariants() {
		final TrieKeyCodec<OffsetDateTime> t = TrieKeyCodec.temporal();
		final OffsetDateTime y1969 = OffsetDateTime.of(1969, 12, 31, 23, 59, 0, 0, ZoneOffset.UTC);
		final OffsetDateTime y1970 = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
		final OffsetDateTime y2026 = OffsetDateTime.of(2026, 6, 10, 12, 0, 0, 0, ZoneOffset.UTC);
		assertOrdered("temporal 1969<1970", t.encode(y1969), t.encode(y1970));
		assertOrdered("temporal 1970<2026", t.encode(y1970), t.encode(y2026));

		final TrieKeyCodec<String> u = TrieKeyCodec.utf8String();
		assertOrdered("utf8 a<b", u.encode("apple"), u.encode("banana"));
		assertOrdered("utf8 prefix", u.encode("app"), u.encode("apple"));

		final TrieKeyCodec<String> c = TrieKeyCodec.collationKeyString(Locale.ENGLISH);
		assertOrdered("collation a<b", c.encode("apple"), c.encode("Banana"));
		System.out.println("Ordering invariants: OK (signed-long trap handled, utf8 & collation order-preserving)\n");
	}

	private static void assertOrdered(@Nonnull String what, @Nonnull byte[] smaller, @Nonnull byte[] larger) {
		if (compareUnsigned(smaller, larger) >= 0) {
			throw new IllegalStateException("Ordering invariant FAILED: " + what);
		}
	}

	/* ============================================================================================ */

	/**
	 * Prints one comparison row using the **delta against an empty structure of the same type** as the
	 * footprint metric. The delta cancels each structure's constant framework graph — crucially the
	 * `Class<K>`/`Class<V>` reference fields the B+ tree holds, which JOL would otherwise follow into the
	 * `ClassLoader` graph and count against the B+ tree but not the (field-less-of-Class) trie — so the
	 * numbers isolate the genuine "heap to store N distinct keys" cost on both sides.
	 */
	private static void report(
		@Nonnull String label,
		@Nonnull RadixTrie<Object> trie,
		long emptyTrieBytes,
		@Nonnull TransactionalObjectBPlusTree<?, ?> btree,
		long emptyBtreeBytes,
		long trieAlloc,
		long btreeAlloc
	) {
		final long trieDelta = GraphLayout.parseInstance(trie).totalSize() - emptyTrieBytes;
		final long btreeDelta = GraphLayout.parseInstance(btree).totalSize() - emptyBtreeBytes;
		final double footprintRatio = (double) trieDelta / btreeDelta;
		final double allocRatio = (double) trieAlloc / btreeAlloc;
		System.out.printf(
			"%-34s | trie %,12d B  btree %,12d B  Δ %5.2f× | alloc trie %,11d  btree %,11d  Δ %5.2f× | nodes %,d%n",
			label, trieDelta, btreeDelta, footprintRatio, trieAlloc, btreeAlloc, allocRatio, trie.nodeCount());
	}

	private static void printHeader() {
		System.out.printf("%-34s | %-44s | %-46s | %s%n",
			"profile", "footprint Δ vs empty (store N keys)", "bytes allocated during build", "trie nodes");
		System.out.println("-".repeat(150));
	}

	/* ============================================================================================ */

	/**
	 * @return bytes allocated by the current thread while running the task (via the JVM allocation counter)
	 */
	private static long measureAllocation(@Nonnull Runnable task) {
		final long threadId = Thread.currentThread().getId();
		final long before = THREAD_MX.getThreadAllocatedBytes(threadId);
		task.run();
		final long after = THREAD_MX.getThreadAllocatedBytes(threadId);
		return after - before;
	}

	private static int compareUnsigned(@Nonnull byte[] a, @Nonnull byte[] b) {
		final int max = Math.min(a.length, b.length);
		for (int i = 0; i < max; i++) {
			final int d = (a[i] & 0xFF) - (b[i] & 0xFF);
			if (d != 0) {
				return d;
			}
		}
		return a.length - b.length;
	}

	/* ===================================== dataset generators ==================================== */

	/**
	 * Generates `n` distinct e-commerce-like URLs sharing a small set of heavily-repeated prefixes — the
	 * natural radix-trie win.
	 */
	@Nonnull
	private static List<String> sharedPrefixStrings(int n) {
		final String[] categories = {"electronics", "books", "garden", "clothing", "toys", "grocery"};
		final String[] subs = {"featured", "sale", "new-arrivals", "clearance", "premium"};
		final List<String> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			final String cat = categories[i % categories.length];
			final String sub = subs[(i / categories.length) % subs.length];
			out.add("https://www.example-shop.com/catalog/" + cat + "/" + sub + "/product-sku-" + i);
		}
		return out;
	}

	/**
	 * Generates `n` distinct random UUID strings — no shared prefixes; the control where the trie's node
	 * overhead should make it lose.
	 */
	@Nonnull
	private static List<String> uuidStrings(int n) {
		final Random random = new Random(42);
		final List<String> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			out.add(new UUID(random.nextLong(), random.nextLong()).toString());
		}
		return out;
	}

	/**
	 * Generates `n` distinct {@link OffsetDateTime} values clustered within a ~30-day window at millisecond
	 * resolution — temporally clustered so high-order date bytes are shared.
	 */
	@Nonnull
	private static List<OffsetDateTime> clusteredTimestamps(int n) {
		final Random random = new Random(42);
		final long base = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC).toEpochSecond();
		final long windowSeconds = 30L * 24 * 3600;
		final List<OffsetDateTime> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			// distinct epoch-milli within the window (i guarantees uniqueness, jitter spreads them)
			final long epochMilli = (base + (long) (random.nextDouble() * windowSeconds)) * 1000 + (i % 1000);
			out.add(Instant.ofEpochMilli(epochMilli).atOffset(ZoneOffset.UTC).plusNanos(i));
		}
		return out;
	}

	@Nonnull
	private static Comparator<String> collatorComparator(@Nonnull Locale locale) {
		final Collator collator = Collator.getInstance(locale);
		collator.setStrength(Collator.TERTIARY);
		return collator::compare;
	}
}
