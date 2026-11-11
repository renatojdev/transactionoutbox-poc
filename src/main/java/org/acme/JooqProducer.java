package org.acme;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

@ApplicationScoped
public class JooqProducer {

    @Inject
    DataSource dataSource;

    @Produces
    @ApplicationScoped
    public DSLContext dslContext() {
        Settings settings = new Settings()
                .withRenderSchema(false)             // não prefixar tabelas com o schema
                .withRenderNameCase(RenderNameCase.LOWER); // ajuste conforme a convenção do seu banco

        return DSL.using(dataSource, SQLDialect.MARIADB, settings);
    }
}
