package ai.wanaku.backend.audit;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * The health of the audit store.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AuditHealth {
    private boolean healthy;
    private long droppedEvents;
    private long retainedEvents;
    private int capacity;
    private String lastError;

    public AuditHealth() {}

    public AuditHealth(boolean healthy, long droppedEvents, long retainedEvents, int capacity, String lastError) {
        this.healthy = healthy;
        this.droppedEvents = droppedEvents;
        this.retainedEvents = retainedEvents;
        this.capacity = capacity;
        this.lastError = lastError;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public long getDroppedEvents() {
        return droppedEvents;
    }

    public void setDroppedEvents(long droppedEvents) {
        this.droppedEvents = droppedEvents;
    }

    public long getRetainedEvents() {
        return retainedEvents;
    }

    public void setRetainedEvents(long retainedEvents) {
        this.retainedEvents = retainedEvents;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
