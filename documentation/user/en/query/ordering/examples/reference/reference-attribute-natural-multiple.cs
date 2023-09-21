EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		ReferenceHaving(
        			"brand",
        			EntityHaving(
        				AttributeInSet("code", "sale", "new")
        			)
        		)
        	),
        	OrderBy(
        		ReferenceProperty(
        			"groups",
        			AttributeNatural("orderInGroup", Asc)
        		)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code"),
        			ReferenceContentWithAttributes(
        				"groups",
        				AttributeContent("orderInGroup")
        			)
        		)
        	)
        )
	)
);