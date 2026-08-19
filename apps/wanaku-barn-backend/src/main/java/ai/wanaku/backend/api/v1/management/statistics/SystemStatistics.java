package ai.wanaku.backend.api.v1.management.statistics;

public class SystemStatistics {
    private long serviceCatalogsCount;
    private long serviceTemplatesCount;
    private long dataStoresCount;

    public SystemStatistics() {}

    public SystemStatistics(long serviceCatalogsCount, long serviceTemplatesCount, long dataStoresCount) {
        this.serviceCatalogsCount = serviceCatalogsCount;
        this.serviceTemplatesCount = serviceTemplatesCount;
        this.dataStoresCount = dataStoresCount;
    }

    public long getServiceCatalogsCount() {
        return serviceCatalogsCount;
    }

    public void setServiceCatalogsCount(long serviceCatalogsCount) {
        this.serviceCatalogsCount = serviceCatalogsCount;
    }

    public long getServiceTemplatesCount() {
        return serviceTemplatesCount;
    }

    public void setServiceTemplatesCount(long serviceTemplatesCount) {
        this.serviceTemplatesCount = serviceTemplatesCount;
    }

    public long getDataStoresCount() {
        return dataStoresCount;
    }

    public void setDataStoresCount(long dataStoresCount) {
        this.dataStoresCount = dataStoresCount;
    }
}
