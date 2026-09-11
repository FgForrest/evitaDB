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

import io.evitadb.api.CatalogContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;

import static io.evitadb.index.bitmap.RoaringBitmapBackedBitmap.getRoaringBitmap;
import static io.evitadb.roaringbitmap.PersistentRoaringBitmap.and;

/**
 * Measures the **shipped** reduced-index membership structure on a real catalog — what it costs in memory, and
 * what the trigger's sibling resolution costs with it against the walk it replaces.
 *
 * The STRUCTURE measured is the shipped one. Slices are filled through the very
 * {@link ReducedIndexMembership#registerIndex} call `EntityCollection#rebuildReducedIndexMembership` makes on
 * the load path, so the threshold decisions, the covered/residual split and the heap figure are all real, at
 * real cardinality. This is what separates the report from `ConditionalFacetSiblingResolverReport`, whose
 * arm C is a reverse map the harness invents.
 *
 * # What the timed arms measure, and where they diverge from the shipped resolver
 *
 * The RESOLUTION measured is **not** the shipped one. Both arms are re-implementations local to this class,
 * and they diverge from `ReevaluateExpressionExecutor` in two ways, in increasing order of consequence:
 *
 * - **Both arms are probe-only.** They stop at the intersection and never call `getOrCreateIndexByPrimaryKey`,
 *   so neither pays the registering re-fetch nor the per-owner de-duplication of `SiblingReducedIndex`
 *   instances the shipped resolver pays. That matches how the 2026-09-09 decomposition defined "the walk"
 *   against the whole resolver, and it costs both arms the same, so it does not tilt one against the other.
 * - **The lookup arm's COVERED half diverges further, and only that half.** {@link #resolveWithLookup} emits
 *   the map's `(owner, index)` pairs straight out of {@link ReducedIndexMembership#getIndexPrimaryKeys},
 *   whereas `ReevaluateExpressionExecutor#collectOwnersFromMembership` uses the map as an index *selector*
 *   only — it collects the selected primary keys, then resolves each index and intersects its member bitmap
 *   against the affected owners, exactly as the residual half does. This arm performs none of that resolve
 *   and intersect.
 *
 * The emitted pairs are the same either way, and the checksum proves it; the work is not. So the lookup arm's
 * timings are a **lower bound** on what the shipped lookup costs, and — because the walk arm is not short-cut
 * in the same way — the lookup-over-walk speedup printed here is an **upper bound** on the shipped one. Every
 * figure carried into `documentation/adr/2026-09-09-sibling-resolver-partition-cardinality.md` inherits both
 * bounds. Bringing the covered half in line with the shipped selector changes what is measured rather than how
 * it is described, so it is left to a deliberate re-measurement instead of being done in passing.
 *
 * # Reaching the high-cardinality case
 *
 * No schema is raised and nothing is written — the report is read-only. References named on the command line
 * get a STANDALONE slice built for them by {@link #buildSlicesFor} out of their live indexes, which reaches
 * the end state a raise-and-reload would leave behind without the schema write the production export cannot
 * accept. That method's javadoc carries the reasoning and says what the substitution does not exercise.
 *
 * ```
 * java -Xmx48g -cp <cp> io.evitadb.spike.ConditionalFacetMembershipReport <dir> <catalog> <collection> [refs]
 * ```
 *
 * @author Claude (issue #1529 sibling-resolver optimization), FG Forrest a.s. (c) 2026
 */
public class ConditionalFacetMembershipReport {

	/**
	 * How long the report waits for the catalog's background load to finish before giving up.
	 */
	private static final long LOAD_TIMEOUT_NANOS = 15L * 60L * 1_000_000_000L;

	/**
	 * Rounds of the timed series, per shape and per arm.
	 */
	private static final int ROUNDS = 400;

	/**
	 * Rounds discarded before recording, so the arms are compared at steady state.
	 */
	private static final int WARMUP_ROUNDS = 100;

