/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.api.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InternallyScheduledTask} annotation verifying its retention policy, target,
 * and documentation metadata.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("InternallyScheduledTask annotation metadata")
class InternallyScheduledTaskTest {

	@Test
	@DisplayName("should have RUNTIME retention")
	void shouldHaveRuntimeRetention() {
		final Retention retention = InternallyScheduledTask.class.getAnnotation(Retention.class);

		assertNotNull(retention);
		assertEquals(RetentionPolicy.RUNTIME, retention.value());
	}

	@Test
	@DisplayName("should target only TYPE element")
	void shouldTargetOnlyTypes() {
		final Target target = InternallyScheduledTask.class.getAnnotation(Target.class);

		assertNotNull(target);
		final ElementType[] elementTypes = target.value();
		assertEquals(1, elementTypes.length);
		assertEquals(ElementType.TYPE, elementTypes[0]);
	}

	@Test
	@DisplayName("should be documented")
	void shouldBeDocumented() {
		final Documented documented = InternallyScheduledTask.class.getAnnotation(Documented.class);

		assertNotNull(documented);
	}
}
