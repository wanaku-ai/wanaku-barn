import {
  Header,
  HeaderGlobalAction,
  HeaderGlobalBar,
  HeaderMenuButton,
  HeaderMenuItem,
  HeaderName,
  HeaderNavigation,
  SkipToContent,
} from "@carbon/react";

import { Link } from "react-router-dom";
import { ExternalLinks, Links } from "../router/links.models";

import { LogoGithub, Logout, Notification, Search } from "@carbon/icons-react";
import wanakuLogo from "../assets/wanaku.svg";

interface HeaderComponentProps {
  onClickSideNavExpand: () => void;
  isSideNavExpanded: boolean;
}

function HeaderComponent({
  onClickSideNavExpand,
  isSideNavExpanded,
}: HeaderComponentProps) {
  const action = (click: string) => () => {
    console.log(click);
  };
  return (
    <Header aria-label="Platform Name">
      <SkipToContent />
      <HeaderMenuButton
        aria-label={isSideNavExpanded ? "Close menu" : "Open menu"}
        onClick={onClickSideNavExpand}
        isActive={isSideNavExpanded}
        aria-expanded={isSideNavExpanded}
      />
      <HeaderName href={ExternalLinks.Home} target="_blank" prefix="">
        <img src={wanakuLogo} alt="Wanaku" style={{ marginRight: "1em" }} />
        Wanaku
      </HeaderName>

      <HeaderNavigation aria-label="Wanaku">
        <HeaderMenuItem as={Link} to={Links.DataStores}>
          Data Stores
        </HeaderMenuItem>
        <HeaderMenuItem as={Link} to={Links.ServiceCatalog}>
          Service Catalog
        </HeaderMenuItem>
        <HeaderMenuItem as={Link} to={Links.SemanticRouters}>
          Semantic Routers
        </HeaderMenuItem>
        <HeaderMenuItem as={Link} to={Links.Kamelets}>
          Kamelets
        </HeaderMenuItem>
        <HeaderMenuItem as={Link} to={Links.ChangeHistory}>
          Change History
        </HeaderMenuItem>
      </HeaderNavigation>
      <HeaderGlobalBar>
        <HeaderGlobalAction
          aria-label="Search"
          onClick={action("search click")}
        >
          <Search size={20} />
        </HeaderGlobalAction>
        <HeaderGlobalAction
          aria-label="Notifications"
          onClick={action("notification click")}
        >
          <Notification size={20} />
        </HeaderGlobalAction>
        <HeaderGlobalAction
          aria-label="GitHub"
          onClick={() => {
            window.open(ExternalLinks.GitHub, "_blank");
          }}
          tooltipAlignment="end"
        >
          <LogoGithub size={20} />
        </HeaderGlobalAction>
        <HeaderGlobalAction
          aria-label="Logout"
          onClick={() => {
            window.open(Links.Logout);
          }}
        >
          <Logout size={20} />
        </HeaderGlobalAction>
      </HeaderGlobalBar>
    </Header>
  );
}

export default HeaderComponent;