	public static void main(@Nonnull String[] args) {
		if (args.length < 3) {
			System.err.println(
				"Usage: ConditionalFacetMembershipReport <storageDir> <catalogName> <collection> [refsToRaise]"
			);
			System.exit(1);
		}
		final Path storageDirectory = Path.of(args[0]);
		final String catalogName = args[1];
		final String collectionName = args[2];
		final List<String> toRaise = args.length > 3 && !args[3].isBlank()
			? List.of(args[3].split(",")) : List.of();

		try (final Evita evita = openEvita(storageDirectory)) {
			final long loadStart = System.nanoTime();
			final Catalog catalog = awaitLoaded(evita, catalogName);
			final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(collectionName);
			final long loadNanos = System.nanoTime() - loadStart;

			final GlobalEntityIndex globalIndex = globalIndex(collection);
			if (globalIndex == null) {
				System.err.println("no global index - nothing to report");
				return;
			}
			System.out.printf(
				"catalog v%d %s | collection `%s` | load %,.1f s%n%n",
				catalog.getVersion(), catalog.getCatalogState(), collectionName, loadNanos / 1_000_000_000.0
			);

			final long buildStart = System.nanoTime();
			final Map<String, ReducedIndexMembership> simulated = toRaise.isEmpty()
				? Map.of() : buildSlicesFor(collection, toRaise);
			if (!toRaise.isEmpty()) {
				System.out.printf(
					"built membership for %s in %,.1f ms - the load path's own work, timed%n%n",
					toRaise, (System.nanoTime() - buildStart) / 1_000_000.0
				);
			}
			reportCoverage(collection, globalIndex, simulated);
			reportTimings(collection, globalIndex, simulated, toRaise);
		}
	}

	/**
	 * Prints, per reference, how much of its advertisement the lookup covers and what it costs.
	 *
	 * @param collection  the measured collection
	 * @param globalIndex the collection's global index, which carries the lookup
	 * @param simulated   standalone slices built by {@link #buildSlicesFor}, keyed by reference name; each one
	 *                    takes precedence over the slice the global index carries for that reference
	 */
	private static void reportCoverage(
		@Nonnull EntityCollection collection,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Map<String, ReducedIndexMembership> simulated
	) {
		System.out.printf(
			"  %-22s %-32s %11s %11s %11s %12s%n",
			"reference", "index type", "advertised", "covered", "residual", "heap"
		);
		long totalHeap = 0L;
		long totalCovered = 0L;
		long totalResidual = 0L;
		for (final ReferenceSchemaContract reference : collection.getSchema().getReferences().values()) {
			final ReducedIndexMembership membership = simulated.containsKey(reference.getName())
				? simulated.get(reference.getName())
				: globalIndex.getReducedIndexMembership(reference.getName());
			if (membership == null) {
				continue;
			}
			final long advertised = advertisedCount(collection, reference.getName());
			final long covered = membership.getCoveredIndexPrimaryKeys().size();
			final long residual = membership.getResidualIndexPrimaryKeys().size();
			final long heap = membership.getHeapSizeInBytes();
			totalHeap += heap;
			totalCovered += covered;
			totalResidual += residual;
			System.out.printf(
				"  %-22s %-32s %,11d %,11d %,11d %,9d KiB%n",
				reference.getName(), reference.getReferenceIndexType(Scope.LIVE),
				advertised, covered, residual, heap / 1024
			);
		}
		System.out.printf(
			"%n  covered %,d reduced indexes, %,d left on the walk, %,.1f MiB total%n%n",
			totalCovered, totalResidual, totalHeap / (1024.0 * 1024.0)
		);
	}

