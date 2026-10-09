import { SideNav, SideNavItems, SideNavMenuItem } from "@carbon/react";
import { Links } from "../router/links.models";
import { Link } from "react-router-dom";

interface SideNavComponentProps {
  isSideNavExpanded: boolean;
  onClickSideNavExpand: () => void;
}

function SideNavComponent({
  isSideNavExpanded,
  onClickSideNavExpand,
}: SideNavComponentProps) {
  return (
    <SideNav
      aria-label="Side navigation"
      expanded={isSideNavExpanded}
      isPersistent={false}
      onOverlayClick={onClickSideNavExpand}
    >
      <SideNavItems>
        <SideNavMenuItem
          element={Link}
          to={Links.DataStores}
          onClick={onClickSideNavExpand}
        >
          Data Stores
        </SideNavMenuItem>
        <SideNavMenuItem
          element={Link}
          to={Links.ServiceCatalog}
          onClick={onClickSideNavExpand}
        >
          Service Catalog
        </SideNavMenuItem>
        <SideNavMenuItem
          element={Link}
          to={Links.SemanticRouters}
          onClick={onClickSideNavExpand}
        >
          Semantic Routers
        </SideNavMenuItem>
        <SideNavMenuItem
          element={Link}
          to={Links.Kamelets}
          onClick={onClickSideNavExpand}
        >
          Kamelets
        </SideNavMenuItem>
        <SideNavMenuItem
          element={Link}
          to={Links.ChangeHistory}
          onClick={onClickSideNavExpand}
        >
          Change History
        </SideNavMenuItem>
      </SideNavItems>
    </SideNav>
  );
}

export default SideNavComponent;
