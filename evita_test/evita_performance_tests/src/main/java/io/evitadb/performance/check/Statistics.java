/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.performance.check;

import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.utils.StringUtils;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Contains gathered records from sanity checker.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
class Statistics {
	private final int threadCount;
	private final AtomicLong returnedEntities = new AtomicLong();
	private final AtomicLong returnedEntitiesInTotal = new AtomicLong();
	private final AtomicLong returnedEntityPks = new AtomicLong();
	private final AtomicLong returnedEntityPksInTotal = new AtomicLong();
	private final AtomicInteger queryCount = new AtomicInteger();
	private final AtomicInteger emptyResultQueryCount = new AtomicInteger();
	private final AtomicLong durationInNanos = new AtomicLong();

	public void recordResponseEntityReference(long durationInNanos, EvitaResponse<EntityReference> response) {
		this.queryCount.incrementAndGet();
		this.durationInNanos.addAndGet(durationInNanos);
		this.returnedEntityPks.addAndGet(response.getRecordData().size());
		this.returnedEntityPksInTotal.addAndGet(response.getTotalRecordCount());
		if (response.getTotalRecordCount() == 0) {
			this.emptyResultQueryCount.incrementAndGet();
		}
	}

	public void recordResponseSealedEntity(long durationInNanos, EvitaResponse<SealedEntity> response) {
		this.queryCount.incrementAndGet();
		this.durationInNanos.addAndGet(durationInNanos);
		this.returnedEntities.addAndGet(response.getRecordData().size());
		this.returnedEntitiesInTotal.addAndGet(response.getTotalRecordCount());
		if (response.getTotalRecordCount() == 0) {
			this.emptyResultQueryCount.incrementAndGet();
		}
	}

	@Override
	public String toString() {
		return "Time taken: " + StringUtils.formatNano(this.durationInNanos.get() / this.threadCount) + ", " +
			"queries: " + this.queryCount.get() + " (avg. " + StringUtils.formatRequestsPerSec(this.queryCount.get(), this.durationInNanos.get() / this.threadCount) + " qps), " +
			"computed results total: " + StringUtils.formatCount(this.returnedEntityPksInTotal.get() + this.returnedEntitiesInTotal.get()) + ", " +
			"returned entities: " + this.returnedEntities.get() + ", " +
			"returned PKs: " + this.returnedEntityPks.get() + ", " +
			"empty results: " + this.emptyResultQueryCount.get() + " (" + (int)(((float)this.emptyResultQueryCount.get() / (float)this.queryCount.get() * 100.0)) + "% of all)";
	}
}