	/**
	 * Times the sibling resolution the trigger performs, with the lookup and without it, over the sparse and
	 * dense affected-owner shapes of the reference carrying the conditional facet.
	 *
	 * Both arms emit the same `(owner, index)` pairs; the checksum is compared so an arm computing a different
	 * answer is never reported as merely faster. Both are also probe-only — they stop at the intersection and
	 * never call `getOrCreateIndexByPrimaryKey` — and {@link #resolveWithLookup}'s covered half does not even
	 * probe. Neither figure is the shipped resolver's; see the class javadoc for what that costs the reading.
	 *
	 * @param collection    the measured collection
	 * @param globalIndex   the collection's global index, which carries the lookup
	 * @param simulated     standalone slices built by {@link #buildSlicesFor}, keyed by reference name
	 * @param extraSiblings references named on the command line, added to the sibling set even when the schema
	 *                      does not index them at `FOR_FILTERING_AND_PARTITIONING`
	 */
	private static void reportTimings(
		@Nonnull EntityCollection collection,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Map<String, ReducedIndexMembership> simulated,
		@Nonnull List<String> extraSiblings
	) {
		String trigger = null;
		final List<ReferenceSchemaContract> siblings = new ArrayList<>(16);
		for (final ReferenceSchemaContract reference : collection.getSchema().getReferences().values()) {
			if (reference.getFacetedPartiallyInScope(Scope.LIVE) != null) {
				trigger = reference.getName();
			}
			if (reference.getReferenceIndexType(Scope.LIVE) == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING) {
				siblings.add(reference);
			}
		}
		for (final String extra : extraSiblings) {
			final ReferenceSchemaContract reference = collection.getSchema().getReference(extra).orElse(null);
			if (reference != null && siblings.stream().noneMatch(it -> it.getName().equals(extra))) {
				siblings.add(reference);
			}
		}
		if (trigger == null) {
			System.out.println("  no conditional facet declared - nothing to time");
			return;
		}
		final String mutated = trigger;
		siblings.removeIf(it -> it.getName().equals(mutated));
		System.out.printf("  trigger `%s` | siblings %s%n%n", mutated, siblings.stream().map(
			ReferenceSchemaContract::getName).toList());

		for (final Bitmap shape : shapes(collection, mutated)) {
			final PersistentRoaringBitmap affected = getRoaringBitmap(shape);
			System.out.printf("  === %,d affected owners ===%n", shape.size());
			final long[] withLookup = new long[ROUNDS];
			final long[] withoutLookup = new long[ROUNDS];
			// One throwaway transaction bound for the whole series. The real trigger always runs inside one,
			// and both `TransactionalBitmap#getRoaringBitmap` and the index accessors resolve a transactional
			// layer only then - measuring without one understates ALIVE, the wrong direction of error here.
			final Transaction transaction = new Transaction(new TransactionalBitmap(new BaseBitmap()));
			final long[] checksums = {Long.MIN_VALUE, Long.MIN_VALUE};
			Transaction.executeInTransactionIfProvided(transaction, (Runnable) () -> {
				for (int round = -WARMUP_ROUNDS; round < ROUNDS; round++) {
					// alternate which arm runs first: whichever goes second reads a cache the first one just
					// warmed, and a fixed order silently credits that to one of them
					final boolean lookupFirst = (round & 1) == 0;
					long lookupElapsed = 0L;
					long walkElapsed = 0L;
					long a = 0L;
					long b = 0L;
					for (int slot = 0; slot < 2; slot++) {
						if (lookupFirst == (slot == 0)) {
							final long start = System.nanoTime();
							a = resolveWithLookup(collection, globalIndex, simulated, siblings, affected);
							lookupElapsed = System.nanoTime() - start;
						} else {
							final long start = System.nanoTime();
							b = resolveByWalking(collection, siblings, affected);
							walkElapsed = System.nanoTime() - start;
						}
					}
					if (round >= 0) {
						withLookup[round] = lookupElapsed;
						withoutLookup[round] = walkElapsed;
					}
					if (checksums[0] == Long.MIN_VALUE) {
						checksums[0] = a;
						checksums[1] = b;
					}
					if (a != checksums[0] || b != checksums[1] || a != b) {
						throw new IllegalStateException("arms disagree: lookup=" + a + " walk=" + b);
					}
				}
			});
			report("lookup", withLookup);
			report("walk", withoutLookup);
			System.out.println();
		}
	}

