Query(
    Collection("Product"),
    FilterBy(
       And(
          EntityPrimaryKeyInSet(1, 2, 3),
          AttributeEquals("visibility", "VISIBLE")
       )
    ),
    OrderBy(
        AttributeNatural("code", Asc),
        AttributeNatural("priority", Desc)
    ),
    Require(
        EntityFetch(
			AttributeContentAll(), PriceContentAll()
		),
        FacetSummary()
    )
)