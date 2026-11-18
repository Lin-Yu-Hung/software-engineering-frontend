package com.booking.neon;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class NeonDatabaseInitializerTests {
    private MockEnvironment environment(String credentials,String query) {
        return new MockEnvironment()
                .withProperty("DATABASE_URL","postgresql://"+credentials+"@ep-demo-pooler.example.com/neondb"+query)
                .withProperty("DATABASE_URL_UNPOOLED","postgresql://"+credentials+"@ep-demo.example.com/neondb"+query);
    }
    @Test void pooledApplicationAndDirectMigrationsUseExistingJdbcDriver() {
        var properties=NeonDatabaseInitializer.properties(environment("owner:secret","?sslmode=require&channel_binding=require"));
        assertThat(properties.get("spring.datasource.url")).isEqualTo("jdbc:postgresql://ep-demo-pooler.example.com:5432/neondb?sslmode=require&channelBinding=require");
        assertThat(properties.get("spring.flyway.url")).isEqualTo("jdbc:postgresql://ep-demo.example.com:5432/neondb?sslmode=require&channelBinding=require");
        assertThat(properties.get("spring.datasource.username")).isEqualTo("owner");
        assertThat(properties.get("spring.datasource.password")).isEqualTo("secret");
        assertThat(properties.get("spring.datasource.url").toString()).doesNotContain("secret");
    }
    @Test void credentialsDecodePercentEscapesWithoutChangingLiteralPlus() {
        var properties=NeonDatabaseInitializer.properties(environment("own%40er:a+b%3Ac%40d%2520",""));
        assertThat(properties.get("spring.datasource.username")).isEqualTo("own@er");
        assertThat(properties.get("spring.datasource.password")).isEqualTo("a+b:c@d%20");
        assertThat(properties.get("spring.datasource.url").toString()).endsWith("?sslmode=require");
    }
    @Test void missingCredentialsFailWithoutSilentlyUsingTheLocalDatabase() {
        assertThatThrownBy(()->NeonDatabaseInitializer.properties(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing DATABASE_URL");
        var environment=environment("owner:secret","");environment.setProperty("DATABASE_URL_UNPOOLED","");
        assertThatThrownBy(()->NeonDatabaseInitializer.properties(environment)).hasMessageContaining("Missing DATABASE_URL_UNPOOLED");
    }
    @Test void pooledFlywayAndMismatchedBranchesAreRejected() {
        var environment=environment("owner:secret","");
        environment.setProperty("DATABASE_URL_UNPOOLED",environment.getProperty("DATABASE_URL"));
        assertThatThrownBy(()->NeonDatabaseInitializer.properties(environment)).hasMessageContaining("direct connection");
        environment.setProperty("DATABASE_URL_UNPOOLED","postgresql://owner:secret@ep-other.example.com/neondb");
        assertThatThrownBy(()->NeonDatabaseInitializer.properties(environment)).hasMessageContaining("same endpoint");
    }
    @Test void invalidUrlsAndDisabledTlsNeverExposePasswordsInExceptions() {
        for(String raw:new String[]{"postgresql://owner:do not leak@host/db", "postgresql://owner:secret@host/db?sslmode=disable", "https://owner:secret@host/db"}) {
            var environment=environment("owner:secret","");environment.setProperty("DATABASE_URL",raw);
            assertThatThrownBy(()->NeonDatabaseInitializer.properties(environment))
                    .hasMessageContaining("Invalid DATABASE_URL").hasMessageNotContaining("secret").hasMessageNotContaining("do not leak").hasNoCause();
        }
    }
    @Test void normalLocalProfileIgnoresCloudUrls() {
        try(var context=new GenericApplicationContext()) {
            context.setEnvironment(environment("owner:secret",""));
            new NeonDatabaseInitializer().initialize(context);
            assertThat(context.getEnvironment().getProperty("spring.datasource.url")).isNull();
        }
    }
    @Test void neonProfileInstallsPropertiesBeforeFlywayAndJpaStart() {
        try(var context=new GenericApplicationContext()) {
            var environment=environment("owner:secret","");environment.setActiveProfiles("neon");context.setEnvironment(environment);
            new NeonDatabaseInitializer().initialize(context);
            assertThat(context.getEnvironment().getProperty("spring.datasource.url")).contains("ep-demo-pooler");
            assertThat(context.getEnvironment().getProperty("spring.flyway.url")).contains("ep-demo.example");
        }
    }
}
