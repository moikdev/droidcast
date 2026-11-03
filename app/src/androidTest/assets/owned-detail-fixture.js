// Owned native-renderer fixture. No Store, external service or arbitrary-app credit.
const React = require("react");
const api = require("@raycast/api");
const h = React.createElement;
const configuration = globalThis.__ownedDetailConfiguration;
module.exports.default = function OwnedDetail() {
  const [revision, publish] = React.useState(0);
  const state = React.useRef({ view: configuration.mode === "list" ? "list" : "detail", shown: true,
    document: "A", icon: api.Icon.CircleEllipsis, contentMode: configuration.mode, rootKey: 0, detailKey: 0, tagCalls: 0, selected: null,
    target: "javascript:globalThis.__ownedUnexpectedLink=true", loading: configuration.mode === "loading" }).current;
  globalThis.__ownedDetailPatch = patch => { Object.assign(state, patch); publish(value => value + 1); };
  globalThis.__ownedDetailRead = () => ({ revision, tagCalls: state.tagCalls, selected: state.selected,
    unexpectedLink: globalThis.__ownedUnexpectedLink === true });
  const marker = "Detail publication " + revision;
  const actions = name => h(api.ActionPanel, null,
    h(api.Action.CopyToClipboard, { title: "Copy owned " + name, content: "Owned exact copy " + name + " café é 日本 🌱" }));
  function detail(Type, row) {
    const M = Type.Metadata;
    const metadata = h(M, null,
      h(M.Label, { title: "Owned label", text: { value: "Owned value " + row + " " + state.document, color: "#22aa66" }, icon: state.icon }),
      h(M.Link, { title: "Owned reference", text: "Owned link " + row, target: state.target }),
      h(M.TagList, { title: "Owned tags" }, h(M.TagList.Item, { text: "Owned tag " + row, icon: api.Icon.Star, color: "#228833",
        onAction: () => { state.tagCalls++; publish(value => value + 1); } })),
      h(M.Separator), h(M.Label, { title: "Owned activation", text: "Owned tag count " + state.tagCalls }),
      h(M.Label, { title: "Owned publication", text: marker }));
    let markdown = "# Owned document " + row + " " + state.document + "\n\nOwned content café é 日本 🌱.\n\n" + marker;
    if (state.contentMode === "images") markdown = configuration.images + "\n\n" + marker;
    if (state.contentMode === "typography") markdown = configuration.typography + "\n\n" + marker;
    if (state.contentMode === "links") markdown += "\n\n[Owned paragraph link](javascript:owned-unused)";
    if (state.contentMode === "entity-lines") markdown = "Owned entity foo&#10;&#10;bar\n\nOwned hard first  \nOwned hard second\n\n```text\nOwned code first\n\nOwned code third\n```\n\n" + marker;
    if (["metadata-only", "empty", "loading"].includes(state.contentMode)) markdown = "";
    const noMetadata = ["empty", "loading", "images", "typography", "entity-lines"].includes(state.contentMode);
    return h(Type, { key: state.detailKey, navigationTitle: marker, markdown, metadata: noMetadata ? undefined : metadata,
      isLoading: state.loading, actions: actions(row) });
  }
  if (state.view === "list") {
    const props = { key: state.rootKey, navigationTitle: marker, onSelectionChange: id => { state.selected = id; } };
    if (state.shown !== null) props.isShowingDetail = state.shown;
    return h(api.List, props, ["alpha", "beta"].map(row => h(api.List.Item, {
      key: row, id: row, title: "Owned " + row, subtitle: marker,
      detail: detail(api.List.Item.Detail, row), actions: actions(row)
    })));
  }
  return React.cloneElement(detail(api.Detail, "standalone"), { key: state.rootKey });
};
