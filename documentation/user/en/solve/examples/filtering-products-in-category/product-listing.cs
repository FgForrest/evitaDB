EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("en")),
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("url", "/en/smartwatches")
        		),
        		AttributeEquals("status", "ACTIVE"),
        		Or(
        			AttributeInRangeNow("validity"),
        			AttributeIs("validity", Null)
        		),
        		ReferenceHaving(
        			"stocks",
        			AttributeGreaterThan("quantityOnStock", 0)
        		),
        		PriceInCurrency(new Currency("EUR")),
        		PriceInPriceLists("basic"),
        		PriceValidInNow()
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("name"),
        			ReferenceContentWithAttributes(
        				"stocks",
        				AttributeContent("quantityOnStock")
        			),
        			PriceContentRespectingFilter("reference")
        		),
        		Page(1, 16)
        	)
        )
	)
);