	/**
	 * Resolves the sibling reduced indexes through the lookup, in two halves that do NOT cost the same:
	 *
	 * - the **residual** half probes, exactly as the shipped resolver does: it resolves each residual index and
	 *   intersects its member bitmap against the affected owners;
	 * - the **covered** half does not. It intersects the affected set against the lookup's covered-owner union,
	 *   then emits `pair(owner, indexPk)` straight out of {@link ReducedIndexMembership#getIndexPrimaryKeys} —
	 *   no index is resolved and no bitmap is intersected.
	 *
	 * `ReevaluateExpressionExecutor#collectOwnersFromMembership` probes both halves: it treats the lookup as an
	 * index selector, collects the primary keys it names and hands them to the same probe the residual half
	 * uses. The pairs agree, so the checksum passes; the cost does not, and this arm's covered half is the
	 * reason every figure here is a lower bound on the shipped resolver. Fixing it is a measurement change and
	 * is deliberately not made here — see the class javadoc.
	 *
	 * @param collection  the measured collection
	 * @param globalIndex the collection's global index
	 * @param simulated   standalone slices built by {@link #buildSlicesFor}, taking precedence over the global
	 *                    index's own slice for the references they name
	 * @param siblings    the partitioned sibling references
	 * @param affected    affected owner primary keys
	 * @return checksum of the emitted `(owner, index)` pairs
	 */
	private static long resolveWithLookup(
		@Nonnull EntityCollection collection,
		@Nonnull GlobalEntityIndex globalIndex,
		@Nonnull Map<String, ReducedIndexMembership> simulated,
		@Nonnull List<ReferenceSchemaContract> siblings,
		@Nonnull PersistentRoaringBitmap affected
	) {
		long checksum = 0L;
		for (final ReferenceSchemaContract sibling : siblings) {
			final ReducedIndexMembership membership = simulated.containsKey(sibling.getName())
				? simulated.get(sibling.getName())
				: globalIndex.getReducedIndexMembership(sibling.getName());
			if (membership == null) {
				checksum += walkReference(collection, sibling.getName(), affected);
				continue;
			}
			final Bitmap coveredOwners = membership.getCoveredOwners();
			if (!coveredOwners.isEmpty()) {
				for (final int owner : and(getRoaringBitmap(coveredOwners), affected).toArray()) {
					final OfInt it = membership.getIndexPrimaryKeys(owner).iterator();
					while (it.hasNext()) {
						checksum += pair(owner, it.nextInt());
					}
				}
			}
			final OfInt residualIt = membership.getResidualIndexPrimaryKeys().iterator();
			while (residualIt.hasNext()) {
				final int indexPk = residualIt.nextInt();
				checksum += probe(collection, indexPk, affected);
			}
		}
		return checksum;
	}

	/**
	 * Resolves the same set by walking every advertised reduced index, which is what the executor does when
	 * no lookup exists.
	 *
	 * @param collection the measured collection
	 * @param siblings   the partitioned sibling references
	 * @param affected   affected owner primary keys
	 * @return checksum of the emitted `(owner, index)` pairs
	 */
	private static long resolveByWalking(
		@Nonnull EntityCollection collection,
		@Nonnull List<ReferenceSchemaContract> siblings,
		@Nonnull PersistentRoaringBitmap affected
	) {
		long checksum = 0L;
		for (final ReferenceSchemaContract sibling : siblings) {
			checksum += walkReference(collection, sibling.getName(), affected);
		}
		return checksum;
	}

	/**
	 * Walks every reduced index one reference advertises, intersecting each against the affected owners.
	 *
	 * @param collection    the measured collection
	 * @param referenceName the reference to walk
	 * @param affected      affected owner primary keys
	 * @return checksum contribution
	 */
	private static long walkReference(
		@Nonnull EntityCollection collection,
		@Nonnull String referenceName,
		@Nonnull PersistentRoaringBitmap affected
	) {
		final long[] checksum = new long[1];
		for (final EntityIndexType family : new EntityIndexType[]{
			EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
		}) {
			final ReferencedTypeEntityIndex typeIndex = typeIndex(collection, family, referenceName);
			if (typeIndex == null) {
				continue;
			}
			typeIndex.forEachReferenceIndexPrimaryKey(
				pk -> checksum[0] += probe(collection, pk, affected)
			);
		}
		return checksum[0];
	}

	/**
	 * Intersects one reduced index against the affected owners and folds the matches into a checksum.
	 *
	 * @param collection the measured collection
	 * @param indexPk    primary key of the reduced index
	 * @param affected   affected owner primary keys
	 * @return checksum contribution
	 */
	private static long probe(
		@Nonnull EntityCollection collection,
		int indexPk,
		@Nonnull PersistentRoaringBitmap affected
	) {
		final EntityIndex index = collection.getIndexByPrimaryKeyIfExists(indexPk);
		if (index == null) {
			return 0L;
		}
		long checksum = 0L;
		for (final int owner : and(getRoaringBitmap(index.getAllPrimaryKeys()), affected).toArray()) {
			checksum += pair(owner, indexPk);
		}
		return checksum;
	}

	/**
	 * Order-independent contribution of one emitted `(owner, index)` pair.
	 *
	 * @param owner   owner primary key
	 * @param indexPk reduced-index primary key
	 * @return the contribution
	 */
	private static long pair(int owner, int indexPk) {
		return (long) owner * 1000003L + indexPk;
	}

