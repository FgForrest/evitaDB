/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.configuration;

import io.evitadb.api.exception.ApiNotFoundOnClasspath;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * This DTO record encapsulates common settings shared among all the API endpoints.
 *
 * @param exposedOn   the name of the host the APIs will be exposed on when evitaDB is running inside a container
 * @param accessLog   defines whether the access logs will be enabled or not
 * @param endpoints   contains specific configuration for all the API endpoints
 * @param certificate defines the certificate settings that will be used to secure connections to the web servers providing APIs
 * @param exposedOn              the name of the host the APIs will be exposed on when evitaDB is running inside a container
 * @param workerGroupThreads              defines the number of IO threads
 * @param serviceWorkerGroupThreads              defines the number of threads for execution of service logic
 * @param idleTimeoutInMillis    The amount of time a connection can be idle for before it is timed out. An idle connection is a
 *                               connection that has had no data transfer in the idle timeout period. Note that this is a fairly coarse
 *                               grained approach, and small values will cause problems for requests with a long processing time.
 * @param parseTimeoutInMillis   How long a request can spend in the parsing phase before it is timed out. This timer is started when
 *                               the first bytes of a request are read, and finishes once all the headers have been parsed.
 * @param requestTimeoutInMillis The amount of time a connection can sit idle without processing a request, before it is closed by
 *                               the server.
 * @param keepAlive              If this is true then a Connection: keep-alive header will be added to responses, even when it is not strictly required by
 *                               the specification.
 * @param maxEntitySizeInBytes   The default maximum size of a request entity. If entity body is larger than this limit then a
 *                               java.io.IOException will be thrown at some point when reading the request (on the first read for fixed
 *                               length requests, when too much data has been read for chunked requests).
 * @param accessLog              defines whether the access logs will be enabled or not
 * @param endpoints              contains specific configuration for all the API endpoints
 * @param certificate            defines the certificate settings that will be used to secure connections to the web servers providing APIs
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record ApiOptions(
	@Nonnull String exposedOn,
	int workerGroupThreads,
	int serviceWorkerGroupThreads,
	int idleTimeoutInMillis,
	int requestTimeoutInMillis,
	int parseTimeoutInMillis,
	boolean keepAlive,
	long maxEntitySizeInBytes,
	boolean accessLog,
	@Nonnull CertificateSettings certificate,
	@Nonnull Map<String, AbstractApiConfiguration> endpoints
) {
	public static final int DEFAULT_WORKER_GROUP_THREADS = Runtime.getRuntime().availableProcessors();
	public static final int DEFAULT_SERVICE_WORKER_GROUP_THREADS = Runtime.getRuntime().availableProcessors() << 1;
	public static final int DEFAULT_IDLE_TIMEOUT = 20 * 1000;
	public static final int DEFAULT_PARSE_TIMEOUT = 1000;
	public static final int DEFAULT_REQUEST_TIMEOUT = 1000;
	public static final boolean DEFAULT_KEEP_ALIVE = true;
	public static final long DEFAULT_MAX_ENTITY_SIZE = 2_097_152L;

	/**
	 * Builder for the api options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static ApiOptions.Builder builder() {
		return new ApiOptions.Builder();
	}

	public ApiOptions(
		@Nonnull String exposedOn,
		int workerGroupThreads, int serviceWorkerGroupThreads, int idleTimeoutInMillis, int requestTimeoutInMillis, int parseTimeoutInMillis,
		boolean keepAlive, long maxEntitySizeInBytes, boolean accessLog,
		@Nonnull CertificateSettings certificate,
		@Nonnull Map<String, AbstractApiConfiguration> endpoints
	) {
		this.exposedOn = exposedOn;
		this.workerGroupThreads = workerGroupThreads <= 0 ? DEFAULT_WORKER_GROUP_THREADS : workerGroupThreads;
		this.serviceWorkerGroupThreads = serviceWorkerGroupThreads <= 0 ? DEFAULT_SERVICE_WORKER_GROUP_THREADS : serviceWorkerGroupThreads;
		this.idleTimeoutInMillis = idleTimeoutInMillis <= 0 ? DEFAULT_IDLE_TIMEOUT : idleTimeoutInMillis;
		this.requestTimeoutInMillis = requestTimeoutInMillis <= 0 ? DEFAULT_REQUEST_TIMEOUT : requestTimeoutInMillis;
		this.parseTimeoutInMillis = parseTimeoutInMillis <= 0 ? DEFAULT_PARSE_TIMEOUT : parseTimeoutInMillis;
		this.keepAlive = keepAlive;
		this.maxEntitySizeInBytes = maxEntitySizeInBytes <= 0 ? DEFAULT_MAX_ENTITY_SIZE : maxEntitySizeInBytes;
		this.accessLog = accessLog;
		this.certificate = certificate;
		this.endpoints = endpoints;

	}

	public ApiOptions() {
		this(
			null, DEFAULT_WORKER_GROUP_THREADS, DEFAULT_SERVICE_WORKER_GROUP_THREADS, DEFAULT_IDLE_TIMEOUT, DEFAULT_REQUEST_TIMEOUT, DEFAULT_PARSE_TIMEOUT,
			DEFAULT_KEEP_ALIVE, DEFAULT_MAX_ENTITY_SIZE, false,
			new CertificateSettings(), new HashMap<>(8)
		);
	}

	/**
	 * Returns endpoint configuration if present.
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public <T extends AbstractApiConfiguration> T getEndpointConfiguration(@Nonnull String endpointCode) {
		return (T) endpoints.get(endpointCode);
	}

	/**
	 * Returns set {@link #workerGroupThreads} or returns a default value.
	 */
	public int workerGroupThreadsAsInt() {
		return ofNullable(workerGroupThreads)
			// double the value of available processors (recommended by Netty configuration)
			.orElse(DEFAULT_WORKER_GROUP_THREADS);
	}

	/**
	 * Returns set {@link #serviceWorkerGroupThreads} or returns a default value.
	 */
	public int serviceWorkerGroupThreadsAsInt() {
		return ofNullable(serviceWorkerGroupThreads)
			// double the value of available processors (recommended by Netty configuration)
			.orElse(DEFAULT_SERVICE_WORKER_GROUP_THREADS);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private final Map<String, Class<?>> apiProviders;
		private final Map<String, AbstractApiConfiguration> enabledProviders;
		private int idleTimeoutInMillis = DEFAULT_IDLE_TIMEOUT;
		private int requestTimeoutInMillis = DEFAULT_REQUEST_TIMEOUT;
		private int parseTimeoutInMillis = DEFAULT_PARSE_TIMEOUT;
		private boolean keepAlive = DEFAULT_KEEP_ALIVE;
		private long maxEntitySizeInBytes = DEFAULT_MAX_ENTITY_SIZE;
		private CertificateSettings certificate;
		@Nullable private String exposedOn;
		private int workerGroupThreads = DEFAULT_WORKER_GROUP_THREADS;
		private int serviceWorkerGroupThreads = DEFAULT_SERVICE_WORKER_GROUP_THREADS;
		private boolean accessLog;

		Builder() {
			//noinspection unchecked
			apiProviders = ExternalApiServer.gatherExternalApiProviders()
				.stream()
				.collect(
					Collectors.toMap(
						ExternalApiProviderRegistrar::getExternalApiCode,
						ExternalApiProviderRegistrar::getConfigurationClass
					)
				);
			enabledProviders = CollectionUtils.createHashMap(apiProviders.size());
			certificate = new CertificateSettings.Builder().build();
		}

		@Nonnull
		public ApiOptions.Builder exposedOn(@Nonnull String exposedOn) {
			this.exposedOn = exposedOn;
			return this;
		}

		@Nonnull
		public ApiOptions.Builder workerGroupThreads(int workerGroupThreads) {
			this.workerGroupThreads = workerGroupThreads;
			return this;
		}

		@Nonnull
		public ApiOptions.Builder serviceWorkerGroupThreads(int serviceWorkerGroupThreads) {
			this.serviceWorkerGroupThreads = serviceWorkerGroupThreads;
			return this;
		}

		@Nonnull
		public ApiOptions.Builder idleTimeoutInMillis(int idleTimeoutInMillis) {
			this.idleTimeoutInMillis = idleTimeoutInMillis;
			return this;
		}

		@Nonnull
		public ApiOptions.Builder requestTimeoutInMillis(int requestTimeoutInMillis) {
			this.requestTimeoutInMillis = requestTimeoutInMillis;
			return this;
		}

		@Nonnull
		public ApiOptions.Builder parseTimeoutInMillis(int parseTimeoutInMillis) {
			this.parseTimeoutInMillis = parseTimeoutInMillis;
			return this;
		}

		@Nonnull
		public ApiOptions.Builder keepAlive(boolean keepAlive) {
			this.keepAlive = keepAlive;
			return this;
		}

		@Nonnull
		public ApiOptions.Builder maxEntitySizeInBytes(long maxEntitySizeInBytes) {
			this.maxEntitySizeInBytes = maxEntitySizeInBytes;
			return this;
		}

		@Nonnull
		public ApiOptions.Builder accessLog(boolean accessLog) {
			this.accessLog = accessLog;
			return this;
		}

		@Nonnull
		public ApiOptions.Builder certificate(@Nonnull CertificateSettings certificate) {
			this.certificate = certificate;
			return this;
		}


		@Nonnull
		public ApiOptions.Builder enable(@Nonnull String apiCode) {
			final Class<?> configurationClass = ofNullable(this.apiProviders.get(apiCode))
				.orElseThrow(() -> new ApiNotFoundOnClasspath(apiCode));
			final AbstractApiConfiguration cfg;
			try {
				cfg = (AbstractApiConfiguration) configurationClass.getDeclaredConstructor().newInstance();
			} catch (Exception ex) {
				throw new GenericEvitaInternalError(
					"Failed to instantiate default configuration of `" + apiCode + "` API: " + ex.getMessage(),
					"Failed to instantiate default configuration of `" + apiCode + "` API!",
					ex
				);
			}
			this.enabledProviders.put(apiCode, cfg);
			return this;
		}

		@Nonnull
		public <T extends AbstractApiConfiguration> ApiOptions.Builder enable(@Nonnull String apiCode, @Nonnull T configuration) {
			final Class<?> configurationClass = ofNullable(this.apiProviders.get(apiCode))
				.orElseThrow(() -> new ApiNotFoundOnClasspath(apiCode));
			Assert.isTrue(
				configurationClass.isInstance(configuration),
				"Passed configuration is not of type `" + configurationClass.getName() + "`"
			);
			this.enabledProviders.put(apiCode, configuration);
			return this;
		}

		@Nonnull
		public ApiOptions build() {
			return new ApiOptions(
				exposedOn, workerGroupThreads, serviceWorkerGroupThreads, idleTimeoutInMillis, requestTimeoutInMillis, parseTimeoutInMillis,
				keepAlive, maxEntitySizeInBytes, accessLog, certificate, enabledProviders
			);
		}
	}

}
