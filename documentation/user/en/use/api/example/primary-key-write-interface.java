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

@EntityRef("Product")
public interface MyEntityEditor extends MyEntity {

	@PrimaryKey void setId(int id);
	@PrimaryKey void setIdAsIntegerObject(@Nonnull Integer id);
	@PrimaryKey void setIdAsLong(long id);
	@PrimaryKey void setIdAsLongObject(@Nonnull Long id);
	@PrimaryKeyRef void setIdAlternative(int id);
	@PrimaryKeyRef void setIdAlternativeAsIntegerObject(@Nonnull Integer id);
	@PrimaryKeyRef void setIdAlternativeAsLong(long id);
	@PrimaryKeyRef void setIdAlternativeAsLongObject(@Nonnull Long id);

	@PrimaryKey @Nonnull MyEntityEditor setIdReturningSelf(int id);
	@PrimaryKey @Nonnull MyEntityEditor setIdAsIntegerObjectReturningSelf(@Nonnull Integer id);
	@PrimaryKey @Nonnull MyEntityEditor setIdAsLongReturningSelf(long id);
	@PrimaryKey @Nonnull MyEntityEditor setIdAsLongObjectReturningSelf(@Nonnull Long id);
	@PrimaryKeyRef @Nonnull MyEntityEditor setIdAlternativeReturningSelf(int id);
	@PrimaryKeyRef @Nonnull MyEntityEditor setIdAlternativeAsIntegerObjectReturningSelf(@Nonnull Integer id);
	@PrimaryKeyRef @Nonnull MyEntityEditor setIdAlternativeAsLongReturningSelf(long id);
	@PrimaryKeyRef @Nonnull MyEntityEditor setIdAlternativeAsLongObjectReturningSelf(@Nonnull Long id);

}
