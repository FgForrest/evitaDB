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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * This DTO record encapsulates common settings shared among all the API endpoints.
 *
 * @param accessLog              defines whether the access logs will be enabled or not
 * @param endpoints              contains specific configuration for all the API endpoints
 * @param headers                contains header names configuration (overrides and support for client defined headers)
 * @param certificate            defines the certificate settings that will be used to secure connections to the web servers providing APIs
 * @param workerGroupThreads     defines the number of IO threads
 * @param idleTimeoutInMillis    The amount of time a connection can be idle for before it is timed out. An idle connection is a
 *                               connection that has had no data transfer in the idle timeout period. Note that this is a fairly coarse
 *                               grained approach, and small values will cause problems for requests with a long processing time.
 * @param requestTimeoutInMillis The amount of time a connection can sit idle without processing a request, before it is closed by
 *                               the server.
 * @param maxEntitySizeInBytes   The default maximum size of a request entity. If entity body is larger than this limit then a
 *                               java.io.IOException will be thrown at some point when reading the request (on the first read for fixed
 *                               length requests, when too much data has been read for chunked requests).
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record ApiOptions(
	@Nullable Integer workerGroupThreads,
	int idleTimeoutInMillis,
	int requestTimeoutInMillis,
	long maxEntitySizeInBytes,
	boolean accessLog,
	@Nonnull HeaderOptions headers,
	@Nonnull CertificateOptions certificate,
	@Nonnull Map<String, AbstractApiOptions> endpoints
) {
	public static final int DEFAULT_WORKER_GROUP_THREADS = Runtime.getRuntime().availableProcessors();
	public static final int DEFAULT_IDLE_TIMEOUT = 20 * 1000;
	public static final int DEFAULT_REQUEST_TIMEOUT = 1000;
	public static final long DEFAULT_MAX_ENTITY_SIZE = 2_097_152L;

	/**
	 * Builder for the api options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static ApiOptions.Builder builder() {
		return new ApiOptions.Builder();
	}

	public ApiOptions(
		@Nullable Integer workerGroupThreads,
		int idleTimeoutInMillis, int requestTimeoutInMillis,
		long maxEntitySizeInBytes, boolean accessLog,
		@Nonnull HeaderOptions headers,
		@Nonnull CertificateOptions certificate,
		@Nonnull Map<String, AbstractApiOptions> endpoints
	) {
		this.workerGroupThreads = ofNullable(workerGroupThreads).orElse(DEFAULT_WORKER_GROUP_THREADS);
		this.idleTimeoutInMillis = idleTimeoutInMillis <= 0 ? DEFAULT_IDLE_TIMEOUT : idleTimeoutInMillis;
		this.requestTimeoutInMillis = requestTimeoutInMillis <= 0 ? DEFAULT_REQUEST_TIMEOUT : requestTimeoutInMillis;
		this.maxEntitySizeInBytes = maxEntitySizeInBytes <= 0 ? DEFAULT_MAX_ENTITY_SIZE : maxEntitySizeInBytes;
		this.accessLog = accessLog;
		this.headers = headers;
		this.certificate = certificate;
		this.endpoints = endpoints;
	}

	public ApiOptions() {
		this(
			DEFAULT_WORKER_GROUP_THREADS, DEFAULT_IDLE_TIMEOUT, DEFAULT_REQUEST_TIMEOUT,
			DEFAULT_MAX_ENTITY_SIZE, false,
			new HeaderOptions(), new CertificateOptions(), new HashMap<>(8)
		);
	}

	/**
	 * Returns endpoint configuration if present.
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public <T extends AbstractApiOptions> T getEndpointConfiguration(@Nonnull String endpointCode) {
		return (T) this.endpoints.get(endpointCode);
	}

	/**
	 * Returns set {@link #workerGroupThreads} or returns a default value.
	 */
	public int workerGroupThreadsAsInt() {
		return ofNullable(this.workerGroupThreads)
			// double the value of available processors (recommended by Netty configuration)
			.orElse(DEFAULT_WORKER_GROUP_THREADS);
	}

	/**
	 * Returns true if at least one endpoint requires TLS.
	 *
	 * @return true if at least one endpoint requires TLS
	 */
	public boolean atLeastOneEndpointRequiresTls() {
		return this.endpoints
			.values()
			.stream()
			.anyMatch(it -> it.isEnabled() && it.getTlsMode() != TlsMode.FORCE_NO_TLS);
	}

	/**
	 * Returns true if at least one endpoint requires TLS.
	 *
	 * @return true if at least one endpoint requires TLS
	 */
	public boolean atLeastOneEndpointRequiresTls(@Nonnull HostDefinition host) {
		return this.endpoints
			.values()
			.stream()
			.filter(it -> Arrays.asList(it.getHost()).contains(host))
			.anyMatch(it -> it.isEnabled() && it.getTlsMode() != TlsMode.FORCE_NO_TLS);
	}

	/**
	 * Returns true if at least one endpoint requires mutual TLS.
	 *
	 * @return true if at least one endpoint requires mutual TLS
	 */
	public boolean atLeastOnEndpointRequiresMtls() {
		return this.endpoints
			.values()
			.stream()
			.anyMatch(it -> {
				if (it.isMtlsEnabled()) {
					Assert.isPremiseValid(
						it.getTlsMode() != TlsMode.FORCE_NO_TLS, "mTLS cannot be enabled without enabled TLS!"
					);
					return true;
				} else {
					return false;
				}
			});
	}

	/**
	 * Returns the enabled API endpoints.
	 *
	 * @return array of codes of the enabled API endpoints
	 */
	@Nonnull
	public String[] getEnabledApiEndpoints() {
		return endpoints()
			.entrySet()
			.stream()
			.filter(entry -> entry.getValue() != null)
			.filter(entry -> entry.getValue().isEnabled())
			.map(Entry::getKey)
			.toArray(String[]::new);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private final Map<String, Class<?>> apiProviders;
		private final Map<String, AbstractApiOptions> enabledProviders;
		private int idleTimeoutInMillis = DEFAULT_IDLE_TIMEOUT;
		private int requestTimeoutInMillis = DEFAULT_REQUEST_TIMEOUT;
		private long maxEntitySizeInBytes = DEFAULT_MAX_ENTITY_SIZE;
		private HeaderOptions headers;
		private CertificateOptions certificate;
		private int workerGroupThreads = DEFAULT_WORKER_GROUP_THREADS;
		private boolean accessLog;

		Builder() {
			//noinspection unchecked
			this.apiProviders = ExternalApiServer.gatherExternalApiProviders()
				.stream()
				.collect(
					Collectors.toMap(
						ExternalApiProviderRegistrar::getExternalApiCode,
						ExternalApiProviderRegistrar::getConfigurationClass
					)
				);
			this.enabledProviders = CollectionUtils.createHashMap(this.apiProviders.size());
			this.headers = new HeaderOptions.Builder().build();
			this.certificate = new CertificateOptions.Builder().build();
		}

		@Nonnull
		public ApiOptions.Builder workerGroupThreads(int workerGroupThreads) {
			this.workerGroupThreads = workerGroupThreads;
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
		public ApiOptions.Builder headers(@Nonnull HeaderOptions headers) {
			this.headers = headers;
			return this;
		}

		@Nonnull
		public ApiOptions.Builder certificate(@Nonnull CertificateOptions certificate) {
			this.certificate = certificate;
			return this;
		}

		@Nonnull
		public ApiOptions.Builder enable(@Nonnull String apiCode) {
			final Class<?> configurationClass = ofNullable(this.apiProviders.get(apiCode))
				.orElseThrow(() -> new ApiNotFoundOnClasspath(apiCode));
			final AbstractApiOptions cfg;
			try {
				cfg = (AbstractApiOptions) configurationClass.getDeclaredConstructor().newInstance();
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
		public <T extends AbstractApiOptions> ApiOptions.Builder enable(@Nonnull String apiCode, @Nonnull T configuration) {
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
				this.workerGroupThreads,
				this.idleTimeoutInMillis, this.requestTimeoutInMillis,
				this.maxEntitySizeInBytes,
				this.accessLog,
				this.headers,
				this.certificate,
				this.enabledProviders
			);
		}
	}

}
