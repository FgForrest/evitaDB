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

package io.evitadb.export.s3.configuration;

import io.evitadb.api.configuration.ExportOptions;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Configuration options for the S3-compatible storage export service implementation.
 * This class extends {@link ExportOptions} and adds S3-specific settings.
 *
 * When this export implementation is enabled, the following settings are required:
 * - endpoint: S3-compatible endpoint URL
 * - bucket: Name of the S3 bucket
 * - accessKey: Access key for authentication
 * - secretKey: Secret key for authentication
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ToString(exclude = {"accessKey", "secretKey"})
public class S3ExportOptions extends ExportOptions {

	/**
	 * Implementation code used to identify this export service type.
	 */
	public static final String IMPLEMENTATION_CODE = "s3";

	/**
	 * S3-compatible endpoint URL (e.g., "http://localhost:9000" for MinIO).
	 */
	@Getter
	@Nullable
	private final String endpoint;

	/**
	 * Name of the S3 bucket to store exported files.
	 */
	@Getter
	@Nullable
	private final String bucket;

	/**
	 * Access key for S3 authentication.
	 */
	@Getter
	@Nullable
	private final String accessKey;

	/**
	 * Secret key for S3 authentication.
	 */
	@Getter
	@Nullable
	private final String secretKey;

	/**
	 * Optional region for the S3 bucket.
	 */
	@Getter
	@Nullable
	private final String region;

	/**
	 * Timeout in milliseconds for external S3 operations performed by the export service.
	 * The timeout is applied to all asynchronous MinIO client calls that wait for completion.
	 *
	 * Default value is {@value #DEFAULT_REQUEST_TIMEOUT_IN_MILLIS}.
	 */
	@Getter
	private final long requestTimeoutInMillis;

	/**
	 * Default timeout for S3 requests in milliseconds.
	 */
	public static final long DEFAULT_REQUEST_TIMEOUT_IN_MILLIS = 30_000L;

	/**
	 * Default constructor with default values.
	 */
	public S3ExportOptions() {
		super();
		this.endpoint = null;
		this.bucket = null;
		this.accessKey = null;
		this.secretKey = null;
		this.region = null;
		this.requestTimeoutInMillis = DEFAULT_REQUEST_TIMEOUT_IN_MILLIS;
	}

	/**
	 * Constructor with all parameters.
	 *
	 * @param enabled                  indicates whether this export implementation is enabled
	 * @param sizeLimitBytes           maximum overall size of the export storage
	 * @param historyExpirationSeconds maximal age of exported file in seconds
	 * @param endpoint                 S3-compatible endpoint URL
	 * @param bucket                   name of the S3 bucket
	 * @param accessKey                access key for authentication
	 * @param secretKey                secret key for authentication
	 * @param region                   optional region for the S3 bucket
	 */
	public S3ExportOptions(
		@Nullable Boolean enabled,
		long sizeLimitBytes,
		long historyExpirationSeconds,
		@Nullable String endpoint,
		@Nullable String bucket,
		@Nullable String accessKey,
		@Nullable String secretKey,
		@Nullable String region,
		long requestTimeoutInMillis
	) {
		super(enabled, sizeLimitBytes, historyExpirationSeconds);
		this.endpoint = endpoint;
		this.bucket = bucket;
		this.accessKey = accessKey;
		this.secretKey = secretKey;
		this.region = region;
		this.requestTimeoutInMillis = requestTimeoutInMillis;
	}

	@Nonnull
	@Override
	public String getImplementationCode() {
		return IMPLEMENTATION_CODE;
	}

	/**
	 * Returns the S3-compatible endpoint URL.
	 *
	 * @return the endpoint URL
	 * @throws EvitaInvalidUsageException if endpoint is null
	 */
	@Nonnull
	public String getEndpointOrThrowException() {
		if (this.endpoint == null) {
			throw new EvitaInvalidUsageException("S3 endpoint is not configured.");
		}
		return this.endpoint;
	}

	/**
	 * Returns the name of the S3 bucket.
	 *
	 * @return the bucket name
	 * @throws EvitaInvalidUsageException if bucket is null
	 */
	@Nonnull
	public String getBucketOrThrowException() {
		if (this.bucket == null) {
			throw new EvitaInvalidUsageException("S3 bucket is not configured.");
		}
		return this.bucket;
	}

	/**
	 * Returns the access key for S3 authentication.
	 *
	 * @return the access key
	 * @throws EvitaInvalidUsageException if accessKey is null
	 */
	@Nonnull
	public String getAccessKeyOrThrowException() {
		if (this.accessKey == null) {
			throw new EvitaInvalidUsageException("S3 access key is not configured.");
		}
		return this.accessKey;
	}

