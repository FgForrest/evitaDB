/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.proxy.mock;


import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.structure.EntityReference;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * Example editor interface mapping a related product reference.
 *
 * Provides mutator methods for attributes and the referenced entity/its reference
 * so that tests can validate editor proxy behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface RelatedProductInterfaceEditor extends RelatedProductInterface, InstanceEditor<RelatedProductInterface>  {

	RelatedProductInterfaceEditor setRelationType(@Nonnull String relationType);

	RelatedProductInterfaceEditor setLabel(@Nonnull String label);

	RelatedProductInterfaceEditor setLabel(Locale locale, @Nonnull String label);

	RelatedProductInterfaceEditor setRelatedProduct(@Nonnull ProductInterface relatedProduct);

	RelatedProductInterfaceEditor setRelatedProductReference(@Nonnull EntityReference relatedProductReference);

	RelatedProductInterfaceEditor setRelatedProductReferencePrimaryKey(int relatedProductReferencePrimaryKey);

}
