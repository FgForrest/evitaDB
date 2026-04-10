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

package io.evitadb.driver.config;

import io.evitadb.externalApi.grpc.certificate.ClientCertificateManager;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;

/**
 * Record contains TLS and certificate-related settings for the evitaDB Java client.
 *
 * @param tlsEnabled              Whether to use TLS encryption for communication with the server. When `false`,
 *                                the client uses HTTP/2 without TLS. Corresponding setting must be set on the
 *                                server side.
 * @param mtlsEnabled             Whether to use mutual TLS authentication. The client must correctly identify itself
 *                                using a public/private key pair that is known and trusted by the server.
 * @param useGeneratedCertificate Whether to automatically download the root certificate from the server's system API
 *                                endpoint.
 * @param trustCertificate        Whether to trust the server CA certificate or not when it's not a trusted CA.
 *                                Setting to `true` in production is generally not recommended.
 * @param serverCertificatePath   A relative path to the server certificate. Required when `useGeneratedCertificate`
 *                                and `trustCertificate` are disabled and the server uses a non-trusted certificate.
 * @param certificateFileName     The relative path from `certificateFolderPath` to the client certificate. Required
 *                                if mTLS is enabled and `useGeneratedCertificate` is `false`.
 * @param certificateKeyFileName  The relative path from `certificateFolderPath` to the client private key. Required
 *                                if mTLS is enabled and `useGeneratedCertificate` is `false`.
 * @param certificateKeyPassword  The password for the client's private key (if one is set).
 * @param certificateFolderPath   A path to the folder where the client certificate and private key will be located,
 *                                or if not present, downloaded.
 * @param trustStorePassword      The password for the trust store used to store server certificates when
 *                                `trustCertificate` is `true`.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record ClientTlsOptions(
	boolean tlsEnabled,
	boolean mtlsEnabled,
	boolean useGeneratedCertificate,
	boolean trustCertificate,
	@Nullable Path serverCertificatePath,
	@Nullable Path certificateFileName,
	@Nullable Path certificateKeyFileName,
	@Nullable String certificateKeyPassword,
	@Nullable Path certificateFolderPath,
	@Nonnull String trustStorePassword
) {
	public static final boolean DEFAULT_TLS_ENABLED = true;
	public static final boolean DEFAULT_MTLS_ENABLED = false;
	public static final boolean DEFAULT_USE_GENERATED_CERTIFICATE = true;
	public static final boolean DEFAULT_TRUST_CERTIFICATE = false;
	public static final String DEFAULT_TRUST_STORE_PASSWORD = "trustStorePassword";

	/**
	 * Creates a new instance with all default values.
	 */
	public ClientTlsOptions() {
		this(
			DEFAULT_TLS_ENABLED,
			DEFAULT_MTLS_ENABLED,
			DEFAULT_USE_GENERATED_CERTIFICATE,
			DEFAULT_TRUST_CERTIFICATE,
			null, null, null, null,
			ClientCertificateManager.getDefaultClientCertificateFolderPath(),
			DEFAULT_TRUST_STORE_PASSWORD
		);
	}

	/**
	 * Returns the path to the server certificate.
	 *
	 * @return The path to the server certificate.
	 * @deprecated Use {@link #serverCertificatePath()} instead.
	 */
	@Deprecated(since = "2024.11", forRemoval = true)
	@Nullable
	public Path rootCaCertificatePath() {
		return this.serverCertificatePath;
	}

	/**
	 * Builder for the TLS options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static ClientTlsOptions.Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for the TLS options initialized from an existing configuration.
	 */
	@Nonnull
	public static ClientTlsOptions.Builder builder(@Nonnull ClientTlsOptions tlsOptions) {
		return new Builder(tlsOptions);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private boolean tlsEnabled = DEFAULT_TLS_ENABLED;
		private boolean mtlsEnabled = DEFAULT_MTLS_ENABLED;
		private boolean useGeneratedCertificate = DEFAULT_USE_GENERATED_CERTIFICATE;
		private boolean trustCertificate = DEFAULT_TRUST_CERTIFICATE;
		@Nullable private Path serverCertificatePath = null;
		@Nullable private Path certificateFileName = null;
		@Nullable private Path certificateKeyFileName = null;
		@Nullable private String certificateKeyPassword = null;
		@Nullable private Path certificateFolderPath =
			ClientCertificateManager.getDefaultClientCertificateFolderPath();
		@Nonnull private String trustStorePassword = DEFAULT_TRUST_STORE_PASSWORD;

		Builder() {
		}

		Builder(@Nonnull ClientTlsOptions tlsOptions) {
			this.tlsEnabled = tlsOptions.tlsEnabled();
			this.mtlsEnabled = tlsOptions.mtlsEnabled();
			this.useGeneratedCertificate = tlsOptions.useGeneratedCertificate();
			this.trustCertificate = tlsOptions.trustCertificate();
			this.serverCertificatePath = tlsOptions.serverCertificatePath();
			this.certificateFileName = tlsOptions.certificateFileName();
			this.certificateKeyFileName = tlsOptions.certificateKeyFileName();
			this.certificateKeyPassword = tlsOptions.certificateKeyPassword();
			this.certificateFolderPath = tlsOptions.certificateFolderPath();
			this.trustStorePassword = tlsOptions.trustStorePassword();
		}

		@Nonnull
		public ClientTlsOptions.Builder tlsEnabled(boolean tlsEnabled) {
			this.tlsEnabled = tlsEnabled;
			return this;
		}

		@Nonnull
		public ClientTlsOptions.Builder mtlsEnabled(boolean mtlsEnabled) {
			this.mtlsEnabled = mtlsEnabled;
			return this;
		}

		@Nonnull
		public ClientTlsOptions.Builder useGeneratedCertificate(boolean useGeneratedCertificate) {
			this.useGeneratedCertificate = useGeneratedCertificate;
			return this;
		}

		@Nonnull
		public ClientTlsOptions.Builder trustCertificate(boolean trustCertificate) {
			this.trustCertificate = trustCertificate;
			return this;
		}

		/**
		 * This setting was renamed to {@link #serverCertificatePath(Path)}.
		 *
		 * @deprecated Use {@link #serverCertificatePath(Path)} instead.
		 * @param rootCaCertificatePath Path to the server certificate that should be used for the TLS connection.
		 * @return Builder instance for chaining.
		 */
		@Deprecated(since = "2024.11", forRemoval = true)
		@Nonnull
		public ClientTlsOptions.Builder rootCaCertificatePath(@Nonnull Path rootCaCertificatePath) {
			this.serverCertificatePath = rootCaCertificatePath;
			return this;
		}

		@Nonnull
		public ClientTlsOptions.Builder serverCertificatePath(@Nonnull Path serverCertificatePath) {
			this.serverCertificatePath = serverCertificatePath;
			return this;
		}

		@Nonnull
		public ClientTlsOptions.Builder certificateFileName(@Nonnull Path certificateFileName) {
			this.certificateFileName = certificateFileName;
			return this;
		}

		@Nonnull
		public ClientTlsOptions.Builder certificateKeyFileName(@Nonnull Path certificateKeyFileName) {
			this.certificateKeyFileName = certificateKeyFileName;
			return this;
		}

		@Nonnull
		public ClientTlsOptions.Builder certificateKeyPassword(@Nonnull String certificateKeyPassword) {
			this.certificateKeyPassword = certificateKeyPassword;
			return this;
		}

		@Nonnull
		public ClientTlsOptions.Builder certificateFolderPath(@Nonnull Path certificateFolderPath) {
			this.certificateFolderPath = certificateFolderPath;
			return this;
		}

		@Nonnull
		public ClientTlsOptions.Builder trustStorePassword(@Nonnull String trustStorePassword) {
			this.trustStorePassword = trustStorePassword;
			return this;
		}

		@Nonnull
		public ClientTlsOptions build() {
			return new ClientTlsOptions(
				this.tlsEnabled, this.mtlsEnabled, this.useGeneratedCertificate, this.trustCertificate,
				this.serverCertificatePath, this.certificateFileName, this.certificateKeyFileName,
				this.certificateKeyPassword, this.certificateFolderPath, this.trustStorePassword
			);
		}
	}
}
