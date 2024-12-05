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

package io.evitadb.externalApi.certificate;

import io.evitadb.core.async.Scheduler;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificatePath;
import io.evitadb.externalApi.configuration.CertificateSettings;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Service responsible for loading and reloading certificates and providing a reference to a wrapper that holds currently loaded certificates.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class CertificateService {
	@Getter
	@SuppressWarnings("WriteOnlyObject")
	private final AtomicReference<LoadedCertificates> loadedCertificates = new AtomicReference<>();
	private CertificatesLoader certificatesLoader;
	private CertificateFactory certificateFactory;
	private CertificateSettings certificateSettings;

	private long lastModifiedTime = System.currentTimeMillis();

	public void initCertificateLoader(
		@Nonnull ApiOptions apiOptions,
		boolean shouldStartCertificateChangedWatcher,
		@Nonnull CertificatePath certificatePath,
		@Nonnull Scheduler serviceExecutor
	) {
		this.certificateSettings = apiOptions.certificate();
		final List<String> allowedClientCertificatePaths = apiOptions.endpoints()
			.values()
			.stream()
			.filter(endpoint -> endpoint.isEnabled() && endpoint.getMtlsConfiguration() != null && endpoint.isMtlsEnabled())
			.flatMap(mtlsEndpoint -> mtlsEndpoint.getMtlsConfiguration().allowedClientCertificatePaths().stream())
			.distinct()
			.collect(Collectors.toList());
		this.certificatesLoader = new CertificatesLoader(apiOptions, allowedClientCertificatePaths, getCertificateFactory(), certificatePath);
		this.loadedCertificates.set(certificatesLoader.reinitialize());
		if (shouldStartCertificateChangedWatcher) {
			serviceExecutor.scheduleAtFixedRate(this::reloadCertificatesIfAnyModified, 0, 1, TimeUnit.MINUTES);
		}
	}

	/**
	 * Reloads certificates if any of them was modified.
	 * @return true if any certificate was modified, false otherwise
	 */
	public boolean reloadCertificatesIfAnyModified() {
		final Path pathToWatch = getCertificateFolderPathPath();
		final boolean wasModified;
		try {
			long millisOfCertDirectoryLatestUpdate = Files.getLastModifiedTime(pathToWatch).toMillis();
			if (isAnyCertificateFileChanged(pathToWatch)) {
				loadedCertificates.set(certificatesLoader.reinitialize());
				lastModifiedTime = millisOfCertDirectoryLatestUpdate;
				wasModified = true;
			} else {
				wasModified = false;
			}
		} catch (IOException e) {
			throw new GenericEvitaInternalError("Failed to get last modified time of the certificate folder.", e);
		}
		return wasModified;
	}

	private boolean isAnyCertificateFileChanged(@Nonnull Path pathToWatch) throws IOException {
		final File directory = pathToWatch.toFile();
		if (pathToWatch.toFile().isDirectory()) {
			return Arrays.stream(directory.listFiles())
				.anyMatch(file -> file.lastModified() > lastModifiedTime);
		} else {
			throw new EvitaInvalidUsageException("The configured certificate path does not point to a directory.");
		}
	}

	private Path getCertificateFolderPathPath() {
		return Paths.get(certificateSettings.getFolderPath().toString());
	}

	private CertificateFactory getCertificateFactory() {
		try {
			if (certificateFactory == null) {
				this.certificateFactory = CertificateFactory.getInstance("X.509");
			}
			return certificateFactory;
		} catch (CertificateException e) {
			throw new GenericEvitaInternalError("Failed to get certificate factory instance.", e);
		}
	}
}
