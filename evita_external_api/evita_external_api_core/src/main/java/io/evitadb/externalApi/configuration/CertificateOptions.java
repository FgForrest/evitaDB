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

import io.evitadb.externalApi.certificate.ServerCertificateManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.nio.file.Path;

/**
 * This DTO record encapsulates certificate settings that will be used to secure connections to the web servers providing APIs.
 *
 * @param generateAndUseSelfSigned defines the path to the certificate which will be used to secure the connection
 * @param folderPath               defines the path to the folder where the certificate and private key will be stored
 * @param custom                   DTO containing paths to the certificate and private keys used to secure the connection
 * @author Tomáš Pozler, 2023
 */
public record CertificateOptions(
	boolean generateAndUseSelfSigned,
	@Nullable String folderPath,
	@Nullable CertificatePath custom
) {

	/**
	 * Builder for the certificate options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static CertificateOptions.Builder builder() {
		return new CertificateOptions.Builder();
	}

	public CertificateOptions() {
		this(true, null, null);
	}

	@Nonnull
	public Path getFolderPath() {
		final String fp = this.folderPath == null ? ServerCertificateManager.getDefaultServerCertificateFolderPath() : this.folderPath;
		return fp.endsWith(File.separator) ? Path.of(fp) : Path.of(fp + File.separator);
	}

	public static class Builder {
		private boolean generateAndUseSelfSigned = true;
		private String folderPath = null;
		private CertificatePath custom = null;

		public Builder generateAndUseSelfSigned(boolean generateAndUseSelfSigned) {
			this.generateAndUseSelfSigned = generateAndUseSelfSigned;
			return this;
		}

		public Builder folderPath(@Nonnull String folderPath) {
			this.folderPath = folderPath;
			return this;
		}

		public Builder custom(@Nonnull CertificatePath custom) {
			this.custom = custom;
			return this;
		}

		public CertificateOptions build() {
			return new CertificateOptions(this.generateAndUseSelfSigned, this.folderPath, this.custom);
		}
	}
}
