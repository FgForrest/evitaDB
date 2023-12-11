EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		And(
        			EntityPrimaryKeyInSet(1),
        			EntityLocaleEquals(CultureInfo.GetCultureInfo("en")),
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
	)
);