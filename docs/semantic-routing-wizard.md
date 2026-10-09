# Create a semantic router

The Semantic Routers page helps you build a focused decision service from native business actions and sinks. Upload new native Kamelets through the [Kamelets page](kamelets.md). Eligible uploads become available when a wizard opens. Barn does not require a restart. Barn saves editable definitions and publishes catalogs. WSR runs the selected catalog revision. Wanaku applies governance before it forwards MCP requests.

The page shows the title and description in a separate header section. The content section shows saved semantic routers in a responsive card grid. The **Create semantic router** button is on the right above the cards.

## Create a definition

1. Open **Semantic Routers** in the admin UI.
2. Select **Create semantic router**.
3. Enter the router name.
4. Describe its business purpose.
5. Enter the MCP tool name.
6. Select **Next**.
7. Select two or more compatible actions.
8. Complete the configuration fields for each action.

Router names and MCP tool names must start with a letter. Use only letters, digits, hyphens, and underscores. Do not use spaces. For example, `support-route` and `support_route` are valid. Router names have a maximum of 120 characters. MCP tool names have a maximum of 64 characters.

Each text field has an input hint. The configuration fields use the curated Kamelet schema. The initial profile is `message-to-string/v1`. The request contains a message string. An action returns its string result. A sink receives the message and returns `Routed to <label>.` after successful delivery. Compatible native sinks do not need Barn annotations or an output schema. The wizard identifies sink destinations. Sources create messages and are not offered as destinations. The wizard disables destinations with other profiles.

A saved action selection keeps its exact Kamelet revision. When a newer revision is available, select **Use current revision** to change the selection. This operation resets deployment configuration to the new defaults. The wizard preserves the business label and criteria.

Configuration applies to the deployment. It does not contain per-request arguments. Use `env:VARIABLE` for a credential reference. Native password fields also require this reference format. Do not enter credential values. Do not enter Camel expressions or property placeholders.

## Define the decision

1. Select a configured expert.
2. Select **Request message (message field)** under **Message to classify**.
3. Enter **Classification instructions**.
4. Check the label for each action.
5. Enter the selection criteria for each action.
6. Enter the criteria for no match.

The expert identifies one predefined label. The route maps that label to a fixed action. Labels contain lowercase letters, digits, and underscores. The `no_match` label is reserved.

**Message to classify** selects the request text that the expert reads. The current profile uses the MCP request's `message` field. For example, a request can contain `{"message":"I need a refund for my invoice."}`. The generated Camel route extracts this field into the route body. The generated semantic question reads that body with `${body}`. The wizard does not accept other input expressions.

**Classification instructions** give the expert the rules that apply to every request. For example: “Which team should handle this support request? If the state is an envelope, classify `message` and use `serviceScope` only as background context.” Enter the rules for a specific action in **When to select** for that action. Enter representative request messages in the Examples step.

No match returns an explicit result and does not execute an action. Provider failures and malformed decisions return evaluation errors. Action failures return execution errors. The current expert contract does not provide confidence controls.

Expert credentials, model settings, timeouts, and limits belong to deployment configuration. A configured expert identifier refers to a named Camel bean and its implementation dependency.

## Check examples

1. Select **Add example**.
2. Enter a representative message.
3. Select the expected action label or `no_match`.
4. Select **Preview example**.
5. Compare the expected and actual labels.

A preview saves the current draft. It uses the same definition and expert configuration as production. It performs classification only. It does not execute an action. The UI shows evaluation errors separately from no match. It shows evaluation time and diagnostics when the API supplies them.

Use **Back** to change an earlier step. The wizard retains the entered values and validation errors. Select **Save draft** to keep an incomplete definition. Open the saved definition from the list to continue later.

## Publish and deploy

The review summary is the main view. Select the eye control, **View generated files**, to inspect the generated YAML, Kamelets, dependencies, and properties. Select a file to view its content. The preview is read-only. It uses the current draft. It does not save, publish, or execute the route. Publication assigns the final catalog name and revision.

