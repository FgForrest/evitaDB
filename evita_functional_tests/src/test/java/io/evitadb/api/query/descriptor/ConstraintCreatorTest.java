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

package io.evitadb.api.query.descriptor;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.descriptor.ConstraintCreator.ClassifierParameterDescriptor;
import io.evitadb.api.query.descriptor.ConstraintCreator.FixedImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ImplicitClassifier;
import io.evitadb.api.query.descriptor.ConstraintCreator.ParameterDescriptor;
import io.evitadb.api.query.filter.And;
import io.evitadb.exception.EvitaInternalError;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ConstraintCreator}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class ConstraintCreatorTest {

	@Test
	void shouldCreateCorrectCreator() {
		assertDoesNotThrow(() -> createCreator( null, new ClassifierParameterDescriptor("name")));
		assertDoesNotThrow(() -> createCreator(new FixedImplicitClassifier("primaryKey")));
	}

	@Test
	void shouldNotCreateWithMultipleClassifiers() {
		assertThrows(EvitaInternalError.class, () -> createCreator(new FixedImplicitClassifier("primaryKey"), new ClassifierParameterDescriptor("name")));
		assertThrows(EvitaInternalError.class, () -> createCreator(null, new ClassifierParameterDescriptor("name"), new ClassifierParameterDescriptor("type")));
	}


	@Nonnull
	private static ConstraintCreator createCreator(@Nullable ImplicitClassifier implicitClassifier,
	                                               @Nonnull ParameterDescriptor... parameters) throws NoSuchMethodException {
		return new ConstraintCreator(
			And.class.getConstructor(FilterConstraint[].class),
			List.of(parameters),
			implicitClassifier
		);
	}
}
