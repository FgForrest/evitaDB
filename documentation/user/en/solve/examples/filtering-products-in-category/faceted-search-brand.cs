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
        		UserFilter(
        			FacetHaving(
        				"brand",
        				EntityPrimaryKeyInSet(66465)
        			)
        		)
        	),
        	Require(
        		FacetSummaryOfReference(
        			"brand",
        			Impact,
        			OrderBy(
        				AttributeNatural("name", Asc)
        			),
        			EntityFetch(
        				AttributeContent("name")
        			)
        		)
        	)
        )
	)
);