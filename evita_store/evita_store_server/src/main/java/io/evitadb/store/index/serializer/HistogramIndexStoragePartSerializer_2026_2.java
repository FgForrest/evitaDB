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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStoragePart;
import io.evitadb.store.index.serializer.FilterIndexPayloadSerializer.FilterIndexPayload;
import io.evitadb.store.index.serializer.HistogramIdentitySerializer.HistogramIdentity;
import io.evitadb.store.index.serializer.PagedStreamMetadataSerializer.PagedStreamMetadata;

import java.io.Serializable;

/**
 * Backward-compatible {@link Serializer} for the {@link HistogramIndexStoragePart} format shipped by release 2026.2.
 * Its **byte layout is identical** to the one {@link HistogramIndexStoragePartSerializer} writes today — the histogram
 * identity, the value type, then the shared filter payload. What changed is not the layout but the meaning of the
 * payload's range thresholds: they were epoch **seconds**, because {@code DateTimeRange} compared at second
 * granularity, and they are epoch **milliseconds** now.
 *
 * This reader exists because the record really did ship. Its predecessor uid was left unregistered on the premise
 * that "the reference bucketed-histogram feature is unreleased (absent from every release branch), so no released
 * catalog carries a histogram part" — a premise that is **false** for uid `5083172946028471653L`, which
 * `git tag --contains fa01ba65f` places in v2026.2.0 through v2026.2.6. A released catalog carrying a histogram over
 * a `DateTimeRange` attribute would otherwise have every range threshold read a thousand times too small, silently.
 * (The uid *before* that one, `7294816253748291063L`, genuinely never shipped, which is why nothing reads it.)
 *
 * Like {@link FilterIndexStoragePartSerializer_2026_2}, this reader only **marks** the part's provenance; the rescale
 * itself happens in the load path. It cannot happen here: a range-`PAGED` axis keeps its thresholds in
 * {@code HistogramRangeIndexLeafPagePart} records this serializer never sees, and only a `DateTimeRange` histogram may
 * be rescaled at all. See {@code HistogramIndexMapLoader#reloadOwnerFilterIndex}.
 *
 * This serializer only reads — writes always go through the current {@link HistogramIndexStoragePartSerializer},
 * which persists the millisecond form under the bumped serial-version-uid.
 *
 * @deprecated kept for backward compatibility; can be removed once no catalog written before `DateTimeRange` moved to
 *             millisecond comparison granularity is still in use.
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.2", forRemoval = true)
public class HistogramIndexStoragePartSerializer_2026_2 extends Serializer<HistogramIndexStoragePart> {

	@Override
	public void write(Kryo kryo, Output output, HistogramIndexStoragePart part) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used for writing.");
	}

	@SuppressWarnings("unchecked")
	@Override
	public HistogramIndexStoragePart read(Kryo kryo, Input input, Class<? extends HistogramIndexStoragePart> type) {
		final HistogramIdentity identity = HistogramIdentitySerializer.read(kryo, input);
		final Class<? extends Serializable> valueType =
			(Class<? extends Serializable>) kryo.readClass(input).getType();

		final FilterIndexPayload payload = FilterIndexPayloadSerializer.read(kryo, input);
		final PagedStreamMetadata bucketMetadata = payload.bucketMetadata();
		final PagedStreamMetadata rangeMetadata = payload.rangeMetadata();

		final HistogramIndexStoragePart part = new HistogramIndexStoragePart(
			identity.entityIndexPrimaryKey(), identity.histogramName(), identity.locale(), valueType,
			payload.points(), payload.rangeIndex(), payload.indexedDecimalPlaces(),
			bucketMetadata.paged(), bucketMetadata.highWaterPageSequence(), bucketMetadata.leafPageSequences(),
			rangeMetadata.paged(), rangeMetadata.highWaterPageSequence(), rangeMetadata.leafPageSequences(),
			identity.uniquePartId()
		);
		// every HistogramIndexStoragePart format older than the millisecond change persisted its range thresholds as
		// epoch SECONDS; mark the provenance so HistogramIndexMapLoader can rescale the ones that belong to a
		// `DateTimeRange` histogram (a threshold is an untyped long shared with every NumberRange subtype, so the
		// persisted value type - not this flag alone - decides)
		part.setSecondGranularityRangeThresholds(true);
		return part;
	}

}
