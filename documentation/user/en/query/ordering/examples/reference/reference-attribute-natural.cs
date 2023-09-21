EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		ReferenceHaving(
        			"brand",
        			EntityHaving(
        				AttributeEquals("code", "sale")
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