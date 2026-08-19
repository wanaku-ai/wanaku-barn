package ai.wanaku.backend.api.v1.management.statistics;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import ai.wanaku.backend.api.v1.datastores.DataStoresBean;
import ai.wanaku.backend.api.v1.servicecatalog.ServiceCatalogBean;
import ai.wanaku.backend.api.v1.servicecatalog.ServiceTemplateBean;

@ApplicationScoped
public class StatisticsBean {

    @Inject
    ServiceCatalogBean serviceCatalogBean;

    @Inject
    ServiceTemplateBean serviceTemplateBean;

    @Inject
    DataStoresBean dataStoresBean;

    public SystemStatistics getStatistics() {
        long serviceCatalogsCount = serviceCatalogBean.list(null).size();
        long serviceTemplatesCount = serviceTemplateBean.list(null).size();
        long dataStoresCount = dataStoresBean.list(null).size();

        return new SystemStatistics(serviceCatalogsCount, serviceTemplatesCount, dataStoresCount);
    }
}