1. Review the tool, expert, action labels, and examples.
2. Select **Publish catalog**.
3. Record the published revision and SHA-256 digest.
4. Set `TYPESAFE_API_KEY` in the WSR process environment for the default expert.
5. Start WSR with the saved route name.

```shell
java -jar target/wanaku-semantic-router-0.1.0-SNAPSHOT.jar runtime --semantic-route support-route
```

Replace `support-route` with the saved name. The local defaults use Barn at `http://localhost:8180` and Wanaku management at `http://localhost:8080`. Kubernetes can set `WSR_SEMANTIC_ROUTE`, `WSR_BARN_URL`, `WSR_REGISTRATION_URL`, and `WSR_MCP_ADDRESS`. Set `WSR_BIND=0.0.0.0` for the container listener. See the [WSR deployment guide](https://github.com/wanaku-ai/wanaku-semantic-router/blob/ci-issue-182/docs/deployment.md) for the complete manifest and custom expert settings.

Publication creates an immutable catalog revision. Publication does not start WSR. The UI reports runtime status as not observed because Barn does not have a runtime observation for this workflow. WSR must load the selected revision and pass its startup checks before it registers with Wanaku. Restart or replace WSR to select another revision.

Barn records the current publication after a successful publish operation. Draft edits do not change this selection. WSR reads it once at startup. Changes to a saved definition do not change an existing publication. Publish the definition again to create a revision for those changes.

## Download stored artifacts

1. Open **Data Stores**.
2. Find the published catalog name from the publication result.
3. Select **Download** in that row.

The published catalog contains a ZIP archive. The download has a `.zip` extension. The wizard also stores editable definitions, publication records, and a current-publication record in Data Stores. These records contain plain JSON. Their downloads have a `.json` extension. Existing uploaded files keep their names. The UI retrieves the persisted record before it downloads the file. It shows an error if retrieval or decoding fails.

Use the published catalog ZIP for WSR deployment. Definition JSON contains authoring settings. Publication JSON contains the revision, digest, and deployment instructions. Current-publication JSON selects the publication for route-name startup.

Select **Remove** in the list to remove an editable definition. Confirm the removal in the modal. This operation does not change an existing WSR deployment.

## Validate the UI

Run these commands from `apps/ui/admin`:

```shell
npm run build
npm run lint
npm test
```

Run these commands from `tests/e2e/ui`:

```shell
npx playwright install chromium
npx playwright test --config playwright.ui.config.ts
```

The browser suite uses deterministic HTTP fixtures. It checks authoring, field descriptions, configuration errors, saved drafts, keyboard navigation, selected labels, no match, evaluation failures, publication, and removal. It checks ZIP and JSON download bytes, file names, and visible retrieval or decoding errors. Backend and WSR tests check native Camel evaluation and the classification-only execution boundary. The browser fixtures do not prove provider accuracy or runtime execution.

Run the live browser suite against an isolated Barn instance. Configure that instance with the native deterministic preview service. The provider fixture selects `billing` or `technical` when the message contains that word. Other messages select `no_match`.

Allow the Vite UI origin in the isolated Barn CORS configuration. Use `BARN_UI_URL` to select another UI address when needed. Run this command from `tests/e2e/ui`:

```shell
BARN_URL=http://127.0.0.1:8180 npx playwright test --config playwright.live.config.ts
```

The live suite uses the real definition, validation, preview, publication, Data Store, and removal APIs. It checks the ZIP download against the published SHA-256 digest. It checks definition and publication JSON downloads against their stored content. It does not intercept browser API requests. It removes only the editable definition and publication record that it creates. The immutable catalog remains until the isolated test instance is removed.

The wizard supports one destination per request. It does not provide a route designer, YAML editing, arbitrary transformations, or hot reload.
