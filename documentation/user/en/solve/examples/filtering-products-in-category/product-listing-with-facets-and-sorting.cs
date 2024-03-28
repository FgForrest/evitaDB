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
        		PriceValidInNow(),
        		UserFilter(
        			FacetHaving(
        				"brand",
        				EntityPrimaryKeyInSet(66465)
        			),
        			PriceBetween(50m, 400m)
        		)
        	),
        	OrderBy(
        		AttributeNatural("order", Asc)
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
        		FacetSummaryOfReference(
        			"brand",
        			Impact,
        			OrderBy(
        				AttributeNatural("name", Asc)
        			),
        			EntityFetch(
        				AttributeContent("name")
        			)
        		),
        		FacetSummaryOfReference(
        			"parameterValues",
        			Impact,
        			FilterGroupBy(
        				AttributeEquals("isVisibleInFilter", true)
        			),
        			OrderBy(
        				AttributeNatural("order", Asc)
        			),
        			OrderGroupBy(
        				AttributeNatural("order", Asc)
        			),
        			EntityFetch(
        				AttributeContent("name")
        			),
        			EntityGroupFetch(
        				AttributeContent("name")
        			)
        		),
        		PriceHistogram(10, Standard),
        		Page(1, 16)
        	)
        )
	)
);