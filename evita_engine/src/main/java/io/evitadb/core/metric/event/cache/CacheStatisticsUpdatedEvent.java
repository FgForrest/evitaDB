/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.metric.event.cache;

import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * Event that is fired when a transaction is started.
 */
@Name(AbstractCacheEvent.PACKAGE_NAME + ".CacheStatisticsUpdated")
@Description("Event that is fired periodically to update cache statistics.")
@Label("Cache statistics updated")
public class CacheStatisticsUpdatedEvent extends AbstractCacheStatisticsRelatedEvent {

	public CacheStatisticsUpdatedEvent(
		long cacheHitsTotal,
		long cacheMissesTotal,
		long cacheEnrichmentsTotal,
		long cacheInitializationsTotal
	) {
		super(cacheHitsTotal, cacheMissesTotal, cacheEnrichmentsTotal, cacheInitializationsTotal);
	}
}
