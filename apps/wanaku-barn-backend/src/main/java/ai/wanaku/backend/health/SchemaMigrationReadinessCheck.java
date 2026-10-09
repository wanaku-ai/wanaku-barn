package ai.wanaku.backend.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import ai.wanaku.backend.core.persistence.migration.SchemaMigrations;

/**
 * Reports the service as not ready until the persisted data is migrated to the current schema version.
 */
@Readiness
@ApplicationScoped
public class SchemaMigrationReadinessCheck implements HealthCheck {

    @Inject
    SchemaMigrations migrations;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("Persistence schema")
                .status(migrations.isComplete())
                .withData("schemaVersion", migrations.storedVersion())
                .build();
    }
}
