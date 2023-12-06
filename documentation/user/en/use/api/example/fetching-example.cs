EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => {
		return session.QuerySealedEntity(
			Query(
				Collection("Product"),
				FilterBy(
					And(
						EntityPrimaryKeyInSet(1),
						EntityLocaleEquals(new CultureInfo("en")),
						PriceInPriceLists("basic"),
						PriceInCurrency(new Currency("CZK"))
					)
				),
				Require(
					EntityFetch(
						AttributeContent("name"),
						AssociatedDataContentAll(),
						PriceContentRespectingFilter(),
						ReferenceContent("brand")
					)
				)
			)
		);
	}
);