	/**
	 * Returns the secret key for S3 authentication.
	 *
	 * @return the secret key
	 * @throws EvitaInvalidUsageException if secretKey is null
	 */
	@Nonnull
	public String getSecretKeyOrThrowException() {
		if (this.secretKey == null) {
			throw new EvitaInvalidUsageException("S3 secret key is not configured.");
		}
		return this.secretKey;
	}

	@Override
	public void validateWhenEnabled() {
		if (Boolean.TRUE.equals(getEnabled())) {
			Assert.isTrue(
				this.endpoint != null && !this.endpoint.isBlank(),
				"S3 endpoint must be specified when S3 export is enabled."
			);
			Assert.isTrue(
				this.bucket != null && !this.bucket.isBlank(),
				"S3 bucket must be specified when S3 export is enabled."
			);
			Assert.isTrue(
				this.accessKey != null && !this.accessKey.isBlank(),
				"S3 access key must be specified when S3 export is enabled."
			);
			Assert.isTrue(
				this.secretKey != null && !this.secretKey.isBlank(),
				"S3 secret key must be specified when S3 export is enabled."
			);
			Assert.isTrue(
				this.requestTimeoutInMillis > 0,
				"S3 request timeout must be a positive number of milliseconds."
			);
		}
	}

	/**
	 * Builder for the S3 export options.
	 * Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for the S3 export options.
	 * Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static Builder builder(@Nonnull S3ExportOptions options) {
		return new Builder(options);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString(exclude = {"accessKey", "secretKey"})
	public static class Builder {
		@Nullable private Boolean enabled = null;
		private long sizeLimitBytes = DEFAULT_SIZE_LIMIT_BYTES;
		private long historyExpirationSeconds = DEFAULT_HISTORY_EXPIRATION_SECONDS;
		@Nullable private String endpoint = null;
		@Nullable private String bucket = null;
		@Nullable private String accessKey = null;
		@Nullable private String secretKey = null;
		@Nullable private String region = null;
		private long requestTimeoutInMillis = DEFAULT_REQUEST_TIMEOUT_IN_MILLIS;

		Builder() {
		}

		Builder(@Nonnull S3ExportOptions options) {
			this.enabled = options.getEnabled();
			this.sizeLimitBytes = options.getSizeLimitBytes();
			this.historyExpirationSeconds = options.getHistoryExpirationSeconds();
			this.endpoint = options.getEndpoint();
			this.bucket = options.getBucket();
			this.accessKey = options.getAccessKey();
			this.secretKey = options.getSecretKey();
			this.region = options.getRegion();
			this.requestTimeoutInMillis = options.getRequestTimeoutInMillis();
		}

		@Nonnull
		public Builder enabled(@Nullable Boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		@Nonnull
		public Builder sizeLimitBytes(long sizeLimitBytes) {
			this.sizeLimitBytes = sizeLimitBytes;
			return this;
		}

		@Nonnull
		public Builder historyExpirationSeconds(long historyExpirationSeconds) {
			this.historyExpirationSeconds = historyExpirationSeconds;
			return this;
		}

		@Nonnull
		public Builder endpoint(@Nullable String endpoint) {
			this.endpoint = endpoint;
			return this;
		}

		@Nonnull
		public Builder bucket(@Nullable String bucket) {
			this.bucket = bucket;
			return this;
		}

		@Nonnull
		public Builder accessKey(@Nullable String accessKey) {
			this.accessKey = accessKey;
			return this;
		}

		@Nonnull
		public Builder secretKey(@Nullable String secretKey) {
			this.secretKey = secretKey;
			return this;
		}

		@Nonnull
		public Builder region(@Nullable String region) {
			this.region = region;
			return this;
		}

		/**
		 * Configures the timeout in milliseconds for external S3 requests performed by the export service.
		 * The timeout is applied to all asynchronous MinIO client calls that wait for completion.
		 */
		@Nonnull
		public Builder requestTimeoutInMillis(long requestTimeoutInMillis) {
			this.requestTimeoutInMillis = requestTimeoutInMillis;
			return this;
		}

		@Nonnull
		public S3ExportOptions build() {
			return new S3ExportOptions(
				this.enabled,
				this.sizeLimitBytes,
				this.historyExpirationSeconds,
				this.endpoint,
				this.bucket,
				this.accessKey,
				this.secretKey,
				this.region,
				this.requestTimeoutInMillis
			);
		}
	}

}
