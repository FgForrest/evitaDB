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

package io.evitadb.core.catalog;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.statistics.DataStoreFragmentation;
import io.evitadb.api.statistics.FragmentationStatistics;
import io.evitadb.spi.store.catalog.persistence.CatalogFragmentationSnapshot;
import io.evitadb.spi.store.catalog.persistence.CatalogStorageFootprint;
import io.evitadb.spi.store.catalog.persistence.CollectionStorageFootprint;
import io.evitadb.spi.store.catalog.persistence.CompactionForecast;

import javax.annotation.Nonnull;

/**
 * Assembles the {@link io.evitadb.api.statistics.CatalogStatisticsComponent#FRAGMENTATION} component out of the three
 * things it is made of: the measured live/waste split, the persistence layer's verdict on compaction, and the
 * configured thresholds that verdict was reached against.
 *
 * It exists as its own class - and is public rather than package-private, unlike {@link StorageSizeProjection} - for
 * the same reason {@link StoragePartProjection} is: the catalog level and the collection level are assembled in
 * different packages, and the active-record-share rule below has to be the same in both. Written out at each call
 * site it would be two definitions of *share*, and a management screen comparing a collection's number against its
 * catalog's would be comparing two different quantities.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class FragmentationProjection {

	private FragmentationProjection() {
		// this class is a namespace for the projections below, never instantiated
	}

	/**
	 * Projects a catalog-wide footprint and forecast onto the statistics component that reports them.
	 *
	 * The configured thresholds ride along so a client can see what the engine is set to act on without a second call.
	 * They are catalog-wide configuration, which is why they appear here and not on the per-collection record - and
	 * they are *not* comparable to the share reported beside them; see `activeRecordShareOf` below and the record's
	 * own javadoc for why.
	 *
	 * @param snapshot       the one measurement of the catalog directory the caller took for this request
	 * @param storageOptions the configured compaction thresholds
	 * @return the catalog-level fragmentation component
	 */
	@Nonnull
	static FragmentationStatistics toFragmentationStatistics(
		@Nonnull CatalogFragmentationSnapshot snapshot,
		@Nonnull StorageOptions storageOptions
	) {
		final CatalogStorageFootprint footprint = snapshot.footprint();
		final CompactionForecast forecast = snapshot.totalForecast();
		final CompactionForecast catalogDataStoreForecast = snapshot.catalogDataStoreForecast();
		return new FragmentationStatistics(
			activeRecordShareOf(footprint.liveBytes(), footprint.wasteBytes()),
			footprint.liveBytes(),
			footprint.wasteBytes(),
			forecast.compactionEligibleNow(),
			forecast.wasteBytesGenerated(),
			forecast.wasteAccumulationRateBytesPerSecond(),
			forecast.estimatedCompactionAt(),
			// the same store the catalog-wide figures already fold in, reported apart from them so that a raised
			// eligibility flag can be attributed to the metadata store or to a collection - see the record
			new DataStoreFragmentation(
				activeRecordShareOf(
					footprint.catalogDataStoreLiveBytes(), footprint.catalogDataStoreWasteBytes()
				),
				footprint.catalogDataStoreLiveBytes(),
				footprint.catalogDataStoreWasteBytes(),
				catalogDataStoreForecast.compactionEligibleNow(),
				catalogDataStoreForecast.wasteBytesGenerated(),
				catalogDataStoreForecast.wasteAccumulationRateBytesPerSecond(),
				catalogDataStoreForecast.estimatedCompactionAt()
			),
			storageOptions.fileSizeCompactionThresholdBytes(),
			storageOptions.minimalActiveRecordShare(),
			storageOptions.maxWasteActiveShare(),
			storageOptions.minCompactionIntervalMilliseconds()
		);
	}

	/**
	 * Projects one collection's footprint and forecast onto the statistics component that reports them.
	 *
	 * @param footprint the listing of this collection's data store files the caller measured for this request
	 * @param forecast  what the persistence layer says about compacting that data store
	 * @return the collection-level fragmentation component
	 */
	@Nonnull
	public static DataStoreFragmentation toDataStoreFragmentation(
		@Nonnull CollectionStorageFootprint footprint,
		@Nonnull CompactionForecast forecast
	) {
		return new DataStoreFragmentation(
			activeRecordShareOf(footprint.liveBytes(), footprint.wasteBytes()),
			footprint.liveBytes(),
			footprint.wasteBytes(),
			forecast.compactionEligibleNow(),
			forecast.wasteBytesGenerated(),
			forecast.wasteAccumulationRateBytesPerSecond(),
			forecast.estimatedCompactionAt()
		);
	}

	/**
	 * Returns the share of the reported bytes that is still live, `1.0` when there are none.
	 *
	 * **Derived from the two reported byte figures, deliberately not read from the store's own `activeRecordShare`.**
	 * The store computes its share against the whole file length, which also carries the serialized offset-index
	 * table and anything else the classification could not attribute; reporting that number next to these two would
	 * ship a record whose own fields cannot reproduce it. The trigger keeps using the store's version - see
	 * `CompactionForecast` - so the two are answering deliberately different questions: *how wasteful is this data*
	 * versus *is this file due for a rewrite*. That consequence is spelled out for clients on
	 * {@link io.evitadb.api.statistics.FragmentationStatistics} itself, because it is the kind of thing a consumer
	 * gets wrong by drawing the share and the thresholds on one gauge; do not let the two explanations drift.
	 *
	 * A store holding nothing at all is fully live rather than fully wasted: `0/0` has no answer, and of the two
	 * conventions this is the one that does not render an empty catalog as the most fragmented thing on the screen.
	 *
	 * @param liveBytes  bytes of active records
	 * @param wasteBytes bytes compaction would reclaim
	 * @return the active record share, between `0` and `1`
	 */
	private static double activeRecordShareOf(long liveBytes, long wasteBytes) {
		final long total = liveBytes + wasteBytes;
		return total == 0L ? 1.0d : (double) liveBytes / (double) total;
	}

}
