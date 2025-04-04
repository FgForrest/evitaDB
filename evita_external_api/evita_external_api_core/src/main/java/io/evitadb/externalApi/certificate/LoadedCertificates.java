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

package io.evitadb.externalApi.certificate;

import com.linecorp.armeria.common.TlsKeyPair;

import javax.annotation.Nullable;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * A wrapper that holds references to currently loaded certificates.
 *
 * @param tlsKeyPair TLS key pair
 * @param allowedClientCertificates List of allowed client certificates
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public record LoadedCertificates(
	@Nullable TlsKeyPair tlsKeyPair,
	@Nullable List<X509Certificate> allowedClientCertificates
) {

}