	/**
	 * Builds membership slices for references that are not partitioned in the schema, so the high-cardinality
	 * case can be measured on the **real** structure without a schema change.
	 *
	 * # Why not change the schema
	 *
	 * Two reasons, and neither is a shortcut. The production export this runs against carries no usable WAL
	 * slice — its log record names WAL segment 63 while only segment 0 is present — so the
	 * catalog refuses every write transaction, schema mutations included. And the supported way to change a
	 * reference's index type is a full reindex, which is a twenty-minute rebuild that would spend its time in
	 * the loader rather than in anything this measures.
	 *
	 * # What this does and does not exercise
	 *
	 * It calls {@link ReducedIndexMembership#registerIndex} with each reduced index and its real membership —
	 * the identical call `EntityCollection#rebuildReducedIndexMembership` makes on the load path — so the
	 * structure, its threshold decisions, its memory and the resolution path over it are all the shipped
	 * code, at the cardinality that matters.
	 *
	 * What it does **not** exercise is the load path's own gating: which references it decides to cover. That
	 * is a two-line schema test rather than a performance question, and
	 * `ReducedIndexMembershipCompletenessTest` covers it.
	 *
	 * @param collection the measured collection
	 * @param references references to build slices for
	 * @return the built slices, keyed by reference name
	 */
	@Nonnull
	private static Map<String, ReducedIndexMembership> buildSlicesFor(
		@Nonnull EntityCollection collection,
		@Nonnull List<String> references
	) {
		final Map<String, ReducedIndexMembership> built = new HashMap<>(references.size());
		for (final String referenceName : references) {
			// A STANDALONE instance, deliberately not the one hanging off the global index. The global index
			// holds no slice for these references at all: `EntityCollection#rebuildReducedIndexMembership`
			// builds one only for a reference indexed at FOR_FILTERING_AND_PARTITIONING, and these are the
			// ones that are not. Note that an absent slice is NOT "no indexes" - to the shipped resolver it
			// means "walk the whole advertisement", which is precisely the baseline being measured against.
			// Calling `getOrCreateReducedIndexMembership` here would therefore create a slice on a live index
			// and change the very thing under measurement. On a genuinely raised-and-reloaded catalog the load
			// build would see the reference as partitioned and decide coverage in the first place; this
			// reproduces that end state without needing the schema write the export cannot accept.
			final ReducedIndexMembership membership = new ReducedIndexMembership();
			built.put(referenceName, membership);
			for (final EntityIndexType family : new EntityIndexType[]{
				EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
			}) {
				final ReferencedTypeEntityIndex typeIndex = typeIndex(collection, family, referenceName);
				if (typeIndex == null) {
					continue;
				}
				typeIndex.forEachReferenceIndexPrimaryKey(reducedIndexPk -> {
					if (membership.isKnown(reducedIndexPk)) {
						return;
					}
					final EntityIndex reducedIndex = collection.getIndexByPrimaryKeyIfExists(reducedIndexPk);
					if (reducedIndex != null) {
						membership.registerIndex(reducedIndexPk, reducedIndex.getAllPrimaryKeys());
					}
				});
			}
		}
		return built;
	}

	/**
	 * Builds the affected-owner shapes: the sparse and dense ends of the mutated reference's partition-size
	 * distribution, which is what a trigger's affected-owner set actually is.
	 *
	 * @param collection the measured collection
	 * @param mutated    the reference carrying the conditional facet
	 * @return the shapes to measure
	 */
	@Nonnull
	private static List<Bitmap> shapes(@Nonnull EntityCollection collection, @Nonnull String mutated) {
		final ReferencedTypeEntityIndex typeIndex =
			typeIndex(collection, EntityIndexType.REFERENCED_ENTITY_TYPE, mutated);
		if (typeIndex == null) {
			return List.of();
		}
		final List<Integer> pks = new ArrayList<>();
		typeIndex.forEachReferenceIndexPrimaryKey(pks::add);
		Bitmap smallest = null;
		Bitmap largest = null;
		for (final int pk : pks) {
			final EntityIndex index = collection.getIndexByPrimaryKeyIfExists(pk);
			if (index == null) {
				continue;
			}
			final Bitmap owners = index.getAllPrimaryKeys();
			if (owners.size() >= 20 && (smallest == null || owners.size() < smallest.size())) {
				smallest = owners;
			}
			if (largest == null || owners.size() > largest.size()) {
				largest = owners;
			}
		}
		final List<Bitmap> shapes = new ArrayList<>(2);
		if (smallest != null) {
			shapes.add(smallest);
		}
		if (largest != null) {
			shapes.add(largest);
		}
		return shapes;
	}

