EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("code", "macbooks")
        		),
        		ReferenceHaving(
        			"parameterValues",
        			GroupHaving(
        				AttributeEquals("code", "ram-memory")
        			)
        		)
        	),
        	Require(
        		Page(1, 5),
        		EntityFetch(
        			AttributeContent("code"),
        			ReferenceContentWithAttributes(
        				"parameterValues",
        				FilterBy(
        					EntityHaving(
        						AttributeStartsWith("code", "ram-memory")
        					)
        				),
        				EntityFetch(
        					AttributeContent("code")
        				),
        				EntityGroupFetch(
        					AttributeContent("code")
        				)
        			)
        		)
        	)
        )
	)
);
