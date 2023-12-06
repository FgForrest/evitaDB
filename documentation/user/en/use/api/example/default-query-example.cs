EvitaResponse<EntityReference> entities = evita.QueryCatalog(
	"evita",
	session => {
		return session.Query<EntityReferenceResponse, EntityReference>(
			Query(
				Collection("Product"),
				FilterBy(
					And(
						EntityPrimaryKeyInSet(1),
						EntityLocaleEquals(new CultureInfo("en")),
						PriceInPriceLists("basic"),
						PriceInCurrency(new Currency("CZK"))
					)
				)
			)
		);
	}
);