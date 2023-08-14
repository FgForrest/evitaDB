EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "accessories")
        		)
        	),
        	OrderBy(
        		ReferenceProperty(
        			"categories",
        			AttributeNatural("orderInCategory", Asc)
        		)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code"),
        			ReferenceContentWithAttributes(
        				"categories",
        				AttributeContent("orderInCategory")
        			)
        		)
        	)
        )
	)
);