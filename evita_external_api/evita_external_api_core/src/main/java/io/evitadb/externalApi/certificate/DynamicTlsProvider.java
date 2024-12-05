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

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A class that retrieves certificates from the provided {@link AtomicReference} that refers to {@link LoadedCertificates}
 * instance. With this mechanism, the certificates can be dynamically reloaded and updated in the runtime, since there
 * is only a reference that can be updated and server successfully registers it.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class DynamicTlsProvider implements TlsProvider {
	private final AtomicReference<LoadedCertificates> loadedCertificates;

	@Override
	public @Nullable TlsKeyPair keyPair(@Nonnull String hostname) {
		return loadedCertificates.get().tlsKeyPair();
	}

	@Override
	public @Nullable List<X509Certificate> trustedCertificates(@Nonnull String hostname) {
		// we want to implicitly trust all certificates at the SSL validation stage and verify them later on the
		// application level
		return null;
	}
}
