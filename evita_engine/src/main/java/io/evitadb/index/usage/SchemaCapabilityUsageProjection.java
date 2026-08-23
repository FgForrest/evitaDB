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

package io.evitadb.index.usage;

import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics.ElementKind;
import io.evitadb.index.usage.SchemaCapabilityUsageRegistry.UsageEntry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Projects one owner's {@link SchemaCapabilityUsageRegistry} into the {@link SchemaCapabilityUsageStatistics} rows the
 * diagnostic surface reports.
 *
 * **One projection serves both owners**, unlike the index projections it sits beside - an entity collection's registry
 * and the catalog's produce identical rows differing only in `entityType`, because a capability of a globally-unique
 * attribute is described by exactly the same fields as a capability of a collection's own. There is nothing here that
 * would have to be specialised per owner, so specialising it would be two copies of one mapping able to drift apart.
 *
 * **The response is schema-bounded**, which is what allows it to be a plain list where an index browse needs paging,
 * filtering and a top-N heap: a collection declares dozens of indexed elements, not the hundreds of thousands of
 * physical indexes they fan out into. Allocating a row per entry and sorting the lot is affordable at that size, and
 * this runs when an operator asks a question rather than on any path a query or a write takes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see SchemaCapabilityUsageStatistics
 * @see SchemaCapabilityUsageRegistry
 */
public final class SchemaCapabilityUsageProjection {

	/**
	 * The order rows are reported in.
	 *
	 * Ordered at all - rather than handed over in the registry's hash order - because two polls of the same unchanged
	 * registry would otherwise reshuffle a table an operator is reading, and because the rows a reader compares are
	 * the ones of a single element: the `FILTERABLE` and `SORTABLE` entries of one attribute belong next to each
	 * other, not scattered by a hash. The element's own name therefore ranks before the capability, and the container
	 * before both, so a reference's attributes arrive grouped under it.
	 *
	 * The kind participates only as a tiebreaker between an attribute and a sortable compound sharing a name, which is
	 * the one collision the fields above cannot resolve.
	 *
	 * The rows describing an element that *is* a container - a reference's own `INDEXED` and `FACETED` - are grouped
	 * by {@link #groupingContainer} rather than by their own null container, and ranked ahead of the rows inside that
	 * container by {@link #withinGroupRank}. Sorting them on `containerName` alone would strand a reference's own
	 * flags among the entity-level rows while its attributes sat elsewhere, which is the grouping this order exists
	 * to provide.
	 */
	private static final Comparator<SchemaCapabilityUsageStatistics> ROW_ORDER =
		Comparator.comparing(
				SchemaCapabilityUsageProjection::groupingContainer, Comparator.nullsFirst(Comparator.naturalOrder())
			)
			.thenComparingInt(SchemaCapabilityUsageProjection::withinGroupRank)
			.thenComparing(SchemaCapabilityUsageStatistics::elementName)
			.thenComparing(SchemaCapabilityUsageStatistics::elementKind)
			.thenComparing(SchemaCapabilityUsageStatistics::capability)
			.thenComparing(SchemaCapabilityUsageStatistics::scope);

	/**
	 * The container a row is rendered *under* - the reference owning it, or null for the entity level.
	 *
	 * This is {@link SchemaCapabilityUsageStatistics#containerName()} for every kind but one. A
	 * {@link ElementKind#REFERENCE} row describes the reference itself, so its name lives in `elementName` and its
	 * `containerName` is null; grouping it by that null would file it among the entity's own rows instead of at the
	 * head of the reference it names.
	 *
	 * @param row the row being ordered
	 * @return name of the reference the row belongs under, or null when it belongs at the entity level
	 */
	@Nullable
	private static String groupingContainer(@Nonnull SchemaCapabilityUsageStatistics row) {
		return row.elementKind() == ElementKind.REFERENCE ? row.elementName() : row.containerName();
	}

