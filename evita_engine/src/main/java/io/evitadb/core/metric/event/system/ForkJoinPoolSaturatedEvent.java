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

package io.evitadb.core.metric.event.system;

import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import io.evitadb.api.observability.annotation.ExportMetricLabel;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * Event raised when an {@link io.evitadb.core.executor.ObservableThreadExecutor}'s underlying
 * {@link java.util.concurrent.ForkJoinPool} saturate predicate is triggered, indicating all
 * compensating threads are exhausted and the current thread is allowed to block instead of
 * throwing a {@link java.util.concurrent.RejectedExecutionException}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Name(AbstractSystemEvent.PACKAGE_NAME + ".ObservableThreadExecutorSaturated")
@Description(
	"Event raised when an ObservableThreadExecutor's underlying ForkJoinPool saturate predicate " +
		"is triggered, indicating all compensating threads are exhausted."
)
@ExportInvocationMetric(label = "ObservableThreadExecutor ForkJoinPool saturated")
@Label("ObservableThreadExecutor ForkJoinPool saturated")
@Getter
public class ForkJoinPoolSaturatedEvent extends AbstractSystemEvent {

	/**
	 * The name of the pool that became saturated.
	 */
	@Label("Pool name")
	@Description("Name of the ObservableThreadExecutor pool that became saturated.")
	@ExportMetricLabel
	final String poolName;

	public ForkJoinPoolSaturatedEvent(@Nonnull String poolName) {
		this.poolName = poolName;
	}

}
