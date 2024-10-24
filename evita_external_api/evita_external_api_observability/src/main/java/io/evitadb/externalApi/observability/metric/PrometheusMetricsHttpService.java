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

package io.evitadb.externalApi.observability.metric;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.core.Evita;
import io.evitadb.core.async.ObservableThreadExecutor;
import io.evitadb.utils.CollectionUtils;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.exporter.common.PrometheusScrapeHandler;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.snapshots.Unit;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * This service provides Prometheus metrics in text format. The service mimics original PrometheusServlet behavior.
 * The service also actively updates metrics for thread pools and ForkJoinPools. These metrics cannot be easily updated
 * via JFR events because it would cause a lot of overhead - instead they are lazily actuated in a light-weight manner
 * when the metrics are scraped.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class PrometheusMetricsHttpService implements HttpService {
	private static final Unit UNIT_TASKS = new Unit("tasks");
	private static final Unit UNIT_THREADS = new Unit("threads");
	private static final Map<String, Collector> REGISTERED_THREAD_POOL_METRICS = CollectionUtils.createHashMap(256);
	private final Evita evita;
	private final PrometheusScrapeHandler prometheusScrapeHandler;
	private final List<Runnable> metricActuators;


	/**
	 * Monitors various metrics of a given ThreadPoolExecutor and returns a stream of
	 * Runnable tasks that can be used to update these metrics.
	 *
	 * @param metricPrefix The prefix to use for the metric names.
	 * @param tp           The ThreadPoolExecutor to monitor.
	 * @return A stream of Runnables that update the metrics when executed.
	 */
	@Nonnull
	private static Stream<Runnable> monitor(@Nonnull String metricPrefix, @Nonnull ThreadPoolExecutor tp) {
		final Counter completed = (Counter) REGISTERED_THREAD_POOL_METRICS.computeIfAbsent(
			metricPrefix + "executor_completed",
			name -> Counter.builder()
				.name(name)
				.help("The approximate total number of tasks that have completed execution")
				.unit(UNIT_TASKS)
				.register()
		);

		final Gauge active = (Gauge) REGISTERED_THREAD_POOL_METRICS.computeIfAbsent(
			metricPrefix + "executor_active",
			name -> Gauge.builder()
				.name(name)
				.help("The approximate number of threads that are actively executing tasks")
				.unit(UNIT_THREADS)
				.register()
		);

		final Gauge queued = (Gauge) REGISTERED_THREAD_POOL_METRICS.computeIfAbsent(
			metricPrefix + "executor_queued",
			name -> Gauge.builder()
				.name(name)
				.help("The approximate number of tasks that are queued for execution")
				.unit(UNIT_TASKS)
				.register()
		);

		final Gauge queueRemaining = (Gauge) REGISTERED_THREAD_POOL_METRICS.computeIfAbsent(
			metricPrefix + "executor_queue_remaining",
			name -> Gauge.builder()
				.name(name)
				.help("The number of additional elements that this queue can ideally accept without blocking")
				.unit(UNIT_TASKS)
				.register()
		);

		final Gauge poolSize = (Gauge) REGISTERED_THREAD_POOL_METRICS.computeIfAbsent(
			metricPrefix + "executor_pool_size",
			name -> Gauge.builder()
				.name(name)
				.help("The current number of threads in the pool")
				.unit(UNIT_THREADS)
				.register()
		);

		final Gauge poolCore = (Gauge) REGISTERED_THREAD_POOL_METRICS.computeIfAbsent(
			metricPrefix + "executor_pool_core",
			name -> Gauge.builder()
				.name(name)
				.help("The core number of threads for the pool")
				.unit(UNIT_THREADS)
				.register()
		);

		final Gauge poolMax = (Gauge) REGISTERED_THREAD_POOL_METRICS.computeIfAbsent(
			metricPrefix + "executor_pool_max",
			name -> Gauge.builder()
				.name(name)
				.help("The maximum allowed number of threads in the pool")
				.unit(UNIT_THREADS)
				.register()
		);

		return Stream.of(
			() -> completed.inc(tp.getCompletedTaskCount() - tp.getCompletedTaskCount()),
			() -> active.set(tp.getActiveCount()),
			() -> queued.set(tp.getQueue().size()),
			() -> queueRemaining.set(tp.getQueue().remainingCapacity()),
			() -> poolSize.set(tp.getPoolSize()),
			() -> poolCore.set(tp.getCorePoolSize()),
			() -> poolMax.set(tp.getMaximumPoolSize())
		);
	}

	/**
	 * Monitors various metrics of a given ForkJoinPool and returns a stream of
	 * Runnable tasks that can be used to update these metrics.
	 *
	 * @param metricPrefix The prefix to use for the metric names.
	 * @param fj           The ForkJoinPool to monitor.
	 * @return A stream of Runnables that update the metrics when executed.
	 */
	@Nonnull
	private static Stream<Runnable> monitor(@Nonnull String metricPrefix, @Nonnull ForkJoinPool fj) {
		final Counter steals = (Counter) REGISTERED_THREAD_POOL_METRICS.computeIfAbsent(
			metricPrefix + "executor_steals",
			name -> Counter.builder()
				.name(name)
				.help(
					"Estimate of the total number of tasks stolen from one thread's work queue by another. The reported value " +
						"underestimates the actual total number of steals when the pool is not quiescent")
				.unit(UNIT_TASKS)
				.register()
		);
		final Gauge queued = (Gauge) REGISTERED_THREAD_POOL_METRICS.computeIfAbsent(
			metricPrefix + "executor_queued",
			name -> Gauge.builder()
				.name(name)
				.help("An estimate of the total number of tasks currently held in queues by worker threads")
				.unit(UNIT_TASKS)
				.register()
		);
		final Gauge active = (Gauge) REGISTERED_THREAD_POOL_METRICS.computeIfAbsent(
			metricPrefix + "executor_active",
			name -> Gauge.builder()
				.name(name)
				.help("An estimate of the number of threads that are currently stealing or executing tasks")
				.unit(UNIT_THREADS)
				.register()
		);
		final Gauge running = (Gauge) REGISTERED_THREAD_POOL_METRICS.computeIfAbsent(
			metricPrefix + "executor_running",
			name -> Gauge.builder()
				.name(name)
				.help(
					"An estimate of the number of worker threads that are not blocked waiting to join tasks or for other managed" +
						" synchronization threads"
				)
				.unit(UNIT_THREADS)
				.register()
		);

		return Stream.of(
			() -> steals.inc(fj.getStealCount() - steals.get()),
			() -> queued.set(fj.getQueuedTaskCount()),
			() -> active.set(fj.getActiveThreadCount()),
			() -> running.set(fj.getRunningThreadCount())
		);
	}

	public PrometheusMetricsHttpService(@Nonnull Evita evita) {
		this.evita = evita;
		this.prometheusScrapeHandler = new PrometheusScrapeHandler();
		this.metricActuators = Stream.of(
				monitor("io_evitadb_scheduled_", evita.getServiceExecutor().getExecutorServiceInternal()),
				monitor("io_evitadb_request_", ((ObservableThreadExecutor) evita.getRequestExecutor()).getForkJoinPoolInternal()),
				monitor("io_evitadb_transaction_", ((ObservableThreadExecutor) evita.getTransactionExecutor()).getForkJoinPoolInternal())
			)
			.flatMap(Function.identity())
			.toList();
	}

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) {
		return HttpResponse.of(
			evita.executeAsyncInRequestThreadPool(
				() -> {
					try (final ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
						final ArmeriaPrometheusHttpExchangeAdapter exchange = new ArmeriaPrometheusHttpExchangeAdapter(ctx, req, outputStream);
						// actuate thread metrics
						this.metricActuators.forEach(Runnable::run);
						// return metrics for scrape
						this.prometheusScrapeHandler.handleRequest(exchange);
						return HttpResponse.of(exchange.headersBuilder().build(), HttpData.copyOf(outputStream.toByteArray()));
					} catch (IOException e) {
						return HttpResponse.ofFailure(e);
					}
				}
			)
		);
	}
}
