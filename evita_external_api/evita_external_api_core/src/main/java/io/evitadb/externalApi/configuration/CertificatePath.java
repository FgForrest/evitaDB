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

import javax.annotation.Nullable;

/**
 * This DTO record encapsulates path to the certificate and private keys used to .
 *
 * @param certificate        defines the path to the certificate which will be used to secure the connection
 * @param privateKey         defines the path to the private key of the certificate
 * @param privateKeyPassword defines the password of the private key
 * @author Tomáš Pozler, 2023
 */
public record CertificatePath(
	@Nullable String certificate,
	@Nullable String privateKey,
	@Nullable String privateKeyPassword
) {}
