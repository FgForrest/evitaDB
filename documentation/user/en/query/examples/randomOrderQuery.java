query(
	collection("Product"),
	orderBy(attributeNatural("code", ASC)),
	require(entityFetch())
)