	/**
	 * Ranks a row within its group, so that the flags of the container itself precede the flags of what it contains.
	 *
	 * @param row the row being ordered
	 * @return 0 for a row describing the container itself, 1 for one describing an element inside it
	 */
	private static int withinGroupRank(@Nonnull SchemaCapabilityUsageStatistics row) {
		// no `default` branch on purpose: a future element kind must fail to compile here rather than sort arbitrarily
		return switch (row.elementKind()) {
			case REFERENCE, ENTITY -> 0;
			case ATTRIBUTE, SORTABLE_COMPOUND -> 1;
		};
	}

	/**
	 * Renders everything one owner has observed so far.
	 *
	 * The counts of one row are read a nanosecond apart from each other and from the next row's, and nothing is frozen
	 * first - see {@link SchemaCapabilityUsageRegistry#listUsages()} for why a reading that is current beats one that
	 * is coherent here.
	 *
	 * @param entityType name of the entity collection the registry belongs to, or null when the catalog owns it - the
	 *                   value every produced row carries as its owner
	 * @param registry   the registry to read
	 * @param measured   whether anything has been counting into that registry - the server-wide
	 *                   `server.usageStatisticsTracking` switch, stamped onto every row so that a client can tell a
	 *                   capability nothing asked for from one nobody was counting.
	 *
	 *                   **The registry is seeded either way**, which is why the rows exist at all with the switch off:
	 *                   {@link SchemaCapabilityUsageRegistry#alignWith(EntitySchemaContract)} runs on collection
	 *                   creation and on schema adoption, never on a query or a write, so leaving it on costs nothing
	 *                   measurable and keeps the one thing that stays true without counters - *which capabilities this
	 *                   schema declares*. What the switch removes is the hot-path work: the key allocation and map
	 *                   lookup a query does per candidate plan, and the resolve a write does per touched element
	 * @return the rows, ordered as {@link #ROW_ORDER} describes, empty when nothing has been observed yet
	 */
	@Nonnull
	public static List<SchemaCapabilityUsageStatistics> project(
		@Nullable String entityType,
		@Nonnull SchemaCapabilityUsageRegistry registry,
		boolean measured
	) {
		final List<UsageEntry> entries = registry.listUsages();
		final List<SchemaCapabilityUsageStatistics> rows = new ArrayList<>(entries.size());
		for (final UsageEntry entry : entries) {
			final SchemaCapabilityKey key = entry.key();
			final SchemaCapabilityUsage usage = entry.usage();
			rows.add(
				new SchemaCapabilityUsageStatistics(
					entityType,
					key.elementKind(),
					key.containerName(),
					key.elementName(),
					key.capability(),
					key.scope(),
					usage.getRequestedCount(),
					usage.getUpdatedCount(),
					toTimestamp(usage.getLastRequestedAtMillis()),
					toTimestamp(usage.getLastUpdatedAtMillis()),
					atSystemZone(usage.getObservedSinceMillis()),
					measured
				)
			);
		}
		rows.sort(ROW_ORDER);
		return rows;
	}

	/**
	 * Decodes one stamp, turning the holder's "never" sentinel into an explicit absence rather than an epoch-zero
	 * instant a client would render as a date in 1970 - the same decoding {@link io.evitadb.index.IndexActivity}
	 * performs for the per-index stamps, done here because the holder deliberately keeps its write side allocation-free
	 * and hands out raw millis.
	 *
	 * @param millis the recorded stamp, `0` when nothing was ever recorded
	 * @return the timestamp, or null when nothing was ever recorded
	 */
	@Nullable
	private static OffsetDateTime toTimestamp(long millis) {
		return millis == 0L ? null : atSystemZone(millis);
	}

	/**
	 * Renders epoch millis in the JVM's own zone - the conversion the two stamps share with the observation window,
	 * which reaches it directly because it has no sentinel to decode first.
	 *
	 * @param millis the instant to render
	 * @return the timestamp
	 */
	@Nonnull
	private static OffsetDateTime atSystemZone(long millis) {
		return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
	}

	/**
	 * A holder of static projections, never instantiated.
	 */
	private SchemaCapabilityUsageProjection() {
	}

}
