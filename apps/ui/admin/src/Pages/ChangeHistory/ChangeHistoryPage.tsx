import React, {useEffect, useState} from "react";
import {
  DataTable,
  InlineNotification,
  Pagination,
  Select,
  SelectItem,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
  Tag,
} from "@carbon/react";
import {getApiV1AuditEvents} from "../../api/wanaku-router-api";
import type {AuditEvent} from "../../models";

const targetTypes = [
  { value: "", label: "All resources" },
  { value: "service_catalog", label: "Service catalogs" },
  { value: "service_template", label: "Service templates" },
  { value: "semantic_router", label: "Semantic routers" },
  { value: "data_store", label: "Data stores" },
  { value: "kamelet", label: "Kamelets" },
];

const headers = [
  { key: "timestamp", header: "Time" },
  { key: "operation", header: "Operation" },
  { key: "target", header: "Target" },
  { key: "decision", header: "Decision" },
  { key: "reason", header: "Reason" },
  { key: "status", header: "Status" },
];

// Read without React Router: in plugin mode the host mounts this page outside a router.
const initialTargetType = () =>
  new URLSearchParams(window.location.hash.split("?")[1] ?? "").get("type") ?? "";

const decisionColors: Record<string, "green" | "red" | "magenta"> = {
  allow: "green",
  reject_malformed: "magenta",
  error: "red",
};

export const ChangeHistoryPage: React.FC = () => {
  const [targetType, setTargetType] = useState(initialTargetType);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [total, setTotal] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    setIsLoading(true);
    getApiV1AuditEvents({
      target_type: targetType || undefined,
      offset: (page - 1) * pageSize,
      limit: pageSize,
    })
      .then((result) => {
        setEvents(result.data.data?.events ?? []);
        setTotal(result.data.data?.total ?? 0);
        setErrorMessage(null);
      })
      .catch(() => setErrorMessage("Failed to load the change history"))
      .finally(() => setIsLoading(false));
  }, [targetType, page, pageSize]);

  const selectTargetType = (value: string) => {
    setPage(1);
    setTargetType(value);
  };

  return (
    <div>
      {errorMessage && (
        <InlineNotification
          kind="error"
          title="Error"
          subtitle={errorMessage}
          onCloseButtonClick={() => setErrorMessage(null)}
        />
      )}
      <h1 className="title">Change History</h1>
      <p className="description">
        Review the changes to service catalogs, service templates, semantic routers and the other resources,
        including rejected and failed requests. The newest events are first.
      </p>
      <div id="page-content">
        <Select
          id="change-history-target-type"
          labelText="Resource"
          value={targetType}
          onChange={(event) => selectTargetType(event.target.value)}
        >
          {targetTypes.map((type) => (
            <SelectItem key={type.value} value={type.value} text={type.label} />
          ))}
        </Select>
        <DataTable
          rows={events.map((event, index) => ({
            id: event.event_id || `event-${index}`,
            timestamp: event.timestamp ? new Date(event.timestamp).toLocaleString() : "",
            operation: event.operation ?? "",
            target: event.target ?? "",
            decision: event.decision ?? "",
            reason: event.reason_code ?? "",
            status: event.response_status ?? "",
          }))}
          headers={headers}
        >
          {({ rows, headers, getTableProps, getHeaderProps, getRowProps }) => (
            <TableContainer>
              <Table {...getTableProps()} aria-label="Change history events">
                <TableHead>
                  <TableRow>
                    {headers.map((header) => (
                      <TableHeader {...getHeaderProps({ header })} key={header.key}>
                        {header.header}
                      </TableHeader>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {rows.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={headers.length} style={{ textAlign: "center", color: "var(--cds-text-secondary)" }}>
                        {isLoading ? "Loading..." : "No changes found."}
                      </TableCell>
                    </TableRow>
                  ) : (
                    rows.map((row) => {
                      const { key, ...rowProps } = getRowProps({ row });
                      return (
                        <TableRow key={key} {...rowProps}>
                          {row.cells.map((cell) =>
                            cell.info.header === "decision" ? (
                              <TableCell key={cell.id}>
                                <Tag type={decisionColors[cell.value] ?? "gray"}>{cell.value}</Tag>
                              </TableCell>
                            ) : (
                              <TableCell key={cell.id}>{cell.value}</TableCell>
                            )
                          )}
                        </TableRow>
                      );
                    })
                  )}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </DataTable>
        <Pagination
          page={page}
          pageSize={pageSize}
          pageSizes={[25, 50, 100]}
          totalItems={total}
          onChange={({ page, pageSize }) => {
            setPage(page);
            setPageSize(pageSize);
          }}
        />
      </div>
    </div>
  );
};
