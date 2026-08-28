/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.observability.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one invariant of {@link ErrorMonitor} that no ordinary test can catch: it is injected into the
 * **bootstrap classloader** by `ErrorMonitoringAgent#premain`, so it may not reference any type outside `java.*`.
 *
 * A violation compiles, passes every functional test, and then fails with `NoClassDefFoundError` only in a server
 * started with the agent attached - by which point error monitoring is silently dead. An innocent-looking "optimise
 * imports", or adding `@Nonnull` to a parameter, is enough to cause it.
 *
 * The check reads the compiled class file and inspects every UTF-8 constant, which catches both direct class
 * references and types that appear only inside a method or field descriptor.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ErrorMonitor stays loadable from the bootstrap classloader")
@Tag(OBSERVABILITY_API)
@Tag(OBSERVABILITY)
class ErrorMonitorBootstrapVisibilityTest {

	/**
	 * Reads every UTF-8 constant-pool entry of the passed class.
	 *
	 * @param type class whose constant pool should be read
	 * @return all UTF-8 constants, in pool order
	 */
	@Nonnull
	private static List<String> readUtf8Constants(@Nonnull Class<?> type) throws IOException {
		final String resource = type.getName().replace('.', '/') + ".class";
		final byte[] classBytes;
		try (InputStream stream = type.getClassLoader().getResourceAsStream(resource)) {
			assertNotNull(stream, "class file `" + resource + "` must be readable from the classpath");
			classBytes = stream.readAllBytes();
		}

		final ByteBuffer buffer = ByteBuffer.wrap(classBytes);
		assertEquals(0xCAFEBABE, buffer.getInt(), "not a class file");
		buffer.getShort();  // minor version
		buffer.getShort();  // major version

		final int constantPoolCount = Short.toUnsignedInt(buffer.getShort());
		final List<String> utf8Constants = new ArrayList<>(constantPoolCount);
		// the pool is 1-based and `long` / `double` entries occupy two slots each
		for (int index = 1; index < constantPoolCount; index++) {
			final int tag = Byte.toUnsignedInt(buffer.get());
			switch (tag) {
				case 1 -> {  // CONSTANT_Utf8
					final int length = Short.toUnsignedInt(buffer.getShort());
					final byte[] bytes = new byte[length];
					buffer.get(bytes);
					utf8Constants.add(new String(bytes, StandardCharsets.UTF_8));
				}
				case 7, 8, 16, 19, 20 -> buffer.position(buffer.position() + 2);
				case 15 -> buffer.position(buffer.position() + 3);
				case 3, 4, 9, 10, 11, 12, 17, 18 -> buffer.position(buffer.position() + 4);
				case 5, 6 -> {  // CONSTANT_Long / CONSTANT_Double take two pool slots
					buffer.position(buffer.position() + 8);
					index++;
				}
				default -> throw new IllegalStateException("Unknown constant pool tag `" + tag + "`.");
			}
		}
		return utf8Constants;
	}

	@Test
	@DisplayName("Should reference no evitaDB type other than itself")
	void shouldReferenceNoEvitaDbTypeOtherThanItself() throws IOException {
		final String ownInternalName = ErrorMonitor.class.getName().replace('.', '/');
		final List<String> offending = new ArrayList<>();
		for (final String constant : readUtf8Constants(ErrorMonitor.class)) {
			if (!constant.contains("io/evitadb") && !constant.contains("io.evitadb")) {
				continue;
			}
			// the class names itself in its own `this_class` entry and in `this`-typed descriptors; nothing else
			// from io.evitadb may appear, because the bootstrap classloader cannot resolve it
			if (constant.equals(ownInternalName) || constant.equals("L" + ownInternalName + ";")) {
				continue;
			}
			offending.add(constant);
		}
		assertTrue(
			offending.isEmpty(),
			"ErrorMonitor is injected into the bootstrap classloader and must reference only `java.*` types, but " +
				"its constant pool names: " + offending
		);
	}

	@Test
	@DisplayName("Should reference no third-party library type either")
	void shouldReferenceNoThirdPartyType() throws IOException {
		final List<String> offending = new ArrayList<>();
		for (final String constant : readUtf8Constants(ErrorMonitor.class)) {
			// annotations survive as descriptors; Lombok, JSR-305 and Byte Buddy are all equally unreachable from
			// the bootstrap classloader, and `@Nonnull` on a parameter is the easiest way to add one by accident
			if (constant.contains("lombok/") || constant.contains("javax/annotation") ||
				constant.contains("net/bytebuddy") || constant.contains("org/slf4j")) {
				offending.add(constant);
			}
		}
		assertTrue(
			offending.isEmpty(),
			"ErrorMonitor must reference only `java.*` types, but its constant pool names: " + offending
		);
	}
}