	/**
	 * Counts the reduced indexes a reference advertises, duplicates included — a group index is advertised
	 * once per referenced entity filed under it, and the walk visits it once per advertisement.
	 *
	 * @param collection    the measured collection
	 * @param referenceName the reference
	 * @return advertisement count
	 */
	private static long advertisedCount(
		@Nonnull EntityCollection collection,
		@Nonnull String referenceName
	) {
		final long[] counter = new long[1];
		for (final EntityIndexType family : new EntityIndexType[]{
			EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
		}) {
			final ReferencedTypeEntityIndex typeIndex = typeIndex(collection, family, referenceName);
			if (typeIndex != null) {
				typeIndex.forEachReferenceIndexPrimaryKey(pk -> counter[0]++);
			}
		}
		return counter[0];
	}

	/**
	 * Prints one reading as median and p95.
	 *
	 * @param label   what was measured
	 * @param samples per-round durations
	 */
	private static void report(@Nonnull String label, @Nonnull long[] samples) {
		final long[] sorted = samples.clone();
		Arrays.sort(sorted);
		System.out.printf(
			"    %-10s median %,12d ns   p95 %,12d ns%n",
			label, sorted[sorted.length / 2], sorted[(int) (sorted.length * 0.95)]
		);
	}

	/**
	 * Resolves the collection's `LIVE` global index, or `null` when the scope holds none.
	 *
	 * @param collection the collection holding the indexes
	 * @return the global index, or `null` when absent or of an unexpected type
	 */
	@Nullable
	private static GlobalEntityIndex globalIndex(@Nonnull EntityCollection collection) {
		final EntityIndex index = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
		return index instanceof final GlobalEntityIndex typed ? typed : null;
	}

	/**
	 * Resolves one `REFERENCED_*_TYPE` index of a reference in the `LIVE` scope, whatever its declared index
	 * type.
	 *
	 * @param collection    the collection holding the indexes
	 * @param family        the referenced-type index family
	 * @param referenceName the reference whose type index is resolved
	 * @return the type index, or `null` when the reference has none of that kind
	 */
	@Nullable
	private static ReferencedTypeEntityIndex typeIndex(
		@Nonnull EntityCollection collection,
		@Nonnull EntityIndexType family,
		@Nonnull String referenceName
	) {
		final EntityIndex index = collection.getIndexByKeyIfExists(
			new EntityIndexKey(family, Scope.LIVE, referenceName)
		);
		return index instanceof final ReferencedTypeEntityIndex typed ? typed : null;
	}

	/**
	 * Opens an embedded engine over an existing storage directory, with the default thread-pool options — the
	 * measurement is single-threaded, so nothing here is tuned for it.
	 *
	 * @param storageDirectory directory holding the catalog to read
	 * @return the running engine, which the caller closes
	 */
	@Nonnull
	private static Evita openEvita(@Nonnull Path storageDirectory) {
		return new Evita(
			EvitaConfiguration.builder()
				.storage(StorageOptions.builder().storageDirectory(storageDirectory).build())
				.server(
					ServerOptions.builder()
						.requestThreadPool(ThreadPoolOptions.requestThreadPoolBuilder().build())
						.build()
				)
				.build()
		);
	}

	/**
	 * Waits until the catalog finishes its background load and becomes a usable {@link Catalog}.
	 *
	 * @param evita       the running engine
	 * @param catalogName the catalog to wait for
	 * @return the loaded catalog
	 */
	@Nonnull
	private static Catalog awaitLoaded(@Nonnull Evita evita, @Nonnull String catalogName) {
		final long deadline = System.nanoTime() + LOAD_TIMEOUT_NANOS;
		while (System.nanoTime() < deadline) {
			final CatalogContract candidate = evita.getCatalogInstance(catalogName)
				.orElseThrow(() -> new IllegalArgumentException("Catalog `" + catalogName + "` not found!"));
			if (candidate instanceof final Catalog loaded) {
				return loaded;
			}
			try {
				Thread.sleep(500L);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for catalog!", e);
			}
		}
		throw new IllegalStateException("Catalog `" + catalogName + "` did not load in time!");
	}

	/**
	 * Not instantiable — this is a `main()`-style report.
	 */
	private ConditionalFacetMembershipReport() {
	}
}
