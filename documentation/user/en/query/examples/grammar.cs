Query(
    Collection("Product"),
    FilterBy(EntityPrimaryKeyInSet(1, 2, 3)),
    OrderBy(AttributeNatural("code", Desc)),
    Require(EntityFetch())
)