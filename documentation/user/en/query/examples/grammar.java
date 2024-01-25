query(
	collection("Product"),
	filterBy(entityPrimaryKeyInSet(1, 2, 3)),
	orderBy(attributeNatural("code", DESC)),
	require(entityFetch())
)