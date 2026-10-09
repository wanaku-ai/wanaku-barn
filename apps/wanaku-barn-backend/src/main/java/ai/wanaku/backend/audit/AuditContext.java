package ai.wanaku.backend.audit;

import jakarta.enterprise.context.RequestScoped;

/**
 * Lets an audited resource method name the target and the resulting revision when the request URL does not
 * identify them. Set the target at the start of the method, so that failed requests also carry it.
 */
@RequestScoped
public class AuditContext {
    private String target;
    private String policyRevision;

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getPolicyRevision() {
        return policyRevision;
    }

    public void setPolicyRevision(String policyRevision) {
        this.policyRevision = policyRevision;
    }
}
