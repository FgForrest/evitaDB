EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "e-readers")
        		)
        	),
        	OrderBy(
        		ReferenceProperty(
        			"categories",
        			PickFirstByEntityProperty(
        				AttributeNatural("order", Desc)
        			),
        			AttributeNatural("categoryPriority", Desc)
        		)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code"),
        			ReferenceContentWithAttributes(
        				"categories",
        				AttributeContent("categoryPriority")
        			)
        		)
        	)
        )
	)
);