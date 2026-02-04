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

package io.evitadb.utils;

import io.evitadb.exception.InvalidHostDefinitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test verifies contract of {@link NetworkUtils} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("NetworkUtils contract tests")
class NetworkUtilsTest {

	@Nested
	@DisplayName("Host name tests")
	class HostNameTests {

		@Test
		@DisplayName("Should return local host name")
		void shouldReturnLocalHostName() {
			final String hostName = NetworkUtils.getLocalHostName();
			assertNotNull(hostName);
			assertFalse(hostName.isEmpty());
		}

		@Test
		@DisplayName("Should resolve localhost by name")
		void shouldResolveLocalhostByName() {
			final InetAddress address = NetworkUtils.getByName("localhost");
			assertNotNull(address);
		}

		@Test
		@DisplayName("Should resolve 127.0.0.1 by name")
		void shouldResolveLoopbackByName() {
			final InetAddress address = NetworkUtils.getByName("127.0.0.1");
			assertNotNull(address);
		}

		@Test
		@DisplayName("Should throw exception for invalid host")
		void shouldThrowExceptionForInvalidHost() {
			assertThrows(
				InvalidHostDefinitionException.class,
				() -> NetworkUtils.getByName("this.host.definitely.does.not.exist.invalid")
			);
		}

		@Test
		@DisplayName("Should get host name for inet address")
		void shouldGetHostNameForInetAddress() throws UnknownHostException {
			final InetAddress localhost = InetAddress.getLocalHost();
			final String hostName = NetworkUtils.getHostName(localhost);
			assertNotNull(hostName);
			assertFalse(hostName.isEmpty());
		}

		@Test
		@DisplayName("Should get host name for loopback address")
		void shouldGetHostNameForLoopbackAddress() {
			final InetAddress loopback = InetAddress.getLoopbackAddress();
			final String hostName = NetworkUtils.getHostName(loopback);
			assertNotNull(hostName);
			assertFalse(hostName.isEmpty());
		}

		@Test
		@DisplayName("Should get host name for wildcard address")
		void shouldGetHostNameForWildcardAddress() throws UnknownHostException {
			final InetAddress wildcard = InetAddress.getByName("0.0.0.0");
			final String hostName = NetworkUtils.getHostName(wildcard);
			assertNotNull(hostName);
			assertFalse(hostName.isEmpty());
		}
	}
}
