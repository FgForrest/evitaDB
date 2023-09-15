EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "accessories"),
        			Having(
        				Or(
        					AttributeIs("validity", Null),
        					AttributeInRange(
        						"validity", 
        						DateTimeOffset.Parse("2023-12-02T01:00:00-01:00", DateTimeFormatInfo.InvariantInfo)
        					)
        				)
        			)
        		)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code")
        		)
        	)
        )
	)
);