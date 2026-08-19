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

import io.evitadb.api.statistics.SchemaCapabilityUsageSnapshot;
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
 * Projects one owner's {@link SchemaCapabilityUsageRegistry} into the {@link SchemaCapabilityUsageSnapshot} rows the
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
 * @see SchemaCapabilityUsageSnapshot
 * @see SchemaCapabilityUsageRegistry
 */
public final class SchemaCapabilityUsageProjection {

	/**
	 * The order rows are reported in.
	 *
	 * Ordered at all - rather than handed over in the registry's hash order - because two polls of the same unchanged
	 * registry would otherwise reshuffle a table an operator is reading, and because the rows a reader compares are
	 * the ones of a single element: the `FILTER` and `SORT` entries of one attribute belong next to each other, not
	 * scattered by a hash. The element's own name therefore ranks before the capability, and the container before
	 * both, so a reference's attributes arrive grouped under it.
	 *
	 * The kind participates only as a tiebreaker between an attribute and a sortable compound sharing a name, which is
	 * the one collision the fields above cannot resolve.
	 */
	private static final Comparator<SchemaCapabilityUsageSnapshot> ROW_ORDER =
		Comparator.comparing(
				SchemaCapabilityUsageSnapshot::containerName, Comparator.nullsFirst(Comparator.naturalOrder())
			)
			.thenComparing(SchemaCapabilityUsageSnapshot::elementName)
			.thenComparing(SchemaCapabilityUsageSnapshot::elementKind)
			.thenComparing(SchemaCapabilityUsageSnapshot::capability)
			.thenComparing(SchemaCapabilityUsageSnapshot::scope);

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
	 * @return the rows, ordered as {@link #ROW_ORDER} describes, empty when nothing has been observed yet
	 */
	@Nonnull
	public static List<SchemaCapabilityUsageSnapshot> project(
		@Nullable String entityType,
		@Nonnull SchemaCapabilityUsageRegistry registry
	) {
		final List<UsageEntry> entries = registry.listUsages();
		final List<SchemaCapabilityUsageSnapshot> rows = new ArrayList<>(entries.size());
		for (int i = 0; i < entries.size(); i++) {
			final UsageEntry entry = entries.get(i);
			final SchemaCapabilityKey key = entry.key();
			final SchemaCapabilityUsage usage = entry.usage();
			rows.add(
				new SchemaCapabilityUsageSnapshot(
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
					atSystemZone(usage.getObservedSinceMillis())
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
