Query(
	Collection("Product"),
	OrderBy(AttributeNatural("code", Asc)),
	Require(EntityFetch())
)