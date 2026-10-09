package ai.wanaku.backend.audit;

import java.util.List;

/**
 * One page of audit events, newest first.
 */
public class AuditPage {
    private List<AuditEvent> events;
    private int offset;
    private int limit;
    private long total;

    public AuditPage() {}

    public AuditPage(List<AuditEvent> events, int offset, int limit, long total) {
        this.events = events;
        this.offset = offset;
        this.limit = limit;
        this.total = total;
    }

    public List<AuditEvent> getEvents() {
        return events;
    }

    public void setEvents(List<AuditEvent> events) {
        this.events = events;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }
}
