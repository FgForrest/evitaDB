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
 * @param ioThreads   defines the number of IO thread will be used by Undertow for accept and send HTTP payload
 * @param accessLog   defines whether the access logs will be enabled or not
 * @param endpoints   contains specific configuration for all the API endpoints
 * @param certificate defines the certificate settings that will be used to secure connections to the web servers providing APIs
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record ApiOptions(
	@Nonnull String exposedOn,
	@Nullable Integer ioThreads,
	@Nullable Integer workerGroupThreads,
	@Nullable Integer serviceWorkerGroupThreads,
	boolean accessLog,
	@Nonnull CertificateSettings certificate,
	@Nonnull Map<String, AbstractApiConfiguration> endpoints
) {

	/**
	 * Builder for the api options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static ApiOptions.Builder builder() {
		return new ApiOptions.Builder();
	}

	public ApiOptions() {
		this(null, null, null, null, false, new CertificateSettings(), new HashMap<>(8));
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
	 * Returns set {@link #ioThreads} or returns a default value.
	 */
	public int ioThreadsAsInt() {
		return ofNullable(ioThreads)
			// double the value of available processors (recommended by Undertow configuration)
			.orElseGet(() -> Runtime.getRuntime().availableProcessors() << 1);
	}

	/**
	 * Returns set {@link #workerGroupThreads} or returns a default value.
	 */
	public int workerGroupThreadsAsInt() {
		return ofNullable(workerGroupThreads)
			// double the value of available processors (recommended by Netty configuration)
			.orElseGet(() -> Runtime.getRuntime().availableProcessors() << 1);
	}

	/**
	 * Returns set {@link #serviceWorkerGroupThreads} or returns a default value.
	 */
	public int serviceWorkerGroupThreadsAsInt() {
		return ofNullable(serviceWorkerGroupThreads)
			// double the value of available processors (recommended by Netty configuration)
			.orElseGet(() -> Runtime.getRuntime().availableProcessors() << 1);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private final Map<String, Class<?>> apiProviders;
		private final Map<String, AbstractApiConfiguration> enabledProviders;
		private CertificateSettings certificate;
		@Nullable private String exposedOn;
		@Nullable private Integer ioThreads;
		@Nullable private Integer workerGroupThreads;
		@Nullable private Integer serviceWorkerGroupThreads;
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
		public ApiOptions.Builder ioThreads(int ioThreads) {
			this.ioThreads = ioThreads;
			return this;
		}

		@Nonnull
		public ApiOptions.Builder workerGroupThreads(int workerGroupThreads) {
			this.workerGroupThreads = ioThreads;
			return this;
		}

		@Nonnull
		public ApiOptions.Builder serviceWorkerGroupThreads(int serviceWorkerGroupThreads) {
			this.serviceWorkerGroupThreads = ioThreads;
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
				exposedOn, ioThreads, workerGroupThreads, serviceWorkerGroupThreads, accessLog, certificate, enabledProviders
			);
		}
	}

}
